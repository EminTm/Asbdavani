package com.asbai.predictor.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.asbai.predictor.model.HorseAnalysis
import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.TopPick
import java.util.Locale

@Composable
fun PredictionResultView(
    report: PredictionReport,
    onExportPdf: () -> Unit,
    onRecordWinner: (number: String, horseName: String) -> Unit,
    onToggleScratch: (number: String, horseName: String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(onClick = onExportPdf, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("تبدیل گزارش به PDF و ارسال", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Share, contentDescription = null)
        }

        // خلاصه کورس + ستاره‌های اطمینان + Advantage Pick + منبع خوانش
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(report.confidenceStars.coerceIn(1, 4)) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.width(18.dp).height(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("تحلیل کمی کورس", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                        Text(
                            text = "${report.confidenceLevel} (${report.confidencePercent}٪)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(report.raceSummary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (report.trackAndClassNote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(report.trackAndClassNote, style = MaterialTheme.typography.bodySmall)
                }
                if (report.advantageNote.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(report.advantageNote, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF0A4D2E))
                }
                if (report.comboSuggestion.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(report.comboSuggestion, style = MaterialTheme.typography.bodySmall, color = Color(0xFF1A3B5D))
                }
                if (report.extractionSource.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(report.extractionSource, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // اسب‌های خط‌خورده (Scratch) با امکان بازگردانی
        if (report.scratchedKeys.isNotEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("اسب‌های خط‌خورده (برای بازگردانی لمس کنید):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.scratchedKeys.forEach { key ->
                            val num = key.substringBefore(":")
                            val name = key.substringAfter(":", "")
                            AssistChip(
                                onClick = { onToggleScratch(num, name) },
                                label = { Text("بازگردانی #$num $name") }
                            )
                        }
                    }
                }
            }
        }

        // ثبت برنده واقعی برای آموزش خودکار مدل
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE8F5E9))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (report.actualWinnerName.isNotBlank()) {
                    Text("🏆 برنده ثبت‌شده: ${report.actualWinnerName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                } else {
                    Text("🎓 آموزش مدل (بعد از مسابقه برنده واقعی را لمس کنید):", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.horseAnalyses.forEach { h ->
                            AssistChip(onClick = { onRecordWinner(h.number, h.horseName) }, label = { Text("#${h.number} ${h.horseName}") })
                        }
                    }
                }
            }
        }

        if (report.topThree.isNotEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFC79518))
                        Spacer(Modifier.width(8.dp))
                        Text("رتبه ۱ تا ۳ احتمالی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    report.topThree.forEachIndexed { idx, pick ->
                        TopPickItem(pick)
                        if (idx < report.topThree.lastIndex) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }

        if (report.preScores.isNotEmpty()) {
            val valuePicks = report.preScores.filter { (it.edgePercent ?: 0) >= 12 }
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF1F8E9))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = Color(0xFF2E7D32))
                        Spacer(Modifier.width(8.dp))
                        Text("ضرایب منصفانه و حداقل ضریب باارزش", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    }
                    if (valuePicks.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(color = Color(0xFFDCEFD5), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = "🔥 ارزش پنهان (Edge مثبت): " + valuePicks.joinToString("، ") { "#${it.number} ${it.horseName} (+${it.edgePercent}٪)" },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF145A1A),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    report.preScores.take(6).forEachIndexed { idx, h ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${idx + 1}. #${h.number} ${h.horseName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            val oddsTxt = if (h.fairOdds > 0) " | ضریب ≥ ${String.format(Locale.US, "%.1f", h.minValueOdds)}" else ""
                            Text("${h.mathWinProb}٪$oddsTxt", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        if (h.summary.isNotBlank()) Text(h.summary, style = MaterialTheme.typography.labelSmall, color = Color(0xFF424242))
                        if (idx < minOf(report.preScores.size, 6) - 1) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        if (report.anomalyAlerts.isNotEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFFFF4E5))) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFB35C00))
                        Spacer(Modifier.width(8.dp))
                        Text("رادار ناهنجاری، هم‌اصطبلی و تاکتیک", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF5C2F00))
                    }
                    Spacer(Modifier.height(8.dp))
                    report.anomalyAlerts.forEachIndexed { idx, alert ->
                        Text("• [${alert.severity}] ${alert.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF5C2F00))
                        Text(alert.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF422800))
                        if (idx < report.anomalyAlerts.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        if (report.tacticalScenario.isNotBlank()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("سناریوی تاکتیکی کورس", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(report.tacticalScenario, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        report.darkHorse?.let { dark ->
            if (dark.horseName.isNotBlank()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Text("گزینه شگفتی‌ساز: #${dark.number} ${dark.horseName} (${dark.winProbability}٪)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(dark.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (report.horseAnalyses.isNotEmpty()) {
            Text("آنالیز کامل اسب‌ها (۳ ستون تخصصی SPAIA)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            report.horseAnalyses.sortedByDescending { it.winProbability }.forEach { horse ->
                HorseAnalysisCard(horse = horse, onScratch = { onToggleScratch(horse.number, horse.horseName) })
            }
        }

        if (report.disclaimer.isNotBlank()) {
            Text(report.disclaimer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun TopPickItem(pick: TopPick) {
    val badgeColor = when (pick.rank) {
        1 -> Color(0xFFD4AF37)
        2 -> Color(0xFF8C969E)
        else -> Color(0xFFAD6F3B)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = badgeColor, shape = RoundedCornerShape(8.dp)) {
                    Text("رتبه ${pick.rank}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("#${pick.number}  ${pick.horseName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text("${pick.winProbability}٪ شانس برد", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(pick.reason, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HorseAnalysisCard(horse: HorseAnalysis, onScratch: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val markPrefix = if (horse.mark.isNotBlank()) "${horse.mark} " else ""
                Text(
                    text = "$markPrefix#${horse.number}  ${horse.horseName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onScratch) {
                    Icon(Icons.Default.Close, contentDescription = "خط‌خورد", modifier = Modifier.width(14.dp).height(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("خط‌خورد", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (horse.marketBadge.isNotBlank() || horse.speedBadge.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    if (horse.marketBadge.isNotBlank()) {
                        Surface(color = Color(0xFFFFF3CD), shape = RoundedCornerShape(6.dp)) {
                            Text(horse.marketBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF7A5901), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (horse.speedBadge.isNotBlank()) {
                        Surface(color = Color(0xFFE0F2F1), shape = RoundedCornerShape(6.dp)) {
                            Text(horse.speedBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF004D40), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("برد: ${horse.winProbability}٪ (سکو: ${horse.placeProbability}٪)", style = MaterialTheme.typography.labelMedium)
                if (horse.fairOdds > 0) {
                    Text("ضریب باارزش ≥ ${String.format(Locale.US, "%.1f", horse.minValueOdds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { (horse.winProbability.coerceIn(0, 100)) / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp))

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🐎 توانایی: ${horse.abilityScore}/10", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text("⏱ سرعت/آمادگی: ${horse.speedFitnessScore}/10", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text("📏 تناسب: ${horse.suitabilityScore}/10", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }

            if (horse.strengths.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                horse.strengths.forEach { point ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF1B8A5A), modifier = Modifier.padding(top = 2.dp).width(15.dp).height(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(point, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (horse.risks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                horse.risks.forEach { risk ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC26A00), modifier = Modifier.padding(top = 2.dp).width(15.dp).height(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(risk, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (horse.verdict.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("جمع‌بندی: ${horse.verdict}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
