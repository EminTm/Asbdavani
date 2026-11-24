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
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
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
    onRecordWinner: (number: String, horseName: String) -> Unit
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

        // کارت ثبت برنده واقعی برای آموزش خودکار مدل
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE8F5E9))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (report.actualWinnerName.isNotBlank()) {
                    Text(
                        text = "🏆 برنده ثبت‌شده برای این کورس: ${report.actualWinnerName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20)
                    )
                } else {
                    Text(
                        text = "🎓 آموزش مدل (بعد از مسابقه برنده واقعی را لمس کنید):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        report.horseAnalyses.forEach { h ->
                            AssistChip(
                                onClick = { onRecordWinner(h.number, h.horseName) },
                                label = { Text("#${h.number} ${h.horseName}") }
                            )
                        }
                    }
                }
            }
        }

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
                    Text(
                        text = "تحلیل کمی و آماری کورس",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                        Text(
                            text = "اطمینان: ${report.confidenceLevel} (${report.confidencePercent}٪)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(report.raceSummary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (report.trackAndClassNote.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(report.trackAndClassNote, style = MaterialTheme.typography.bodySmall)
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
                        Text("ضرایب منصفانه و ارزش پنهان (Value Edge)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    }
                    if (valuePicks.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(color = Color(0xFFDCEFD5), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = "🔥 ارزش پنهان نسبت به AGF/بازار: " +
                                    valuePicks.joinToString("، ") { "#${it.number} ${it.horseName} (+${it.edgePercent}٪)" },
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
                            val oddsTxt = if (h.fairOdds > 0) " | ضریب ${String.format(Locale.US, "%.1f", h.fairOdds)}" else ""
                            Text("${h.mathWinProb}٪$oddsTxt", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        if (h.summary.isNotBlank()) Text(h.summary, style = MaterialTheme.typography.labelSmall, color = Color(0xFF424242))
                        if (idx < minOf(report.preScores.size, 6) - 1) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        if (report.anomalyAlerts.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFFFF4E5))
            ) {
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
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Text("گزینه شگفتی‌ساز (ارزش بالا): #${dark.number} ${dark.horseName} (${dark.winProbability}٪)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(dark.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (report.horseAnalyses.isNotEmpty()) {
            Text("جزئیات فنی تک‌تک اسب‌ها", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            report.horseAnalyses.sortedByDescending { it.winProbability }.forEach { horse ->
                HorseAnalysisCard(horse)
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
private fun HorseAnalysisCard(horse: HorseAnalysis) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("#${horse.number}  ${horse.horseName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                    Text("آمادگی: ${horse.formScore}/10", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("شانس برد: ${horse.winProbability}٪", style = MaterialTheme.typography.labelMedium)
                Text("شانس سکو: ${horse.placeProbability}٪", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { (horse.winProbability.coerceIn(0, 100)) / 100f }, modifier = Modifier.fillMaxWidth().height(7.dp))
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
