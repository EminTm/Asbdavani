package com.asbai.predictor.network

import com.asbai.predictor.model.DiscoveredLink
import com.asbai.predictor.model.ScrapedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.util.Locale

class AsbdavaniScraper {

    private val client = HttpClientProvider.sharedClient

    suspend fun fetchPage(rawUrl: String): Result<ScrapedPage> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = normalizeUrl(rawUrl)
            val request = Request.Builder()
                .url(normalizedUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fa-IR,fa;q=0.9,tr-TR;q=0.8,en-US;q=0.7")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("خطا در دریافت سایت (کد ${response.code}). از تب «مرورگر سایت» بازش کنید.")
                }
                val html = response.body?.string().orEmpty()
                if (html.isBlank()) {
                    error("پاسخ سایت خالی بود.")
                }

                val doc = Jsoup.parse(html, normalizedUrl)
                val title = doc.title().ifBlank { "مسابقات اسبدوانی" }

                val links = doc.select("a[href]")
                    .mapNotNull { el ->
                        val href = el.absUrl("href").trim()
                        val text = el.text().trim()
                        if (href.startsWith("http") &&
                            text.isNotBlank() &&
                            isInterestingRaceLink(href, text)
                        ) {
                            DiscoveredLink(title = text.take(60), url = href)
                        } else {
                            null
                        }
                    }
                    .distinctBy { it.url }
                    .take(25)

                val nextData = extractNextDataText(html)
                doc.select("script, style, noscript, svg, nav, footer").remove()

                val builder = StringBuilder()
                builder.appendLine("عنوان صفحه: $title")
                builder.appendLine("آدرس: $normalizedUrl")
                builder.appendLine()

                val tables = doc.select("table")
                tables.forEachIndexed { index, table ->
                    builder.appendLine("--- جدول ${index + 1} ---")
                    val rows = table.select("tr")
                    for (row in rows) {
                        val rawCells = row.select("th, td").map { cell ->
                            val parts = cell.select("a, span, div, p, strong, sup")
                                .map { it.ownText().trim() }
                                .filter { it.isNotEmpty() }
                            val textVal = if (parts.size > 1) parts.joinToString(" · ") else cell.text().trim()
                            textVal.ifEmpty { " " }
                        }
                        if (rawCells.any { it.isNotBlank() }) {
                            builder.appendLine(rawCells.joinToString(" | "))
                        }
                    }
                    builder.appendLine()
                }

                val headingsAndBlocks = doc.select("h1, h2, h3, h4, p, li")
                    .map { it.text().trim() }
                    .filter { it.length > 2 }
                    .distinct()

                if (headingsAndBlocks.isNotEmpty()) {
                    builder.appendLine("--- متن و جزئیات صفحه ---")
                    for (line in headingsAndBlocks.take(140)) {
                        builder.appendLine(line)
                    }
                }

                if (nextData.isNotBlank()) {
                    builder.appendLine()
                    builder.appendLine("--- داده‌های ساختاری صفحه ---")
                    builder.appendLine(nextData.take(18000))
                }

                val bodyFallback = doc.body()?.text()?.trim().orEmpty()
                if (builder.length < 250 && bodyFallback.isNotBlank()) {
                    builder.appendLine(bodyFallback)
                }

                val finalText = builder.toString().trim().take(36000)
                val needsWebView = (tables.isEmpty() && nextData.isBlank()) ||
                    finalText.length < 400 ||
                    finalText.contains("Just a moment", ignoreCase = true)

                ScrapedPage(
                    url = normalizedUrl,
                    title = title,
                    extractedText = finalText,
                    links = links,
                    tableCount = tables.size,
                    likelyNeedsWebView = needsWebView
                )
            }
        }
    }

    private fun extractNextDataText(html: String): String {
        val m = Regex("""<script[^>]*id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>""", RegexOption.IGNORE_CASE)
            .find(html)
        if (m != null) {
            val rawJson = m.groupValues[1]
            val pageProps = runCatching {
                JSONObject(rawJson).optJSONObject("props")?.optJSONObject("pageProps")?.toString()
            }.getOrNull()
            if (!pageProps.isNullOrBlank()) return pageProps.take(20000)
        }

        val chunks = mutableListOf<String>()
        val marker = """self.__next_f.push([1,""""
        var pos = 0
        while (true) {
            val start = html.indexOf(marker, pos)
            if (start == -1) break
            var i = start + marker.length
            var escaped = false
            while (i < html.length) {
                val ch = html[i]
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') break
                i++
            }
            val rawLiteral = html.substring(start + marker.length, i)
            pos = i + 1
            val decoded = runCatching { JSONTokener("\"$rawLiteral\"").nextValue() as? String }.getOrNull()
            if (!decoded.isNullOrBlank() &&
                Regex("""دور|مالک|مربی|چابکسوار|اسبدوانی|race|horse|jockey|trainer""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(decoded)
            ) {
                chunks.add(decoded)
            }
            if (chunks.sumOf { it.length } > 18000) break
        }
        return chunks.joinToString("\n")
    }

    private fun isInterestingRaceLink(href: String, text: String): Boolean {
        val h = href.lowercase(Locale.US)
        val keywordsUrl = listOf("racecard", "race", "event", "performance", "sehir", "gunlukyaris", "betting/racing")
        val keywordsText = listOf("دور", "کورس", "جدول", "هفته", "مسابق", "اسبدوانی", "گنبد", "آق قلا", "آق‌قلا", "ترکمن", "یزد", "مشهد", "koşu", "istanbul", "adana", "izmir")
        return keywordsUrl.any { h.contains(it) } || keywordsText.any { text.lowercase(Locale.US).contains(it) }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().ifEmpty { "https://asbdavani.app/racecards" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
