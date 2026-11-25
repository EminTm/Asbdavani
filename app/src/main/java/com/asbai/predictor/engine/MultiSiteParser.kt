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

object MultiSiteParser {

    private val formRegex = Regex("""(\d+)\s*-\s*(\d+)\s*-\s*(\d+)\s*/\s*(\d+)""")
    private val agfRegex = Regex("""%\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*%""")
    private val timeDotRegex = Regex("""\b(\d)\.(\d{2})\.(\d{2})\b""")
    private val weightRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.(\d))?\b""")
    private val distRegex = Regex("""(\d{3,4})\s*(?:متر|m\.?)?\s*(چمن|خاکی|سنتتیک|Çim|Kum|Sentetik|Dirt|Turf|AW)?""", RegexOption.IGNORE_CASE)

    private val noiseWords = setOf(
        "win", "place", "show", "each way", "ew", "nr", "non-runner",
        "non runner", "uitgeschakeld", "voorspelling", "wedden", "odds", "form",
        "live", "info", "stats", "results", "racecard", "unibet", "paardenraces",
        "favoriet", "race", "races", "all", "today", "tomorrow", "cet", "gmt", "bst",
        "vandaag", "morgen", "starttijd", "afstand", "lopers", "plaats", "winst"
    )

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
                if (isTjkHeader || isAsbHeader) {
                    headerIndices.add(i)
                }
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

        var distMeters: Int? = null
        var surface = ""
        for (line in lines.take(55)) {
            val dm = distRegex.find(line)
            if (dm != null) {
                val d = dm.groupValues[1].toIntOrNull()
                if (d != null && d in 800..4500) {
                    distMeters = d
                    val sRaw = dm.groupValues[2].lowercase()
                    surface = when {
                        "çim" in sRaw || "چمن" in sRaw || "turf" in sRaw -> "چمن"
                        "kum" in sRaw || "خاکی" in sRaw || "dirt" in sRaw -> "خاکی"
                        "sentetik" in sRaw || "سنتتیک" in sRaw || "aw" in sRaw -> "سنتتیک"
                        else -> ""
                    }
                    break
                }
            }
        }

        val candidates = listOf(
            parseTjkTables(lines),
            parseAsbdavaniTables(lines),
            parseGenericRows(lines),
            parseAsbdavaniCardBlocks(lines),
            parseUnibetOrLineBlocks(lines)
        )

        val horses = (candidates.maxByOrNull { it.size }.orEmpty())
            .distinctBy { "${it.number}:${it.horseName.lowercase()}" }

        val cleanTitle = pageTitle.ifBlank {
            when {
                "tjk.org" in sourceUrl -> "مسابقات ترکیه (TJK)"
                "unibet" in sourceUrl -> "مسابقات اروپا (Unibet)"
                else -> "مسابقات اسبدوانی"
            }
        }

        return ParsedRaceContext(cleanTitle, distMeters, surface, horses)
    }

    private fun parseTjkTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxNum = -1; var idxName = -1; var idxWeight = -1; var idxJockey = -1
        var idxOwner = -1; var idxTrainer = -1; var idxGate = -1; var idxHp = -1
        var idxSon6 = -1; var idxKgs = -1; var idxS20 = -1; var idxBestTime = -1
        var idxGny = -1; var idxAgf = -1

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
            val number = if (idxNum in cells.indices && Regex("""^\d{1,2}$""").matches(cells[idxNum])) {
                cells[idxNum]
            } else {
                numAndName?.groupValues?.get(1) ?: (out.size + 1).toString()
            }
            val cleanName = (numAndName?.groupValues?.get(2) ?: firstNameSegment)
                .replace(Regex("""\b(KG|DB|SK|GKR|YP|ÖG|K)\b"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
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

    private fun parseAsbdavaniTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxGateCol = -1; var idxHorse = -1; var idxStats = -1; var idxForm = -1; var idxPeople = -1

        for (line in lines) {
            if (!line.contains("|")) continue
            val cells = line.split("|").map { it.trim() }
            val isHeader = cells.any { it.contains("اسب") } &&
                (cells.any { it.contains("عملکرد") || it.contains("مربی") || it.contains("دپار") || it.contains("مقام") })

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

            val shirtNum = Regex("""(?:پیراهن\s*شماره|شماره)\s*(\d{1,2})""").find(line)?.groupValues?.get(1)
                ?: Regex("""^(\d{1,2})\b""").find(cells.getOrNull(idxGateCol).orEmpty())?.groupValues?.get(1)
                ?: (out.size + 1).toString()

            val gate = Regex("""\(\s*(\d{1,2})\s*\)""").find(cells.getOrNull(idxGateCol).orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""دپار\s*[:：]?\s*(\d{1,2})""").find(line)?.groupValues?.get(1)?.toIntOrNull()

            val horseParts = rawHorseCell.split("·").map { it.trim() }.filter { it.isNotEmpty() }
            val cleanName = (horseParts.firstOrNull { p ->
                !p.contains("پیراهن") && !p.contains("شماره") && !p.startsWith("(") && p.length >= 2
            } ?: rawHorseCell)
                .replace(Regex("""پیراهن\s*شماره\s*\d+"""), "")
                .replace(Regex("""\([^)]*\)"""), "")
                .trim()
            if (cleanName.length < 2) continue

            val statsText = cells.getOrNull(idxStats).orEmpty()
            val weight = Regex("""(\d{2}(?:\.\d)?)\s*(?:کیلو|kg)?""").find(statsText)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: weightRegex.find(statsText)?.value?.toDoubleOrNull()
            val ior = Regex("""IOR\s*[:：]?\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(statsText)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""\b(\d{1,3})\s*$""").find(statsText)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 10.0..140.0 }

            val formStr = formRegex.find(cells.getOrNull(idxForm) ?: line)?.value
            val peopleParts = cells.getOrNull(idxPeople).orEmpty().split("·").map { it.trim() }.filter { it.isNotEmpty() }
            val agfVal = agfRegex.find(formRegex.replace(line, " "))?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() }

            out.add(
                ParsedHorseRow(
                    number = shirtNum,
                    gate = gate,
                    horseName = cleanName.take(40),
                    weightKg = weight,
                    rating = ior,
                    formString = formStr,
                    marketAgfPct = agfVal,
                    owner = peopleParts.getOrElse(0) { "" },
                    trainer = peopleParts.getOrElse(1) { "" },
                    jockey = peopleParts.getOrElse(2) { "" }
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

            val formStr = formRegex.find(line)?.value
            val noForm = formRegex.replace(line, " ")
            val agfVal = agfRegex.find(noForm)?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() }
            val decOdds = cells.firstNotNullOfOrNull { parseOddsToken(it) }?.takeIf { it > 1.0 }
            if (formStr == null && agfVal == null && decOdds == null) continue

            val number = Regex("""^(\d{1,2})\b""").find(first)?.groupValues?.get(1) ?: (out.size + 1).toString()
            val horseName = cells.drop(1).firstOrNull { c ->
                c.length >= 2 && !formRegex.containsMatchIn(c) && parseOddsToken(c) == null && !Regex("""^[\d\s/%.\-,()]+$""").matches(c)
            }.orEmpty().take(36)
            if (horseName.isBlank()) continue

            out.add(ParsedHorseRow(number = number, horseName = horseName, weightKg = weightRegex.find(noForm)?.value?.toDoubleOrNull(), formString = formStr, marketOdds = decOdds, marketAgfPct = agfVal))
        }
        return out
    }

    // برای کارت‌های موبایلی asbdavani (حتی برای اسب‌های بدون استارت قبلی)
    private fun parseAsbdavaniCardBlocks(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var blockStart = 0
        for (i in lines.indices) {
            val isShirtMarker = lines[i].contains("پیراهن شماره")
            val isFormMarker = formRegex.containsMatchIn(lines[i])
            if (!isShirtMarker && !isFormMarker) continue

            val start = maxOf(blockStart, if (isShirtMarker) i else i - 5)
            val end = minOf(lines.lastIndex, if (isShirtMarker) i + 6 else i + 3)
            val window = lines.subList(start, end + 1)
            val joined = window.joinToString(" | ")

            val num = Regex("""(?:پیراهن\s*شماره|شماره)\s*(\d{1,2})""").find(joined)?.groupValues?.get(1)
                ?: window.firstNotNullOfOrNull { Regex("""^(\d{1,2})\s*(?:\(\d+\))?$""").find(it)?.groupValues?.get(1) }
                ?: (out.size + 1).toString()

            val gate = Regex("""\(\s*(\d{1,2})\s*\)|دپار\s*[:：]?\s*(\d{1,2})""").find(joined)?.let {
                (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull()
            }

            val name = window.firstOrNull { ln ->
                ln.length in 2..36 && !formRegex.containsMatchIn(ln) && !ln.contains("پیراهن") &&
                    !ln.contains("کیلو") && !ln.contains("IOR") && !ln.contains("دپار") &&
                    !ln.contains("مقام") && !ln.contains("عملکرد") && !ln.startsWith("(") &&
                    Regex("""[\u0600-\u06FFa-zA-Z]""").containsMatchIn(ln) && !Regex("""^[\d\s/%.\-(),]+$""").matches(ln)
            }.orEmpty()

            if (name.isNotBlank() && out.none { it.horseName == name }) {
                out.add(
                    ParsedHorseRow(
                        number = num,
                        gate = gate,
                        horseName = name,
                        weightKg = Regex("""(\d{2}(?:\.\d)?)\s*(?:کیلو|kg)""").find(joined)?.groupValues?.get(1)?.toDoubleOrNull(),
                        rating = Regex("""IOR\s*[:：]?\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(joined)?.groupValues?.get(1)?.toDoubleOrNull(),
                        formString = formRegex.find(joined)?.value
                    )
                )
                blockStart = end + 1
            }
        }
        return out
    }

    // جستجوی رو به بالا از روی هر ضریب (Unibet با ۱ یا ۲ رقم اعشار و SP)
    private fun parseUnibetOrLineBlocks(lines: List<String>): List<ParsedHorseRow> {
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
            var gate: Int? = null
            var horseName = ""
            var jockey = ""
            var trainer = ""
            var formStr: String? = null

            for (k in (i - 1) downTo winFrom) {
                val trimmed = lines[k].trim()
                if (trimmed.isBlank()) continue

                if (trimmed.startsWith("J:", true) || trimmed.contains("Jockey", true)) {
                    if (jockey.isBlank()) jockey = trimmed.replace(Regex("""J:|Jockey:?""", RegexOption.IGNORE_CASE), "").trim()
                    continue
                }
                if (trimmed.startsWith("T:", true) || trimmed.contains("Trainer", true)) {
                    if (trainer.isBlank()) trainer = trimmed.replace(Regex("""T:|Trainer:?""", RegexOption.IGNORE_CASE), "").trim()
                    continue
                }
                if (Regex("""^\d[\d\-/]{2,12}$""").matches(trimmed)) {
                    if (formStr == null) formStr = formRegex.find(trimmed)?.value
                    continue
                }
                val gParen = Regex("""^\((\d{1,2})\)$|^(?:Draw|Post|Gate|Box)\s*[:#]?\s*(\d{1,2})$""", RegexOption.IGNORE_CASE).find(trimmed)
                if (gParen != null) {
                    if (gate == null) gate = (gParen.groupValues[1].ifBlank { gParen.groupValues[2] }).toIntOrNull()
                    continue
                }
                val onlyNum = Regex("""^(\d{1,2})\.?$""").find(trimmed)
                if (onlyNum != null) {
                    if (number == null) number = onlyNum.groupValues[1]
                    continue
                }
                val numPrefix = Regex("""^(\d{1,2})[.)]\s+([A-Za-z\u0600-\u06FF].+)$""").find(trimmed)
                if (numPrefix != null) {
                    if (number == null) number = numPrefix.groupValues[1]
                    val candidate = cleanCandidateHorseName(numPrefix.groupValues[2])
                    if (horseName.isBlank() && isValidHorseName(candidate)) horseName = candidate
                    continue
                }

                if (horseName.isBlank()) {
                    val candidate = cleanCandidateHorseName(trimmed)
                    if (isValidHorseName(candidate)) horseName = candidate
                }
            }

            if (horseName.isNotBlank() && out.none { it.horseName.equals(horseName, ignoreCase = true) }) {
                val joined = lines.subList(winFrom, i).joinToString(" | ")
                out.add(
                    ParsedHorseRow(
                        number = number ?: (out.size + 1).toString(),
                        gate = gate,
                        horseName = horseName.take(36),
                        weightKg = weightRegex.find(joined)?.value?.toDoubleOrNull(),
                        formString = formStr,
                        marketOdds = odds.takeIf { it > 1.0 },
                        trainer = trainer,
                        jockey = jockey
                    )
                )
                blockStart = i + 1
                if (i + 1 < lines.size && parseOddsToken(lines[i + 1]) != null) i++
            }
            i++
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

    private fun cleanCandidateHorseName(raw: String): String {
        return raw.replace(Regex("""\(\d{1,2}\)\s*$"""), "").replace(Regex("""\s+"""), " ").trim()
    }

    private fun isValidHorseName(s: String): Boolean {
        if (s.length !in 3..42) return false
        val lower = s.lowercase()
        if (lower in noiseWords) return false
        if (lower.startsWith("cet ") || lower.startsWith("gmt ") || lower.startsWith("http")) return false
        if (s.contains("آدرس:") || s.contains("عنوان:") || s.contains("---")) return false
        if (Regex("""\b\d{3,4}\s*m\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return false
        if (!Regex("""[A-Za-z\u0600-\u06FF]{2,}""").containsMatchIn(s)) return false
        if (Regex("""^\d[\d\s.,:/\-()]+$""").matches(s)) return false
        return true
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
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
