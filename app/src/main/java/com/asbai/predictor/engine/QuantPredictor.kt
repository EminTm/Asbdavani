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
        newsNotes: String
    ): Result<QuantPredictionOutput> = runCatching {
        val ctx = MultiSiteParser.parse(rawText, pageTitle, sourceUrl)
        if (ctx.horses.size < 2) {
            error("جدول اسب‌ها با ساختار کافی پیدا نشد. لطفاً در «مرورگر سایت» جدول یک دور مشخص را باز کرده و دکمه استخراج را بزنید.")
        }

        val horses = ctx.horses
        val fieldSize = horses.size
        val placeCutoff = when {
            fieldSize <= 4 -> 1
            fieldSize <= 7 -> 2
            fieldSize <= 15 -> 3
            else -> 4
        }

        val avgBestTime = horses.mapNotNull { it.bestTimeSec }.takeIf { it.size >= 2 }?.average()
        val avgRating = horses.mapNotNull { it.rating }.takeIf { it.size >= 2 }?.average()
        val ratingSpread = horses.mapNotNull { it.rating }.takeIf { it.size >= 2 }?.let {
            (it.max() - it.min()).coerceAtLeast(8.0)
        } ?: 20.0
        val avgWeight = horses.mapNotNull { it.weightKg }.takeIf { it.size >= 2 }?.average()
        val distFactor = if ((ctx.distanceMeters ?: 1400) >= 1600) 1.25 else 1.0

        val rawFormScores = horses.map { computeRawFormScore(it) }
        val avgFormScore = rawFormScores.average()

        val effWeights = adjustWeightsForProfile(weights, riskProfile)

        val computed = horses.mapIndexed { idx, h ->
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
            val drawPct = h.gate?.let { (it.toDouble() / fieldSize).coerceIn(0.05, 1.0) }
            val fDraw = computeDrawFeature(drawPct)
            val marketProb = h.marketAgfPct ?: h.marketOdds?.takeIf { it > 1.0 }?.let { (100.0 / it) }
            val fMarket = if (marketProb != null) {
                ((marketProb - (100.0 / fieldSize)) / 22.0).coerceIn(-1.0, 1.0)
            } else 0.0

            val features = listOf(fForm, fSpeed, fRating, fWeight, fFreshness, fDraw, fMarket)
            val techScore = (fForm * effWeights.formWeight) +
                (fSpeed * effWeights.speedWeight) +
                (fRating * effWeights.ratingWeight) +
                (fWeight * effWeights.weightDiffWeight) +
                (fFreshness * effWeights.freshnessWeight) +
                (fDraw * effWeights.drawWeight)
            val totalScore = techScore + (fMarket * effWeights.marketWeight)

            val (winRate, placeRate) = extractWinPlaceRates(h)

            ComputedHorse(
                horse = h,
                features = features,
                techScore = techScore,
                totalScore = totalScore,
                drawPct = drawPct,
                marketProb = marketProb?.coerceIn(1.0, 90.0),
                winRate = winRate,
                placeRate = placeRate
            )
        }

        val winProbs = softmaxPercents(computed.map { it.totalScore }, temp = 1.45)
        val techProbs = softmaxPercents(computed.map { it.techScore }, temp = 1.35)

        val enriched = computed.mapIndexed { idx, c ->
            val wp = winProbs[idx]
            val tp = techProbs[idx]
            val fairOdds = ((100.0 / wp) * 10.0).roundToInt() / 10.0
            val edgePct = c.marketProb?.let { mp ->
                (((tp.toDouble() / mp) - 1.0) * 100.0).roundToInt().coerceIn(-85, 250)
            }
            val placeProb = (wp * (1.1 + placeCutoff * 0.65)).roundToInt().coerceIn(wp, 95)
            val mathScore = (50.0 + (c.totalScore * 16.0)).roundToInt().coerceIn(12, 99)

            Triple(c, Triple(wp, placeProb, fairOdds), Triple(tp, edgePct, mathScore))
        }.sortedByDescending { it.second.first }

        val preScores = enriched.map { (c, wpf, tem) ->
            val (wp, _, fairOdds) = wpf
            val (_, edgePct, mathScore) = tem
            val summary = buildList {
                add("شانس $wp٪ (ضریب ${String.format(Locale.US, "%.1f", fairOdds)})")
                if (c.marketProb != null && edgePct != null) {
                    val sign = if (edgePct >= 0) "+$edgePct" else "$edgePct"
                    add("AGF/بازار ${c.marketProb.roundToInt()}٪ | Edge $sign٪")
                }
                if (c.bestTimeSec != null) add("بهترین تایم ${formatSec(c.bestTimeSec)}")
                if (c.daysSinceLast != null) add("استراحت ${c.daysSinceLast} روز")
            }.joinToString(" • ")

            PreScoredHorse(
                number = c.horse.number,
                horseName = c.horse.horseName,
                mathScore = mathScore,
                mathWinProb = wp,
                fairOdds = fairOdds,
                marketProbPercent = c.marketProb?.roundToInt(),
                edgePercent = edgePct,
                drawPct = c.drawPct,
                winRatePercent = c.winRate,
                placeRatePercent = c.placeRate,
                summary = summary
            )
        }

        val analyses = enriched.map { (c, wpf, tem) ->
            val (wp, placeProb, _) = wpf
            val (_, edgePct, _) = tem
            val f = c.features
            val strengths = buildHorseStrengths(c, f, edgePct, avgWeight, avgBestTime)
            val risks = buildHorseRisks(c, f)
            val formScore10 = (5.5 + (f[0] * 2.5) + (f[1] * 1.5)).roundToInt().coerceIn(1, 10)
            val verdict = when {
                wp >= 25 -> "مدعی اصلی قهرمانی"
                edgePct != null && edgePct >= 15 -> "گزینه باارزش و خطرناک (Value)"
                placeProb >= 45 -> "شانس بالا برای سکو"
                else -> "گزینه ذخیره / ریسکی"
            }
            HorseAnalysis(
                number = c.horse.number,
                horseName = c.horse.horseName,
                winProbability = wp,
                placeProbability = placeProb,
                formScore = formScore10,
                strengths = strengths,
                risks = risks,
                verdict = verdict
            )
        }

        val topThree = enriched.take(3).mapIndexed { idx, (c, wpf, tem) ->
            val wp = wpf.first
            val f = c.features
            val reasons = buildHorseStrengths(c, f, tem.second, avgWeight, avgBestTime)
            TopPick(
                rank = idx + 1,
                number = c.horse.number,
                horseName = c.horse.horseName,
                winProbability = wp,
                reason = reasons.ifEmpty { listOf("امتیاز متوازن در مجموع فاکتورهای فنی") }.joinToString(" • ")
            )
        }

        val darkHorse = enriched.drop(1)
            .filter { (_, _, tem) -> (tem.second ?: 0) >= 12 || tem.first >= 12 }
            .maxByOrNull { (_, _, tem) -> tem.second ?: tem.first }
            ?.let { (c, wpf, tem) ->
                val edgeTxt = tem.second?.let { "اختلاف ارزش +$it٪ نسبت به بازار دارد" }
                    ?: "از نظر فنی و وزنی پتانسیل شگفتی‌سازی دارد"
                DarkHorsePick(
                    number = c.horse.number,
                    horseName = c.horse.horseName,
                    winProbability = wpf.first,
                    reason = "$edgeTxt (${buildHorseStrengths(c, c.features, tem.second, avgWeight, avgBestTime).firstOrNull() ?: "فرم مناسب"})"
                )
            }

        val anomalies = buildAnomalies(horses, enriched, avgBestTime)
        val topGap = if (enriched.size >= 2) enriched[0].second.first - enriched[1].second.first else 20
        val dataRichness = listOf(avgBestTime != null, avgRating != null, avgWeight != null, rawFormScores.any { it != 50.0 }).count { it }
        val confPct = (48 + dataRichness * 8 + topGap.coerceAtMost(14)).coerceIn(45, 88)
        val confLvl = when {
            confPct >= 72 -> "بالا"
            confPct >= 58 -> "متوسط"
            else -> "پایین"
        }

        val distInfo = listOfNotNull(
            ctx.distanceMeters?.let { "${it} متر" },
            ctx.surface.takeIf { it.isNotBlank() }
        ).joinToString(" ")

        val featureMap = enriched.associate { (c, _, _) ->
            "${c.horse.number}:${c.horse.horseName}" to c.features
        }

        val report = PredictionReport(
            raceSummary = "${ctx.raceTitle} ($fieldSize اسب${if (distInfo.isNotBlank()) " — $distInfo" else ""})",
            trackAndClassNote = "حد سکو: $placeCutoff نفر اول | استراتژی: ${riskProfile.label} | ${riskProfile.multiplierNote}",
            confidenceLevel = confLvl,
            confidencePercent = confPct,
            consensusSummary = "موتور کمی خودآموز Equintel-Quant (${weights.totalRacesLogged} کورس آموزش‌دیده)",
            preScores = preScores,
            newsAndContextSummary = newsNotes.trim(),
            tacticalScenario = buildTacticalScenario(enriched, ctx.distanceMeters),
            topThree = topThree,
            darkHorse = darkHorse,
            anomalyAlerts = anomalies,
            horseAnalyses = analyses
        )

        QuantPredictionOutput(report = report, featureVectors = featureMap)
    }

    fun calibrate(
        current: ModelWeights,
        saved: SavedPrediction,
        winnerKey: String
    ): Pair<ModelWeights, String> {
        val winnerFeatures = saved.horseFeatures[winnerKey]
        val top1 = saved.report.topThree.firstOrNull()
        val top1Key = top1?.let { "${it.number}:${it.horseName}" }
        val top1Features = top1Key?.let { saved.horseFeatures[it] }

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

    private fun adjustWeightsForProfile(w: ModelWeights, p: RiskProfile): ModelWeights = when (p) {
        RiskProfile.BALANCED -> w
        RiskProfile.CONSERVATIVE -> w.copy(
            formWeight = w.formWeight * 1.25,
            speedWeight = w.speedWeight * 1.2,
            ratingWeight = w.ratingWeight * 1.2,
            marketWeight = w.marketWeight * 1.2
        )
        RiskProfile.UPSET_HUNTER -> w.copy(
            marketWeight = w.marketWeight * 0.4,
            weightDiffWeight = w.weightDiffWeight * 1.3,
            drawWeight = w.drawWeight * 1.25,
            freshnessWeight = w.freshnessWeight * 1.2
        )
    }

    private fun computeRawFormScore(h: ParsedHorseRow): Double {
        if (h.formString != null) {
            val m = formRegex.find(h.formString)
            if (m != null) {
                val f = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val s = m.groupValues[2].toDoubleOrNull() ?: 0.0
                val t = m.groupValues[3].toDoubleOrNull() ?: 0.0
                val n = (m.groupValues[4].toDoubleOrNull() ?: 0.0).coerceAtLeast(1.0)
                val bayesWin = (f + 0.35) / (n + 3.0)
                val bayesPlace = (f + s + t + 0.9) / (n + 3.0)
                return (bayesWin * 120.0) + (bayesPlace * 65.0)
            }
        }
        if (h.recentDigits.isNotEmpty()) {
            val recencyWeights = listOf(0.5, 0.7, 0.9, 1.15, 1.45, 1.9)
            val slice = h.recentDigits.takeLast(6)
            val offset = recencyWeights.size - slice.size
            var sumW = 0.0
            var sumPts = 0.0
            slice.forEachIndexed { idx, pos ->
                val w = recencyWeights[offset + idx]
                val pts = ((11.0 - pos.coerceIn(1, 10)) / 10.0) * 100.0
                sumW += w
                sumPts += pts * w
            }
            val base = if (sumW > 0) sumPts / sumW else 50.0
            val s20Boost = h.s20Score?.let { (it - 16) * 1.5 } ?: 0.0
            return base + s20Boost
        }
        return 50.0
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
            val w = h.recentDigits.count { it == 1 }
            val p = h.recentDigits.count { it in 1..3 }
            val n = h.recentDigits.size
            return ((w * 100.0) / n).roundToInt() to ((p * 100.0) / n).roundToInt()
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
        drawPct <= 0.30 -> 0.65
        drawPct <= 0.55 -> 0.25
        drawPct <= 0.78 -> -0.15
        else -> -0.55
    }

    private fun softmaxPercents(scores: List<Double>, temp: Double): List<Int> {
        val maxS = scores.maxOrNull() ?: 0.0
        val exps = scores.map { exp(((it - maxS) / temp).coerceIn(-8.0, 0.0)) }
        val sum = exps.sum().takeIf { it > 0.0 } ?: 1.0
        return exps.map { ((it / sum) * 100.0).roundToInt().coerceIn(1, 95) }
    }

    private fun buildHorseStrengths(
        c: ComputedHorse,
        f: List<Double>,
        edgePct: Int?,
        avgWeight: Double?,
        avgBestTime: Double?
    ): List<String> = buildList {
        if (f[0] >= 0.25) add("فرم و ثبات بالا در کورس‌های اخیر")
        if (c.horse.bestTimeSec != null && avgBestTime != null && c.horse.bestTimeSec < avgBestTime - 0.3) {
            add("رکورد زمانی سریع‌تر از میانگین (${formatSec(c.horse.bestTimeSec)})")
        }
        if (f[2] >= 0.3) add("ریتینگ / هندیکپ برتر (${c.horse.rating?.toInt()})")
        if (c.horse.weightKg != null && avgWeight != null && c.horse.weightKg <= avgWeight - 1.5) {
            add("مزیت وزن سبک (${c.horse.weightKg}kg)")
        }
        if (f[4] >= 0.5) add("آمادگی زمانی عالی (${c.horse.daysSinceLast} روز استراحت)")
        if (c.drawPct != null && c.drawPct <= 0.35) add("دپار داخلی مناسب (${String.format(Locale.US, "%.2f", c.drawPct)})")
        if (edgePct != null && edgePct >= 15) add("ارزش پنهان +$edgePct٪ نسبت به AGF/بازار")
    }.take(4)

    private fun buildHorseRisks(c: ComputedHorse, f: List<Double>): List<String> = buildList {
        if (f[0] <= -0.3) add("افت فرم در مسابقات اخیر")
        if (f[3] <= -0.35) add("وزن سنگین‌تر از میانگین گروه (${c.horse.weightKg}kg)")
        if (c.horse.daysSinceLast != null && c.horse.daysSinceLast > 60) add("دوری طولانی از مسابقه (${c.horse.daysSinceLast} روز)")
        if (c.drawPct != null && c.drawPct >= 0.80) add("دپار بیرونی (${c.horse.gate})")
    }.take(3)

    private fun buildAnomalies(
        horses: List<ParsedHorseRow>,
        enriched: List<Triple<ComputedHorse, Triple<Int, Int, Double>, Triple<Int, Int?, Int>>>,
        avgBestTime: Double?
    ): List<AnomalySignal> {
        val list = mutableListOf<AnomalySignal>()

        horses.filter { it.trainer.isNotBlank() }
            .groupBy { it.trainer.trim().lowercase() }
            .filter { it.value.size >= 2 }
            .forEach { (_, group) ->
                val names = group.joinToString("، ") { "#${it.number} ${it.horseName}" }
                list.add(
                    AnomalySignal(
                        title = "هم‌مربی در کورس (${group.first().trainer})",
                        severity = "نکته تاکتیکی",
                        description = "این مربی همزمان ${group.size} اسب در این دور دارد: $names (احتمال تاکتیک تیمی)."
                    )
                )
            }

        horses.filter { it.owner.isNotBlank() }
            .groupBy { it.owner.trim().lowercase() }
            .filter { it.value.size >= 2 }
            .forEach { (_, group) ->
                val names = group.joinToString("، ") { "#${it.number} ${it.horseName}" }
                list.add(
                    AnomalySignal(
                        title = "هم‌مالک در کورس (${group.first().owner})",
                        severity = "نکته تاکتیکی",
                        description = "مالکیت مشترک برای ${group.size} اسب: $names."
                    )
                )
            }

        val valueGem = enriched.firstOrNull { (c, _, tem) ->
            (tem.second ?: 0) >= 20 && (c.marketProb ?: 100.0) <= 18.0
        }
        if (valueGem != null) {
            val (c, _, tem) = valueGem
            list.add(
                AnomalySignal(
                    title = "هشدار ارزش پنهان روی #${c.horse.number} ${c.horse.horseName}",
                    severity = "هشدار بالا",
                    description = "AGF/بازار فقط ${c.marketProb?.roundToInt()}٪ است اما شاخص فنی شانس ${tem.first}٪ نشان می‌دهد (Edge +${tem.second}٪)."
                )
            )
        }

        if (avgBestTime != null) {
            val fastest = horses.filter { it.bestTimeSec != null }.minByOrNull { it.bestTimeSec!! }
            if (fastest != null && (fastest.marketAgfPct ?: 50.0) < 15.0) {
                list.add(
                    AnomalySignal(
                        title = "سریع‌ترین رکورد کم‌توجه: #${fastest.number} ${fastest.horseName}",
                        severity = "نکته تاکتیکی",
                        description = "بهترین زمان (${formatSec(fastest.bestTimeSec!!)}) را بین رقبا دارد اما در AGF کمتر دیده شده است."
                    )
                )
            }
        }

        return list.take(6)
    }

    private fun buildTacticalScenario(
        enriched: List<Triple<ComputedHorse, Triple<Int, Int, Double>, Triple<Int, Int?, Int>>>,
        dist: Int?
    ): String {
        val insideFav = enriched.firstOrNull { it.first.drawPct != null && it.first.drawPct!! <= 0.35 }?.first?.horse
        val top1 = enriched.first().first.horse
        val distNote = if ((dist ?: 1400) <= 1300) "در این مسافت سرعتی، استارت سریع و دپار داخلی نقش کلیدی دارد."
        else "در این مسافت، ذخیره انرژی تا پیچ آخر و مزیت وزن سبک تعیین‌کننده است."
        val insideNote = insideFav?.let { " اسب #${it.number} ${it.horseName} از خط داخلی شانس شروع بهتری دارد." } ?: ""
        return "$distNote محور اصلی کورس روی #${top1.number} ${top1.horseName} پیش‌بینی می‌شود.$insideNote"
    }

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
        val placeRate: Int
    )
}
