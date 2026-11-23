package com.asbai.predictor.util

import com.asbai.predictor.model.PreScoredHorse
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

data class PreScoreResult(
    val horses: List<PreScoredHorse>,
    val promptBlock: String
)

object PreScorer {

    private val formRegex = Regex("""(\d+)\s*-\s*(\d+)\s*-\s*(\d+)\s*/\s*(\d+)""")
    private val agfRegex = Regex("""%\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*%""")
    private val fracOddsRegex = Regex("""(?:^|[\s|])(\d{1,2})\s*/\s*(\d{1,2})(?=$|[\s|])""")
    private val weightRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.\d)?\b""")
    private val ratingRegex = Regex("""(?:IOR|HP|OR|RPR|رتبه|ریتینگ|امتیاز)\s*[:：]?\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val gateParenRegex = Regex("""^\d+\s*\((\d{1,2})\)""")
    private val gateWordRegex = Regex("""(?:دپار|St\.?|Draw)\s*[:：]?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    fun analyze(rawText: String): PreScoreResult {
        val ascii = toAsciiDigits(rawText)
        val lines = ascii.lines().map { it.trim() }.filter { it.length > 6 }

        val candidates = mutableListOf<RawRowScore>()
        for (line in lines) {
            if (!line.contains("|")) continue
            val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size < 2) continue

            val firstToken = cells.first()
            if (firstToken.contains("مقام") || firstToken.contains("اسب") || firstToken.equals("N", true) || firstToken.equals("#")) {
                continue
            }

            val formMatch = formRegex.find(line)
            val lineNoForm = formRegex.replace(line, " ")
            val agfMatch = agfRegex.find(lineNoForm)
            val fracOddsMatch = fracOddsRegex.find(lineNoForm)

            if (formMatch == null && agfMatch == null && fracOddsMatch == null) continue

            val numMatch = Regex("""^(\d{1,2})\b""").find(firstToken)
            val number = numMatch?.groupValues?.get(1) ?: "-"

            val horseName = pickHorseName(cells)
            if (horseName.isBlank()) continue

            var winRate = 0
            var placeRate = 0
            var formPoints = 0.0
            val notes = mutableListOf<String>()

            if (formMatch != null) {
                val firsts = formMatch.groupValues[1].toIntOrNull() ?: 0
                val seconds = formMatch.groupValues[2].toIntOrNull() ?: 0
                val thirds = formMatch.groupValues[3].toIntOrNull() ?: 0
                val starts = (formMatch.groupValues[4].toIntOrNull() ?: 0).coerceAtLeast(1)
                winRate = ((firsts.toDouble() / starts) * 100.0).roundToInt().coerceIn(0, 100)
                placeRate = (((firsts + seconds + thirds).toDouble() / starts) * 100.0).roundToInt().coerceIn(0, 100)
                formPoints = (winRate * 0.45) + (placeRate * 0.30) + (starts.coerceAtMost(10) * 0.5)
                notes.add("برد $winRate٪ | سکو $placeRate٪ ($firsts-$seconds-$thirds/$starts)")
            }

            var marketProb: Double? = agfMatch?.let { m ->
                (m.groupValues[1].ifBlank { m.groupValues[2] }).toDoubleOrNull()
            }
            if (marketProb == null && fracOddsMatch != null) {
                val num = fracOddsMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val den = fracOddsMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                if (num + den > 0.0 && num <= 80.0 && den <= 20.0) {
                    marketProb = (den / (num + den)) * 100.0
                }
            }

            val gate = gateParenRegex.find(firstToken)?.groupValues?.get(1)?.toIntOrNull()
                ?: gateWordRegex.find(lineNoForm)?.groupValues?.get(1)?.toIntOrNull()

            val ratingVal = ratingRegex.find(lineNoForm)?.groupValues?.get(1)?.toIntOrNull()
            if (ratingVal != null) notes.add("ریتینگ $ratingVal")

            val weightVal = weightRegex.find(cells.drop(1).joinToString(" "))?.value?.toDoubleOrNull()
            if (weightVal != null) notes.add("وزن ${weightVal}kg")

            candidates.add(
                RawRowScore(
                    number = number,
                    horseName = horseName,
                    formPoints = formPoints,
                    winRate = winRate,
                    placeRate = placeRate,
                    marketProb = marketProb?.coerceIn(1.0, 95.0),
                    gate = gate,
                    rating = ratingVal,
                    weight = weightVal,
                    baseNote = notes.joinToString(" • ")
                )
            )
        }

        val unique = candidates.distinctBy { "${it.number}:${it.horseName}" }
        if (unique.isEmpty()) {
            return PreScoreResult(emptyList(), "")
        }

        val fieldSize = unique.size
        val placeCutoff = when {
            fieldSize <= 4 -> 1
            fieldSize <= 7 -> 2
            fieldSize <= 15 -> 3
            else -> 4
        }
        val avgWeight = unique.mapNotNull { it.weight }.takeIf { it.isNotEmpty() }?.average()
        val maxRating = unique.mapNotNull { it.rating }.maxOrNull()?.takeIf { it > 0 }

        val strengths = unique.map { c ->
            var strength = 35.0
            if (c.formPoints > 0) strength += c.formPoints * 0.55
            if (c.marketProb != null) strength += c.marketProb * 0.45
            if (c.rating != null && maxRating != null) strength += (c.rating.toDouble() / maxRating) * 18.0
            if (c.weight != null && avgWeight != null) strength += (avgWeight - c.weight) * 1.8

            val drawPct = c.gate?.let { (it.toDouble() / fieldSize.coerceAtLeast(1)).coerceIn(0.05, 1.0) }
            if (drawPct != null) {
                strength += when {
                    drawPct <= 0.40 -> 3.0
                    drawPct >= 0.85 -> -2.5
                    else -> 0.0
                }
            }
            Triple(c, strength, drawPct)
        }

        // نرمال‌سازی Softmax برای به دست آوردن احتمال برد دقیق (جمع = ۱۰۰٪) و Fair Odds
        val maxS = strengths.maxOf { it.second }
        val expWeights = strengths.map { exp(((it.second - maxS) / 14.0).coerceIn(-8.0, 0.0)) }
        val sumExp = expWeights.sum().takeIf { it > 0.0 } ?: 1.0

        val scored = strengths.mapIndexed { idx, (c, strength, drawPct) ->
            val winProb = ((expWeights[idx] / sumExp) * 100.0).roundToInt().coerceIn(1, 95)
            val fairOdds = ((100.0 / winProb) * 10.0).roundToInt() / 10.0

            val mProbInt = c.marketProb?.roundToInt()?.coerceIn(1, 95)
            val edgePct = mProbInt?.let { mp ->
                (((winProb.toDouble() / mp.toDouble()) - 1.0) * 100.0).roundToInt().coerceIn(-85, 250)
            }

            val fullSummary = buildList {
                add("شانس $winProb٪ (ضریب منصفانه ${String.format(Locale.US, "%.1f", fairOdds)})")
                if (mProbInt != null && edgePct != null) {
                    val edgeSign = if (edgePct >= 0) "+$edgePct" else "$edgePct"
                    val tag = if (edgePct >= 12) " 🔥ارزش پنهان" else ""
                    add("بازار/AGF $mProbInt٪ | Edge $edgeSign٪$tag")
                }
                if (drawPct != null) {
                    add("دپار نسبی ${String.format(Locale.US, "%.2f", drawPct)}")
                }
                if (c.baseNote.isNotBlank()) add(c.baseNote)
            }.joinToString(" • ")

            PreScoredHorse(
                number = c.number,
                horseName = c.horseName,
                mathScore = strength.roundToInt().coerceIn(10, 99),
                mathWinProb = winProb,
                fairOdds = fairOdds,
                marketProbPercent = mProbInt,
                edgePercent = edgePct,
                drawPct = drawPct,
                winRatePercent = c.winRate,
                placeRatePercent = c.placeRate,
                summary = fullSummary
            )
        }.sortedByDescending { it.mathWinProb }

        val block = buildString {
            appendLine("--- آنالیز ریاضی پیشرفته (مدل Equintel) ---")
            appendLine("تعداد اسب‌های شناسایی‌شده: $fieldSize | حد واقعی سکو: $placeCutoff نفر اول")
            scored.forEachIndexed { idx, h ->
                appendLine("${idx + 1}. #${h.number} ${h.horseName} => امتیاز: ${h.mathScore}/100 | ${h.summary}")
            }
        }

        return PreScoreResult(horses = scored, promptBlock = block)
    }

    private fun pickHorseName(cells: List<String>): String {
        for (cell in cells.drop(1)) {
            val cleaned = cell.replace(Regex("""^\d+\s*[.\-)]\s*"""), "").trim()
            if (cleaned.length >= 2 &&
                !formRegex.containsMatchIn(cleaned) &&
                !Regex("""^[\d\s/%.\-()]+$""").matches(cleaned) &&
                !cleaned.contains("کیلو")
            ) {
                return cleaned.take(36)
            }
        }
        return ""
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

    private data class RawRowScore(
        val number: String,
        val horseName: String,
        val formPoints: Double,
        val winRate: Int,
        val placeRate: Int,
        val marketProb: Double?,
        val gate: Int?,
        val rating: Int?,
        val weight: Double?,
        val baseNote: String
    )
}
