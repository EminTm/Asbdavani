package com.asbai.predictor.network

import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiAnalyzer {

    private val client = HttpClientProvider.sharedClient

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun supportsThinkingLevel(cleanModel: String): Boolean {
        return cleanModel.startsWith("gemini-4") ||
            cleanModel.startsWith("gemini-3.7") ||
            cleanModel.startsWith("gemini-3.8")
    }

    private fun JsonObjectBuilder.applyFastThinkingIfNeeded(cleanModel: String) {
        if (supportsThinkingLevel(cleanModel)) {
            putJsonObject("thinkingConfig") {
                put("thinkingLevel", "LOW")
            }
        }
    }

    suspend fun testConnection(apiKey: String, model: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanKey = apiKey.trim()
                val cleanModel = model.trim().removePrefix("models/")
                if (cleanKey.isBlank()) error("لطفاً ابتدا کلید API جمنای را وارد کنید.")
                val payload = buildJsonObject {
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                addJsonObject { put("text", "فقط کلمه OK را برگردان.") }
                            }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("maxOutputTokens", 512)
                        applyFastThinkingIfNeeded(cleanModel)
                    }
                }
                val text = executeGenerateContent(cleanKey, cleanModel, payload.toString()).first
                "اتصال Gemini با مدل $cleanModel برقرار شد (${text.trim().take(20)})"
            }
        }

    suspend fun fetchLiveNewsOnly(
        apiKey: String,
        model: String,
        raceTitle: String,
        rawRaceData: String
    ): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "" to emptyList()
        withTimeoutOrNull(12_000L) {
            runCatching {
                fetchLiveNewsContext(apiKey.trim(), model, raceTitle, rawRaceData)
            }.getOrDefault("" to emptyList())
        } ?: ("" to emptyList())
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
            val primaryModel = model.trim().removePrefix("models/")
            if (cleanKey.isBlank()) error("کلید Gemini ثبت نشده است.")
            if (rawRaceData.isBlank()) error("داده‌ای برای تحلیل وجود ندارد.")

            var liveNewsContext = preFetchedNews?.first.orEmpty()
            var discoveredSources = preFetchedNews?.second.orEmpty()

            if (preFetchedNews == null && useWebSearch) {
                val searchRes = withTimeoutOrNull(12_000L) {
                    runCatching {
                        fetchLiveNewsContext(cleanKey, primaryModel, raceTitle, rawRaceData)
                    }.getOrNull()
                }
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
                maxDataChars = 22000
            )

            val candidateModels = (listOf(primaryModel) + listOf(
                "gemini-3.5-flash-lite",
                "gemini-4-hydrogen",
                "gemini-3.8-flash"
            )).distinct()

            var lastError: Exception? = null
            var rawResponse: String? = null

            for ((idx, candidateModel) in candidateModels.withIndex()) {
                if (idx > 0) {
                    runCatching { Thread.sleep(1500L) }
                }
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
                        put("maxOutputTokens", 16384)
                        applyFastThinkingIfNeeded(candidateModel)
                    }
                }

                try {
                    rawResponse = executeGenerateContent(cleanKey, candidateModel, payload.toString()).first
                    break
                } catch (e: Exception) {
                    lastError = e
                    val msg = e.message.orEmpty()
                    val isTransient = "(503)" in msg || "(429)" in msg || "(500)" in msg || "404" in msg
                    if (!isTransient) throw e
                }
            }

            val finalRaw = rawResponse ?: throw (lastError ?: IllegalStateException("خطای نامشخص Gemini"))
            val cleanedJson = RacePromptBuilder.extractPureJson(finalRaw)
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
        val cleanModel = model.trim().removePrefix("models/")
        val searchPrompt = """
            با جستجوی گوگل، اخبار مرتبط با مسابقه اسبدوانی زیر را پیدا کن (ایران asbdavani.app، ترکیه TJK، یا مسابقات بین‌المللی، وضعیت پیست، تغییر چابکسوار یا مصدومیت).
            خلاصه کوتاه و واقعی بده.
            عنوان: $raceTitle
            داده: ${rawRaceData.take(1500)}
        """.trimIndent()

        val candidateModels = (listOf(cleanModel, "gemini-3.5-flash-lite")).distinct()
        for (m in candidateModels) {
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
                    addJsonObject { putJsonObject("google_search") {} }
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", 1200)
                    applyFastThinkingIfNeeded(m)
                }
            }
            val attempt = runCatching { executeGenerateContent(apiKey, m, payload.toString()) }
            if (attempt.isSuccess) return attempt.getOrThrow()
        }
        return "" to emptyList()
    }

    private fun executeGenerateContent(
        apiKey: String,
        cleanModel: String,
        jsonBody: String
    ): Pair<String, List<String>> {
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
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()

                val translated = translateApiError(apiMsg)
                val friendlyMsg = when (response.code) {
                    400 -> "درخواست نامعتبر Gemini (400): ${translated ?: apiMsg ?: bodyString.take(150)}"
                    401, 403 -> "خطای دسترسی Gemini (${response.code}): ${translated ?: apiMsg ?: bodyString.take(150)}"
                    404 -> "مدل '$cleanModel' در Gemini یافت نشد (404): ${apiMsg ?: ""}"
                    429 -> "سهمیه درخواست Gemini پر شده است (429): ${apiMsg ?: ""}"
                    503 -> "سرور Gemini ($cleanModel) موقتاً شلوغ است (503)."
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
                if (isThought) null else obj["text"]?.jsonPrimitive?.contentOrNull
            }

            val sources = candidate["groundingMetadata"]
                ?.jsonObject?.get("groundingChunks")
                ?.jsonArray
                ?.mapNotNull { chunk ->
                    val web = chunk.jsonObject["web"]?.jsonObject
                    val title = web?.get("title")?.jsonPrimitive?.contentOrNull
                    val uri = web?.get("uri")?.jsonPrimitive?.contentOrNull
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
