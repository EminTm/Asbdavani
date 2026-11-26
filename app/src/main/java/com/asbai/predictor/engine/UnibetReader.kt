package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

object UnibetReader {

    private val unibetHeaderRegex = Regex("""^(\d{1,2})\s*\((\d{1,2})?\)\s+([A-Za-z0-9'’.\- ]{2,45})$""")
    private val unibetFormRegex = Regex("""^[0-9XPFRUBxpfrub]{1,10}$""")
    private val weightStoneRegex = Regex("""(\d{1,2})\s*st\s*(\d{1,2})\s*lbs""", RegexOption.IGNORE_CASE)
    private val weightKgRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.(\d))?\b""")

    private val noiseWords = setOf(
        "win", "place", "show", "each way", "ew", "sp", "nr", "non-runner",
        "non runner", "uitgeschakeld", "voorspelling", "wedden", "odds", "form",
        "live", "info", "stats", "results", "racecard", "unibet", "paardenraces",
        "favoriet", "race", "races", "all", "today", "tomorrow", "cet", "gmt", "bst",
        "vandaag", "morgen", "starttijd", "afstand", "lopers", "plaats", "winst", "prijs"
    )

    fun read(cleanText: String, jsonBlocks: List<String>, pageTitle: String, sourceUrl: String): ReadPageResult {
        val lines = PageReaderEngine.toAsciiDigits(cleanText).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dist = parseDistance(lines)
        val surface = parseSurface(lines)
        val heading = extractHeading(lines) ?: pageTitle.ifBlank { "مسابقات Unibet" }

        val textHorses = parseFrankelCards(lines).ifEmpty { parseLineBlocks(lines) }
        var bestHorses = textHorses
        var sourceLabel = if (bestHorses.size >= 2) "موتور اختصاصی Unibet (${bestHorses.size} اسب ✅)" else "داده کافی از Unibet یافت نشد"

        for (block in jsonBlocks) {
            val kambi = tryParseKambiJson(block)
            if (kambi != null && kambi.size > bestHorses.size) {
                bestHorses = kambi
                sourceLabel = "موتور اختصاصی Unibet JSON (${bestHorses.size} اسب ✅)"
            }
        }

        val ctx = ParsedRaceContext(heading, dist, surface, bestHorses.distinctBy { "${it.number}:${it.horseName.lowercase()}" })
        return ReadPageResult(ctx, sourceLabel, cleanText)
    }

    private fun parseFrankelCards(lines: List<String>): List<ParsedHorseRow> {
        val headerIndices = lines.indices.filter { unibetHeaderRegex.matches(lines[it]) }
        if (headerIndices.size < 2) return emptyList()

        val footerIdx = lines.indexOfFirst { it.startsWith("Weergave odds", true) || it.startsWith("Wat kost gokken", true) }
            .takeIf { it > 0 } ?: lines.size

        val out = mutableListOf<ParsedHorseRow>()
        for (hPos in headerIndices.indices) {
            val idx = headerIndices[hPos]
            if (idx >= footerIdx) break
            val hm = unibetHeaderRegex.find(lines[idx]) ?: continue
            val number = hm.groupValues[1]
            val gate = hm.groupValues[2].toIntOrNull()
            val horseName = hm.groupValues[3].trim()
            if (horseName.length < 2) continue

            val recentDigits = if (idx - 1 >= 0 && unibetFormRegex.matches(lines[idx - 1])) {
                lines[idx - 1].map { ch -> if (ch == '0') 10 else if (ch.isDigit()) ch.digitToInt() else 9 }
            } else emptyList()

            val nextIdx = headerIndices.getOrNull(hPos + 1)?.let { minOf(it, footerIdx) } ?: footerIdx
            var jockey = ""; var trainer = ""; var weightKg: Double? = null; var lastOdds: Double? = null

            for (k in (idx + 1) until nextIdx) {
                val ln = lines[k]
                if (ln.startsWith("Weergave odds", true) || ln.startsWith("WIN/", true)) break
                if (jockey.isBlank() && Regex("""^J[A-Z]""").containsMatchIn(ln)) {
                    jockey = ln.substring(1).replace(Regex("""\(\d+(?:\.\d+)?\).*$"""), "").replace(weightStoneRegex, "").trim()
                }
                if (trainer.isBlank() && Regex("""^T[A-Z]""").containsMatchIn(ln)) {
                    trainer = ln.substring(1).trim()
                }
                val sw = weightStoneRegex.find(ln)
                if (weightKg == null && sw != null) {
                    val kg = (((sw.groupValues[1].toDoubleOrNull() ?: 0.0) * 14.0 + (sw.groupValues[2].toDoubleOrNull() ?: 0.0)) * 0.45359237 * 10.0).roundToInt() / 10.0
                    if (kg in 40.0..75.0) weightKg = kg
                }
                val od = parseOddsToken(ln)
                if (od != null && od > 1.0) lastOdds = od
            }

            out.add(ParsedHorseRow(number, gate, horseName, weightKg, recentDigits = recentDigits, marketOdds = lastOdds, trainer = trainer, jockey = jockey))
        }
        return out
    }

    private fun parseLineBlocks(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        val bodyIdx = lines.indexOfFirst { it.contains("--- متن") || it.contains("متن صفحه") }
        val startIdx = if (bodyIdx >= 0) bodyIdx + 1 else 0
        var blockStart = startIdx

        var i = startIdx
        while (i < lines.size) {
            val odds = parseOddsToken(lines[i])
            if (odds == null) { i++; continue }

            val winFrom = maxOf(blockStart, i - 10)
            var number: String? = null
            var horseName = ""; var jockey = ""; var trainer = ""

            for (k in (i - 1) downTo winFrom) {
                val t = lines[k].trim()
                if (t.isBlank()) continue
                if (t.startsWith("J") && t.length > 2 && t[1].isUpperCase()) { if (jockey.isBlank()) jockey = t.substring(1).trim(); continue }
                if (t.startsWith("T") && t.length > 2 && t[1].isUpperCase()) { if (trainer.isBlank()) trainer = t.substring(1).trim(); continue }
                val onlyNum = Regex("""^(\d{1,2})\.?$""").find(t)
                if (onlyNum != null) { if (number == null) number = onlyNum.groupValues[1]; continue }
                if (horseName.isBlank() && isValidName(t)) horseName = t
            }

            if (horseName.isNotBlank() && out.none { it.horseName.equals(horseName, true) }) {
                val joined = lines.subList(winFrom, i).joinToString(" ")
                out.add(ParsedHorseRow(number ?: (out.size + 1).toString(), horseName = horseName.take(36), weightKg = weightKgRegex.find(joined)?.value?.toDoubleOrNull(), marketOdds = odds.takeIf { it > 1.0 }, trainer = trainer, jockey = jockey))
                blockStart = i + 1
                while (i + 1 < lines.size && parseOddsToken(lines[i + 1]) != null) i++
            }
            i++
        }
        return out
    }

    private fun tryParseKambiJson(jsonText: String): List<ParsedHorseRow>? {
        return runCatching {
            val root = JSONTokener(jsonText).nextValue() as? JSONObject ?: return@runCatching null
            val betOffers = root.optJSONArray("betOffers") ?: return@runCatching null
            var bestOutcomes: JSONArray? = null
            var bestScore = -1
            for (i in 0 until betOffers.length()) {
                val bo = betOffers.optJSONObject(i) ?: continue
                val outcomes = bo.optJSONArray("outcomes") ?: continue
                if (outcomes.length() < 2) continue
                val tn = bo.optJSONObject("betOfferType")?.optString("name").orEmpty().lowercase()
                val cn = bo.optJSONObject("criterion")?.optString("label").orEmpty().lowercase()
                val isWin = "win" in tn || "winnaar" in tn || "vinnare" in tn || "win" in cn
                val notH2H = "head" !in tn && "match" !in tn && "top" !in cn
                val score = outcomes.length() + (if (isWin) 100 else 0) + (if (notH2H) 20 else 0)
                if (score > bestScore) { bestScore = score; bestOutcomes = outcomes }
            }
            val outcomes = bestOutcomes ?: return@runCatching null
            val horses = mutableListOf<ParsedHorseRow>()
            for (i in 0 until outcomes.length()) {
                val o = outcomes.optJSONObject(i) ?: continue
                val status = o.optString("status")
                if (status.equals("NON_RUNNER", true) || status.equals("SCRATCHED", true)) continue
                val name = o.optString("englishLabel").ifBlank { o.optString("label") }.trim()
                if (name.length < 2 || name.equals("Other", true) || name.equals("Field", true)) continue
                val rawNum = o.optString("startNumber").ifBlank { o.optString("clothNumber") }.trim()
                val num = if (rawNum.isNotBlank() && rawNum.length <= 3) rawNum else (horses.size + 1).toString()
                val rawOdds = o.optDouble("odds", 0.0)
                horses.add(ParsedHorseRow(num, o.optInt("draw", 0).takeIf { it in 1..30 }, name.take(40), marketOdds = if (rawOdds >= 100.0) rawOdds / 1000.0 else if (rawOdds > 1.0) rawOdds else null))
            }
            if (horses.size >= 2) horses else null
        }.getOrNull()
    }

    private fun parseOddsToken(raw: String): Double? {
        val s = raw.trim()
        if (s.contains(":") || s.contains("%")) return null
        if (s.equals("sp", true)) return 0.0
        if (s.equals("evens", true) || s.equals("evs", true)) return 2.0
        val dec = Regex("""^(\d{1,3})[.,](\d{1,2})$""").find(s)
        if (dec != null) {
            val v = "${dec.groupValues[1]}.${dec.groupValues[2]}".toDoubleOrNull()
            if (v != null && v in 1.05..200.0) return v
        }
        val frac = Regex("""^(\d{1,2})\s*/\s*(\d{1,2})$""").find(s)
        if (frac != null) {
            val n = frac.groupValues[1].toDoubleOrNull() ?: return null
            val d = frac.groupValues[2].toDoubleOrNull() ?: return null
            if (d > 0 && n <= 150 && d <= 20) return (n / d) + 1.0
        }
        return null
    }

    private fun isValidName(s: String): Boolean {
        if (s.length !in 3..42 || s.lowercase() in noiseWords) return false
        if (s.startsWith("cet ", true) || s.startsWith("gmt ", true) || s.startsWith("http", true)) return false
        if (!Regex("""[A-Za-z]{2,}""").containsMatchIn(s)) return false
        return !Regex("""^\d[\d\s.,:/\-()]+$""").matches(s)
    }

    private fun extractHeading(lines: List<String>): String? {
        val raceLine = lines.firstOrNull { Regex("""^Race\s+\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val trackLine = lines.firstOrNull {
            val idx = lines.indexOf(it)
            idx > 0 && lines.getOrNull(idx + 1)?.startsWith("Race ", true) == true && it.length in 3..40
        }
        return if (trackLine != null && raceLine != null) "$trackLine — $raceLine" else raceLine
    }

    private fun parseDistance(lines: List<String>): Int? {
        for (ln in lines.take(65)) {
            val mi = Regex("""(\d+)\s*mi(?:les?)?\s*(?:(\d+)\s*f)?\s*(?:(\d+)\s*y)?""", RegexOption.IGNORE_CASE).find(ln)
            if (mi != null) {
                val total = ((mi.groupValues[1].toDoubleOrNull() ?: 0.0) * 1609.34 + (mi.groupValues[2].toDoubleOrNull() ?: 0.0) * 201.168 + (mi.groupValues[3].toDoubleOrNull() ?: 0.0) * 0.9144).roundToInt()
                if (total in 700..5000) return total
            }
            val f = Regex("""\b(\d+)\s*f\b""", RegexOption.IGNORE_CASE).find(ln)?.groupValues?.get(1)?.toDoubleOrNull()
            if (f != null) return (f * 201.168).roundToInt()
            val m = Regex("""\b(\d{3,4})\s*m\b""", RegexOption.IGNORE_CASE).find(ln)?.groupValues?.get(1)?.toIntOrNull()
            if (m != null && m in 800..4500) return m
        }
        return null
    }

    private fun parseSurface(lines: List<String>): String {
        for (ln in lines.take(65)) {
            val l = ln.lowercase()
            if ("turf" in l) return "چمن"
            if ("dirt" in l) return "خاکی"
        }
        return ""
    }
}
