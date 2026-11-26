package com.asbai.predictor.engine

import com.asbai.predictor.model.ParsedHorseRow

object AsbDavaniReader {

    private val formRegex = Regex("""(\d{1,3})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\s*/\s*(\d{1,3})""")
    private val agfRegex = Regex("""%\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*%""")
    private val asbTimeRegex = Regex("""\b(\d):(\d{2})\.(\d{2,3})\b""")
    private val asbKgIorRegex = Regex("""(\d{2}(?:\.\d)?)\s*Kg\s*(\d{1,3})?""", RegexOption.IGNORE_CASE)
    private val daysAgoRegex = Regex("""(\d{1,4})\s*روز\s*قبل""")
    private val shirtNumRegex = Regex("""پیراهن\s*شماره\s*(\d{1,2})""")
    private val weightKgRegex = Regex("""\b(4[5-9]|5[0-9]|6[0-5])(?:\.(\d))?\b""")

    fun splitSections(rawText: String): List<RaceSection> {
        val lines = PageReaderEngine.toAsciiDigits(rawText).lines()
        val headerIndices = lines.indices.filter { lines[it].contains("مقام (دپار)") }
        if (headerIndices.size <= 1) return emptyList()

        return headerIndices.mapIndexed { k, hIdx ->
            val start = maxOf(0, hIdx - 8)
            val end = headerIndices.getOrNull(k + 1)?.let { maxOf(start + 1, it - 8) } ?: lines.size
            val chunk = lines.subList(start, end).joinToString("\n")
            val dor = Regex("""دور\s*(\d+)""").find(chunk)?.groupValues?.get(1)
            RaceSection(label = dor?.let { "دور $it" } ?: "دور ${k + 1}", rawChunk = chunk)
        }
    }

    fun read(cleanText: String, jsonBlocks: List<String>, pageTitle: String, sourceUrl: String): ReadPageResult {
        val lines = PageReaderEngine.toAsciiDigits(cleanText).lines().map { it.trim() }.filter { it.isNotEmpty() }

        var distMeters: Int? = null
        var surface = ""
        for (i in lines.indices) {
            val pairLine = if (i + 1 < lines.size) "${lines[i]} ${lines[i + 1]}" else lines[i]
            if (distMeters == null) {
                val d = Regex("""(\d{3,4})\s*متر""").find(pairLine)?.groupValues?.get(1)?.toIntOrNull()
                if (d != null && d in 800..4000) distMeters = d
            }
            if (surface.isBlank()) {
                surface = when {
                    "چمن" in lines[i] -> "چمن"
                    "خاکی" in lines[i] -> "خاکی"
                    else -> ""
                }
            }
            if (distMeters != null && surface.isNotBlank() && i > 50) break
        }

        val candidates = listOf(
            parseVerticalBlocks(lines),
            parsePipeTables(lines),
            parseLegacyShirtCards(lines)
        )
        val horses = (candidates.maxByOrNull { it.size }.orEmpty())
            .distinctBy { "${it.number}:${it.horseName}" }

        val title = extractTitle(lines) ?: pageTitle.ifBlank { "مسابقات اسبدوانی ایران" }
        val ctx = ParsedRaceContext(title, distMeters, surface, horses)
        val label = if (horses.size >= 2) "موتور اختصاصی اسبدوانی (${horses.size} اسب ✅)" else "داده کافی از اسبدوانی یافت نشد"
        return ReadPageResult(ctx, label, cleanText)
    }

    private fun parseVerticalBlocks(lines: List<String>): List<ParsedHorseRow> {
        val slashAnchors = mutableListOf<Int>()
        for (k in 2 until lines.size - 2) {
            if (lines[k] == "/") {
                val gender = lines[k + 2]
                if ((gender == "ن" || gender == "م") &&
                    Regex("""[\u0600-\u06FFa-zA-Z]""").containsMatchIn(lines[k - 1]) &&
                    Regex("""[\u0600-\u06FFa-zA-Z]""").containsMatchIn(lines[k + 1])
                ) {
                    slashAnchors.add(k)
                }
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

            var weightKg: Double? = null
            var ior: Double? = null
            val kgIdx = block.indexOfFirst { it.equals("Kg", ignoreCase = true) }
            if (kgIdx > 0) {
                weightKg = block[kgIdx - 1].toDoubleOrNull()?.takeIf { it in 40.0..75.0 }
                if (kgIdx + 1 < block.size) ior = block[kgIdx + 1].toDoubleOrNull()?.takeIf { it in 0.0..150.0 }
            }

            var daysAgo: Int? = null
            var recentDigits: List<Int> = emptyList()
            val daysIdx = block.indexOfFirst { it == "روز قبل" || it.contains("روز قبل") }
            if (daysIdx >= 0) {
                daysAgo = daysAgoRegex.find(block[daysIdx])?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (daysIdx > 0) block[daysIdx - 1].toIntOrNull() else null
                val nextAfterDays = block.getOrNull(daysIdx + 1).orEmpty()
                if (Regex("""^\d+(?:-\d+)+$""").matches(nextAfterDays)) {
                    recentDigits = nextAfterDays.split("-").mapNotNull { it.toIntOrNull() }
                }
            }

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
            if (formString == null) formString = extractCleanForm(block.joinToString(" "))

            val peopleStart = if (formEndLocalIdx >= 0) formEndLocalIdx + 1 else (daysIdx + 1).coerceAtLeast(6)
            val tail = if (peopleStart < block.size) {
                block.subList(peopleStart, block.size).filter { ln ->
                    ln.length >= 2 && !asbTimeRegex.matches(ln) && !Regex("""^\d+$""").matches(ln)
                }
            } else emptyList()

            out.add(
                ParsedHorseRow(
                    number = (out.size + 1).toString(),
                    horseName = horseName.take(40),
                    weightKg = weightKg,
                    rating = ior,
                    formString = formString,
                    recentDigits = recentDigits,
                    daysSinceLast = daysAgo,
                    bestTimeSec = block.firstNotNullOfOrNull { parseTime(it) },
                    owner = tail.getOrElse(0) { "" },
                    trainer = tail.getOrElse(1) { "" },
                    jockey = tail.getOrElse(2) { "" }
                )
            )
        }
        return out
    }

    private fun parsePipeTables(lines: List<String>): List<ParsedHorseRow> {
        val out = mutableListOf<ParsedHorseRow>()
        var inTable = false
        var idxGate = -1; var idxHorse = -1; var idxStats = -1; var idxForm = -1; var idxPeople = -1

        for (line in lines) {
            if (!line.contains("|")) continue
            val cells = line.split("|").map { it.trim() }
            val isHeader = cells.any { it.contains("اسب") } &&
                cells.any { it.contains("عملکرد") || it.contains("مربی") || it.contains("دپار") || it.contains("مقام") }

            if (isHeader) {
                if (out.size >= 2) break
                inTable = true
                idxGate = cells.indexOfFirst { it.contains("مقام") || it.contains("دپار") || it == "#" }
                idxHorse = cells.indexOfFirst { it.contains("اسب") }
                idxStats = cells.indexOfFirst { it.contains("وزن") || it.contains("IOR") || it.contains("سن") }
                idxForm = cells.indexOfFirst { it.contains("عملکرد") }
                idxPeople = cells.indexOfFirst { it.contains("مالک") || it.contains("مربی") || it.contains("چابکسوار") }
                continue
            }

            if (!inTable || idxHorse < 0 || cells.size <= idxHorse) continue
            val rawCell = cells[idxHorse]
            if (rawCell.isBlank()) continue

            val num = shirtNumRegex.find(line)?.groupValues?.get(1)
                ?: Regex("""^(\d{1,2})\b""").find(cells.getOrNull(idxGate).orEmpty())?.groupValues?.get(1)
                ?: (out.size + 1).toString()
            val gate = Regex("""\(\s*(\d{1,2})\s*\)""").find(cells.getOrNull(idxGate).orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val name = cleanHorseName(rawCell)
            if (name.length < 2) continue

            val stats = cells.getOrNull(idxStats) ?: line
            val kgIor = asbKgIorRegex.find(stats)
            val weight = kgIor?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 40.0..75.0 } ?: weightKgRegex.find(stats)?.value?.toDoubleOrNull()
            val ior = kgIor?.groupValues?.get(2)?.toDoubleOrNull()
            val formCell = cells.getOrNull(idxForm) ?: line
            val people = cells.getOrNull(idxPeople).orEmpty().split("·").map { it.trim() }.filter { it.isNotEmpty() }

            out.add(
                ParsedHorseRow(
                    number = num,
                    gate = gate,
                    horseName = name,
                    weightKg = weight,
                    rating = ior,
                    formString = extractCleanForm(formCell) ?: extractCleanForm(line),
                    daysSinceLast = daysAgoRegex.find(formCell)?.groupValues?.get(1)?.toIntOrNull(),
                    bestTimeSec = parseTime(line),
                    marketAgfPct = agfRegex.find(formRegex.replace(line, " "))?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toDoubleOrNull() },
                    owner = people.getOrElse(0) { "" },
                    trainer = people.getOrElse(1) { "" },
                    jockey = people.getOrElse(2) { "" }
                )
            )
        }
        return out
    }

    private fun parseLegacyShirtCards(lines: List<String>): List<ParsedHorseRow> {
        val shirtIndices = lines.indices.filter { shirtNumRegex.containsMatchIn(lines[it]) }
        if (shirtIndices.size < 2) return emptyList()

        val out = mutableListOf<ParsedHorseRow>()
        for (pos in shirtIndices.indices) {
            val idx = shirtIndices[pos]
            val nextIdx = shirtIndices.getOrNull(pos + 1) ?: minOf(lines.size, idx + 12)
            val joined = lines.subList(idx, nextIdx).joinToString(" · ")
            val num = shirtNumRegex.find(lines[idx])?.groupValues?.get(1) ?: (out.size + 1).toString()
            var name = cleanHorseName(lines[idx])
            if (name.length < 2 && idx + 1 < nextIdx) name = cleanHorseName(lines[idx + 1])
            if (name.length < 2) continue
            val kgIor = asbKgIorRegex.find(joined)
            out.add(
                ParsedHorseRow(
                    number = num,
                    horseName = name,
                    weightKg = kgIor?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 40.0..75.0 },
                    rating = kgIor?.groupValues?.get(2)?.toDoubleOrNull(),
                    formString = extractCleanForm(joined),
                    daysSinceLast = daysAgoRegex.find(joined)?.groupValues?.get(1)?.toIntOrNull(),
                    bestTimeSec = parseTime(joined)
                )
            )
        }
        return out
    }

    private fun cleanHorseName(raw: String): String {
        val after = raw.replace(Regex("""پیراهن\s*شماره\s*\d+\s*·?\s*"""), "").trim()
        val segs = after.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        val cand = segs.firstOrNull { !it.startsWith("(") && !it.contains("/") && it.length >= 2 }
            ?: segs.firstOrNull().orEmpty().substringBefore("/")
        return cand.replace(Regex("""\([^)]*\)"""), "").replace(Regex("""\s+"""), " ").trim().take(40)
    }

    private fun extractCleanForm(text: String): String? {
        val m = formRegex.find(text) ?: return null
        var w = m.groupValues[1].toIntOrNull() ?: 0
        val p = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        val n = (m.groupValues[4].toIntOrNull() ?: 0).coerceAtLeast(1)
        if (w > n && w >= 10) w %= 10
        return "$w-$p-$s/$n"
    }

    private fun parseTime(text: String): Double? {
        val m = asbTimeRegex.find(text) ?: return null
        val mins = m.groupValues[1].toIntOrNull() ?: return null
        val secs = m.groupValues[2].toIntOrNull() ?: return null
        val ms = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return mins * 60.0 + secs + (if (m.groupValues[3].length == 3) ms / 1000.0 else ms / 100.0)
    }

    private fun extractTitle(lines: List<String>): String? {
        val idx = lines.indexOfFirst { it == "جدول مسابقات" }
        if (idx >= 0 && idx + 1 < lines.size && lines[idx + 1].contains("هفته")) {
            val dorIdx = lines.indexOfFirst { it == "دور" }
            val roundTxt = if (dorIdx >= 0 && dorIdx + 1 < lines.size) " — دور ${lines[dorIdx + 1]}" else ""
            return "${lines[idx + 1]}$roundTxt"
        }
        return lines.firstOrNull { it.contains("جدول مسابقات") && it.contains("هفته") }?.take(65)
    }
}
