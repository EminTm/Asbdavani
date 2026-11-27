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
enum class RiskProfile(val label: String, val multiplierNote: String) {
    BALANCED(
        label = "متعادل و علمی (پیشنهادی)",
        multiplierNote = "تعادل بهینه بین فرم، سرعت، ریتینگ، وزن و سیگنال بازار"
    ),
    CONSERVATIVE(
        label = "محافظه‌کارانه (مدعیان باثبات)",
        multiplierNote = "وزن بیشتر به ثبات فرم، ریتینگ و تایم‌های مطمئن"
    ),
    UPSET_HUNTER(
        label = "شکار شگفتی (Value Hunter)",
        multiplierNote = "تمرکز ویژه روی اختلاف وزن، دپار، فرم پنهان و Value Edge"
    )
}

@Serializable
data class ModelWeights(
    val formWeight: Double = 1.0,
    val speedWeight: Double = 1.0,
    val ratingWeight: Double = 1.0,
    val weightDiffWeight: Double = 1.0,
    val freshnessWeight: Double = 0.9,
    val drawWeight: Double = 0.8,
    val marketWeight: Double = 0.75,
    val totalRacesLogged: Int = 0,
    val top1Hits: Int = 0,
    val top3Hits: Int = 0
)

data class ParsedHorseRow(
    val number: String,
    val gate: Int? = null,
    val horseName: String,
    val weightKg: Double? = null,
    val rating: Double? = null,
    val formString: String? = null,
    val recentDigits: List<Int> = emptyList(),
    val daysSinceLast: Int? = null,
    val bestTimeSec: Double? = null,
    val s20Score: Int? = null,
    val openingOdds: Double? = null,
    val marketOdds: Double? = null,
    val marketAgfPct: Double? = null,
    val owner: String = "",
    val trainer: String = "",
    val jockey: String = ""
)

@Serializable
data class PreScoredHorse(
    val number: String = "-",
    val horseName: String = "",
    val mathScore: Int = 50,
    val mathWinProb: Int = 0,
    val fairOdds: Double = 0.0,
    val minValueOdds: Double = 0.0,
    val marketOdds: Double? = null,
    val marketProbPercent: Int? = null,
    val edgePercent: Int? = null,
    val drawPct: Double? = null,
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
    val confidenceStars: Int = 2,
    val advantageNote: String = "",
    val comboSuggestion: String = "",
    val extractionSource: String = "",
    val scratchedKeys: List<String> = emptyList(),
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
    val actualWinnerName: String = "",
    val disclaimer: String = "این پیش‌بینی توسط موتور آماری-کمی خودآموز انجام شده است و تضمینی برای نتیجه قطعی مسابقه نیست."
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
    val mark: String = "",
    val winProbability: Int = 0,
    val placeProbability: Int = 0,
    val formScore: Int = 5,
    val abilityScore: Int = 5,
    val speedFitnessScore: Int = 5,
    val suitabilityScore: Int = 5,
    val fairOdds: Double = 0.0,
    val minValueOdds: Double = 0.0,
    val marketBadge: String = "",
    val speedBadge: String = "",
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
    val report: PredictionReport,
    val horseFeatures: Map<String, List<Double>> = emptyMap()
)