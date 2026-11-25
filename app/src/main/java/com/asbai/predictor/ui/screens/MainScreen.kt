package com.asbai.predictor.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.ui.components.PredictionResultView
import com.asbai.predictor.util.AppUpdateInfo
import com.asbai.predictor.util.PdfExporter
import com.asbai.predictor.viewmodel.AppTab
import com.asbai.predictor.viewmodel.MainUiState
import com.asbai.predictor.viewmodel.MainViewModel
import org.json.JSONTokener
import java.util.Locale

private val QUICK_SITES = listOf(
    "🇮🇷 اسبدوانی" to "https://asbdavani.app/racecards",
    "🇹🇷 TJK ترکیه" to "https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami",
    "🇳🇱 Unibet" to "https://www.unibet.nl/betting/racing"
)

private val SNIFFER_JS = """
    (function() {
        if (window.__raceSnifferInstalled) return;
        window.__raceSnifferInstalled = true;
        window.__capturedRacePayloads = [];
        function maybeSave(url, text) {
            try {
                if (!text || text.length < 80 || text.length > 350000) return;
                var lower = text.toLowerCase();
                if (lower.indexOf("horse") !== -1 || lower.indexOf("runner") !== -1 ||
                    lower.indexOf("jockey") !== -1 || lower.indexOf("outcomes") !== -1 ||
                    lower.indexOf("betoffers") !== -1 || lower.indexOf("اسب") !== -1 ||
                    lower.indexOf("چابکسوار") !== -1 || lower.indexOf("مربی") !== -1) {
                    window.__capturedRacePayloads.push(text);
                    if (window.__capturedRacePayloads.length > 8) window.__capturedRacePayloads.shift();
                }
            } catch (e) {}
        }
        var origFetch = window.fetch;
        if (origFetch) {
            window.fetch = function() {
                var reqUrl = (arguments[0] && arguments[0].url) ? arguments[0].url : String(arguments[0] || "");
                return origFetch.apply(this, arguments).then(function(res) {
                    try { res.clone().text().then(function(t) { maybeSave(reqUrl, t); }); } catch (e) {}
                    return res;
                });
            };
        }
        var origOpen = XMLHttpRequest.prototype.open;
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(m, u) { this.__reqUrl = u; return origOpen.apply(this, arguments); };
        XMLHttpRequest.prototype.send = function() {
            var xhr = this;
            xhr.addEventListener("load", function() {
                try {
                    if (!xhr.responseType || xhr.responseType === "text" || xhr.responseType === "json") {
                        var txt = typeof xhr.responseText === "string" ? xhr.responseText : JSON.stringify(xhr.response);
                        maybeSave(xhr.__reqUrl, txt);
                    }
                } catch (e) {}
            });
            return origSend.apply(this, arguments);
        };
    })();
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تحلیلگر حرفه‌ای اسبدوانی", fontWeight = FontWeight.Bold) },
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
                        AppTab.SETTINGS -> Icons.Default.Tune
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.availableUpdate?.let { update ->
                UpdateAvailableBanner(update, state.isDownloadingUpdate, state.updateDownloadProgress, { viewModel.installAvailableUpdate() }, { viewModel.dismissUpdateBanner() })
            }
            StatusBanners(state.statusMessage, state.errorMessage) { viewModel.clearMessages() }
            when (state.selectedTab) {
                AppTab.PREDICT -> PredictTab(state, viewModel)
                AppTab.BROWSER -> LiveBrowserTab(
                    initialUrl = state.urlInput,
                    onUrlChanged = { viewModel.updateUrlInput(it) },
                    onExtractAndPredict = { title, url, text -> viewModel.onWebViewExtracted(title, url, text) }
                )
                AppTab.HISTORY -> HistoryTab(state, viewModel)
                AppTab.SETTINGS -> CalibrationTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun UpdateAvailableBanner(update: AppUpdateInfo, isDownloading: Boolean, progress: Int, onInstallClick: () -> Unit, onDismiss: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("نسخه جدید ${update.versionName} آماده است!", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!isDownloading) {
                    Button(onClick = onInstallClick) { Text("نصب آپدیت") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "بستن") }
                }
            }
            if (isDownloading) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { (progress.coerceIn(0, 100)) / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("در حال دانلود... $progress٪", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatusBanners(statusMessage: String?, errorMessage: String?, onDismiss: () -> Unit) {
    val msg = errorMessage ?: statusMessage ?: return
    val isErr = errorMessage != null
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = if (isErr) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(msg, color = if (isErr) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "بستن") }
        }
    }
}

@Composable
private fun PredictTab(state: MainUiState, viewModel: MainViewModel) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showRawData by remember { mutableStateOf(true) }
    var showNotes by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("انتخاب سایت مسابقات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_SITES.forEach { (label, siteUrl) ->
                        SuggestionChip(onClick = { viewModel.updateUrlInput(siteUrl) }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = state.urlInput, onValueChange = { viewModel.updateUrlInput(it) }, label = { Text("آدرس صفحه مسابقات") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.fetchFromSite() }, enabled = !state.isFetchingSite && !state.isAnalyzing, modifier = Modifier.weight(1f)) {
                        if (state.isFetchingSite) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("دریافت مستقیم")
                    }
                    OutlinedButton(onClick = { viewModel.selectTab(AppTab.BROWSER) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("مرورگر سایت")
                    }
                }
                if (state.needsWebViewHint) {
                    Spacer(Modifier.height(8.dp))
                    Text("برای Unibet و صفحات جدول‌دار TJK / اسبدوانی، دکمه «مرورگر سایت» را بزنید و داخل صفحه دکمه استخراج را لمس کنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (state.raceSections.size > 1) {
            Column {
                Text("انتخاب کورس / دورِ پیداشده:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.raceSections.forEachIndexed { idx, sec ->
                        FilterChip(
                            selected = state.selectedSectionIndex == idx,
                            onClick = { viewModel.selectRaceSection(idx) },
                            label = { Text(sec.label) }
                        )
                    }
                }
            }
        }

        if (state.discoveredLinks.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.discoveredLinks.forEach { link ->
                    AssistChip(onClick = { viewModel.fetchFromSite(link.url) }, label = { Text(link.title) }, leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) })
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().clickable { showRawData = !showRawData }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("داده‌های استخراج‌شده (${state.raceDataInput.length} کاراکتر)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Icon(if (showRawData) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
                if (showRawData) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val pasted = clipboardManager.getText()?.text.orEmpty()
                            if (pasted.isNotBlank()) viewModel.updateRaceDataInput(pasted)
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("چسباندن متن")
                        }
                        if (state.raceDataInput.isNotEmpty()) TextButton(onClick = { viewModel.updateRaceDataInput("") }) { Text("پاک کردن") }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = state.raceDataInput, onValueChange = { viewModel.updateRaceDataInput(it) }, label = { Text("جدول اسب‌ها...") }, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().clickable { showNotes = !showNotes }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("یادداشت شرایط پیست یا اخبار (اختیاری)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(if (showNotes) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
                if (showNotes || state.newsNotesInput.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = state.newsNotesInput, onValueChange = { viewModel.updateNewsNotes(it) }, label = { Text("مثلاً: پیست گل‌آلود است...") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Button(onClick = { viewModel.analyzeCurrentRace() }, enabled = !state.isAnalyzing && state.raceDataInput.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.BarChart, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("شروع تحلیل کمی پیشرفته (فوری)", fontWeight = FontWeight.Bold)
        }

        state.currentReport?.let { report ->
            PredictionResultView(
                report = report,
                onExportPdf = {
                    PdfExporter.exportAndShare(context, report).fold(
                        onSuccess = { viewModel.showStatus("فایل PDF ساخته شد و آماده ارسال است.") },
                        onFailure = { err -> viewModel.showError("خطا در ساخت PDF: ${err.message}") }
                    )
                },
                onRecordWinner = { num, name -> viewModel.recordActualWinner(num, name) },
                onToggleScratch = { num, name -> viewModel.toggleScratchHorse(num, name) }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveBrowserTab(initialUrl: String, onUrlChanged: (String) -> Unit, onExtractAndPredict: (title: String, url: String, extractedText: String) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl.ifBlank { "https://asbdavani.app/racecards" }) }
    var urlBarText by remember { mutableStateOf(currentUrl) }
    var currentTitle by remember { mutableStateOf("مرورگر مسابقات") }
    var progress by remember { mutableIntStateOf(0) }

    fun navigateTo(raw: String) {
        val target = raw.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        currentUrl = target
        urlBarText = target
        onUrlChanged(target)
        webView?.loadUrl(target)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "عقب") }
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "رفرش") }
                OutlinedTextField(value = urlBarText, onValueChange = { urlBarText = it }, singleLine = true, placeholder = { Text("آدرس سایت...") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { navigateTo(urlBarText) }) { Text("برو", fontWeight = FontWeight.Bold) }
            }

            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_SITES.forEach { (label, siteUrl) ->
                    FilterChip(selected = currentUrl.contains(siteUrl.removePrefix("https://").substringBefore("/")), onClick = { navigateTo(siteUrl) }, label = { Text(label) })
                }
            }

            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                view?.evaluateJavascript(SNIFFER_JS, null)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(SNIFFER_JS, null)
                                if (!url.isNullOrBlank()) { currentUrl = url; urlBarText = url; onUrlChanged(url) }
                                if (!view?.title.isNullOrBlank()) currentTitle = view?.title.orEmpty()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
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
                        var hasShadow = false;
                        function collectTables(root) {
                            root.querySelectorAll("table").forEach(function(table, idx) {
                                parts.push("--- جدول " + (idx + 1) + " ---");
                                table.querySelectorAll("tr").forEach(function(row) {
                                    var cells = [];
                                    var hasText = false;
                                    row.querySelectorAll("th, td").forEach(function(cell) {
                                        var t = (cell.innerText || "").trim().replace(/\n+/g, " · ").replace(/[ \t]+/g, " ");
                                        if (t) hasText = true;
                                        cells.push(t || " ");
                                    });
                                    if (cells.length > 0 && hasText) parts.push(cells.join(" | "));
                                });
                            });
                            root.querySelectorAll("*").forEach(function(el) {
                                if (el.shadowRoot) {
                                    hasShadow = true;
                                    collectTables(el.shadowRoot);
                                }
                            });
                        }
                        collectTables(document);
                        function collectDeepText(root) {
                            var lines = [];
                            function walk(node) {
                                if (!node) return;
                                if (node.nodeType === 3) {
                                    var txt = (node.nodeValue || "").trim();
                                    if (txt) lines.push(txt);
                                    return;
                                }
                                if (node.nodeType !== 1 && node.nodeType !== 11) return;
                                var tag = (node.tagName || "").toUpperCase();
                                if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "SVG") return;
                                if (node.shadowRoot) walk(node.shadowRoot);
                                var children = node.childNodes;
                                if (children) {
                                    for (var i = 0; i < children.length; i++) walk(children[i]);
                                }
                            }
                            walk(root);
                            return lines.join("\n");
                        }
                        var deepBody = hasShadow
                            ? collectDeepText(document.body)
                            : (document.body.innerText || collectDeepText(document.body));
                        parts.push("--- متن صفحه ---");
                        parts.push(deepBody.replace(/\n{3,}/g, "\n\n").trim().slice(0, 32000));
                        var captured = window.__capturedRacePayloads || [];
                        for (var j = 0; j < captured.length; j++) {
                            parts.push("--- CAPTURED_JSON_START ---");
                            parts.push(String(captured[j]).slice(0, 60000));
                            parts.push("--- CAPTURED_JSON_END ---");
                        }
                        return parts.join("\n");
                    })();
                """.trimIndent()

                webView?.evaluateJavascript(js) { rawResult ->
                    val decoded = runCatching { JSONTokener(rawResult).nextValue() as? String }.getOrNull().orEmpty()
                    if (decoded.isNotBlank()) onExtractAndPredict(currentTitle, currentUrl, decoded)
                }
            },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            text = { Text("استخراج و تحلیل فوری") },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun HistoryTab(state: MainUiState, viewModel: MainViewModel) {
    if (state.history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("هنوز هیچ تحلیلی ذخیره نشده است.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.history, key = { it.id }) { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { viewModel.openHistoryItem(item) }) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.report.raceSummary.ifBlank { "گزارش مسابقه" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        item.report.topThree.firstOrNull()?.let { top ->
                            Text("پیش‌بینی اول: #${top.number} ${top.horseName} (${top.winProbability}٪)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (item.report.actualWinnerName.isNotBlank()) {
                            Text("🏆 برنده واقعی: ${item.report.actualWinnerName}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(item.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.deleteHistoryItem(item.id) }) { Icon(Icons.Default.Delete, contentDescription = "حذف") }
                }
            }
        }
    }
}

@Composable
private fun CalibrationTab(state: MainUiState, viewModel: MainViewModel) {
    var w by remember(state.weights) { mutableStateOf(state.weights) }
    var selectedRisk by remember(state.riskProfile) { mutableStateOf(state.riskProfile) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("به‌روزرسانی خودکار برنامه", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("نسخه فعلی: ${state.currentVersionName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { viewModel.checkForAppUpdate(silent = false) }, enabled = !state.isCheckingUpdate && !state.isDownloadingUpdate) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.isCheckingUpdate) "در حال بررسی..." else "بررسی آپدیت")
                    }
                }
                state.availableUpdate?.let { upd ->
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { viewModel.installAvailableUpdate() }, enabled = !state.isDownloadingUpdate, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isDownloadingUpdate) "در حال دانلود (${state.updateDownloadProgress}٪)..." else "دانلود و نصب نسخه ${upd.versionName}")
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("📊 آمار دقت موتور خودآموز", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                val total = state.weights.totalRacesLogged
                val top1Pct = if (total > 0) (state.weights.top1Hits * 100) / total else 0
                val top3Pct = if (total > 0) (state.weights.top3Hits * 100) / total else 0
                Text("مسابقات ثبت‌شده: $total کورس", style = MaterialTheme.typography.bodyMedium)
                Text("دقت رتبه اول: ${state.weights.top1Hits}/$total ($top1Pct٪)", style = MaterialTheme.typography.bodySmall)
                Text("دقت ۳تای اول (سکو): ${state.weights.top3Hits}/$total ($top3Pct٪)", style = MaterialTheme.typography.bodySmall)
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🎛 تنظیم وزن فاکتورهای مدل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                WeightSliderRow("فرم و رتبه‌های اخیر", w.formWeight) { w = w.copy(formWeight = it) }
                WeightSliderRow("سرعت و بهترین تایم", w.speedWeight) { w = w.copy(speedWeight = it) }
                WeightSliderRow("ریتینگ (HP / IOR)", w.ratingWeight) { w = w.copy(ratingWeight = it) }
                WeightSliderRow("اختلاف وزن حمل‌شده", w.weightDiffWeight) { w = w.copy(weightDiffWeight = it) }
                WeightSliderRow("آمادگی و استراحت (KGS)", w.freshnessWeight) { w = w.copy(freshnessWeight = it) }
                WeightSliderRow("دپار نسبی (draw_pct)", w.drawWeight) { w = w.copy(drawWeight = it) }
                WeightSliderRow("سیگنال بازار (AGF / ضریب)", w.marketWeight) { w = w.copy(marketWeight = it) }
                TextButton(onClick = { viewModel.resetWeights() }) { Text("بازنشانی وزن‌ها به حالت استاندارد") }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("استراتژی پیش‌بینی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                RiskProfile.entries.forEach { profile ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedRisk = profile }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedRisk == profile, onClick = { selectedRisk = profile })
                        Spacer(Modifier.width(6.dp))
                        Text(profile.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Button(onClick = { viewModel.saveCalibration(w, selectedRisk) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("ذخیره تنظیمات کالیبراسیون", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WeightSliderRow(label: String, value: Double, onValueChange: (Double) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.US, "%.2f", value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.toFloat().coerceIn(0.25f, 2.5f), onValueChange = { onValueChange(it.toDouble()) }, valueRange = 0.25f..2.5f)
    }
}
