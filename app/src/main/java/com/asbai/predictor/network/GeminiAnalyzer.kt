package com.asbai.predictor.network

import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun testConnection(apiKey: String, model: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanKey = apiKey.trim()
                if (cleanKey.isBlank()) error("لطفاً ابتدا کلید API جمنای را وارد کنید.")
                val payload = buildJsonObject {
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", "فقط کلمه OK را برگردان.")
                                }
                            }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("maxOutputTokens", 32)
                    }
                }
                val text = executeGenerateContent(cleanKey, model, payload.toString()).first
                "اتصال Gemini با مدل $model برقرار شد (${text.trim().take(20)})"
            }
        }

    suspend fun fetchLiveNewsOnly(
        apiKey: String,
        model: String,
        raceTitle: String,
        rawRaceData: String
    ): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "" to emptyList()
        runCatching {
            fetchLiveNewsContext(apiKey.trim(), model, raceTitle, rawRaceData)
        }.getOrDefault("" to emptyList())
    }

    suspend fun analyzeRace(
        apiKey: String,
        model: String,
        riskProfile: RiskProfile,
        raceTitle: String,
        rawRaceData: String,
        userNewsNotes: String = "",
        useWebSearch: Boolean = true,
        preFetchedNews: Pair<String, List<String>>? = null
    ): Result<PredictionReport> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) {
                error("کلید Gemini ثبت نشده است.")
            }
            if (rawRaceData.isBlank()) {
                error("داده‌ای برای تحلیل وجود ندارد.")
            }

            var liveNewsContext = preFetchedNews?.first.orEmpty()
            var discoveredSources = preFetchedNews?.second.orEmpty()

            if (preFetchedNews == null && useWebSearch) {
                val searchRes = runCatching {
                    fetchLiveNewsContext(cleanKey, model, raceTitle, rawRaceData)
                }.getOrNull()
                if (searchRes != null) {
                    liveNewsContext = searchRes.first
                    discoveredSources = searchRes.second
                }
            }

            val systemPrompt = RacePromptBuilder.buildSystemPrompt(riskProfile)
            val userPrompt = RacePromptBuilder.buildUserPrompt(
                raceTitle = raceTitle,
                rawRaceData = rawRaceData,
                userNewsNotes = userNewsNotes,
                liveNewsContext = liveNewsContext,
                maxDataChars = 20000
            )

            val payload = buildJsonObject {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemPrompt) }
                    }
                }
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", userPrompt) }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("maxOutputTokens", 8192)
                }
            }

            val (rawResponse, _) = executeGenerateContent(cleanKey, model, payload.toString())
            val cleanedJson = RacePromptBuilder.extractPureJson(rawResponse)
            val parsed = json.decodeFromString<PredictionReport>(cleanedJson)
            parsed.copy(searchSources = discoveredSources)
        }
    }

    private fun fetchLiveNewsContext(
        apiKey: String,
        model: String,
        raceTitle: String,
        rawRaceData: String
    ): Pair<String, List<String>> {
        val searchPrompt = """
            با استفاده از جستجوی گوگل، درباره مسابقات اسبدوانی ایران مرتبط با اطلاعات زیر جستجو کن (سایت asbdavani.app، وضعیت پیست گنبد/آق‌قلا/بندرترکمن، اخبار اخیر اسب‌ها یا چابکسواران و مصدومیت‌ها یا انصراف‌های احتمالی).
            خلاصه‌ای کوتاه، دقیق و واقعی فقط از اطلاعات تاییدشده بده.
            عنوان: $raceTitle
            بخشی از داده‌های دور:
            ${rawRaceData.take(1800)}
        """.trimIndent()

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", searchPrompt) }
                    }
                }
            }
            putJsonArray("tools") {
                addJsonObject {
                    putJsonObject("google_search") {}
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", 1500)
            }
        }
        return executeGenerateContent(apiKey, model, payload.toString())
    }

    private fun executeGenerateContent(
        apiKey: String,
        model: String,
        jsonBody: String
    ): Pair<String, List<String>> {
        val cleanModel = model.trim().removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMsg = runCatching {
                    json.parseToJsonElement(bodyString)
                        .jsonObject["error"]
                        ?.jsonObject?.get("message")
                        ?.jsonPrimitive?.content
                }.getOrNull()

                val translated = translateApiError(apiMsg)
                val friendlyMsg = when (response.code) {
                    400 -> "درخواست نامعتبر Gemini (400): ${translated ?: apiMsg ?: bodyString.take(150)}"
                    401, 403 -> "خطای دسترسی Gemini (${response.code}): ${translated ?: apiMsg ?: bodyString.take(150)}"
                    404 -> "مدل '$cleanModel' در Gemini یافت نشد (404): ${apiMsg ?: ""}"
                    429 -> "سهمیه درخواست Gemini پر شده است (429): ${apiMsg ?: ""}"
                    503 -> "سرور Gemini موقتاً شلوغ است (503)."
                    else -> "خطای Gemini (کد ${response.code}): ${apiMsg ?: bodyString.take(150)}"
                }
                error(friendlyMsg)
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("پاسخی از مدل Gemini دریافت نشد.")

            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?: error("ساختار پاسخ Gemini خالی بود.")

            val textParts = parts.mapNotNull { partEl ->
                val obj = partEl.jsonObject
                val isThought = obj["thought"]?.jsonPrimitive?.booleanOrNull == true
                if (isThought) null else obj["text"]?.jsonPrimitive?.content
            }

            val sources = candidate["groundingMetadata"]
                ?.jsonObject?.get("groundingChunks")
                ?.jsonArray
                ?.mapNotNull { chunk ->
                    val web = chunk.jsonObject["web"]?.jsonObject
                    val title = web?.get("title")?.jsonPrimitive?.content
                    val uri = web?.get("uri")?.jsonPrimitive?.content
                    if (!uri.isNullOrBlank()) {
                        if (!title.isNullOrBlank()) "$title ($uri)" else uri
                    } else null
                }
                ?.distinct()
                ?.take(6)
                ?: emptyList()

            val combined = textParts.joinToString("\n").trim()
            if (combined.isBlank()) {
                error("متن خروجی Gemini خالی بود.")
            }
            return combined to sources
        }
    }

    private fun translateApiError(apiMsg: String?): String? {
        if (apiMsg.isNullOrBlank()) return null
        val lower = apiMsg.lowercase()
        return when {
            "location" in lower || "country" in lower || "region" in lower ->
                "لوکیشن یا آی‌پی فعلی توسط گوگل پشتیبانی نمی‌شود.\nجزئیات: $apiMsg"
            "api key not valid" in lower || "api_key_invalid" in lower ->
                "کلید API اشتباه یا ناقص است.\nجزئیات: $apiMsg"
            "denied access" in lower || "permission_denied" in lower ->
                "پروژه این کلید توسط گوگل محدود شده است.\nجزئیات: $apiMsg"
            else -> apiMsg
        }
    }
}
