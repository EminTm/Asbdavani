package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ReadPageResult(
    val context: ParsedRaceContext,
    val sourceLabel: String,
    val cleanDisplayText: String
)

object PageReaderEngine {

    private const val JSON_START = "--- CAPTURED_JSON_START ---"
    private const val JSON_END = "--- CAPTURED_JSON_END ---"

    fun read(rawText: String, pageTitle: String, sourceUrl: String): ReadPageResult {
        val jsonBlocks = extractCapturedJsonBlocks(rawText)
        val cleanTextOnly = stripCapturedJsonBlocks(rawText)

        val candidates = mutableListOf<Pair<ParsedRaceContext, String>>()

        // ۱. بررسی همه JSONهای شکارشده از شبکه سایت (به جای توقف روی اولین مورد)
        for (block in jsonBlocks) {
            tryParseKambiJson(block, pageTitle)?.let {
                candidates.add(it to "موتور خواننده: JSON شبکه سایت (${it.horses.size} اسب ✅)")
            }
            tryParseGenericRacingJson(block, pageTitle)?.let {
                candidates.add(it to "موتور خواننده: داده‌های ساختاری JSON (${it.horses.size} اسب ✅)")
            }
        }

        // ۲. بررسی جداول و متن صفحه
        val fromParser = MultiSiteParser.parse(cleanTextOnly, pageTitle, sourceUrl)
        if (fromParser.horses.isNotEmpty()) {
            val lbl = when {
                "tjk.org" in sourceUrl -> "موتور خواننده: جدول رسمی TJK (${fromParser.horses.size} اسب ✅)"
                "asbdavani" in sourceUrl -> "موتور خواننده: جدول اسبدوانی (${fromParser.horses.size} اسب ✅)"
                else -> "موتور خواننده: خوانش صفحه (${fromParser.horses.size} اسب ✅)"
            }
            candidates.add(fromParser to lbl)
        }

        // انتخاب کامل‌ترین لیست با بیشترین تعداد اسب
        val winner = candidates.maxByOrNull { it.first.horses.size }
        return if (winner != null && winner.first.horses.size >= 2) {
            ReadPageResult(
                context = winner.first,
                sourceLabel = winner.second,
                cleanDisplayText = cleanTextOnly
            )
        } else {
            ReadPageResult(
                context = fromParser,
                sourceLabel = "داده کافی یافت نشد",
                cleanDisplayText = cleanTextOnly
            )
        }
    }

    fun stripCapturedJsonBlocks(rawText: String): String {
        val regex = Regex("""$JSON_START[\s\S]*?$JSON_END""")
        return regex.replace(rawText, "").trim()
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

    private fun tryParseKambiJson(jsonText: String, pageTitle: String): ParsedRaceContext? {
        return runCatching {
            val parsed = JSONTokener(jsonText).nextValue()
            val rootObj = parsed as? JSONObject ?: return@runCatching null
            val betOffers = rootObj.optJSONArray("betOffers") ?: return@runCatching null

            var bestOutcomes: JSONArray? = null
            var bestScore = -1

            for (i in 0 until betOffers.length()) {
                val bo = betOffers.optJSONObject(i) ?: continue
                val outcomes = bo.optJSONArray("outcomes") ?: continue
                if (outcomes.length() < 2) continue

                val typeName = bo.optJSONObject("betOfferType")?.optString("name").orEmpty().lowercase()
                val critName = bo.optJSONObject("criterion")?.optString("label").orEmpty().lowercase()
                val isWinType = "win" in typeName || "winnaar" in typeName || "vinnare" in typeName ||
                    "gagnant" in typeName || "win" in critName || "winnaar" in critName
                val isNotH2H = "head" !in typeName && "match" !in typeName && "top" !in critName && "each" !in critName

                val score = outcomes.length() + (if (isWinType) 100 else 0) + (if (isNotH2H) 20 else 0)
                if (score > bestScore) {
                    bestScore = score
                    bestOutcomes = outcomes
                }
            }
            val outcomes = bestOutcomes ?: return@runCatching null

            val eventsArr = rootObj.optJSONArray("events")
            val eventObj = eventsArr?.optJSONObject(0) ?: rootObj.optJSONObject("event")
            val eventName = eventObj?.optString("name").orEmpty().ifBlank { pageTitle }

            val horses = mutableListOf<ParsedHorseRow>()
            for (i in 0 until outcomes.length()) {
                val o = outcomes.optJSONObject(i) ?: continue
                val status = o.optString("status")
                if (status.equals("NON_RUNNER", true) || status.equals("SCRATCHED", true)) continue

                val rawName = o.optString("englishLabel").ifBlank { o.optString("label") }.trim()
                if (rawName.length < 2 || rawName.equals("Other", true) || rawName.equals("Field", true)) continue

                val rawNum = o.optString("startNumber").ifBlank { o.optString("clothNumber") }.trim()
                val num = if (rawNum.isNotBlank() && rawNum.length <= 3) rawNum else (horses.size + 1).toString()
                val gate = o.optInt("draw", 0).takeIf { it in 1..30 }

                val rawOdds = o.optDouble("odds", 0.0)
                val decOdds = when {
                    rawOdds >= 100.0 -> rawOdds / 1000.0
                    rawOdds > 1.0 -> rawOdds
                    else -> null
                }

                horses.add(
                    ParsedHorseRow(
                        number = num,
                        gate = gate,
                        horseName = rawName.take(40),
                        marketOdds = decOdds,
                        jockey = o.optString("jockey"),
                        trainer = o.optString("trainer")
                    )
                )
            }

            if (horses.size < 2) null
            else ParsedRaceContext(raceTitle = eventName, distanceMeters = null, surface = "", horses = horses)
        }.getOrNull()
    }

    private fun tryParseGenericRacingJson(jsonText: String, pageTitle: String): ParsedRaceContext? {
        return runCatching {
            val root = JSONTokener(jsonText).nextValue()
            val candidateArrays = mutableListOf<JSONArray>()
            collectCandidateArrays(root, candidateArrays)

            val bestList = candidateArrays
                .mapNotNull { arr -> parseRunnerArray(arr) }
                .maxByOrNull { it.size }
                ?: return@runCatching null

            if (bestList.size < 2) null
            else ParsedRaceContext(raceTitle = pageTitle.ifBlank { "مسابقه اسبدوانی" }, distanceMeters = null, surface = "", horses = bestList)
        }.getOrNull()
    }

    private fun collectCandidateArrays(node: Any?, out: MutableList<JSONArray>) {
        when (node) {
            is JSONArray -> {
                if (node.length() in 2..36 && node.optJSONObject(0) != null) {
                    out.add(node)
                }
                for (i in 0 until node.length()) {
                    collectCandidateArrays(node.opt(i), out)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectCandidateArrays(node.opt(keys.next()), out)
                }
            }
        }
    }

    private fun parseRunnerArray(arr: JSONArray): List<ParsedHorseRow>? {
        val out = mutableListOf<ParsedHorseRow>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = extractName(obj) ?: continue
            val hasRacingAttr = obj.has("jockey") || obj.has("trainer") || obj.has("owner") ||
                obj.has("odds") || obj.has("weight") || obj.has("ior") || obj.has("IOR") ||
                obj.has("rating") || obj.has("gate") || obj.has("draw") || obj.has("cloth_number") ||
                obj.has("performance") || obj.has("form") || obj.has("jockeyName")
            if (!hasRacingAttr) continue

            val num = obj.optString("cloth_number")
                .ifBlank { obj.optString("saddle_cloth") }
                .ifBlank { obj.optString("shirtNumber") }
                .ifBlank { obj.optString("number") }
                .ifBlank { (out.size + 1).toString() }

            val gate = obj.optInt("draw_number", 0).takeIf { it > 0 }
                ?: obj.optInt("draw", 0).takeIf { it > 0 }
                ?: obj.optInt("gate", 0).takeIf { it > 0 }

            val weight = obj.optDouble("weight", Double.NaN).takeIf { !it.isNaN() && it in 40.0..75.0 }
                ?: obj.optDouble("weightKg", Double.NaN).takeIf { !it.isNaN() && it in 40.0..75.0 }

            val rating = obj.optDouble("ior", Double.NaN).takeIf { !it.isNaN() }
                ?: obj.optDouble("IOR", Double.NaN).takeIf { !it.isNaN() }
                ?: obj.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }

            val rawOdds = obj.optDouble("odds", Double.NaN).takeIf { !it.isNaN() }
            val odds = rawOdds?.let { if (it >= 100.0) it / 1000.0 else if (it > 1.0) it else null }

            out.add(
                ParsedHorseRow(
                    number = num,
                    gate = gate,
                    horseName = name.take(40),
                    weightKg = weight,
                    rating = rating,
                    formString = obj.optString("performance").ifBlank { obj.optString("form") }.ifBlank { null },
                    marketOdds = odds,
                    owner = extractSubName(obj, "owner"),
                    trainer = extractSubName(obj, "trainer"),
                    jockey = extractSubName(obj, "jockey")
                )
            )
        }
        return if (out.size >= 2) out else null
    }

    private fun extractName(obj: JSONObject): String? {
        val direct = obj.optString("horseName")
            .ifBlank { obj.optString("horse_name") }
            .ifBlank { obj.optString("runnerName") }
            .ifBlank { obj.optString("name") }
        if (direct.length >= 2) return direct
        val sub = obj.optJSONObject("horse") ?: obj.optJSONObject("runner")
        return sub?.optString("name")?.takeIf { it.length >= 2 }
    }

    private fun extractSubName(obj: JSONObject, key: String): String {
        val sub = obj.optJSONObject(key)
        if (sub != null) return sub.optString("name")
        return obj.optString("${key}Name").ifBlank { obj.optString(key) }
    }
}
