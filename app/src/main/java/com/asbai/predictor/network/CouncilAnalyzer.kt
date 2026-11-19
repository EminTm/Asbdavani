package com.asbai.predictor.network

import com.asbai.predictor.model.AiMemberOpinion
import com.asbai.predictor.model.AnomalySignal
import com.asbai.predictor.model.DarkHorsePick
import com.asbai.predictor.model.HorseAnalysis
import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.TopPick
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

data class CouncilConfig(
    val geminiKey: String,
    val geminiModel: String,
    val groqKey: String,
    val groqModel: String,
    val openRouterKey: String,
    val openRouterModel: String
)

data class CouncilOutput(
    val report: PredictionReport,
    val memberWarnings: List<String>
)

private data class MemberResult(
    val providerName: String,
    val modelName: String,
    val report: PredictionReport?,
    val error: String?
)

class CouncilAnalyzer(
    private val geminiAnalyzer: GeminiAnalyzer = GeminiAnalyzer(),
    private val openAiCompatAnalyzer: OpenAiCompatAnalyzer = OpenAiCompatAnalyzer()
) {

    suspend fun analyzeWithCouncil(
        config: CouncilConfig,
        riskProfile: RiskProfile,
        raceTitle: String,
        rawRaceData: String,
        userNewsNotes: String,
        useWebSearch: Boolean
    ): Result<CouncilOutput> = runCatching {
        val hasGemini = config.geminiKey.isNotBlank()
        val hasGroq = config.groqKey.isNotBlank()
        val hasOpenRouter = config.openRouterKey.isNotBlank()

        if (!hasGemini && !hasGroq && !hasOpenRouter) {
            error("حداقل یک کلید هوش مصنوعی (Gemini، Groq یا OpenRouter) را در تب تنظیمات وارد کنید.")
        }

        val liveNewsPair = if (hasGemini && useWebSearch) {
            geminiAnalyzer.fetchLiveNewsOnly(
                apiKey = config.geminiKey,
                model = config.geminiModel,
                raceTitle = raceTitle,
                rawRaceData = rawRaceData
            )
        } else {
            "" to emptyList()
        }

        val memberResults = coroutineScope {
            val jobs = buildList {
                if (hasGemini) {
                    add(async {
                        val res = geminiAnalyzer.analyzeRace(
                            apiKey = config.geminiKey,
                            model = config.geminiModel,
                            riskProfile = riskProfile,
                            raceTitle = raceTitle,
                            rawRaceData = rawRaceData,
                            userNewsNotes = userNewsNotes,
                            useWebSearch = false,
                            preFetchedNews = liveNewsPair
                        )
                        MemberResult(
                            providerName = "Gemini",
                            modelName = config.geminiModel,
                            report = res.getOrNull(),
                            error = res.exceptionOrNull()?.message
                        )
                    })
                }
                if (hasGroq) {
                    add(async {
                        val res = openAiCompatAnalyzer.analyzeRace(
                            providerName = "Groq",
                            endpointUrl = GROQ_ENDPOINT,
                            apiKey = config.groqKey,
                            model = config.groqModel,
                            riskProfile = riskProfile,
                            raceTitle = raceTitle,
                            rawRaceData = rawRaceData,
                            userNewsNotes = userNewsNotes,
                            liveNewsContext = liveNewsPair.first,
                            maxDataChars = 11000
                        )
                        MemberResult(
                            providerName = "Groq",
                            modelName = config.groqModel,
                            report = res.getOrNull(),
                            error = res.exceptionOrNull()?.message
                        )
                    })
                }
                if (hasOpenRouter) {
                    add(async {
                        val res = openAiCompatAnalyzer.analyzeRace(
                            providerName = "OpenRouter",
                            endpointUrl = OPENROUTER_ENDPOINT,
                            apiKey = config.openRouterKey,
                            model = config.openRouterModel,
                            riskProfile = riskProfile,
                            raceTitle = raceTitle,
                            rawRaceData = rawRaceData,
                            userNewsNotes = userNewsNotes,
                            liveNewsContext = liveNewsPair.first,
                            extraHeaders = mapOf(
                                "HTTP-Referer" to "https://asbdavani.app",
                                "X-Title" to "AsbDavaniAI"
                            ),
                            maxDataChars = 13000
                        )
                        MemberResult(
                            providerName = "OpenRouter",
                            modelName = config.openRouterModel,
                            report = res.getOrNull(),
                            error = res.exceptionOrNull()?.message
                        )
                    })
                }
            }
            jobs.map { it.await() }
        }

        val successful = memberResults.mapNotNull { r -> r.report?.let { r to it } }
        val warnings = memberResults.mapNotNull { r ->
            r.error?.let { "${r.providerName}: $it" }
        }

        if (successful.isEmpty()) {
            error("همه هوش مصنوعی‌ها با خطا مواجه شدند:\n" + warnings.joinToString("\n"))
        }

        val mergedReport = mergeReports(
            successful = successful,
            searchSources = liveNewsPair.second
        )
        CouncilOutput(report = mergedReport, memberWarnings = warnings)
    }

    private fun mergeReports(
        successful: List<Pair<MemberResult, PredictionReport>>,
        searchSources: List<String>
    ): PredictionReport {
        val opinions = successful.map { (member, rep) ->
            val top1 = rep.topThree.firstOrNull()
                ?: rep.horseAnalyses.maxByOrNull { it.winProbability }?.let {
                    TopPick(rank = 1, number = it.number, horseName = it.horseName, winProbability = it.winProbability, reason = it.verdict)
                }
            AiMemberOpinion(
                providerName = member.providerName,
                modelName = member.modelName,
                topPickNumber = top1?.number ?: "-",
                topPickName = top1?.horseName.orEmpty(),
                topPickWinProb = top1?.winProbability ?: 0,
                confidencePercent = rep.confidencePercent,
                summary = top1?.reason ?: rep.raceSummary
            )
        }

        if (successful.size == 1) {
            val single = successful.first().second
            return single.copy(
                consensusSummary = "تحلیل تک‌مدل (${successful.first().first.providerName}) — برای مقایسه چندمدلی، کلید Groq یا OpenRouter را هم اضافه کنید.",
                memberOpinions = opinions,
                searchSources = if (single.searchSources.isNotEmpty()) single.searchSources else searchSources
            )
        }

        val primary = successful.first().second

        class HorseBucket(
            var number: String,
            var name: String,
            val winProbs: MutableList<Int> = mutableListOf(),
            val placeProbs: MutableList<Int> = mutableListOf(),
            val formScores: MutableList<Int> = mutableListOf(),
            val strengths: LinkedHashSet<String> = linkedSetOf(),
            val risks: LinkedHashSet<String> = linkedSetOf(),
            val verdicts: MutableList<String> = mutableListOf()
        )

        val buckets = linkedMapOf<String, HorseBucket>()
        for ((member, rep) in successful) {
            for (h in rep.horseAnalyses) {
                val key = normalizeHorseKey(h.number, h.horseName)
                val bucket = buckets.getOrPut(key) {
                    HorseBucket(number = h.number, name = h.horseName)
                }
                if (bucket.number == "-" && h.number != "-") bucket.number = h.number
                if (bucket.name.isBlank() && h.horseName.isNotBlank()) bucket.name = h.horseName
                bucket.winProbs.add(h.winProbability)
                bucket.placeProbs.add(h.placeProbability)
                bucket.formScores.add(h.formScore)
                bucket.strengths.addAll(h.strengths.take(2))
                bucket.risks.addAll(h.risks.take(2))
                if (h.verdict.isNotBlank()) {
                    bucket.verdicts.add("${member.providerName}: ${h.verdict}")
                }
            }
        }

        val count = successful.size.toDouble()
        val rawCombined = buckets.values.map { b ->
            val avgWin = b.winProbs.sum() / count
            val avgPlace = (b.placeProbs.sum() / count).roundToInt().coerceIn(0, 100)
            val avgForm = (b.formScores.sum() / count).roundToInt().coerceIn(1, 10)
            Triple(b, avgWin, avgPlace to avgForm)
        }

        val totalWinSum = rawCombined.sumOf { it.second }.takeIf { it > 0.0 } ?: 100.0
        val mergedHorses = rawCombined
            .map { (b, avgWin, placeAndForm) ->
                val normalizedWin = ((avgWin / totalWinSum) * 100.0).roundToInt().coerceIn(1, 99)
                HorseAnalysis(
                    number = b.number,
                    horseName = b.name,
                    winProbability = normalizedWin,
                    placeProbability = placeAndForm.first,
                    formScore = placeAndForm.second,
                    strengths = b.strengths.take(4),
                    risks = b.risks.take(3),
                    verdict = b.verdicts.joinToString(" | ")
                )
            }
            .sortedByDescending { it.winProbability }

        val firstPickGroups = opinions
            .filter { it.topPickName.isNotBlank() || it.topPickNumber != "-" }
            .groupBy { normalizeHorseKey(it.topPickNumber, it.topPickName) }

        val topGroup = firstPickGroups.maxByOrNull { it.value.size }
        val maxAgree = topGroup?.value?.size ?: 0
        val totalMembers = opinions.size
        val agreedSample = topGroup?.value?.firstOrNull()

        val consensusText = when {
            maxAgree == totalMembers && agreedSample != null ->
                "اجماع کامل ($totalMembers از $totalMembers هوش مصنوعی) روی #${agreedSample.topPickNumber} ${agreedSample.topPickName} ✅"
            maxAgree >= 2 && agreedSample != null ->
                "توافق اکثریت ($maxAgree از $totalMembers هوش مصنوعی) روی #${agreedSample.topPickNumber} ${agreedSample.topPickName}"
            else ->
                "اختلاف نظر بین اعضای شورا ($totalMembers انتخاب متفاوت) — کورس نزدیک و پرریسک است ⚠️"
        }

        val topThreeMerged = if (mergedHorses.isNotEmpty()) {
            mergedHorses.take(3).mapIndexed { index, h ->
                val pickReasons = successful.mapNotNull { (m, rep) ->
                    val match = rep.topThree.firstOrNull {
                        normalizeHorseKey(it.number, it.horseName) == normalizeHorseKey(h.number, h.horseName)
                    }
                    match?.let { "${m.providerName} (رتبه ${it.rank}): ${it.reason}" }
                }
                TopPick(
                    rank = index + 1,
                    number = h.number,
                    horseName = h.horseName,
                    winProbability = h.winProbability,
                    reason = if (pickReasons.isNotEmpty()) {
                        pickReasons.joinToString("\n")
                    } else {
                        h.verdict
                    }
                )
            }
        } else {
            primary.topThree
        }

        val avgConfidence = (successful.map { it.second.confidencePercent }.average() +
            when {
                maxAgree == totalMembers -> 6
                maxAgree == 1 && totalMembers >= 3 -> -8
                else -> 0
            }).roundToInt().coerceIn(25, 95)

        val confidenceLbl = when {
            avgConfidence >= 72 -> "بالا"
            avgConfidence >= 52 -> "متوسط"
            else -> "پایین"
        }

        val mergedAlerts = successful
            .flatMap { (m, rep) ->
                rep.anomalyAlerts.map { alert ->
                    AnomalySignal(
                        title = "${alert.title} (${m.providerName})",
                        severity = alert.severity,
                        description = alert.description
                    )
                }
            }
            .distinctBy { it.description.take(40) }
            .take(8)

        val mergedNews = successful
            .mapNotNull { (m, rep) ->
                val txt = rep.newsAndContextSummary.trim()
                if (txt.isNotBlank()) "${m.providerName}: $txt" else null
            }
            .distinct()
            .joinToString("\n")

        val mergedScenario = successful
            .mapNotNull { (m, rep) ->
                val txt = rep.tacticalScenario.trim()
                if (txt.isNotBlank()) "• ${m.providerName}: $txt" else null
            }
            .joinToString("\n")

        val mergedDarkHorse: DarkHorsePick? = successful
            .mapNotNull { (m, rep) ->
                rep.darkHorse?.takeIf { it.horseName.isNotBlank() }?.let { d ->
                    d.copy(reason = "(${m.providerName}) ${d.reason}")
                }
            }
            .firstOrNull()

        return PredictionReport(
            raceSummary = primary.raceSummary.ifBlank {
                successful.firstNotNullOfOrNull { it.second.raceSummary.takeIf { s -> s.isNotBlank() } }.orEmpty()
            },
            trackAndClassNote = primary.trackAndClassNote,
            confidenceLevel = confidenceLbl,
            confidencePercent = avgConfidence,
            consensusSummary = consensusText,
            memberOpinions = opinions,
            newsAndContextSummary = mergedNews,
            tacticalScenario = mergedScenario,
            topThree = topThreeMerged,
            darkHorse = mergedDarkHorse,
            anomalyAlerts = mergedAlerts,
            horseAnalyses = mergedHorses,
            searchSources = searchSources,
            disclaimer = primary.disclaimer
        )
    }

    private fun normalizeHorseKey(number: String, name: String): String {
        val asciiDigits = toAsciiDigits(number).filter { it.isDigit() }
        if (asciiDigits.isNotEmpty()) {
            return "num:$asciiDigits"
        }
        val normName = name.trim()
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace("‌", "")
            .replace(" ", "")
        return "name:$normName"
    }

    private fun toAsciiDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    companion object {
        const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
