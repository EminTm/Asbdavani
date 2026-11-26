package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow
import kotlin.math.roundToInt

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

object MultiSiteParser {

    private val formRegex = Regex("""(\d{1,3})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\s*/\s*(\d{1,3})""")
    private val agfRegex = Regex("""%\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*%""")
    private val timeDotRegex = Regex("""\b(\d)\.(\d{2})\.(\d{2})\b""")
    private val asbTimeRegex = Regex("""\b(\d):(\d{2})\.(\d{2,3})\b""")
    private val asbKgIorRegex = Regex("""(\d{2}(?:\.\d)?)\s*Kg\s*(\d{1,3})?""", RegexOption.IGNORE_CASE)
    private val daysAgoRegex = Regex("""(\d{1,4})\s*روز\s*قبل""")
    private val shirtNumRegex = Regex("""پیراهن\s*شماره\s*(\d{1,2})""")
    private val weightKgRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.(\d))?\b""")
    private val weightStoneRegex = Regex("""(\d{1,2})\s*st\s*(\d{1,2})\s*lbs""", RegexOption.IGNORE_CASE)
    private val distMetricRegex = Regex("""(\d{3,4})\s*(?:متر|m\.?)?\s*(چمن|خاکی|سنتتیک|Çim|Kum|Sentetik|Dirt|Turf|AW)?""", RegexOption.IGNORE_CASE)
    private val unibetHeaderRegex = Regex("""^(\d{1,2})\s*\((\d{1,2})?\)\s+([A-Za-z0-9'’.\- ]{2,45})$""")
    private val unibetFormRegex = Regex("""^[0-9XPFRUBxpfrub]{1,10}$""")

    fun splitRaceSections(rawText: String): List<RaceSection> {
        val ascii = toAsciiDigits(rawText)
        val lines = ascii.lines()
        val sections = mutableListOf<RaceSection>()
        val headerIndices = mutableListOf<Int>()

        for (i in lines.indices) {
            val ln = lines[i]
            if (ln.contains("|")) {
                val cells = ln.split("|").map { it.trim() }
                val isTjkHeader = cells.any { Regex("""at\s*[iİı]sm[iİı]""", RegexOption.IGNORE_CASE).containsMatchIn(it) } &&
                    cells.any { Regex("""jokey""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                val isAsbHeader = cells.any { it.contains("اسب") } &&
                    cells.any { it.contains("عملکرد") || it.contains("مربی") || it.contains("دپار") }
                if (isTjkHeader || isAsbHeader) headerIndices.add(i)
            }
        }

        if (headerIndices.size <= 1) return emptyList()

        for (k in headerIndices.indices) {
            val start = maxOf(0, headerIndices[k] - 6)
            val end = if (k + 1 < headerIndices.size) maxOf(start + 1, headerIndices[k + 1] - 6) else lines.size
            val chunk = lines.subList(start, end).joinToString("\n")
            val kosuMatch = Regex("""(\d+)\.\s*Ko[sş]u""", RegexOption.IGNORE_CASE).find(chunk)
            val dorMatch = Regex("""دور\s*(\d+)""").find(chunk)
            val label = when {
                kosuMatch != null -> "دور ${kosuMatch.groupValues[1]}"
                dorMatch != null -> "دور ${dorMatch.groupValues[1]}"
                else -> "کورس ${k + 1}"
            }
            sections.add(RaceSection(label = label, rawChunk = chunk))
        }
        return sections
    }

    fun parse(rawText: String, pageTitle: String, sourceUrl: String): ParsedRaceContext {
        val ascii = toAsciiDigits(rawText)
        val lines = ascii.lines().map { it.trim() }.filter { it.isNotEmpty() }

        var distMeters: Int? = parseImperialDistance(lines)
        var surface = ""
        for (i in lines.indices) {
            val pairLine = if (i + 1 < lines.size) "${lines[i]} ${lines[i + 1]}" else lines[i]
            if (distMeters == null) {
                val dm = Regex("""(\d{3,4})\s*(?:متر|m\b)""", RegexOption.IGNORE_CASE).find(pairLine)
                val d = dm?.groupValues?.get(1)?.toIntOrNull()
                if (d != null && d in 800..4500) distMeters = d
            }
            val lower = lines[i].lowercase()
            if (surface.isBlank()) {
                surface = when {
                    "çim" in lower || "چمن" in lower || "turf" in lower -> "چمن"
                    "kum" in lower || "خاکی" in lower || "dirt" in lower -> "خاکی"
                    "sentetik" in lower || "سنتتیک" in lower -> "سنتتیک"
                    else -> ""
                }
            }
            if (distMeters != null && surface.isNotBlank() && i > 40) break
        }

        val candidates = listOf(
            parseAsbdavaniVerticalBlocks(lines),
            parseAsbdavaniTables(lines),
            parseAsbdavaniCardBlocks(lines),
            parseUnibetFrankelCards(lines),
            parseTjkTables(lines),
            parseGenericRows(lines)
        )

        val horses = (candidates.maxByOrNull { it.size }.orEmpty())
            .distinctBy { "${it.number}:${it.horseName.lowercase()}" }

        val cleanTitle = extractRaceHeading(lines) ?: pageTitle.ifBlank {
            when {
                "tjk.org" in sourceUrl -> "مسابقات ترکیه (TJK)"
                "unibet" in sourceUrl -> "مسابقات اروپا/آمریکا (Unibet)"
                else -> "مسابقات اسبدوانی"
            }
        }

        return ParsedRaceContext(cleanTitle, distMeters, surface, horses)
    }

    // پارسر دقیق ساختار خط‌به‌خط asbdavani.app (پدر / مادر -> ن یا م -> سن -> وزن -> Kg -> IOR -> روز قبل -> عملکرد -> مالک/مربی/چابکسوار)
    private fun parseAsbdavaniVerticalBlocks(lines: List<String>): List<ParsedHorseRow> {
        val slashAnchors = mutableListOf<Int>()
        for (k in 2 until lines.size - 2) {
            if (lines[k] == "/") {
                val sire = lines[k - 1]
                val dam = lines[k + 1]
                val gender = lines[k + 2]
                val isPedigree = (gender == "ن" || gender == "م") &&
                    Regex("""[\u0600-\u06FFa-zA-Z]""").containsMatchIn(sire) &&
                    Regex("""[\u0600-\u06FFa-zA-Z]""").containsMatchIn(dam)
                if (isPedigree) slashAnchors.add(k)
            }
        }
        if (slashAnchors.size < 2) return emptyList()

        val footerIdx = lines.indexOfFirst { it == "سایت اسبدوانی" || it == "دسترسی سریع" }
            .takeIf { it > slashAnchors.first() } ?: lines.size

        val out = mutableListOf<ParsedHorseRow>()
        for (pos in slashAnchors.indices) {
            val slashIdx = slashAnchors[pos]
            val nameIdx = slashIdx - 2
            if (nameIdx < 0 || nameIdx >= footerIdx) continue

            val horseName = lines[nameIdx].trim()
            if (horseName.length < 2 || horseName == "مالک / مربی / چابکسوار") continue

            val nextNameIdx = slashAnchors.getOrNull(pos + 1)?.let { minOf(it - 2, footerIdx) } ?: footerIdx
            val block = lines.subList(nameIdx, nextNameIdx)

            // وزن و IOR بر اساس خط «Kg»
            var weightKg: Double? = null
            var ior: Double? = null
            val kgIdx = block.indexOfFirst { it.equals("Kg", ignoreCase = true) }
            if (kgIdx > 0) {
                weightKg = block[kgIdx - 1].toDoubleOrNull()?.takeIf { it in 40.0..75.0 }
                if (kgIdx + 1 < block.size) {
                    ior = block[kgIdx + 1].toDoubleOrNull()?.takeIf { it in 0.0..150.0 }
                }
            }

            // روزهای استراحت بر اساس «روز قبل»
            var daysAgo: Int? = null
            var recentDigits: List<Int> = emptyList()
            val daysIdx = block.indexOfFirst { it == "روز قبل" || it.contains("روز قبل") }
            if (daysIdx >= 0) {
                val inlineDays = daysAgoRegex.find(block[daysIdx])?.groupValues?.get(1)?.toIntOrNull()
                daysAgo = inlineDays ?: if (daysIdx > 0) block[daysIdx - 1].toIntOrNull() else null
                val nextAfterDays = block.getOrNull(daysIdx + 1).orEmpty()
                if (Regex("""^\d+(?:-\d+)+$""").matches(nextAfterDays)) {
                    recentDigits = nextAfterDays.split("-").mapNotNull { it.toIntOrNull() }
                }
            }

            // فرم W - P - S / N (روی ۷ خط متوالی)
            var formString: String? = null
            var formEndLocalIdx = -1
            for (b in 4 until block.size - 6) {
                if (block[b + 1] == "-" && block[b + 3] == "-" && block[b + 5] == "/") {
                    val w = block[b].toIntOrNull()
                    val p = block[b + 2].toIntOrNull()
                    val s = block[b + 4].toIntOrNull()
                    val n = block[b + 6].toIntOrNull()
                    if (w != null && p != null && s != null && n != null) {
                        formString = "$w-$p-$s/$n"
                        formEndLocalIdx = b + 6
                        break
                    }
                }
            }
            if (formString == null) {
                val joined = block.joinToString(" ")
                formString = extractCleanAsbForm(joined)
            }

            // مالک / مربی / چابکسوار بلافاصله بعد از عملکرد
            val peopleStart = if (formEndLocalIdx >= 0) formEndLocalIdx + 1 else (daysIdx + 1).coerceAtLeast(6)
            val tailLines = if (peopleStart < block.size) {
                block.subList(peopleStart, block.size)
                    .filter { ln -> ln.length >= 2 && !asbTimeRegex.matches(ln) && !Regex("""^\d+$""").matches(ln) }
            } else emptyList()

            val owner = tailLines.getOrElse(0) { "" }
            val trainer = tailLines.getOrElse(1) { "" }
            val jockey = tailLines.getOrElse(2) { "" }
            val bestTime = block.firstNotNullOfOrNull { parseAsbTimeSeconds(it) }

            out.add(
                ParsedHorseRow(
                    number = (out.size + 1).toString(),
                    horseName = horseName.take(40),
                    weightKg = weightKg,
                    rating = ior,
                    formString = formString,
                    recentDigits = recentDigits,
                    daysSinceLast = daysAgo,
                    bestTimeSec = bestTime,
                    owner = owner,
                    trainer = trainer,
                    jockey = jockey
                )
            )
        }
        return out
    }

    private fun parseAsbdavaniTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxGateCol = -1; var idxHorse = -1; var idxStats = -1; var idxForm = -1; var idxPeople = -1

        for (line in lines) {
            if (!line.contains("|")) continue
            val cells = line.split("|").map { it.trim() }
            val isHeader = cells.any { it.contains("اسب") } &&
                cells.any { it.contains("عملکرد") || it.contains("مربی") || it.contains("دپار") || it.contains("مقام") }

            if (isHeader) {
                if (out.size >= 2) break
                inTable = true
                idxGateCol = cells.indexOfFirst { it.contains("مقام") || it.contains("دپار") || it == "#" }
                idxHorse = cells.indexOfFirst { it.contains("اسب") }
                idxStats = cells.indexOfFirst { it.contains("وزن") || it.contains("IOR") || it.contains("سن") }
                idxForm = cells.indexOfFirst { it.contains("عملکرد") }
                idxPeople = cells.indexOfFirst { it.contains("مالک") || it.contains("مربی") || it.contains("چابکسوار") }
                continue
            }

            if (!inTable || idxHorse < 0 || cells.size <= idxHorse) continue
            val rawHorseCell = cells[idxHorse]
            if (rawHorseCell.isBlank()) continue

            val shirtNum = shirtNumRegex.find(line)?.groupValues?.get(1)
                ?: Regex("""^(\d{1,2})\b""").find(cells.getOrNull(idxGateCol).orEmpty())?.groupValues?.get(1)
                ?: (out.size + 1).toString()

            val gate = Regex("""\(\s*(\d{1,2})\s*\)""").find(cells.getOrNull(idxGateCol).orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""دپار\s*[:：]?\s*(\d{1,2})""").find(line)?.groupValues?.get(1)?.toIntOrNull()

            val cleanName = extractAsbHorseName(rawHorseCell)
            if (cleanName.length < 2) continue

            val statsText = cells.getOrNull(idxStats) ?: line
            val kgIor = asbKgIorRegex.find(statsText)
            val weight = kgIor?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 40.0..75.0 }
                ?: weightKgRegex.find(statsText)?.value?.toDoubleOrNull()
            val ior = kgIor?.groupValues?.get(2)?.toDoubleOrNull()

            val formCell = cells.getOrNull(idxForm) ?: line
            val daysAgo = daysAgoRegex.find(formCell)?.groupValues?.get(1)?.toIntOrNull()
                ?: daysAgoRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val formStr = extractCleanAsbForm(formCell) ?: extractCleanAsbForm(line)
            val bestTime = parseAsbTimeSeconds(line)

            val peopleParts = cells.getOrNull(idxPeople).orEmpty().split("·").map { it.trim() }.filter { it.isNotEmpty() }
            val agfVal = agfRegex.find(formRegex.replace(line, " "))?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() }

            out.add(
                ParsedHorseRow(
                    number = shirtNum,
                    gate = gate,
                    horseName = cleanName,
                    weightKg = weight,
                    rating = ior,
                    formString = formStr,
                    daysSinceLast = daysAgo,
                    bestTimeSec = bestTime,
                    marketAgfPct = agfVal,
                    owner = peopleParts.getOrElse(0) { "" },
                    trainer = peopleParts.getOrElse(1) { "" },
                    jockey = peopleParts.getOrElse(2) { "" }
                )
            )
        }
        return out
    }

    private fun parseAsbdavaniCardBlocks(lines: List<String>): List<ParsedHorseRow> {
        val shirtIndices = lines.indices.filter { shirtNumRegex.containsMatchIn(lines[it]) }
        if (shirtIndices.size < 2) return emptyList()

        val out = mutableListOf<ParsedHorseRow>()
        for (pos in shirtIndices.indices) {
            val idx = shirtIndices[pos]
            val prevEnd = if (pos > 0) shirtIndices[pos - 1] else maxOf(0, idx - 3)
            val nextIdx = shirtIndices.getOrNull(pos + 1) ?: minOf(lines.size, idx + 12)
            val stopAtHeader = (idx + 1 until nextIdx).firstOrNull { lines[it].contains("مقام (دپار)") } ?: nextIdx
            val blockJoined = lines.subList(idx, stopAtHeader).joinToString(" · ")
            val shirtNum = shirtNumRegex.find(lines[idx])?.groupValues?.get(1) ?: (out.size + 1).toString()

            var gate: Int? = null
            for (gIdx in (idx - 1) downTo maxOf(prevEnd + 1, idx - 3)) {
                val gm = Regex("""^\d{1,2}\s*\(\s*(\d{1,2})\s*\)\.?$""").find(lines[gIdx])
                if (gm != null) { gate = gm.groupValues[1].toIntOrNull(); break }
            }

            var name = extractAsbHorseName(lines[idx])
            if (name.length < 2 && idx + 1 < stopAtHeader) name = extractAsbHorseName(lines[idx + 1])
            if (name.length < 2) continue

            val kgIor = asbKgIorRegex.find(blockJoined)
            out.add(
                ParsedHorseRow(
                    number = shirtNum,
                    gate = gate,
                    horseName = name,
                    weightKg = kgIor?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 40.0..75.0 },
                    rating = kgIor?.groupValues?.get(2)?.toDoubleOrNull(),
                    formString = extractCleanAsbForm(blockJoined),
                    daysSinceLast = daysAgoRegex.find(blockJoined)?.groupValues?.get(1)?.toIntOrNull(),
                    bestTimeSec = parseAsbTimeSeconds(blockJoined)
                )
            )
        }
        return out
    }

    private fun extractAsbHorseName(rawCell: String): String {
        val afterShirt = rawCell.replace(Regex("""پیراهن\s*شماره\s*\d+\s*·?\s*"""), "").trim()
        val segments = afterShirt.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        val candidate = segments.firstOrNull { !it.startsWith("(") && !it.contains("/") && it.length >= 2 }
            ?: segments.firstOrNull().orEmpty().substringBefore("/")
        return candidate.replace(Regex("""\([^)]*\)"""), "").replace(Regex("""\s+"""), " ").trim().take(40)
    }

    private fun extractCleanAsbForm(text: String): String? {
        val m = formRegex.find(text) ?: return null
        var w = m.groupValues[1].toIntOrNull() ?: 0
        val p = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        val n = (m.groupValues[4].toIntOrNull() ?: 0).coerceAtLeast(1)
        if (w > n && w >= 10) w %= 10
        return "$w-$p-$s/$n"
    }

    private fun parseAsbTimeSeconds(text: String): Double? {
        val m = asbTimeRegex.find(text) ?: return null
        val mins = m.groupValues[1].toIntOrNull() ?: return null
        val secs = m.groupValues[2].toIntOrNull() ?: return null
        val ms = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val frac = if (m.groupValues[3].length == 3) ms / 1000.0 else ms / 100.0
        return mins * 60.0 + secs + frac
    }

    private fun parseUnibetFrankelCards(lines: List<String>): List<ParsedHorseRow> {
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
                if (trainer.isBlank() && Regex("""^T[A-Z]""").containsMatchIn(ln)) trainer = ln.substring(1).trim()
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

    private fun extractRaceHeading(lines: List<String>): String? {
        val asbIdx = lines.indexOfFirst { it == "جدول مسابقات" }
        if (asbIdx >= 0 && asbIdx + 1 < lines.size && lines[asbIdx + 1].contains("هفته")) {
            val dorIdx = lines.indexOfFirst { it == "دور" }
            val roundTxt = if (dorIdx >= 0 && dorIdx + 1 < lines.size) " — دور ${lines[dorIdx + 1]}" else ""
            return "${lines[asbIdx + 1]}$roundTxt"
        }
        val asbWeek = lines.firstOrNull { it.contains("جدول مسابقات") && it.contains("هفته") }
        if (asbWeek != null) return asbWeek.take(65)
        val raceLine = lines.firstOrNull { Regex("""^Race\s+\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val trackLine = lines.firstOrNull {
            val idx = lines.indexOf(it)
            idx > 0 && lines.getOrNull(idx + 1)?.startsWith("Race ", true) == true && it.length in 3..40
        }
        return when {
            trackLine != null && raceLine != null -> "$trackLine — $raceLine"
            raceLine != null -> raceLine
            else -> null
        }
    }

    private fun parseImperialDistance(lines: List<String>): Int? {
        for (ln in lines.take(65)) {
            val miMatch = Regex("""(\d+)\s*mi(?:les?)?\s*(?:(\d+)\s*f)?\s*(?:(\d+)\s*y)?""", RegexOption.IGNORE_CASE).find(ln)
            if (miMatch != null) {
                val mi = miMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val f = miMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                val y = miMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                val total = (mi * 1609.34 + f * 201.168 + y * 0.9144).roundToInt()
                if (total in 700..5000) return total
            }
            val furMatch = Regex("""\b(\d+)\s*f\b""", RegexOption.IGNORE_CASE).find(ln)
            if (furMatch != null) {
                val total = ((furMatch.groupValues[1].toDoubleOrNull() ?: 0.0) * 201.168).roundToInt()
                if (total in 700..5000) return total
            }
        }
        return null
    }

    private fun parseTjkTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxNum = -1; var idxName = -1; var idxWeight = -1; var idxJockey = -1
        var idxOwner = -1; var idxTrainer = -1; var idxGate = -1; var idxHp = -1
        var idxSon6 = -1; var idxKgs = -1; var idxS20 = -1; var idxBestTime = -1; var idxGny = -1; var idxAgf = -1

        for (line in lines) {
            if (!line.contains("|")) continue
            val cells = line.split("|").map { it.trim() }
            val hasAtIsmi = cells.any { Regex("""at\s*[iİı]sm[iİı]""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val hasJokey = cells.any { Regex("""jokey""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

            if (hasAtIsmi && hasJokey) {
                if (out.size >= 2) break
                inTable = true
                idxNum = cells.indexOfFirst { it.equals("N", true) || it == "#" }
                idxName = cells.indexOfFirst { Regex("""at\s*[iİı]sm[iİı]""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxWeight = cells.indexOfFirst { Regex("""s[iı]klet""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxJockey = cells.indexOfFirst { Regex("""jokey""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxOwner = cells.indexOfFirst { Regex("""sah[iİı]p""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxTrainer = cells.indexOfFirst { Regex("""antren[oö]r""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxGate = cells.indexOfFirst { Regex("""^st\.?$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxHp = cells.indexOfFirst { it.equals("HP", true) }
                idxSon6 = cells.indexOfFirst { Regex("""son\s*6""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxKgs = cells.indexOfFirst { it.equals("KGS", true) }
                idxS20 = cells.indexOfFirst { it.equals("s20", true) }
                idxBestTime = cells.indexOfFirst { Regex("""en\s*[iİı]y[iİı]""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                idxGny = cells.indexOfFirst { it.equals("Gny", true) }
                idxAgf = cells.indexOfFirst { Regex("""agf""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                continue
            }

            if (!inTable || idxName < 0 || cells.size <= idxName) continue
            val rawNameCell = cells[idxName]
            if (rawNameCell.isBlank() || Regex("""toplam|koşmaz|at\s*[iİı]sm""", RegexOption.IGNORE_CASE).containsMatchIn(rawNameCell)) continue

            val firstNameSegment = rawNameCell.split("·").firstOrNull()?.trim().orEmpty()
            val numAndName = Regex("""^(\d{1,2})\s*[.\-)]\s*(.+)$""").find(firstNameSegment)
            val number = if (idxNum in cells.indices && Regex("""^\d{1,2}$""").matches(cells[idxNum])) cells[idxNum]
            else numAndName?.groupValues?.get(1) ?: (out.size + 1).toString()

            val cleanName = (numAndName?.groupValues?.get(2) ?: firstNameSegment)
                .replace(Regex("""\b(KG|DB|SK|GKR|YP|ÖG|K)\b"""), "").replace(Regex("""\s+"""), " ").trim()
            if (cleanName.length < 2) continue

            val son6Digits = if (idxSon6 in cells.indices) {
                cells[idxSon6].filter { it.isDigit() }.map { ch -> if (ch == '0') 10 else ch.digitToInt() }
            } else emptyList()

            out.add(
                ParsedHorseRow(
                    number = number,
                    gate = if (idxGate in cells.indices) Regex("""(\d{1,2})""").find(cells[idxGate])?.groupValues?.get(1)?.toIntOrNull() else null,
                    horseName = cleanName,
                    weightKg = if (idxWeight in cells.indices) Regex("""(\d{2}(?:\.\d)?)""").find(cells[idxWeight])?.value?.toDoubleOrNull() else null,
                    rating = if (idxHp in cells.indices) cells[idxHp].toDoubleOrNull() else null,
                    recentDigits = son6Digits,
                    daysSinceLast = if (idxKgs in cells.indices) cells[idxKgs].toIntOrNull() else null,
                    bestTimeSec = if (idxBestTime in cells.indices) parseTimeSeconds(cells[idxBestTime]) else null,
                    s20Score = if (idxS20 in cells.indices) cells[idxS20].toIntOrNull() else null,
                    marketOdds = if (idxGny in cells.indices) cells[idxGny].replace(",", ".").toDoubleOrNull() else null,
                    marketAgfPct = if (idxAgf in cells.indices) agfRegex.find(cells[idxAgf])?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() } else null,
                    owner = if (idxOwner in cells.indices) cells[idxOwner].replace("·", " ").trim() else "",
                    trainer = if (idxTrainer in cells.indices) cells[idxTrainer].replace("·", " ").trim() else "",
                    jockey = if (idxJockey in cells.indices) cells[idxJockey].replace("·", " ").trim() else ""
                )
            )
        }
        return out
    }

    private fun parseGenericRows(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        for (line in lines) {
            if (!line.contains("|")) continue
            val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size < 2) continue
            val first = cells.first()
            if (first.contains("مقام") || first.contains("اسب") || first == "#") continue
            val formStr = extractCleanAsbForm(line)
            val noForm = formRegex.replace(line, " ")
            val agfVal = agfRegex.find(noForm)?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() }
            val decOdds = cells.firstNotNullOfOrNull { parseOddsToken(it) }?.takeIf { it > 1.0 }
            if (formStr == null && agfVal == null && decOdds == null) continue
            val number = Regex("""^(\d{1,2})\b""").find(first)?.groupValues?.get(1) ?: (out.size + 1).toString()
            val horseName = cells.drop(1).firstOrNull { c ->
                c.length >= 2 && !formRegex.containsMatchIn(c) && parseOddsToken(c) == null && !Regex("""^[\d\s/%.\-,()]+$""").matches(c)
            }.orEmpty().take(36)
            if (horseName.isBlank()) continue
            out.add(ParsedHorseRow(number = number, horseName = horseName, weightKg = weightKgRegex.find(noForm)?.value?.toDoubleOrNull(), formString = formStr, marketOdds = decOdds, marketAgfPct = agfVal))
        }
        return out
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

    private fun parseTimeSeconds(raw: String): Double? {
        val m = timeDotRegex.find(raw.replace(":", ".")) ?: return null
        val mins = m.groupValues[1].toIntOrNull() ?: return null
        val secs = m.groupValues[2].toIntOrNull() ?: return null
        val hundredths = m.groupValues[3].toIntOrNull() ?: 0
        return mins * 60.0 + secs + (hundredths / 100.0)
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
