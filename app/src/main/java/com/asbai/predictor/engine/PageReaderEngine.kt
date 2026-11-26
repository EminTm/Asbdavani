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

        val fromParser = MultiSiteParser.parse(cleanTextOnly, pageTitle, sourceUrl)
        if (fromParser.horses.isNotEmpty()) {
            val lbl = when {
                "tjk.org" in sourceUrl -> "موتور خواننده: جدول رسمی TJK (${fromParser.horses.size} اسب ✅)"
                "asbdavani" in sourceUrl -> "موتور خواننده: جدول اسبدوانی (${fromParser.horses.size} اسب ✅)"
                else -> "موتور خواننده: خوانش صفحه (${fromParser.horses.size} اسب ✅)"
            }
            candidates.add(fromParser to lbl)
        }

        for (block in jsonBlocks) {
            tryParseKambiJson(block, pageTitle)?.let {
                candidates.add(it to "موتور خواننده: JSON شبکه سایت (${it.horses.size} اسب ✅)")
            }
            tryParseGenericRacingJson(block, pageTitle)?.let {
                candidates.add(it to "موتور خواننده: داده‌های ساختاری JSON (${it.horses.size} اسب ✅)")
            }
        }

        // امتیازدهی به بهترین منبع (تعداد اسب‌ها + غنای ستون‌های وزن/ریتینگ/فرم/چابکسوار)
        val winner = candidates.maxByOrNull { (ctx, _) -> scoreCandidate(ctx) }
        return if (winner != null && winner.first.horses.size >= 2) {
            ReadPageResult(winner.first, winner.second, cleanTextOnly)
        } else {
            ReadPageResult(fromParser, "داده کافی یافت نشد", cleanTextOnly)
        }
    }

    private fun scoreCandidate(ctx: ParsedRaceContext): Int {
        val countScore = ctx.horses.size * 10
        val detailScore = ctx.horses.sumOf { h ->
            (if (h.weightKg != null) 2 else 0) +
                (if (h.rating != null) 2 else 0) +
                (if (h.formString != null || h.recentDigits.isNotEmpty()) 2 else 0) +
                (if (h.jockey.isNotBlank()) 1 else 0) +
                (if (h.marketOdds != null || h.marketAgfPct != null) 1 else 0)
        }
        return countScore + detailScore
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

    private fun tryParseKambiJson(jsonText: String, pageTitle: String): ParsedRaceContext? {
        return runCatching {
            val rootObj = JSONTokener(jsonText).nextValue() as? JSONObject ?: return@runCatching null
            val betOffers = rootObj.optJSONArray("betOffers") ?: return@runCatching null

            var bestOutcomes: JSONArray? = null
            var bestScore = -1
            for (i in 0 until betOffers.length()) {
                val bo = betOffers.optJSONObject(i) ?: continue
                val outcomes = bo.optJSONArray("outcomes") ?: continue
                if (outcomes.length() < 2) continue
                val typeName = bo.optJSONObject("betOfferType")?.optString("name").orEmpty().lowercase()
                val critName = bo.optJSONObject("criterion")?.optString("label").orEmpty().lowercase()
                val isWin = "win" in typeName || "winnaar" in typeName || "vinnare" in typeName || "win" in critName
                val notH2H = "head" !in typeName && "match" !in typeName && "top" !in critName
                val score = outcomes.length() + (if (isWin) 100 else 0) + (if (notH2H) 20 else 0)
                if (score > bestScore) { bestScore = score; bestOutcomes = outcomes }
            }
            val outcomes = bestOutcomes ?: return@runCatching null
            val eventObj = rootObj.optJSONArray("events")?.optJSONObject(0) ?: rootObj.optJSONObject("event")
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
                val rawOdds = o.optDouble("odds", 0.0)
                val decOdds = when {
                    rawOdds >= 100.0 -> rawOdds / 1000.0
                    rawOdds > 1.0 -> rawOdds
                    else -> null
                }
                horses.add(
                    ParsedHorseRow(
                        number = num,
                        gate = o.optInt("draw", 0).takeIf { it in 1..30 },
                        horseName = rawName.take(40),
                        marketOdds = decOdds,
                        jockey = o.optString("jockey"),
                        trainer = o.optString("trainer")
                    )
                )
            }
            if (horses.size < 2) null else ParsedRaceContext(eventName, null, "", horses)
        }.getOrNull()
    }

    private fun tryParseGenericRacingJson(jsonText: String, pageTitle: String): ParsedRaceContext? {
        return runCatching {
            val root = JSONTokener(jsonText).nextValue()
            val candidateArrays = mutableListOf<JSONArray>()
            collectCandidateArrays(root, candidateArrays)
            val bestList = candidateArrays.mapNotNull { parseRunnerArray(it) }.maxByOrNull { it.size } ?: return@runCatching null
            if (bestList.size < 2) null else ParsedRaceContext(pageTitle.ifBlank { "مسابقه اسبدوانی" }, null, "", bestList)
        }.getOrNull()
    }

    private fun collectCandidateArrays(node: Any?, out: MutableList<JSONArray>) {
        when (node) {
            is JSONArray -> {
                if (node.length() in 2..36 && node.optJSONObject(0) != null) out.add(node)
                for (i in 0 until node.length()) collectCandidateArrays(node.opt(i), out)
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) collectCandidateArrays(node.opt(keys.next()), out)
            }
        }
    }

    private fun parseRunnerArray(arr: JSONArray): List<ParsedHorseRow>? {
        val out = mutableListOf<ParsedHorseRow>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = (obj.optString("horseName").ifBlank { obj.optString("horse_name") }
                .ifBlank { obj.optString("runnerName") }
                .ifBlank { obj.optJSONObject("horse")?.optString("name").orEmpty() }).trim()
            if (name.length < 2) continue
            val hasAttr = obj.has("jockey") || obj.has("trainer") || obj.has("weight") ||
                obj.has("ior") || obj.has("IOR") || obj.has("performance") || obj.has("odds")
            if (!hasAttr) continue

            val num = obj.optString("cloth_number").ifBlank { obj.optString("shirtNumber") }
                .ifBlank { obj.optString("number") }.ifBlank { (out.size + 1).toString() }
            val gate = obj.optInt("draw_number", 0).takeIf { it > 0 } ?: obj.optInt("gate", 0).takeIf { it > 0 }
            val weight = obj.optDouble("weight", Double.NaN).takeIf { !it.isNaN() && it in 40.0..75.0 }
            val rating = obj.optDouble("ior", Double.NaN).takeIf { !it.isNaN() } ?: obj.optDouble("IOR", Double.NaN).takeIf { !it.isNaN() }
            val rawOdds = obj.optDouble("odds", Double.NaN).takeIf { !it.isNaN() }
            out.add(
                ParsedHorseRow(
                    number = num,
                    gate = gate,
                    horseName = name.take(40),
                    weightKg = weight,
                    rating = rating,
                    formString = obj.optString("performance").ifBlank { obj.optString("form") }.ifBlank { null },
                    marketOdds = rawOdds?.let { if (it >= 100.0) it / 1000.0 else if (it > 1.0) it else null },
                    owner = obj.optJSONObject("owner")?.optString("name") ?: obj.optString("owner"),
                    trainer = obj.optJSONObject("trainer")?.optString("name") ?: obj.optString("trainer"),
                    jockey = obj.optJSONObject("jockey")?.optString("name") ?: obj.optString("jockey")
                )
            )
        }
        return if (out.size >= 2) out else null
    }
}
