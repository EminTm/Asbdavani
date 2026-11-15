package com.asbai.predictor.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.ui.components.PredictionResultView
import com.asbai.predictor.util.PdfExporter
import com.asbai.predictor.viewmodel.AppTab
import com.asbai.predictor.viewmodel.MainUiState
import com.asbai.predictor.viewmodel.MainViewModel
import org.json.JSONTokener

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "پیش‌بینی هوشمند اسبدوانی",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        AppTab.PREDICT -> Icons.Default.AutoAwesome
                        AppTab.BROWSER -> Icons.Default.Language
                        AppTab.HISTORY -> Icons.Default.History
                        AppTab.SETTINGS -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            StatusBanners(
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                onDismiss = { viewModel.clearMessages() }
            )

            when (state.selectedTab) {
                AppTab.PREDICT -> PredictTab(state = state, viewModel = viewModel)
                AppTab.BROWSER -> LiveBrowserTab(
                    initialUrl = state.urlInput,
                    onExtractAndPredict = { title, url, text ->
                        viewModel.onWebViewExtracted(title, url, text)
                    }
                )
                AppTab.HISTORY -> HistoryTab(state = state, viewModel = viewModel)
                AppTab.SETTINGS -> SettingsTab(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun StatusBanners(
    statusMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    if (errorMessage != null) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "بستن")
                }
            }
        }
    } else if (statusMessage != null) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusMessage,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "بستن")
                }
            }
        }
    }
}

@Composable
private fun PredictTab(state: MainUiState, viewModel: MainViewModel) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showRawData by remember { mutableStateOf(true) }
    var showNewsNotes by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.apiKey.isBlank()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "کلید هوش مصنوعی جمنای هنوز ثبت نشده است.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.selectTab(AppTab.SETTINGS) }) {
                        Text("ثبت کلید")
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "دریافت اطلاعات از سایت asbdavani.app",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = { viewModel.updateUrlInput(it) },
                    label = { Text("آدرس صفحه مسابقات") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.fetchFromSite() },
                        enabled = !state.isFetchingSite && !state.isAnalyzing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isFetchingSite) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("دریافت مستقیم")
                    }
                    OutlinedButton(
                        onClick = { viewModel.selectTab(AppTab.BROWSER) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("باز کردن در مرورگر")
                    }
                }

                if (state.needsWebViewHint) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "نکته: اگر جدول اسب‌ها با جاوااسکریپت لود می‌شود، از دکمه «باز کردن در مرورگر» دور موردنظر را باز کنید و دکمه «تحلیل همین صفحه» را بزنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (state.discoveredLinks.isNotEmpty()) {
            Column {
                Text(
                    text = "لینک‌های پیداشده در صفحه (برای دریافت لمس کنید):",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.discoveredLinks.forEach { link ->
                        AssistChip(
                            onClick = { viewModel.fetchFromSite(link.url) },
                            label = { Text(link.title) },
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRawData = !showRawData },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "اطلاعات خام / جدول مسابقه (${state.raceDataInput.length} کاراکتر)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (showRawData) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                if (showRawData) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val pasted = clipboardManager.getText()?.text.orEmpty()
                                if (pasted.isNotBlank()) {
                                    viewModel.updateRaceDataInput(pasted)
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("چسباندن متن")
                        }
                        if (state.raceDataInput.isNotEmpty()) {
                            TextButton(onClick = { viewModel.updateRaceDataInput("") }) {
                                Text("پاک کردن")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.raceDataInput,
                        onValueChange = { viewModel.updateRaceDataInput(it) },
                        label = { Text("جدول اسب‌ها، ریتینگ، وزن، عملکرد، مسافت...") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // اخبار، جستجوی گوگل و نکات پیست
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "جستجوی زنده اخبار در گوگل",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "بررسی آخرین اخبار، حواشی و پیست قبل از تحلیل",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.useWebSearch,
                        onCheckedChange = { viewModel.toggleWebSearch(it) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showNewsNotes = !showNewsNotes },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "افزودن خبر یا نکته پیست (اختیاری)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (showNewsNotes) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                if (showNewsNotes || state.newsNotesInput.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.newsNotesInput,
                        onValueChange = { viewModel.updateNewsNotes(it) },
                        label = { Text("مثلاً: پیست سنگین است، تغییر چابکسوار لحظه آخری، انصراف اسب ۳...") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Button(
            onClick = { viewModel.analyzeCurrentRace() },
            enabled = !state.isAnalyzing && state.raceDataInput.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (state.isAnalyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text("در حال تحلیل و بررسی اخبار...")
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("شروع پیش‌بینی، اخبار و رادار کورس", fontWeight = FontWeight.Bold)
            }
        }

        state.currentReport?.let { report ->
            PredictionResultView(
                report = report,
                onExportPdf = {
                    PdfExporter.exportAndShare(context, report).fold(
                        onSuccess = {
                            viewModel.showStatus("فایل PDF ساخته شد و آماده ارسال است.")
                        },
                        onFailure = { err ->
                            viewModel.showError("خطا در ساخت PDF: ${err.message}")
                        }
                    )
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveBrowserTab(
    initialUrl: String,
    onExtractAndPredict: (title: String, url: String, extractedText: String) -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember {
        mutableStateOf(
            initialUrl.ifBlank { "https://asbdavani.app/racecards" }
        )
    }
    var currentTitle by remember { mutableStateOf("سایت اسبدوانی") }
    var progress by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "عقب")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "رفرش")
                }
                TextButton(onClick = { webView?.loadUrl("https://asbdavani.app/racecards") }) {
                    Text("جدول مسابقات")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = currentUrl.removePrefix("https://").take(26),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                if (!view?.title.isNullOrBlank()) currentTitle = view?.title.orEmpty()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(currentUrl)
                        webView = this
                    }
                }
            )
        }

        ExtendedFloatingActionButton(
            onClick = {
                val js = """
                    (function() {
                        var parts = [];
                        parts.push("عنوان: " + document.title);
                        parts.push("آدرس: " + window.location.href);
                        var tables = document.querySelectorAll("table");
                        tables.forEach(function(table, idx) {
                            parts.push("--- جدول " + (idx + 1) + " ---");
                            var rows = table.querySelectorAll("tr");
                            rows.forEach(function(row) {
                                var cells = [];
                                row.querySelectorAll("th, td").forEach(function(cell) {
                                    var t = (cell.innerText || "").replace(/\s+/g, " ").trim();
                                    if (t) cells.push(t);
                                });
                                if (cells.length > 0) parts.push(cells.join(" | "));
                            });
                        });
                        var bodyText = (document.body.innerText || "").replace(/\n{3,}/g, "\n\n").trim();
                        parts.push("--- متن کامل صفحه ---");
                        parts.push(bodyText);
                        return parts.join("\n").slice(0, 24000);
                    })();
                """.trimIndent()

                webView?.evaluateJavascript(js) { rawResult ->
                    val decoded = runCatching {
                        JSONTokener(rawResult).nextValue() as? String
                    }.getOrNull().orEmpty()
                    if (decoded.isNotBlank()) {
                        onExtractAndPredict(currentTitle, currentUrl, decoded)
                    }
                }
            },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            text = { Text("استخراج و پیش‌بینی همین صفحه") },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun HistoryTab(state: MainUiState, viewModel: MainViewModel) {
    if (state.history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "هنوز هیچ پیش‌بینی‌ای ذخیره نشده است.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.history, key = { it.id }) { item ->
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openHistoryItem(item) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.report.raceSummary.ifBlank { "گزارش مسابقه" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        val topPick = item.report.topThree.firstOrNull()
                        if (topPick != null) {
                            Text(
                                text = "انتخاب اول: #${topPick.number} ${topPick.horseName} (${topPick.winProbability}٪)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.deleteHistoryItem(item.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(state: MainUiState, viewModel: MainViewModel) {
    var apiKeyInput by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var modelInput by remember(state.selectedModel) { mutableStateOf(state.selectedModel) }
    var selectedRisk by remember(state.riskProfile) { mutableStateOf(state.riskProfile) }
    var showKey by remember { mutableStateOf(false) }

    val presetModels = listOf(
        "gemini-3.5-flash-lite" to "سریع و اقتصادی (پیشنهادی)",
        "gemini-3.8-flash" to "تحلیل عمیق‌تر و دقیق‌تر"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "کلید هوش مصنوعی Gemini",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "می‌توانید از سایت aistudio.google.com یک کلید رایگان بگیرید و اینجا وارد کنید. کلید فقط روی گوشی شما ذخیره می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Gemini API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "نمایش کلید"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "انتخاب مدل هوش مصنوعی:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetModels.forEach { (id, desc) ->
                        FilterChip(
                            selected = modelInput == id,
                            onClick = { modelInput = id },
                            label = { Text("$id ($desc)") }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    label = { Text("نام مدل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "استراتژی پیش‌بینی",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                RiskProfile.entries.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedRisk = profile }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRisk == profile,
                            onClick = { selectedRisk = profile }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(text = profile.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.saveSettings(apiKeyInput, modelInput, selectedRisk) },
                modifier = Modifier.weight(1f)
            ) {
                Text("ذخیره تنظیمات")
            }
            OutlinedButton(
                onClick = { viewModel.testApiKey(apiKeyInput, modelInput) },
                enabled = !state.isTestingKey,
                modifier = Modifier.weight(1f)
            ) {
                if (state.isTestingKey) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text("تست اتصال")
            }
        }
    }
}
