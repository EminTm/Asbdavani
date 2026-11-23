package com.asbai.predictor.model

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredLink(
    val title: String,
    val url: String
)

data class ScrapedPage(
    val url: String,
    val title: String,
    val extractedText: String,
    val links: List<DiscoveredLink>,
    val tableCount: Int,
    val likelyNeedsWebView: Boolean
)

@Serializable
enum class RiskProfile(val label: String, val instruction: String) {
    BALANCED(
        label = "متعادل (پیشنهادی)",
        instruction = "تحلیل علمی و متعادل بر اساس آمار واقعی، ریتینگ، عملکرد اخیر، وزن، مسافت و چابکسوار ارائه بده."
    ),
    CONSERVATIVE(
        label = "محافظه‌کارانه (کم‌ریسک)",
        instruction = "تمرکز ویژه روی اسب‌های باثبات‌تر، ریتینگ بالاتر، سوابق مطمئن‌تر و کاهش ریسک داشته باش."
    ),
    UPSET_HUNTER(
        label = "شکار شگفتی (پرریسک)",
        instruction = "علاوه بر مدعیان اصلی، به اسب‌های کم‌وزن‌تر، اسب‌هایی که به مسافت جدید می‌رسند یا تغییر چابکسوار داده‌اند و پتانسیل شگفتی‌سازی دارند توجه ویژه کن."
    )
}

@Serializable
enum class ThirdProvider(
    val shortName: String,
    val title: String,
    val endpointUrl: String,
    val defaultModel: String,
    val helperText: String
) {
    COHERE(
        shortName = "Cohere",
        title = "Cohere (پایدار - ۱۰۰۰ تحلیل ماهانه)",
        endpointUrl = "https://api.cohere.ai/compatibility/v1/chat/completions",
        defaultModel = "command-a-plus-05-2026",
        helperText = "کلید رایگان از dashboard.cohere.com/api-keys (بدون کارت بانکی)"
    ),
    ZAI(
        shortName = "Z.ai",
        title = "Z.ai / GLM (رایگان دائمی)",
        endpointUrl = "https://api.z.ai/api/paas/v4/chat/completions",
        defaultModel = "glm-4.7-flash",
        helperText = "کلید رایگان از z.ai (مدل‌های glm-4.7-flash و glm-4.5-flash رایگان)"
    ),
    OPENROUTER(
        shortName = "OpenRouter",
        title = "OpenRouter",
        endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "nvidia/nemotron-3.5-super-vl:free",
        helperText = "کلید رایگان از openrouter.ai/keys"
    )
}

@Serializable
data class PreScoredHorse(
    val number: String = "-",
    val horseName: String = "",
    val mathScore: Int = 50,
    val winRatePercent: Int = 0,
    val placeRatePercent: Int = 0,
    val summary: String = ""
)

@Serializable
data class AnomalySignal(
    val title: String = "",
    val severity: String = "نکته",
    val description: String = ""
)

@Serializable
data class AiMemberOpinion(
    val providerName: String = "",
    val modelName: String = "",
    val topPickNumber: String = "-",
    val topPickName: String = "",
    val topPickWinProb: Int = 0,
    val confidencePercent: Int = 0,
    val summary: String = ""
)

@Serializable
data class PredictionReport(
    val raceSummary: String = "",
    val trackAndClassNote: String = "",
    val confidenceLevel: String = "متوسط",
    val confidencePercent: Int = 65,
    val consensusSummary: String = "",
    val memberOpinions: List<AiMemberOpinion> = emptyList(),
    val preScores: List<PreScoredHorse> = emptyList(),
    val newsAndContextSummary: String = "",
    val tacticalScenario: String = "",
    val topThree: List<TopPick> = emptyList(),
    val darkHorse: DarkHorsePick? = null,
    val anomalyAlerts: List<AnomalySignal> = emptyList(),
    val horseAnalyses: List<HorseAnalysis> = emptyList(),
    val searchSources: List<String> = emptyList(),
    val disclaimer: String = "این پیش‌بینی یک تحلیل آماری و خبری توسط هوش مصنوعی است؛ تشخیص ناهنجاری‌ها صرفاً بررسی الگوهای مسابقه‌ای است و به معنای اثبات تخلف نیست."
)

@Serializable
data class TopPick(
    val rank: Int = 1,
    val number: String = "-",
    val horseName: String = "",
    val winProbability: Int = 0,
    val reason: String = ""
)

@Serializable
data class DarkHorsePick(
    val number: String = "-",
    val horseName: String = "",
    val winProbability: Int = 0,
    val reason: String = ""
)

@Serializable
data class HorseAnalysis(
    val number: String = "-",
    val horseName: String = "",
    val winProbability: Int = 0,
    val placeProbability: Int = 0,
    val formScore: Int = 5,
    val strengths: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val verdict: String = ""
)

@Serializable
data class SavedPrediction(
    val id: String,
    val timestamp: String,
    val sourceUrl: String,
    val rawExcerpt: String,
    val report: PredictionReport
)
