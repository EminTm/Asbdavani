package com.asbai.predictor.network

import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
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

object RacePromptBuilder {

    fun buildSystemPrompt(riskProfile: RiskProfile): String = """
        تو یک تحلیلگر کمی و حرفه‌ای مسابقات اسبدوانی (ایران asbdavani.app، ترکیه TJK و بین‌الملل) هستی.
        معیارهای دقیق زیر را حتماً در امتیازدهی لحاظ کن:
        1) آنالیز ریاضی Equintel (شانس ریاضی، Fair Odds، دپار نسبی draw_pct = دپار/تعداد اسب‌ها، و Value Edge نسبت به AGF/بازار).
        2) تغییر کلاس کورس (class_change): اسبی که از کلاس بالاتر سقوط کرده (کورس آسان‌تر) خطرناک است و برعکس.
        3) تخصص در مسافت و جنس پیست (چمن/خاکی/سنتتیک) و رتبه‌های ۳ کورس آخر نسبت به تعداد اسب‌های هر کورس.
        4) وزن حمل‌شده، فاصله استراحت از آخرین کورس و فرم اخیر چابکسوار/مربی.
        5) حد منطقی سکو: تا ۴ اسب فقط نفر اول، ۵-۷ اسب ۲ نفر، ۸-۱۵ اسب ۳ نفر، ۱۶+ اسب ۴ نفر.
        6) کشف Value Pick (در darkHorse): اسبی که AGF یا اقبال عمومی کمی دارد ولی از نظر فنی/کلاس/وزن Edge مثبت دارد.
        استراتژی کاربر: ${riskProfile.instruction}

        بخش ویژه «رادار ناهنجاری‌ها و تاکتیک تیمی» (anomalyAlerts):
        بررسی کن: ۱) چند اسب هم‌مربی یا هم‌مالک ۲) جابجایی غیرعادی چابکسوار ۳) افت/بازگشت مشکوک ریتینگ ۴) اثر اخبار/پیست.

        خروجی را دقیقاً و فقط به صورت یک JSON معتبر مطابق ساختار زیر برگردان:
        {
          "raceSummary": "خلاصه مشخصات دور، مسافت، نژاد و پیست",
          "trackAndClassNote": "تحلیل شرایط مسافت، کلاس (class_change) و سطح رقابت",
          "confidenceLevel": "بالا یا متوسط یا پایین",
          "confidencePercent": 72,
          "newsAndContextSummary": "خلاصه اخبار یافت‌شده و نکات کاربر",
          "tacticalScenario": "پیش‌بینی روند تاکتیکی کورس و اثر دپار نسبی",
          "topThree": [
            { "rank": 1, "number": "شماره", "horseName": "نام اسب", "winProbability": 34, "reason": "دلیل فنی انتخاب اول" },
            { "rank": 2, "number": "شماره", "horseName": "نام اسب", "winProbability": 24, "reason": "دلیل فنی انتخاب دوم" },
            { "rank": 3, "number": "شماره", "horseName": "نام اسب", "winProbability": 16, "reason": "دلیل فنی انتخاب سوم" }
          ],
          "darkHorse": {
            "number": "شماره",
            "horseName": "نام اسب شگفتی‌ساز / Value Pick",
            "winProbability": 10,
            "reason": "چرا این اسب نسبت به AGF/بازار ارزش پنهان (Edge مثبت) دارد"
          },
          "anomalyAlerts": [
            {
              "title": "عنوان هشدار یا الگوی تاکتیکی",
              "severity": "هشدار بالا یا نکته تاکتیکی یا عادی",
              "description": "توضیح دقیق بر اساس داده‌ها"
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
          "disclaimer": "این پیش‌بینی یک تحلیل آماری و خبری توسط هوش مصنوعی است و تضمینی برای نتیجه قطعی مسابقه نیست."
        }
        مجموع winProbability اسب‌ها در horseAnalyses باید حدود 100 باشد.
    """.trimIndent()

    fun buildUserPrompt(
        raceTitle: String,
        rawRaceData: String,
        userNewsNotes: String,
        liveNewsContext: String,
        maxDataChars: Int
    ): String = buildString {
        appendLine("عنوان صفحه / دور: $raceTitle")
        if (userNewsNotes.isNotBlank()) {
            appendLine()
            appendLine("--- اخبار و نکات واردشده توسط کاربر ---")
            appendLine(userNewsNotes.trim().take(2000))
        }
        if (liveNewsContext.isNotBlank()) {
            appendLine()
            appendLine("--- اخبار و سوابق یافت‌شده از وب ---")
            appendLine(liveNewsContext.trim().take(3000))
        }
        appendLine()
        appendLine("--- داده‌های جدول و مشخصات مسابقه ---")
        appendLine(rawRaceData.take(maxDataChars))
    }

    fun extractPureJson(raw: String): String {
        val fence = "\u0060\u0060\u0060"
        var text = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
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

class OpenAiCompatAnalyzer {

    private val client = HttpClientProvider.sharedClient

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun testConnection(
        providerName: String,
        endpointUrl: String,
        apiKey: String,
        model: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            val cleanModel = model.trim()
            if (cleanKey.isBlank()) error("لطفاً ابتدا کلید API مربوط به $providerName را وارد کنید.")
            val isGroqGptOss = "groq.com" in endpointUrl && "gpt-oss" in cleanModel.lowercase()
            val isGroqQwen = "groq.com" in endpointUrl && "qwen" in cleanModel.lowercase()
            val payload = buildJsonObject {
                put("model", cleanModel)
                put("max_tokens", 512)
                if (isGroqGptOss) put("reasoning_effort", "low")
                if (isGroqQwen) put("reasoning_effort", "none")
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "فقط کلمه OK را برگردان.")
                    }
                }
            }
            val reply = executeChat(providerName, endpointUrl, cleanKey, payload.toString(), extraHeaders)
            "اتصال $providerName با مدل $cleanModel برقرار شد (${reply.trim().take(20)})"
        }
    }

    suspend fun analyzeRace(
        providerName: String,
        endpointUrl: String,
        apiKey: String,
        model: String,
        riskProfile: RiskProfile,
        raceTitle: String,
        rawRaceData: String,
        userNewsNotes: String,
        liveNewsContext: String,
        extraHeaders: Map<String, String> = emptyMap(),
        maxDataChars: Int = 11000
    ): Result<PredictionReport> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) error("کلید $providerName وارد نشده است.")

            val effectiveMaxChars = if ("groq.com" in endpointUrl) minOf(maxDataChars, 7000) else maxDataChars
            val systemPrompt = RacePromptBuilder.buildSystemPrompt(riskProfile)
            val userPrompt = RacePromptBuilder.buildUserPrompt(
                raceTitle = raceTitle,
                rawRaceData = rawRaceData,
                userNewsNotes = userNewsNotes,
                liveNewsContext = liveNewsContext,
                maxDataChars = effectiveMaxChars
            )

            try {
                val payload = buildPayload(endpointUrl, model.trim(), systemPrompt, userPrompt, true)
                val reply = executeChat(providerName, endpointUrl, cleanKey, payload, extraHeaders)
                json.decodeFromString<PredictionReport>(RacePromptBuilder.extractPureJson(reply))
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val nonRetryable = "401" in msg || "402" in msg || "403" in msg || "429" in msg
                if (!nonRetryable) {
                    val fallbackPayload = buildPayload(endpointUrl, model.trim(), systemPrompt, userPrompt, false)
                    val reply2 = executeChat(providerName, endpointUrl, cleanKey, fallbackPayload, extraHeaders)
                    json.decodeFromString<PredictionReport>(RacePromptBuilder.extractPureJson(reply2))
                } else {
                    throw e
                }
            }
        }
    }

    private fun buildPayload(
        endpointUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        forceJsonMode: Boolean
    ): String {
        val isGroq = "groq.com" in endpointUrl
        val isGroqGptOss = isGroq && "gpt-oss" in model.lowercase()
        val isGroqQwen = isGroq && "qwen" in model.lowercase()
        return buildJsonObject {
            put("model", model)
            put("temperature", 0.3)
            put("max_tokens", if (isGroq) 3200 else 6000)
            if (isGroqGptOss) put("reasoning_effort", "low")
            if (isGroqQwen) put("reasoning_effort", "none")
            if (forceJsonMode) {
                putJsonObject("response_format") {
                    put("type", "json_object")
                }
            }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", systemPrompt) }
                addJsonObject { put("role", "user"); put("content", userPrompt) }
            }
        }.toString()
    }

    private fun executeChat(
        providerName: String,
        endpointUrl: String,
        apiKey: String,
        jsonBody: String,
        extraHeaders: Map<String, String>
    ): String {
        val builder = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")

        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val request = builder
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

                val friendly = when (response.code) {
                    401, 403 -> "کلید $providerName نامعتبر است یا دسترسی ندارد (${response.code}): ${apiMsg ?: ""}"
                    402 -> "سهمیه/اعتبار $providerName کافی نیست."
                    404 -> "مدل انتخابی در $providerName یافت نشد (404): ${apiMsg ?: ""}"
                    429 -> "سهمیه درخواست $providerName موقتاً پر شده است (429): ${apiMsg ?: ""}"
                    else -> "خطای $providerName (کد ${response.code}): ${apiMsg ?: bodyString.take(150)}"
                }
                error(friendly)
            }

            val root = json.parseToJsonElement(bodyString).jsonObject
            val choices = root["choices"]?.jsonArray ?: error("پاسخی از $providerName دریافت نشد.")
            val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: error("ساختار پیام $providerName خالی بود.")

            val content = runCatching {
                message["content"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty().trim()

            if (content.isBlank()) {
                error("متن خروجی $providerName خالی بود.")
            }
            return content
        }
    }
}
