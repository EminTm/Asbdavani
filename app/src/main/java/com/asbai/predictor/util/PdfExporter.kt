package com.asbai.predictor.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.asbai.predictor.model.PredictionReport
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val CONTENT_WIDTH = (PAGE_WIDTH - MARGIN * 2).toInt()

    fun exportAndShare(context: Context, report: PredictionReport): Result<Unit> = runCatching {
        val pdfFile = createPdfFile(context, report)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "گزارش پیش‌بینی اسبدوانی")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "ارسال یا ذخیره فایل PDF گزارش")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun cleanPdfText(raw: String): String {
        return raw
            .replace("📈", "[رشد]")
            .replace("📉", "[افت]")
            .replace("🔥", "*")
            .replace("🏆", "[برنده]")
            .replace("✅", "✓")
            .replace("🎯", "*")
            .replace("⚡", "*")
            .replace("🥇", "(طلا)")
            .replace("🥈", "(نقره)")
            .replace("◎", "[1]")
            .replace("○", "[2]")
            .replace("▲", "[3]")
            .replace("△", "[4]")
            .replace("☆", "[*]")
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
            .replace(Regex("[\\uFE00-\\uFE0F]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun createPdfFile(context: Context, report: PredictionReport): File {
        val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "AsbDavani_Prediction_$stamp.pdf")

        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        val headerBgPaint = Paint().apply { color = Color.parseColor("#0E5A3E") }
        val sectionBgPaint = Paint().apply { color = Color.parseColor("#EEF5F1") }
        val warnBgPaint = Paint().apply { color = Color.parseColor("#FFF4E5") }
        val dividerPaint = Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1f }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D4AF37"); textSize = 11f }
        val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E5A3E"); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A"); textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B2B2B"); textSize = 10.5f }
        val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666"); textSize = 9.5f }

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun ensureSpace(neededHeight: Float) {
            if (y + neededHeight > PAGE_HEIGHT - MARGIN) newPage()
        }

        fun buildLayout(text: String, paint: TextPaint, width: Int = CONTENT_WIDTH): StaticLayout {
            val cleaned = cleanPdfText(text)
            return StaticLayout.Builder.obtain(cleaned, 0, cleaned.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setLineSpacing(2f, 1.15f)
                .build()
        }

        fun drawTextBlock(text: String, paint: TextPaint, extraBottom: Float = 6f) {
            val cleaned = cleanPdfText(text)
            if (cleaned.isBlank()) return
            val layout = buildLayout(cleaned, paint)
            ensureSpace(layout.height + extraBottom)
            canvas.save()
            canvas.translate(MARGIN, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + extraBottom
        }

        val headerHeight = 64f
        canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + headerHeight, 8f, 8f, headerBgPaint)
        val headerTitle = buildLayout("گزارش پیش‌بینی هوشمند اسبدوانی", titlePaint, CONTENT_WIDTH - 24)
        val dateStr = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date())
        val starsStr = "*".repeat(report.confidenceStars.coerceIn(1, 4))
        val headerSub = buildLayout(
            "سطح اطمینان: ${report.confidenceLevel} (${report.confidencePercent}٪) [$starsStr]  |  $dateStr",
            subtitlePaint,
            CONTENT_WIDTH - 24
        )
        canvas.save()
        canvas.translate(MARGIN + 12f, y + 12f)
        headerTitle.draw(canvas)
        canvas.translate(0f, headerTitle.height + 4f)
        headerSub.draw(canvas)
        canvas.restore()
        y += headerHeight + 14f

        drawTextBlock("مشخصات کورس", headingPaint, 4f)
        drawTextBlock(report.raceSummary, boldPaint, 4f)
        if (report.trackAndClassNote.isNotBlank()) drawTextBlock(report.trackAndClassNote, bodyPaint, 4f)
        if (report.advantageNote.isNotBlank()) drawTextBlock(report.advantageNote, boldPaint, 4f)
        if (report.comboSuggestion.isNotBlank()) drawTextBlock(report.comboSuggestion, bodyPaint, 10f)

        if (report.topThree.isNotEmpty()) {
            drawTextBlock("احتمالی‌های سه‌تای اول (سکو)", headingPaint, 6f)
            report.topThree.forEach { pick ->
                drawTextBlock("رتبه ${pick.rank}: شماره ${pick.number} - ${pick.horseName} (${pick.winProbability}٪ شانس برد)", boldPaint, 2f)
                drawTextBlock(pick.reason, bodyPaint, 8f)
            }
            ensureSpace(8f); canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint); y += 10f
        }

        if (report.tacticalScenario.isNotBlank()) {
            drawTextBlock("سناریوی تاکتیکی کورس", headingPaint, 4f)
            drawTextBlock(report.tacticalScenario, bodyPaint, 10f)
        }

        report.darkHorse?.let { dark ->
            if (dark.horseName.isNotBlank()) {
                drawTextBlock("اسب شگفتی‌ساز: شماره ${dark.number} ${dark.horseName} (${dark.winProbability}٪)", headingPaint, 3f)
                drawTextBlock(dark.reason, bodyPaint, 10f)
            }
        }

        if (report.anomalyAlerts.isNotEmpty()) {
            drawTextBlock("رادار ناهنجاری‌ها، پول هوشمند و هم‌اصطبلی", headingPaint, 6f)
            report.anomalyAlerts.forEach { alert ->
                val alertLayout = buildLayout("[${alert.severity}] ${alert.title}: ${alert.description}", bodyPaint, CONTENT_WIDTH - 16)
                ensureSpace(alertLayout.height + 16f)
                canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + alertLayout.height + 12f, 6f, 6f, warnBgPaint)
                canvas.save(); canvas.translate(MARGIN + 8f, y + 6f); alertLayout.draw(canvas); canvas.restore()
                y += alertLayout.height + 18f
            }
        }

        if (report.horseAnalyses.isNotEmpty()) {
            drawTextBlock("آنالیز کامل همه اسب‌های حاضر", headingPaint, 6f)
            report.horseAnalyses.sortedByDescending { it.winProbability }.forEach { h ->
                val badges = listOf(h.marketBadge, h.speedBadge).filter { it.isNotBlank() }.joinToString(" | ")
                val badgeTxt = if (badges.isNotBlank()) " [$badges]" else ""
                drawTextBlock("شماره ${h.number} - ${h.horseName}$badgeTxt | برد: ${h.winProbability}٪ | سکو: ${h.placeProbability}٪", boldPaint, 2f)
                drawTextBlock("توانایی: ${h.abilityScore}/10 | سرعت: ${h.speedFitnessScore}/10 | تناسب: ${h.suitabilityScore}/10", bodyPaint, 2f)
                if (h.strengths.isNotEmpty()) drawTextBlock("نقاط قوت: ${h.strengths.joinToString("، ")}", bodyPaint, 2f)
                if (h.risks.isNotEmpty()) drawTextBlock("ریسک‌ها: ${h.risks.joinToString("، ")}", bodyPaint, 2f)
                if (h.verdict.isNotBlank()) drawTextBlock("جمع‌بندی: ${h.verdict}", mutedPaint, 6f)
                ensureSpace(6f); canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint); y += 8f
            }
        }

        if (report.disclaimer.isNotBlank()) {
            y += 4f
            drawTextBlock(report.disclaimer, mutedPaint, 4f)
        }

        document.finishPage(page)
        FileOutputStream(outFile).use { fos -> document.writeTo(fos) }
        document.close()
        return outFile
    }
}