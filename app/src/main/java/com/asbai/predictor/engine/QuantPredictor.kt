package com.asbai.predictor.engine

import com.asbai.predictor.model.AnomalySignal
import com.asbai.predictor.model.DarkHorsePick
import com.asbai.predictor.model.HorseAnalysis
import com.asbai.predictor.model.ModelWeights
import com.asbai.predictor.model.ParsedHorseRow
import com.asbai.predictor.model.PreScoredHorse
import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import com.asbai.predictor.model.TopPick
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

data class QuantPredictionOutput(
    val report: PredictionReport,
    val featureVectors: Map<String, List<Double>>
)

object QuantPredictor {

    private val formRegex = Regex("""(\d+)\s*-\s*(\d+)\s*-\s*(\d+)\s*/\s*(\d+)""")

    fun predict(
        rawText: String,
        pageTitle: String,
        sourceUrl: String,
        weights: ModelWeights,
        riskProfile: RiskProfile,
        newsNotes: String,
        scratchedKeys: Set<String> = emptySet()
    ): Result<QuantPredictionOutput> = runCatching {
        val readResult = PageReaderEngine.read(rawText, pageTitle, sourceUrl)
        val ctx = readResult.context

        val activeHorses = ctx.horses.filterNot { "${it.number}:${it.horseName}" in scratchedKeys }
        if (activeHorses.size < 2) {
            error("جدول اسب‌ها با ساختار کافی پیدا نشد. لطفاً در «مرورگر سایت» داخل صفحه‌ی دقیق مسابقه بروید و دکمه استخراج را بزنید.")
        }

        val fieldSize = activeHorses.size
        val placeCutoff = when {
            fieldSize <= 4 -> 1
            fieldSize <= 7 -> 2
            fieldSize <= 15 -> 3
            else -> 4
        }

        val avgBestTime = activeHorses.mapNotNull { it.bestTimeSec }.takeIf { it.size >= 2 }?.average()
        val sortedTimes = activeHorses.mapNotNull { it.bestTimeSec }.distinct().sorted()
        val fastestTime = sortedTimes.getOrNull(0)
        val secondFastestTime = sortedTimes.getOrNull(1)

        val avgRating = activeHorses.mapNotNull { it.rating }.takeIf { it.size >= 2 }?.average()
        val ratingSpread = activeHorses.mapNotNull { it.rating }.takeIf { it.size >= 2 }?.let {
            (it.max() - it.min()).coerceAtLeast(8.0)
        } ?: 20.0
        val avgWeight = activeHorses.mapNotNull { it.weightKg }.takeIf { it.size >= 2 }?.average()
        val distFactor = if ((ctx.distanceMeters ?: 1400) >= 1600) 1.25 else 1.0

        val normalizedMarketProbs = computeNormalizedMarketProbs(activeHorses)
        val trendSlopes = activeHorses.map { computeFormTrendSlope(it.recentDigits) }
        val rawFormScores = activeHorses.mapIndexed { i, h -> computeRawFormScore(h, trendSlopes[i]) }
        val avgFormScore = rawFormScores.average()
        val effWeights = adjustWeightsForProfile(weights, riskProfile)

        val computed = activeHorses.mapIndexed { idx, h ->
            val fForm = ((rawFormScores[idx] - avgFormScore) / 25.0).coerceIn(-1.0, 1.0)
            val fSpeed = if (h.bestTimeSec != null && avgBestTime != null) {
                ((avgBestTime - h.bestTimeSec) / 1.5).coerceIn(-1.0, 1.0)
            } else 0.0
            val fRating = if (h.rating != null && avgRating != null) {
                ((h.rating - avgRating) / (ratingSpread / 2.0)).coerceIn(-1.0, 1.0)
            } else 0.0
            val fWeight = if (h.weightKg != null && avgWeight != null) {
                (((avgWeight - h.weightKg) * distFactor) / 3.5).coerceIn(-1.0, 1.0)
            } else 0.0
            val fFreshness = computeFreshnessFeature(h.daysSinceLast)

            val drawPct = h.gate?.let { g ->
                ((g - 1).toDouble() / (fieldSize - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            }
            val fDraw = computeDrawFeature(drawPct)

            val marketProb = normalizedMarketProbs[idx]
            val steamRatio = if (h.openingOdds != null && h.marketOdds != null && h.openingOdds > 1.0) {
                ((h.openingOdds - h.marketOdds) / h.openingOdds).coerceIn(-0.4, 0.4)
            } else 0.0
            val baseMarket = if (marketProb != null) {
                ((marketProb - (100.0 / fieldSize)) / 20.0)
            } else 0.0
            val fMarket = (baseMarket + (steamRatio * 1.8)).coerceIn(-1.0, 1.0)

            val features = listOf(fForm, fSpeed, fRating, fWeight, fFreshness, fDraw, fMarket)
            val techScore = (fForm * effWeights.formWeight) +
                (fSpeed * effWeights.speedWeight) +
                (fRating * effWeights.ratingWeight) +
                (fWeight * effWeights.weightDiffWeight) +
                (fFreshness * effWeights.freshnessWeight) +
                (fDraw * effWeights.drawWeight) +
                (steamRatio * 0.65)
            val totalScore = techScore + (fMarket * effWeights.marketWeight)
            val (winRate, placeRate) = extractWinPlaceRates(h)

            ComputedHorse(h, features, techScore, totalScore, drawPct, marketProb, winRate, placeRate, trendSlopes[idx], steamRatio)
        }

        val winProbs = softmaxPercents(computed.map { it.totalScore }, temp = 1.10)
        val techProbs = softmaxPercents(computed.map { it.techScore }, temp = 1.10)

        val marketRankedKeys = computed
            .filter { it.marketProb != null }
            .sortedByDescending { it.marketProb }
            .mapIndexed { idx, c -> "${c.horse.number}:${c.horse.horseName}" to (idx + 1) }
            .toMap()

        val enriched = computed.mapIndexed { idx, c ->
            val wp = winProbs[idx]
            val tp = techProbs[idx]
            val fairOdds = ((100.0 / wp) * 10.0).roundToInt() / 10.0
            val minValueOdds = (fairOdds * 1.15 * 10.0).roundToInt() / 10.0
            val edgePct = c.marketProb?.let { mp ->
                (((tp.toDouble() / mp) - 1.0) * 100.0).roundToInt().coerceIn(-85, 250)
            }
            val effectiveOdds = c.horse.marketOdds?.takeIf { it > 1.0 } ?: c.marketProb?.let { 100.0 / it }
            val quarterKellyPct = if (effectiveOdds != null && effectiveOdds > 1.05) {
                val p = wp / 100.0
                val b = effectiveOdds - 1.0
                val k = ((b * p - (1.0 - p)) / b).coerceAtLeast(0.0)
                ((k * 0.25 * 100.0) * 10.0).roundToInt() / 10.0
            } else 0.0

            val placeProb = (wp * (1.1 + placeCutoff * 0.65)).roundToInt().coerceIn(wp, 95)
            val mathScore = (50.0 + (c.totalScore * 16.0)).roundToInt().coerceIn(12, 99)

            EnrichedHorse(c, wp, placeProb, fairOdds, minValueOdds, tp, edgePct, quarterKellyPct, mathScore)
        }.sortedByDescending { it.winProb }

        val preScores = enriched.map { e ->
            val c = e.computed
            val summary = buildList {
                add("شانس ${e.winProb}٪ (ضریب ${fmt1(e.fairOdds)} | ارزش ≥ ${fmt1(e.minValueOdds)})")
                if (c.marketProb != null && e.edgePct != null) {
                    val sign = if (e.edgePct >= 0) "+${e.edgePct}" else "${e.edgePct}"
                    add("بازار خالص ${c.marketProb.roundToInt()}٪ | Edge $sign٪")
                }
                if (e.quarterKellyPct >= 0.5) add("Kelly ${fmt1(e.quarterKellyPct)}٪")
                if (c.horse.bestTimeSec != null) add("تایم ${formatSec(c.horse.bestTimeSec)}")
                if (c.horse.daysSinceLast != null) add("استراحت ${c.horse.daysSinceLast} روز")
            }.joinToString(" • ")

            PreScoredHorse(
                number = c.horse.number,
                horseName = c.horse.horseName,
                mathScore = e.mathScore,
                mathWinProb = e.winProb,
                fairOdds = e.fairOdds,
                minValueOdds = e.minValueOdds,
                marketOdds = c.horse.marketOdds,
                marketProbPercent = c.marketProb?.roundToInt(),
                edgePercent = e.edgePct,
                drawPct = c.drawPct,
                winRatePercent = c.winRate,
                placeRatePercent = c.placeRate,
                summary = summary
            )
        }

        val analyses = enriched.mapIndexed { idx, e ->
            val c = e.computed
            val f = c.features
            val key = "${c.horse.number}:${c.horse.horseName}"
            val mRank = marketRankedKeys[key]

            val mark = when {
                idx == 0 -> "◎"
                idx == 1 -> "○"
                idx == 2 -> "▲"
                (e.edgePct ?: 0) >= 15 -> "☆"
                idx == 3 -> "△"
                else -> ""
            }

            val marketBadge = when {
                idx <= 1 && mRank != null && mRank <= 2 -> "🥇 طلایی (توافق بازار)"
                c.steamRatio >= 0.08 -> "🔥 پول هوشمند (افت ضریب)"
                idx <= 2 && (e.edgePct ?: 0) >= 12 -> "🥈 نقره‌ای (شکار ارزش)"
                else -> ""
            }

            val speedBadge = when {
                c.horse.bestTimeSec != null && fastestTime != null && c.horse.bestTimeSec == fastestTime -> "⚡ طلای سرعت"
                c.horse.bestTimeSec != null && secondFastestTime != null && c.horse.bestTimeSec == secondFastestTime -> "⚡ نقره سرعت"
                else -> ""
            }

            val abilityScore = (5.5 + f[0] * 2.5 + f[2] * 2.0).roundToInt().coerceIn(1, 10)
            val speedFitnessScore = (5.5 + f[1] * 2.8 + f[4] * 1.7).roundToInt().coerceIn(1, 10)
            val suitabilityScore = (5.5 + f[3] * 2.5 + f[5] * 2.0).roundToInt().coerceIn(1, 10)
            val overall10 = ((abilityScore + speedFitnessScore + suitabilityScore) / 3.0).roundToInt().coerceIn(1, 10)

            val verdict = when {
                idx == 0 && e.winProb >= 26 -> "تک‌محور و مدعی اصلی"
                (e.edgePct ?: 0) >= 15 -> "گزینه باارزش (Kelly ${fmt1(e.quarterKellyPct)}٪)"
                e.placeProb >= 45 -> "شانس بالا برای سکو"
                else -> "ذخیره / ریسکی"
            }

            HorseAnalysis(
                number = c.horse.number,
                horseName = c.horse.horseName,
                mark = mark,
                winProbability = e.winProb,
                placeProbability = e.placeProb,
                formScore = overall10,
                abilityScore = abilityScore,
                speedFitnessScore = speedFitnessScore,
                suitabilityScore = suitabilityScore,
                fairOdds = e.fairOdds,
                minValueOdds = e.minValueOdds,
                marketBadge = marketBadge,
                speedBadge = speedBadge,
                strengths = buildHorseStrengths(c, f, e.edgePct, avgWeight, avgBestTime),
                risks = buildHorseRisks(c, f),
                verdict = verdict
            )
        }

        val topThree = enriched.take(3).mapIndexed { idx, e ->
            val c = e.computed
            val reasons = buildHorseStrengths(c, c.features, e.edgePct, avgWeight, avgBestTime)
            TopPick(
                rank = idx + 1,
                number = c.horse.number,
                horseName = c.horse.horseName,
                winProbability = e.winProb,
                reason = reasons.ifEmpty { listOf("امتیاز متوازن در مجموع فاکتورهای فنی") }.joinToString(" • ")
            )
        }

        val darkHorse = enriched.drop(1)
            .filter { (it.edgePct ?: 0) >= 12 || it.computed.steamRatio >= 0.08 || it.techProb >= 12 }
            .maxByOrNull { (it.edgePct ?: 0) + (it.computed.steamRatio * 100).roundToInt() }
            ?.let { e ->
                val c = e.computed
                val edgeTxt = e.edgePct?.let { "اختلاف ارزش +$it٪ نسبت به بازار دارد (Kelly ${fmt1(e.quarterKellyPct)}٪)" }
                    ?: "از نظر فنی و وزنی پتانسیل شگفتی‌سازی دارد"
                DarkHorsePick(
                    number = c.horse.number,
                    horseName = c.horse.horseName,
                    winProbability = e.winProb,
                    reason = "$edgeTxt (${buildHorseStrengths(c, c.features, e.edgePct, avgWeight, avgBestTime).firstOrNull() ?: "فرم مناسب"})"
                )
            }

        val gap12 = if (enriched.size >= 2) enriched[0].winProb - enriched[1].winProb else 20
        val gap23 = if (enriched.size >= 3) enriched[1].winProb - enriched[2].winProb else 0
        val dataRichness = listOf(avgBestTime != null, avgRating != null, avgWeight != null, rawFormScores.any { it != 50.0 }).count { it }

        val stars = when {
            gap12 >= 10 && dataRichness >= 2 -> 4
            gap12 >= 6 -> 3
            gap12 >= 3 -> 2
            else -> 1
        }
        val confPct = (48 + dataRichness * 8 + gap12.coerceAtMost(14)).coerceIn(45, 89)
        val confLvl = when {
            stars >= 4 -> "بسیار بالا"
            stars == 3 -> "خوب"
            stars == 2 -> "متوسط"
            else -> "پرریسک"
        }

        val h1 = enriched[0].computed.horse
        val h2 = enriched.getOrNull(1)?.computed?.horse
        val advantageNote = when {
            gap12 >= 8 -> "🔥 تک‌بخت مطمئن (Strongest Single): #${h1.number} ${h1.horseName} با +$gap12٪ برتری!"
            gap12 >= 5 -> "✅ Advantage Pick: #${h1.number} ${h1.horseName} (+$gap12٪ جلوتر از نفر دوم)"
            gap23 >= 5 && h2 != null -> "🔸 Second Choice Advantage: #${h2.number} ${h2.horseName} (+$gap23٪ جلوتر از نفر سوم)"
            else -> "⚖️ کورس بسیار نزدیک و رقابتی بین مدعیان اصلی"
        }

        val comboSuggestion = buildComboSuggestion(enriched)
        val distInfo = listOfNotNull(ctx.distanceMeters?.let { "${it} متر" }, ctx.surface.takeIf { it.isNotBlank() }).joinToString(" ")
        val featureMap = enriched.associate { e -> "${e.computed.horse.number}:${e.computed.horse.horseName}" to e.computed.features }

        val report = PredictionReport(
            raceSummary = "${ctx.raceTitle} ($fieldSize اسب${if (distInfo.isNotBlank()) " — $distInfo" else ""})",
            trackAndClassNote = "حد سکو: $placeCutoff نفر اول | استراتژی: ${riskProfile.label}",
            confidenceLevel = confLvl,
            confidencePercent = confPct,
            confidenceStars = stars,
            advantageNote = advantageNote,
            comboSuggestion = comboSuggestion,
            extractionSource = readResult.sourceLabel,
            scratchedKeys = scratchedKeys.toList(),
            consensusSummary = "موتور کمی خودآموز (${weights.totalRacesLogged} کورس آموزش‌دیده)",
            preScores = preScores,
            newsAndContextSummary = newsNotes.trim(),
            tacticalScenario = buildTacticalScenario(enriched, ctx.distanceMeters),
            topThree = topThree,
            darkHorse = darkHorse,
            anomalyAlerts = buildAnomalies(activeHorses, enriched, avgBestTime),
            horseAnalyses = analyses
        )

        QuantPredictionOutput(report = report, featureVectors = featureMap)
    }

    fun calibrate(current: ModelWeights, saved: SavedPrediction, winnerKey: String): Pair<ModelWeights, String> {
        val winnerFeatures = saved.horseFeatures[winnerKey]
        val top1 = saved.report.topThree.firstOrNull()
        val top1Features = top1?.let { saved.horseFeatures["${it.number}:${it.horseName}"] }

        val winNum = winnerKey.substringBefore(":").trim()
        val isTop1Hit = saved.report.topThree.firstOrNull()?.number == winNum
        val isTop3Hit = saved.report.topThree.any { it.number == winNum }

        var next = current.copy(
            totalRacesLogged = current.totalRacesLogged + 1,
            top1Hits = current.top1Hits + if (isTop1Hit) 1 else 0,
            top3Hits = current.top3Hits + if (isTop3Hit) 1 else 0
        )

        if (winnerFeatures != null && top1Features != null && winnerFeatures.size == 7 && top1Features.size == 7 && !isTop1Hit) {
            val lr = 0.08
            next = next.copy(
                formWeight = (next.formWeight + lr * (winnerFeatures[0] - top1Features[0])).coerceIn(0.25, 2.5),
                speedWeight = (next.speedWeight + lr * (winnerFeatures[1] - top1Features[1])).coerceIn(0.25, 2.5),
                ratingWeight = (next.ratingWeight + lr * (winnerFeatures[2] - top1Features[2])).coerceIn(0.25, 2.5),
                weightDiffWeight = (next.weightDiffWeight + lr * (winnerFeatures[3] - top1Features[3])).coerceIn(0.25, 2.5),
                freshnessWeight = (next.freshnessWeight + lr * (winnerFeatures[4] - top1Features[4])).coerceIn(0.25, 2.5),
                drawWeight = (next.drawWeight + lr * (winnerFeatures[5] - top1Features[5])).coerceIn(0.25, 2.5),
                marketWeight = (next.marketWeight + lr * (winnerFeatures[6] - top1Features[6])).coerceIn(0.25, 2.5)
            )
        }

        val hitTxt = when {
            isTop1Hit -> "🎯 پیش‌بینی اول دقیقاً درست بود!"
            isTop3Hit -> "✅ برنده در ۳تای اول پیش‌بینی بود!"
            else -> "📊 وزن‌های مدل بر اساس ویژگی‌های برنده اصلاح شدند."
        }
        return next to hitTxt
    }

    private fun computeNormalizedMarketProbs(horses: List<ParsedHorseRow>): List<Double?> {
        val rawProbs = horses.map { h ->
            h.marketAgfPct?.takeIf { it > 0.0 }
                ?: h.marketOdds?.takeIf { it > 1.0 }?.let { 100.0 / it }
        }
        val validCount = rawProbs.count { it != null }
        val totalSum = rawProbs.filterNotNull().sum()
        if (validCount >= (horses.size / 2).coerceAtLeast(2) && totalSum > 1.0) {
            return rawProbs.map { rp -> rp?.let { ((it / totalSum) * 100.0).coerceIn(1.0, 92.0) } }
        }
        return rawProbs.map { it?.coerceIn(1.0, 92.0) }
    }

    private fun computeFormTrendSlope(digits: List<Int>): Double {
        val slice = digits.takeLast(5).map { it.coerceIn(1, 8).toDouble() }
        if (slice.size < 3) return 0.0
        val n = slice.size.toDouble()
        val meanX = (n - 1.0) / 2.0
        val meanY = slice.average()
        var num = 0.0
        var den = 0.0
        for (i in slice.indices) {
            val dx = i - meanX
            num += dx * (slice[i] - meanY)
            den += dx * dx
        }
        return if (den > 0.0) num / den else 0.0
    }

    private fun buildComboSuggestion(enriched: List<EnrichedHorse>): String {
        if (enriched.size < 3) return ""
        val n1 = enriched[0].computed.horse
        val n2 = enriched[1].computed.horse
        val n3 = enriched[2].computed.horse
        val n4 = enriched.getOrNull(3)?.computed?.horse
        val gap12 = enriched[0].winProb - enriched[1].winProb

        return if (gap12 >= 7 && n4 != null) {
            "🎯 پیشنهاد ترکیب: تک‌محور ◎ #${n1.number} ${n1.horseName} ثابت برای اول، و چرخش روی #${n2.number}، #${n3.number}، #${n4.number}"
        } else {
            val fourth = n4?.let { "، #${it.number}" } ?: ""
            "🔄 پیشنهاد چرخشی (Box): ترکیب مدعیان نزدیک (#${n1.number}، #${n2.number}، #${n3.number}$fourth)"
        }
    }

    private fun adjustWeightsForProfile(w: ModelWeights, p: RiskProfile): ModelWeights = when (p) {
        RiskProfile.BALANCED -> w
        RiskProfile.CONSERVATIVE -> w.copy(formWeight = w.formWeight * 1.25, speedWeight = w.speedWeight * 1.2, ratingWeight = w.ratingWeight * 1.2, marketWeight = w.marketWeight * 1.2)
        RiskProfile.UPSET_HUNTER -> w.copy(marketWeight = w.marketWeight * 0.4, weightDiffWeight = w.weightDiffWeight * 1.3, drawWeight = w.drawWeight * 1.25, freshnessWeight = w.freshnessWeight * 1.2)
    }

    private fun computeRawFormScore(h: ParsedHorseRow, trendSlope: Double): Double {
        var baseScore = 50.0
        var hasMain = false

        if (h.formString != null) {
            val m = formRegex.find(h.formString)
            if (m != null) {
                val f = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val s = m.groupValues[2].toDoubleOrNull() ?: 0.0
                val t = m.groupValues[3].toDoubleOrNull() ?: 0.0
                val n = (m.groupValues[4].toDoubleOrNull() ?: 0.0).coerceAtLeast(1.0)
                baseScore = (((f + 0.35) / (n + 3.0)) * 120.0) + (((f + s + t + 0.9) / (n + 3.0)) * 65.0)
                hasMain = true
            }
        }
        if (h.recentDigits.isNotEmpty()) {
            val recencyWeights = listOf(0.6, 0.8, 1.0, 1.2, 1.45, 1.7)
            val slice = h.recentDigits.takeLast(6)
            val offset = recencyWeights.size - slice.size
            var sumW = 0.0
            var sumPts = 0.0
            slice.forEachIndexed { idx, pos ->
                val w = recencyWeights[offset + idx]
                val cappedPos = pos.coerceIn(1, 8)
                sumW += w
                sumPts += (((9.5 - cappedPos) / 8.5) * 95.0) * w
            }
            val winCountBonus = slice.count { it == 1 } * 12.0
            val placeCountBonus = slice.count { it in 2..3 } * 4.0
            val recentScore = (if (sumW > 0) sumPts / sumW else 50.0) + winCountBonus + placeCountBonus + (h.s20Score?.let { (it - 16) * 1.5 } ?: 0.0)
            baseScore = if (hasMain) (baseScore * 0.55 + recentScore * 0.45) else recentScore
        }

        val trendAdjust = (-trendSlope * 1.3).coerceIn(-6.0, 6.0)
        return baseScore + trendAdjust
    }

    private fun extractWinPlaceRates(h: ParsedHorseRow): Pair<Int, Int> {
        h.formString?.let { fs ->
            val m = formRegex.find(fs)
            if (m != null) {
                val f = m.groupValues[1].toIntOrNull() ?: 0
                val s = m.groupValues[2].toIntOrNull() ?: 0
                val t = m.groupValues[3].toIntOrNull() ?: 0
                val n = (m.groupValues[4].toIntOrNull() ?: 0).coerceAtLeast(1)
                return ((f * 100.0) / n).roundToInt() to (((f + s + t) * 100.0) / n).roundToInt().coerceAtMost(100)
            }
        }
        if (h.recentDigits.isNotEmpty()) {
            val n = h.recentDigits.size
            return ((h.recentDigits.count { it == 1 } * 100.0) / n).roundToInt() to ((h.recentDigits.count { it in 1..3 } * 100.0) / n).roundToInt()
        }
        return 0 to 0
    }

    private fun computeFreshnessFeature(days: Int?): Double = when {
        days == null -> 0.0
        days in 8..28 -> 0.75
        days in 29..45 -> 0.25
        days in 5..7 -> 0.1
        days < 5 -> -0.35
        days in 46..75 -> -0.3
        else -> -0.75
    }

    private fun computeDrawFeature(drawPct: Double?): Double = when {
        drawPct == null -> 0.0
        drawPct <= 0.25 -> 0.35
        drawPct <= 0.55 -> 0.15
        drawPct <= 0.80 -> -0.05
        else -> -0.22
    }

    private fun softmaxPercents(scores: List<Double>, temp: Double): List<Int> {
        val maxS = scores.maxOrNull() ?: 0.0
        val exps = scores.map { exp(((it - maxS) / temp).coerceIn(-8.0, 0.0)) }
        val sum = exps.sum().takeIf { it > 0.0 } ?: 1.0
        return exps.map { ((it / sum) * 100.0).roundToInt().coerceIn(1, 95) }
    }

    private fun buildHorseStrengths(c: ComputedHorse, f: List<Double>, edgePct: Int?, avgWeight: Double?, avgBestTime: Double?): List<String> = buildList {
        if (c.steamRatio >= 0.08 && c.horse.openingOdds != null && c.horse.marketOdds != null) {
            add("🔥 پول هوشمند: افت ضریب (${fmt1(c.horse.openingOdds)} ← ${fmt1(c.horse.marketOdds)})")
        }
        if (c.horse.recentDigits.any { it == 1 }) {
            add("سابقه برد در کورس‌های اخیر")
        }
        if (c.trendSlope <= -0.7) add("📈 روند رو به رشد در کورس‌های اخیر")
        if (c.horse.bestTimeSec != null && avgBestTime != null && c.horse.bestTimeSec < avgBestTime - 0.3) {
            add("رکورد زمانی سریع‌تر از میانگین (${formatSec(c.horse.bestTimeSec)})")
        }
        if (f[2] >= 0.3) add("ریتینگ / هندیکپ برتر (${c.horse.rating?.toInt()})")
        if (c.horse.weightKg != null && avgWeight != null && c.horse.weightKg <= avgWeight - 1.5) add("مزیت وزن سبک (${c.horse.weightKg}kg)")
        if (f[4] >= 0.5) add("آمادگی زمانی عالی (${c.horse.daysSinceLast} روز استراحت)")
        if (c.drawPct != null && c.drawPct <= 0.30) add("دپار داخلی مناسب (${String.format(Locale.US, "%.2f", c.drawPct)})")
        if (edgePct != null && edgePct >= 15) add("ارزش پنهان +$edgePct٪ نسبت به بازار")
    }.take(4)

    private fun buildHorseRisks(c: ComputedHorse, f: List<Double>): List<String> = buildList {
        if (c.steamRatio <= -0.14 && c.horse.openingOdds != null && c.horse.marketOdds != null) {
            add("افت اقبال بازار: افزایش ضریب (${fmt1(c.horse.openingOdds)} ← ${fmt1(c.horse.marketOdds)})")
        }
        if (c.trendSlope >= 0.9) add("📉 روند نزولی در کورس‌های اخیر")
        if (f[0] <= -0.35) add("افت فرم در مسابقات اخیر")
        if (f[3] <= -0.35) add("وزن سنگین‌تر از میانگین (${c.horse.weightKg}kg)")
        if (c.horse.daysSinceLast != null && c.horse.daysSinceLast > 60) add("دوری طولانی (${c.horse.daysSinceLast} روز)")
        if (c.drawPct != null && c.drawPct >= 0.85) add("دپار بیرونی (${c.horse.gate})")
    }.take(3)

    private fun buildAnomalies(horses: List<ParsedHorseRow>, enriched: List<EnrichedHorse>, avgBestTime: Double?): List<AnomalySignal> {
        val list = mutableListOf<AnomalySignal>()
        enriched.filter { it.computed.steamRatio >= 0.08 }.forEach { e ->
            val h = e.computed.horse
            if (h.openingOdds != null && h.marketOdds != null) {
                list.add(AnomalySignal("پول هوشمند (Steamer) روی #${h.number} ${h.horseName}", "هشدار بالا", "ضریب اسب از ${fmt1(h.openingOdds)} به ${fmt1(h.marketOdds)} کاهش یافته است (ورود سرمایه جدی)."))
            }
        }
        horses.filter { it.trainer.isNotBlank() }.groupBy { it.trainer.trim().lowercase() }.filter { it.value.size >= 2 }.forEach { (_, g) ->
            list.add(AnomalySignal("هم‌مربی در کورس (${g.first().trainer})", "نکته تاکتیکی", "حضور همزمان ${g.size} اسب: ${g.joinToString("، ") { "#${it.number} ${it.horseName}" }}"))
        }
        horses.filter { it.owner.isNotBlank() }.groupBy { it.owner.trim().lowercase() }.filter { it.value.size >= 2 }.forEach { (_, g) ->
            list.add(AnomalySignal("هم‌مالک در کورس (${g.first().owner})", "نکته تاکتیکی", "مالکیت مشترک: ${g.joinToString("، ") { "#${it.number} ${it.horseName}" }}"))
        }
        enriched.firstOrNull { (it.edgePct ?: 0) >= 20 && (it.computed.marketProb ?: 100.0) <= 18.0 }?.let { e ->
            list.add(AnomalySignal("ارزش پنهان روی #${e.computed.horse.number} ${e.computed.horse.horseName}", "هشدار بالا", "بازار خالص فقط ${e.computed.marketProb?.roundToInt()}٪ است اما شاخص فنی ${e.techProb}٪ است (Edge +${e.edgePct}٪)."))
        }
        if (avgBestTime != null) {
            horses.filter { it.bestTimeSec != null }.minByOrNull { it.bestTimeSec!! }?.let { f ->
                if ((f.marketAgfPct ?: 50.0) < 15.0) {
                    list.add(AnomalySignal("سریع‌ترین رکورد کم‌توجه: #${f.number} ${f.horseName}", "نکته تاکتیکی", "بهترین زمان (${formatSec(f.bestTimeSec!!)}) را دارد اما AGF پایینی گرفته است."))
                }
            }
        }
        return list.take(6)
    }

    private fun buildTacticalScenario(enriched: List<EnrichedHorse>, dist: Int?): String {
        val insideFav = enriched.firstOrNull { it.computed.drawPct != null && it.computed.drawPct!! <= 0.30 }?.computed?.horse
        val top1 = enriched.first().computed.horse
        val distNote = if ((dist ?: 1400) <= 1300) "در این مسافت سرعتی، استارت سریع و دپار داخلی نقش کلیدی دارد." else "در این مسافت، ذخیره انرژی تا پیچ آخر و وزن سبک تعیین‌کننده است."
        val insideNote = insideFav?.let { " اسب #${it.number} ${it.horseName} از خط داخلی شانس شروع بهتری دارد." } ?: ""
        return "$distNote محور اصلی کورس روی #${top1.number} ${top1.horseName} پیش‌بینی می‌شود.$insideNote"
    }

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun formatSec(sec: Double): String {
        val m = (sec / 60.0).toInt()
        val rem = sec - (m * 60.0)
        return if (m > 0) String.format(Locale.US, "%d:%05.2f", m, rem) else String.format(Locale.US, "%.2fs", rem)
    }

    private data class ComputedHorse(
        val horse: ParsedHorseRow,
        val features: List<Double>,
        val techScore: Double,
        val totalScore: Double,
        val drawPct: Double?,
        val marketProb: Double?,
        val winRate: Int,
        val placeRate: Int,
        val trendSlope: Double,
        val steamRatio: Double
    )

    private data class EnrichedHorse(
        val computed: ComputedHorse,
        val winProb: Int,
        val placeProb: Int,
        val fairOdds: Double,
        val minValueOdds: Double,
        val techProb: Int,
        val edgePct: Int?,
        val quarterKellyPct: Double,
        val mathScore: Int
    )
}