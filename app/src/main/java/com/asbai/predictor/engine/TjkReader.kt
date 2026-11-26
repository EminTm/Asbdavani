package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow

object TjkReader {

    private val agfRegex = Regex("""%\s*(\d+(?:[.,]\d+)?)|(\d+(?:[.,]\d+)?)\s*%""")
    private val timeDotRegex = Regex("""\b(\d)[.:](\d{2})[.:](\d{2})\b""")

    fun splitSections(rawText: String): List<RaceSection> {
        val lines = PageReaderEngine.toAsciiDigits(rawText).lines()
        val headerIndices = mutableListOf<Int>()

        for (i in lines.indices) {
            val ln = lines[i]
            if (!ln.contains("|")) continue
            val cells = ln.split("|").map { it.trim() }
            val hasAt = cells.any { Regex("""at\s*[iİı]sm[iİı]""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val hasJokey = cells.any { Regex("""jokey""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            if (hasAt && hasJokey) headerIndices.add(i)
        }

        if (headerIndices.size <= 1) return emptyList()

        return headerIndices.mapIndexed { k, hIdx ->
            val start = maxOf(0, hIdx - 8)
            val end = headerIndices.getOrNull(k + 1)?.let { maxOf(start + 1, it - 8) } ?: lines.size
            val chunk = lines.subList(start, end).joinToString("\n")
            val kosu = Regex("""(\d+)\.\s*Ko[sş]u""", RegexOption.IGNORE_CASE).find(chunk)?.groupValues?.get(1)
            RaceSection(label = kosu?.let { "دور $it" } ?: "دور ${k + 1}", rawChunk = chunk)
        }
    }

    fun read(cleanText: String, pageTitle: String, sourceUrl: String): ReadPageResult {
        val lines = PageReaderEngine.toAsciiDigits(cleanText).lines().map { it.trim() }.filter { it.isNotEmpty() }

        var distMeters: Int? = null
        var surface = ""
        for (ln in lines.take(45)) {
            val dm = Regex("""(\d{3,4})\s*(?:m\.?\s*)?(Çim|Kum|Sentetik)""", RegexOption.IGNORE_CASE).find(ln)
            if (dm != null) {
                distMeters = dm.groupValues[1].toIntOrNull()
                val s = dm.groupValues[2].lowercase()
                surface = when {
                    "çim" in s -> "چمن"
                    "kum" in s -> "خاکی"
                    "sentetik" in s -> "سنتتیک"
                    else -> ""
                }
                break
            }
        }

        val horses = parseTables(lines)
        val kosuTitle = lines.firstOrNull { Regex("""\d+\.\s*Ko[sş]u""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val title = kosuTitle?.take(60) ?: pageTitle.ifBlank { "مسابقات ترکیه (TJK)" }
        val ctx = ParsedRaceContext(title, distMeters, surface, horses)
        val label = if (horses.size >= 2) "موتور اختصاصی ترکیه TJK (${horses.size} اسب ✅)" else "جدول TJK پیدا نشد"
        return ReadPageResult(ctx, label, cleanText)
    }

    private fun parseTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var colShift: Int? = null

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
                colShift = null
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

            if (!inTable || idxName < 0) continue

            // تشخیص خودکار جابجایی ستون ناشی از colspan در ستون Forma
            if (colShift == null) {
                val atCell = cells.getOrNull(idxName).orEmpty()
                val nextCell = cells.getOrNull(idxName + 1).orEmpty()
                colShift = if (Regex("""^\d{1,2}\.?$""").matches(atCell) && Regex("""[A-Za-zÇĞİÖŞÜçğıöşü]{2,}""").containsMatchIn(nextCell)) {
                    1
                } else {
                    0
                }
            }

            val shift = colShift ?: 0
            fun cellAt(baseIdx: Int): String {
                if (baseIdx < 0) return ""
                return cells.getOrNull(baseIdx + shift).orEmpty()
            }

            val rawNameCell = cellAt(idxName)
            if (rawNameCell.isBlank() || Regex("""toplam|koşmaz|at\s*[iİı]sm""", RegexOption.IGNORE_CASE).containsMatchIn(rawNameCell)) {
                continue
            }

            val firstSeg = rawNameCell.split("·").firstOrNull()?.trim().orEmpty()
            val numAndName = Regex("""^(\d{1,2})\s*[.\-)]\s*(.+)$""").find(firstSeg)

            val explicitNum = if (idxNum >= 0) cellAt(idxNum)
            else if (shift == 1) cells.getOrNull(idxName).orEmpty()
            else ""

            val number = Regex("""^(\d{1,2})\.?$""").find(explicitNum)?.groupValues?.get(1)
                ?: numAndName?.groupValues?.get(1)
                ?: (out.size + 1).toString()

            val cleanName = (numAndName?.groupValues?.get(2) ?: firstSeg)
                .replace(Regex("""\b(KG|DB|SK|GKR|YP|ÖG|K)\b"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (cleanName.length < 2 || Regex("""^\d+$""").matches(cleanName)) continue

            val son6Digits = cellAt(idxSon6).filter { it.isDigit() }.map { ch -> if (ch == '0') 10 else ch.digitToInt() }
            val gate = Regex("""(\d{1,2})""").find(cellAt(idxGate))?.groupValues?.get(1)?.toIntOrNull()
            val weight = Regex("""(\d{2}(?:[.,]\d)?)""").find(cellAt(idxWeight))?.value?.replace(",", ".")?.toDoubleOrNull()
            val hp = cellAt(idxHp).trimEnd('.').replace(",", ".").toDoubleOrNull()
            val kgs = cellAt(idxKgs).trimEnd('.').toIntOrNull()
            val s20 = cellAt(idxS20).trimEnd('.').toIntOrNull()
            val bestTime = parseTime(cellAt(idxBestTime))
            val gny = cellAt(idxGny).replace(",", ".").toDoubleOrNull()
            val agf = agfRegex.find(cellAt(idxAgf))?.let {
                (it.groupValues[1].ifBlank { it.groupValues[2] }).replace(",", ".").toDoubleOrNull()
            }

            out.add(
                ParsedHorseRow(
                    number = number,
                    gate = gate,
                    horseName = cleanName.take(40),
                    weightKg = weight,
                    rating = hp,
                    recentDigits = son6Digits,
                    daysSinceLast = kgs,
                    bestTimeSec = bestTime,
                    s20Score = s20,
                    marketOdds = gny,
                    marketAgfPct = agf,
                    owner = cellAt(idxOwner).replace("·", " ").trim(),
                    trainer = cellAt(idxTrainer).replace("·", " ").trim(),
                    jockey = cellAt(idxJockey).replace("·", " ").trim()
                )
            )
        }
        return out
    }

    private fun parseTime(raw: String): Double? {
        val m = timeDotRegex.find(raw) ?: return null
        val mins = m.groupValues[1].toIntOrNull() ?: return null
        val secs = m.groupValues[2].toIntOrNull() ?: return null
        val h = m.groupValues[3].toIntOrNull() ?: 0
        return mins * 60.0 + secs + (h / 100.0)
    }
}
