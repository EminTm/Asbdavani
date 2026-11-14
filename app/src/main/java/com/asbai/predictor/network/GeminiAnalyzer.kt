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
                if (apiKey.isBlank()) error("لطفاً ابتدا کلید API جمنای را وارد کنید.")
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
                val text = executeGenerateContent(apiKey, model, payload.toString())
                "اتصال با مدل $model برقرار شد (${text.trim().take(20)})"
            }
        }

    suspend fun analyzeRace(
        apiKey: String,
        model: String,
        riskProfile: RiskProfile,
        raceTitle: String,
        rawRaceData: String
    ): Result<PredictionReport> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) {
                error("کلید هوش مصنوعی (Gemini API Key) ثبت نشده است. از تب تنظیمات واردش کنید.")
            }
            if (rawRaceData.isBlank()) {
                error("داده‌ای برای تحلیل وجود ندارد.")
            }

            val systemPrompt = """
                تو یک تحلیلگر حرفه‌ای مسابقات اسبدوانی ایران (سایت asbdavani.app) هستی.
                تمامی معیارهای فنی کورس‌های ایران (گنبدکاووس، آق‌قلا، بندر ترکمن، یزد، مشهد، نوروزآباد و...) را می‌شناسی:
                - عملکرد اسب به فرمت مثل 2-3-1/10 یعنی: اول-دوم-سوم / تعداد کل استارت‌ها.
                - ریتینگ (Rating)، کلاس کورس و نژاد (ترکمن، تروبرد، دوخون، عرب)، سن و جنسیت (ن-3، م-4 و...).
                - وزن حمل‌شده (کیلوگرم) و اثر آن در مسافت‌های کوتاه (1000-1200م) در مقابل مسافت‌های بلندتر (1600-2200م).
                - فاصله زمانی از آخرین کورس (آمادگی بدنی یا افت ناشی از دوری طولانی).
                - نقش چابکسوار، مربی، مجوز کورس و جایگاه دپار.
                استراتژی کاربر: ${riskProfile.instruction}

                خروجی را دقیقاً و فقط به صورت JSON معتبر مطابق ساختار زیر برگردان (همه متن‌ها به زبان فارسی روان و تخصصی باشد):
                {
                  "raceSummary": "خلاصه مشخصات دور، مسافت، نژاد و پیست",
                  "trackAndClassNote": "تحلیل شرایط مسافت، کلاس و سطح رقابت اسب‌های حاضر",
                  "confidenceLevel": "بالا یا متوسط یا پایین",
                  "confidencePercent": 72,
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
                  "disclaimer": "این پیش‌بینی یک تحلیل آماری توسط هوش مصنوعی است و نتیجه قطعی کورس در پیست رقم می‌خورد."
                }
                مجموع winProbability اسب‌ها در horseAnalyses باید حدود 100 باشد.
                اگر داده‌های ورودی فقط لیست مسابقات بود و هنوز جدول اسب‌های یک دور خاص داخلش نبود، در raceSummary بنویس که لطفاً یک دور مشخص را باز کنید و بر اساس اطلاعات موجود توضیح بده.
            """.trimIndent()

            val userPrompt = """
                عنوان صفحه / دور: $raceTitle
                داده‌های استخراج‌شده از مسابقه:
                ${rawRaceData.take(24000)}
            """.trimIndent()

            val payload = buildJsonObject {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemPrompt)
                        }
                    }
                }
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", userPrompt)
                            }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("maxOutputTokens", 8192)
                }
            }

            val rawResponse = executeGenerateContent(apiKey, model, payload.toString())
            val cleanedJson = extractPureJson(rawResponse)
            json.decodeFromString<PredictionReport>(cleanedJson)
        }
    }

    private fun executeGenerateContent(
        apiKey: String,
        model: String,
        jsonBody: String
    ): String {
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

                val friendlyMsg = when (response.code) {
                    400 -> "درخواست نامعتبر است (${apiMsg ?: "کد 400"})"
                    401, 403 -> "کلید API جمنای نامعتبر است یا مجوز ندارد."
                    404 -> "مدل '$cleanModel' یافت نشد. از تنظیمات مدل دیگری انتخاب کنید."
                    429 -> "سهمیه درخواست کلید API پر شده است. کمی صبر کنید."
                    503 -> "سرور هوش مصنوعی موقتاً شلوغ است. دوباره تلاش کنید."
                    else -> "خطای هوش مصنوعی (کد ${response.code}): ${apiMsg ?: bodyString.take(150)}"
                }
                error(friendlyMsg)
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val candidates = root["candidates"]?.jsonArray
                ?: error("پاسخی از مدل دریافت نشد.")

            val parts = candidates.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray
                ?: error("ساختار پاسخ مدل خالی بود.")

            val textParts = parts.mapNotNull { partEl ->
                val obj = partEl.jsonObject
                val isThought = obj["thought"]?.jsonPrimitive?.booleanOrNull == true
                if (isThought) null else obj["text"]?.jsonPrimitive?.content
            }

            val combined = textParts.joinToString("\n").trim()
            if (combined.isBlank()) {
                error("متن خروجی هوش مصنوعی خالی بود.")
            }
            return combined
        }
    }

    private fun extractPureJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            if (text.endsWith("```")) {
                text = text.removeSuffix("```").trim()
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
