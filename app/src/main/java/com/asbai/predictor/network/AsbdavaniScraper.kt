package com.asbai.predictor.network

import com.asbai.predictor.model.DiscoveredLink
import com.asbai.predictor.model.ScrapedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class AsbdavaniScraper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
                .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("خطا در دریافت سایت (کد ${response.code})")
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
                        val cells = row.select("th, td")
                            .map { it.text().trim() }
                            .filter { it.isNotEmpty() }
                        if (cells.isNotEmpty()) {
                            builder.appendLine(cells.joinToString(" | "))
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
                    for (line in headingsAndBlocks) {
                        builder.appendLine(line)
                    }
                }

                val bodyFallback = doc.body()?.text()?.trim().orEmpty()
                if (builder.length < 250 && bodyFallback.isNotBlank()) {
                    builder.appendLine(bodyFallback)
                }

                val finalText = builder.toString().trim().take(24000)
                val needsWebView = tables.isEmpty() && finalText.length < 400

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

    private fun isInterestingRaceLink(href: String, text: String): Boolean {
        val h = href.lowercase()
        val keywordsUrl = listOf("racecard", "race", "event", "performance", "horse", "register", "live")
        val keywordsText = listOf("دور", "کورس", "جدول", "هفته", "مسابق", "اسبدوانی", "ثبت نام", "عملکرد", "گنبد", "آق قلا", "آق‌قلا", "ترکمن", "یزد", "مشهد", "نوروزآباد")
        return keywordsUrl.any { h.contains(it) } || keywordsText.any { text.contains(it) }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().ifEmpty { "https://asbdavani.app/racecards" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
