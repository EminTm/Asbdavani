package com.asbai.predictor.util

import com.asbai.predictor.model.PreScoredHorse
import kotlin.math.roundToInt

data class PreScoreResult(
    val horses: List<PreScoredHorse>,
    val promptBlock: String
)

object PreScorer {

    private val formRegex = Regex("""(\d+)\s*-\s*(\d+)\s*-\s*(\d+)\s*/\s*(\d+)""")
    private val agfRegex = Regex("""%\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*%""")
    private val weightRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.\d)?\b""")
    private val ratingRegex = Regex("""(?:IOR|HP|رتبه|ریتینگ|امتیاز)\s*[:：]?\s*(\d{1,3})""", RegexOption.IGNORE_CASE)

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
            val agfMatch = agfRegex.find(line)
            if (formMatch == null && agfMatch == null) continue

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

            val agfVal = agfMatch?.let { m ->
                (m.groupValues[1].ifBlank { m.groupValues[2] }).toDoubleOrNull()
            }
            if (agfVal != null) {
                notes.add("AGF ${agfVal.roundToInt()}٪")
            }

            val ratingVal = ratingRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (ratingVal != null) {
                notes.add("ریتینگ $ratingVal")
            }

            val weightVal = weightRegex.find(cells.drop(1).joinToString(" "))?.value?.toDoubleOrNull()
            if (weightVal != null) {
                notes.add("وزن ${weightVal}kg")
            }

            candidates.add(
                RawRowScore(
                    number = number,
                    horseName = horseName,
                    formPoints = formPoints,
                    winRate = winRate,
                    placeRate = placeRate,
                    agf = agfVal,
                    rating = ratingVal,
                    weight = weightVal,
                    summary = notes.joinToString(" • ")
                )
            )
        }

        if (candidates.isEmpty()) {
            return PreScoreResult(emptyList(), "")
        }

        val avgWeight = candidates.mapNotNull { it.weight }.takeIf { it.isNotEmpty() }?.average()
        val maxRating = candidates.mapNotNull { it.rating }.maxOrNull()?.takeIf { it > 0 }

        val scored = candidates.distinctBy { "${it.number}:${it.horseName}" }.map { c ->
            var score = 35.0
            if (c.formPoints > 0) score += c.formPoints * 0.6
            if (c.agf != null) score += c.agf * 0.75
            if (c.rating != null && maxRating != null) score += (c.rating.toDouble() / maxRating) * 18.0
            if (c.weight != null && avgWeight != null) score += (avgWeight - c.weight) * 1.8

            PreScoredHorse(
                number = c.number,
                horseName = c.horseName,
                mathScore = score.roundToInt().coerceIn(10, 99),
                winRatePercent = c.winRate,
                placeRatePercent = c.placeRate,
                summary = c.summary.ifBlank { "داده محدود" }
            )
        }.sortedByDescending { it.mathScore }

        val block = buildString {
            appendLine("--- پیش‌امتیازدهی ریاضی خودکار (محاسبه‌شده قبل از هوش مصنوعی) ---")
            scored.forEachIndexed { idx, h ->
                appendLine("${idx + 1}. #${h.number} ${h.horseName} => امتیاز ریاضی: ${h.mathScore}/100 (${h.summary})")
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
        val agf: Double?,
        val rating: Int?,
        val weight: Double?,
        val summary: String
    )
}
