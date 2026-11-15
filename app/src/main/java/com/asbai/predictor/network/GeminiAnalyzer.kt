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
                "اتصال با مدل $model برقرار شد (${text.trim().take(20)})"
            }
        }

    suspend fun analyzeRace(
        apiKey: String,
        model: String,
        riskProfile: RiskProfile,
        raceTitle: String,
        rawRaceData: String,
        userNewsNotes: String = "",
        useWebSearch: Boolean = true
    ): Result<PredictionReport> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) {
                error("کلید هوش مصنوعی (Gemini API Key) ثبت نشده است. از تب تنظیمات واردش کنید.")
            }
            if (rawRaceData.isBlank()) {
                error("داده‌ای برای تحلیل وجود ندارد.")
            }

            var liveNewsContext = ""
            var discoveredSources = emptyList<String>()

            if (useWebSearch) {
                val searchRes = runCatching {
                    fetchLiveNewsContext(cleanKey, model, raceTitle, rawRaceData)
                }.getOrNull()
                if (searchRes != null) {
                    liveNewsContext = searchRes.first
                    discoveredSources = searchRes.second
                }
            }

            val systemPrompt = """
                تو یک تحلیلگر حرفه‌ای مسابقات اسبدوانی ایران (سایت asbdavani.app) هستی.
                تمامی معیارهای فنی کورس‌های ایران (گنبدکاووس، آق‌قلا، بندر ترکمن، یزد، مشهد، نوروزآباد و...) را می‌شناسی:
                - عملکرد اسب به فرمت مثل 2-3-1/10 یعنی: اول-دوم-سوم / تعداد کل استارت‌ها.
                - ریتینگ (Rating)، کلاس کورس و نژاد (ترکمن، تروبرد، دوخون، عرب)، سن و جنسیت.
                - وزن حمل‌شده (کیلوگرم) و اثر آن در مسافت‌های کوتاه و بلند.
                - فاصله زمانی از آخرین کورس، چابکسوار، مربی، مالک و دپار.
                استراتژی کاربر: ${riskProfile.instruction}

                بخش ویژه «رادار ناهنجاری‌ها و تاکتیک تیمی» (anomalyAlerts):
                به عنوان تحلیلگر داده‌محور بررسی کن (بدون قضاوت قطعی درباره تبانی مخفی، فقط تحلیل الگوهای واقعی قابل مشاهده):
                1) آیا یک مربی یا مالک همزمان ۲ یا چند اسب در این دور دارد؟ (احتمال تاکتیک تیمی یا اسب سرعتی کمکی).
                2) آیا جابجایی غیرعادی چابکسوار دیده می‌شود؟ (مثلاً چابکسوار اصلی روی اسب ضعیف‌تر یا اسب دیگری نشسته).
                3) آیا اسبی با ریتینگ بالا اخیراً نتایج ضعیف و غیرعادی داشته و الان با تغییر وزن/مسافت/چابکسوار برگشته؟
                4) اگر نکته یا خبری در اخبار زنده یا یادداشت کاربر درباره شرایط پیست یا اسب‌ها آمده، اثر آن را تحلیل کن.

                خروجی را دقیقاً و فقط به صورت JSON معتبر مطابق ساختار زیر برگردان:
                {
                  "raceSummary": "خلاصه مشخصات دور، مسافت، نژاد و پیست",
                  "trackAndClassNote": "تحلیل شرایط مسافت، کلاس و سطح رقابت اسب‌های حاضر",
                  "confidenceLevel": "بالا یا متوسط یا پایین",
                  "confidencePercent": 72,
                  "newsAndContextSummary": "خلاصه اخبار یافت‌شده در وب و نکات کاربر (اگر خبری نبود بنویس خبر خاص جدیدی ثبت نشده است)",
                  "tacticalScenario": "پیش‌بینی روند تاکتیکی کورس از استارت تا خط پایان",
                  "topThree": [
                    { "rank": 1, "number": "شماره", "horseName": "نام اسب", "winProbability": 34, "reason": "دلیل فنی انتخاب اول" },
                    { "rank": 2, "number": "شماره", "horseName": "نام اسب", "winProbability": 24, "reason": "دلیل فنی انتخاب دوم" },
                    { "rank": 3, "number": "شماره", "horseName": "نام اسب", "winProbability": 16, "reason": "دلیل فنی انتخاب سوم" }
                  ],
                  "darkHorse": {
                    "number": "شماره",
                    "horseName": "نام اسب شگفتی‌ساز",
                    "winProbability": 10,
                    "reason": "چرا این اسب می‌تواند شگفتی‌ساز شود"
                  },
                  "anomalyAlerts": [
                    {
                      "title": "عنوان هشدار یا الگوی تاکتیکی",
                      "severity": "هشدار بالا یا نکته تاکتیکی یا عادی",
                      "description": "توضیح دقیق بر اساس نام اسب‌ها، مربی‌ها یا چابکسوارهای حاضر"
                    }
                  ],
                  "horseAnalyses": [
                    {
                      "number": "شماره",
                      "horseName": "نام اسب",
                      "winProbability": 34,
                      "placeProbability": 68,
                      "formScore": 9,
                      "strengths": ["نقطه قوت ۱", "نقطه قوت ۲"],
                      "risks": ["ریسک ۱"],
                      "verdict": "جمع‌بندی کوتاه"
                    }
                  ],
                  "disclaimer": "این پیش‌بینی یک تحلیل آماری و خبری توسط هوش مصنوعی است؛ تشخیص ناهنجاری‌ها صرفاً بررسی الگوهای مسابقه‌ای است و به معنای اثبات تخلف نیست."
                }
                مجموع winProbability اسب‌ها در horseAnalyses باید حدود 100 باشد.
            """.trimIndent()

            val userPrompt = buildString {
                appendLine("عنوان صفحه / دور: $raceTitle")
                if (userNewsNotes.isNotBlank()) {
                    appendLine()
                    appendLine("--- اخبار و نکات واردشده توسط کاربر ---")
                    appendLine(userNewsNotes.trim())
                }
                if (liveNewsContext.isNotBlank()) {
                    appendLine()
                    appendLine("--- نتایج جستجوی زنده گوگل (اخبار و سوابق) ---")
                    appendLine(liveNewsContext.take(6000))
                }
                appendLine()
                appendLine("--- داده‌های جدول و مشخصات مسابقه ---")
                appendLine(rawRaceData.take(20000))
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
                    put("maxOutputTokens", 8192)
                }
            }

            val (rawResponse, _) = executeGenerateContent(cleanKey, model, payload.toString())
            val cleanedJson = extractPureJson(rawResponse)
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
                    400 -> "درخواست نامعتبر (400): ${translated ?: apiMsg ?: bodyString.take(150)}"
                    401, 403 -> "خطای دسترسی (${response.code}): ${translated ?: apiMsg ?: bodyString.take(150)}"
                    404 -> "مدل '$cleanModel' یافت نشد (404): ${apiMsg ?: ""}"
                    429 -> "سهمیه درخواست کلید API پر شده است (429): ${apiMsg ?: ""}"
                    503 -> "سرور هوش مصنوعی موقتاً شلوغ است (503). دوباره تلاش کنید."
                    else -> "خطای هوش مصنوعی (کد ${response.code}): ${apiMsg ?: bodyString.take(150)}"
                }
                error(friendlyMsg)
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("پاسخی از مدل دریافت نشد.")

            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?: error("ساختار پاسخ مدل خالی بود.")

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
                error("متن خروجی هوش مصنوعی خالی بود.")
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

    private fun extractPureJson(raw: String): String {
        val fence = "\u0060\u0060\u0060"
        var text = raw.trim()
        if (text.startsWith(fence)) {
            text = text.removePrefix("${fence}json").removePrefix(fence).trim()
            if (text.endsWith(fence)) {
                text = text.removeSuffix(fence).trim()
            }
        }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            text.substring(firstBrace, lastBrace + 1)
        } else {
            text
        }
    }
}
