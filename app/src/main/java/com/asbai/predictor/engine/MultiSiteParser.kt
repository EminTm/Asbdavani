package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow

data class ParsedRaceContext(
    val raceTitle: String,
    val distanceMeters: Int?,
    val surface: String,
    val horses: List<ParsedHorseRow>
)

object MultiSiteParser {

    private val formRegex = Regex("""(\d+)\s*-\s*(\d+)\s*-\s*(\d+)\s*/\s*(\d+)""")
    private val agfRegex = Regex("""%\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*%""")
    private val timeDotRegex = Regex("""\b(\d)\.(\d{2})\.(\d{2})\b""")
    private val weightRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.(\d))?\b""")
    private val distRegex = Regex("""(\d{3,4})\s*(?:متر|m\.?)?\s*(چمن|خاکی|سنتتیک|Çim|Kum|Sentetik)?""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String, pageTitle: String, sourceUrl: String): ParsedRaceContext {
        val ascii = toAsciiDigits(rawText)
        val lines = ascii.lines().map { it.trim() }.filter { it.isNotEmpty() }

        var distMeters: Int? = null
        var surface = ""
        for (line in lines.take(45)) {
            val dm = distRegex.find(line)
            if (dm != null) {
                val d = dm.groupValues[1].toIntOrNull()
                if (d != null && d in 800..4000) {
                    distMeters = d
                    val sRaw = dm.groupValues[2].lowercase()
                    surface = when {
                        "çim" in sRaw || "چمن" in sRaw -> "چمن"
                        "kum" in sRaw || "خاکی" in sRaw -> "خاکی"
                        "sentetik" in sRaw || "سنتتیک" in sRaw -> "سنتتیک"
                        else -> ""
                    }
                    break
                }
            }
        }

        val tjkParsed = parseTjkTables(lines)
        val asbParsed = if (tjkParsed.isEmpty()) parseAsbdavaniTables(lines) else emptyList()
        val genericParsed = if (tjkParsed.isEmpty() && asbParsed.isEmpty()) parseGenericRows(lines) else emptyList()

        val horses = (tjkParsed.ifEmpty { asbParsed }.ifEmpty { genericParsed })
            .distinctBy { "${it.number}:${it.horseName.lowercase()}" }

        val cleanTitle = pageTitle.ifBlank {
            when {
                "tjk.org" in sourceUrl -> "مسابقات ترکیه (TJK)"
                "unibet" in sourceUrl -> "مسابقات اروپا (Unibet)"
                else -> "مسابقات اسبدوانی"
            }
        }

        return ParsedRaceContext(
            raceTitle = cleanTitle,
            distanceMeters = distMeters,
            surface = surface,
            horses = horses
        )
    }

    private fun parseTjkTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxNum = -1
        var idxName = -1
        var idxWeight = -1
        var idxJockey = -1
        var idxOwner = -1
        var idxTrainer = -1
        var idxGate = -1
        var idxHp = -1
        var idxSon6 = -1
        var idxKgs = -1
        var idxS20 = -1
        var idxBestTime = -1
        var idxGny = -1
        var idxAgf = -1

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
            val rawName = cells[idxName]
            if (rawName.isBlank() || Regex("""toplam|koşmaz""", RegexOption.IGNORE_CASE).containsMatchIn(rawName)) continue

            val numAndName = Regex("""^(\d{1,2})\s*[.\-)]\s*(.+)$""").find(rawName)
            val number = if (idxNum >= 0 && idxNum < cells.size && cells[idxNum].all { it.isDigit() }) {
                cells[idxNum]
            } else {
                numAndName?.groupValues?.get(1) ?: (out.size + 1).toString()
            }
            val cleanName = (numAndName?.groupValues?.get(2) ?: rawName)
                .replace(Regex("""\b(KG|DB|SK|K|GKR|YP)\b"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (cleanName.length < 2) continue

            val son6Digits = if (idxSon6 in cells.indices) {
                cells[idxSon6].filter { it.isDigit() }.map { ch -> if (ch == '0') 10 else ch.digitToInt() }
            } else emptyList()

            val bestTime = if (idxBestTime in cells.indices) parseTimeSeconds(cells[idxBestTime]) else null
            val gateVal = if (idxGate in cells.indices) Regex("""(\d{1,2})""").find(cells[idxGate])?.groupValues?.get(1)?.toIntOrNull() else null
            val weightVal = if (idxWeight in cells.indices) Regex("""(\d{2}(?:\.\d)?)""").find(cells[idxWeight])?.value?.toDoubleOrNull() else null
            val hpVal = if (idxHp in cells.indices) cells[idxHp].toDoubleOrNull() else null
            val kgsVal = if (idxKgs in cells.indices) cells[idxKgs].toIntOrNull() else null
            val s20Val = if (idxS20 in cells.indices) cells[idxS20].toIntOrNull() else null
            val gnyVal = if (idxGny in cells.indices) cells[idxGny].replace(",", ".").toDoubleOrNull() else null
            val agfVal = if (idxAgf in cells.indices) {
                agfRegex.find(cells[idxAgf])?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() }
            } else null

            out.add(
                ParsedHorseRow(
                    number = number,
                    gate = gateVal,
                    horseName = cleanName,
                    weightKg = weightVal,
                    rating = hpVal,
                    recentDigits = son6Digits,
                    daysSinceLast = kgsVal,
                    bestTimeSec = bestTime,
                    s20Score = s20Val,
                    marketOdds = gnyVal,
                    marketAgfPct = agfVal,
                    owner = if (idxOwner in cells.indices) cells[idxOwner] else "",
                    trainer = if (idxTrainer in cells.indices) cells[idxTrainer] else "",
                    jockey = if (idxJockey in cells.indices) cells[idxJockey] else ""
                )
            )
        }
        return out
    }

    private fun parseAsbdavaniTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxGateCol = -1
        var idxHorse = -1
        var idxStats = -1
        var idxForm = -1
        var idxPeople = -1

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
            val cleanName = (horseParts.firstOrNull { !it.contains("پیراهن") && !it.contains("شماره") && it.length >= 2 }
                ?: rawHorseCell)
                .replace(Regex("""پیراهن\s*شماره\s*\d+"""), "")
                .trim()
            if (cleanName.length < 2) continue

            val statsText = cells.getOrNull(idxStats).orEmpty()
            val weight = Regex("""(\d{2}(?:\.\d)?)\s*(?:کیلو|kg)?""").find(statsText)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: weightRegex.find(statsText)?.value?.toDoubleOrNull()
            val ior = Regex("""IOR\s*[:：]?\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(statsText)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""\b(\d{1,3})\s*$""").find(statsText)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 10.0..140.0 }

            val formStr = formRegex.find(cells.getOrNull(idxForm) ?: line)?.value

            val peopleParts = cells.getOrNull(idxPeople).orEmpty()
                .split("·").map { it.trim() }.filter { it.isNotEmpty() }
            val owner = peopleParts.getOrElse(0) { "" }
            val trainer = peopleParts.getOrElse(1) { "" }
            val jockey = peopleParts.getOrElse(2) { "" }

            val agfVal = agfRegex.find(formRegex.replace(line, " "))?.let {
                (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull()
            }

            out.add(
                ParsedHorseRow(
                    number = shirtNum,
                    gate = gate,
                    horseName = cleanName.take(40),
                    weightKg = weight,
                    rating = ior,
                    formString = formStr,
                    marketAgfPct = agfVal,
                    owner = owner,
                    trainer = trainer,
                    jockey = jockey
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
            val fracMatch = Regex("""(?:^|[\s|])(\d{1,2})\s*/\s*(\d{1,2})(?=$|[\s|])""").find(noForm)
            val decOdds = fracMatch?.let {
                val n = it.groupValues[1].toDoubleOrNull() ?: 0.0
                val d = it.groupValues[2].toDoubleOrNull() ?: 1.0
                if (d > 0) (n / d) + 1.0 else null
            }

            if (formStr == null && agfVal == null && decOdds == null) continue

            val number = Regex("""^(\d{1,2})\b""").find(first)?.groupValues?.get(1) ?: (out.size + 1).toString()
            val horseName = cells.drop(1).firstOrNull { c ->
                c.length >= 2 && !formRegex.containsMatchIn(c) && !Regex("""^[\d\s/%.\-()]+$""").matches(c)
            }.orEmpty().take(36)
            if (horseName.isBlank()) continue

            out.add(
                ParsedHorseRow(
                    number = number,
                    horseName = horseName,
                    weightKg = weightRegex.find(noForm)?.value?.toDoubleOrNull(),
                    formString = formStr,
                    marketOdds = decOdds,
                    marketAgfPct = agfVal
                )
            )
        }
        return out
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
