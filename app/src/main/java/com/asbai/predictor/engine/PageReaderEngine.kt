package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow

data class ParsedRaceContext(
    val raceTitle: String,
    val distanceMeters: Int?,
    val surface: String,
    val horses: List<ParsedHorseRow>
)

data class RaceSection(
    val label: String,
    val rawChunk: String
)

data class ReadPageResult(
    val context: ParsedRaceContext,
    val sourceLabel: String,
    val cleanDisplayText: String
)

object PageReaderEngine {

    private const val JSON_START = "--- CAPTURED_JSON_START ---"
    private const val JSON_END = "--- CAPTURED_JSON_END ---"

    fun splitRaceSections(rawText: String, sourceUrl: String): List<RaceSection> {
        val urlLower = sourceUrl.lowercase()
        val clean = stripCapturedJsonBlocks(rawText)
        return when {
            "tjk.org" in urlLower || (clean.contains("At İsmi", true) && clean.contains("Jokey", true)) ->
                TjkReader.splitSections(clean)
            "asbdavani" in urlLower || clean.contains("مقام (دپار)") ->
                AsbDavaniReader.splitSections(clean)
            else -> emptyList()
        }
    }

    fun read(rawText: String, pageTitle: String, sourceUrl: String): ReadPageResult {
        val jsonBlocks = extractCapturedJsonBlocks(rawText)
        val cleanText = stripCapturedJsonBlocks(rawText)
        val urlLower = sourceUrl.lowercase()

        return when {
            "tjk.org" in urlLower || (cleanText.contains("At İsmi", true) && cleanText.contains("Jokey", true)) ->
                TjkReader.read(cleanText, pageTitle, sourceUrl)

            "unibet" in urlLower || jsonBlocks.any { it.contains("betOffers") } ->
                UnibetReader.read(cleanText, jsonBlocks, pageTitle, sourceUrl)

            "asbdavani" in urlLower || cleanText.contains("سن / وزن / IOR") || cleanText.contains("پیراهن شماره") ->
                AsbDavaniReader.read(cleanText, jsonBlocks, pageTitle, sourceUrl)

            else -> {
                val results = listOf(
                    AsbDavaniReader.read(cleanText, jsonBlocks, pageTitle, sourceUrl),
                    TjkReader.read(cleanText, pageTitle, sourceUrl),
                    UnibetReader.read(cleanText, jsonBlocks, pageTitle, sourceUrl)
                )
                results.maxByOrNull { it.context.horses.size } ?: results.first()
            }
        }
    }

    fun stripCapturedJsonBlocks(rawText: String): String {
        return Regex("""$JSON_START[\s\S]*?$JSON_END""").replace(rawText, "").trim()
    }

    private fun extractCapturedJsonBlocks(rawText: String): List<String> {
        val list = mutableListOf<String>()
        var pos = 0
        while (true) {
            val s = rawText.indexOf(JSON_START, pos)
            if (s == -1) break
            val e = rawText.indexOf(JSON_END, s + JSON_START.length)
            if (e == -1) break
            val content = rawText.substring(s + JSON_START.length, e).trim()
            if (content.isNotBlank()) list.add(content)
            pos = e + JSON_END.length
        }
        return list
    }

    fun toAsciiDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '\u00A0' -> sb.append(' ')
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
