package com.asbai.predictor.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.ThirdProvider
import com.asbai.predictor.ui.components.PredictionResultView
import com.asbai.predictor.util.AppUpdateInfo
import com.asbai.predictor.util.PdfExporter
import com.asbai.predictor.viewmodel.AppTab
import com.asbai.predictor.viewmodel.MainUiState
import com.asbai.predictor.viewmodel.MainViewModel
import org.json.JSONTokener

private val QUICK_SITES = listOf(
    "🇮🇷 اسبدوانی" to "https://asbdavani.app/racecards",
    "🇹🇷 TJK ترکیه" to "https://www.tjk.org/TR/yarissever/Info/Page/GunlukYarisProgrami?QueryParameter_Tarih=20/11/2026&Era=today&1=1#",
    "🇳🇱 Unibet" to "https://www.unibet.nl/betting/racing"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("شورای هوشمند اسبدوانی", fontWeight = FontWeight.Bold) },
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.availableUpdate?.let { update ->
                UpdateAvailableBanner(
                    update = update,
                    isDownloading = state.isDownloadingUpdate,
                    progress = state.updateDownloadProgress,
                    onInstallClick = { viewModel.installAvailableUpdate() },
                    onDismiss = { viewModel.dismissUpdateBanner() }
                )
            }
            StatusBanners(
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                onDismiss = { viewModel.clearMessages() }
            )
            when (state.selectedTab) {
                AppTab.PREDICT -> PredictTab(state = state, viewModel = viewModel)
                AppTab.BROWSER -> LiveBrowserTab(
                    initialUrl = state.urlInput,
                    onUrlChanged = { viewModel.updateUrlInput(it) },
                    onExtractAndPredict = { title, url, text -> viewModel.onWebViewExtracted(title, url, text) }
                )
                AppTab.HISTORY -> HistoryTab(state = state, viewModel = viewModel)
                AppTab.SETTINGS -> SettingsTab(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    update: AppUpdateInfo,
    isDownloading: Boolean,
    progress: Int,
    onInstallClick: () -> Unit,
    onDismiss: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "نسخه جدید ${update.versionName} آماده است!",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
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
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isErr) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = msg,
                color = if (isErr) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "بستن") }
        }
    }
}

@Composable
private fun PredictTab(state: MainUiState, viewModel: MainViewModel) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showRawData by remember { mutableStateOf(true) }
    var showNewsNotes by remember { mutableStateOf(false) }

    val hasGeminiKey = state.apiKey.isNotBlank()
    val hasGroqKey = state.groqApiKey.isNotBlank()
    val hasThirdKey = state.thirdApiKey.isNotBlank()
    val hasCfKey = state.cfAccountId.isNotBlank() && state.cfApiToken.isNotBlank()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("اعضای شورا (برای روشن/خاموش لمس کنید):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { viewModel.selectTab(AppTab.SETTINGS) }) { Text("کلیدها") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CouncilToggleChip("Gemini", hasGeminiKey, state.geminiEnabled) {
                        if (hasGeminiKey) viewModel.toggleGemini(!state.geminiEnabled)
                        else {
                            viewModel.showError("ابتدا کلید Gemini را در تنظیمات وارد کنید.")
                            viewModel.selectTab(AppTab.SETTINGS)
                        }
                    }
                    CouncilToggleChip("Groq", hasGroqKey, state.groqEnabled) {
                        if (hasGroqKey) viewModel.toggleGroq(!state.groqEnabled)
                        else {
                            viewModel.showError("ابتدا کلید Groq را در تنظیمات وارد کنید.")
                            viewModel.selectTab(AppTab.SETTINGS)
                        }
                    }
                    CouncilToggleChip(state.thirdProvider.shortName, hasThirdKey, state.thirdEnabled) {
                        if (hasThirdKey) viewModel.toggleThird(!state.thirdEnabled)
                        else {
                            viewModel.showError("ابتدا کلید ${state.thirdProvider.shortName} را وارد کنید.")
                            viewModel.selectTab(AppTab.SETTINGS)
                        }
                    }
                    CouncilToggleChip("Cloudflare", hasCfKey, state.cfEnabled) {
                        if (hasCfKey) viewModel.toggleCf(!state.cfEnabled)
                        else {
                            viewModel.showError("ابتدا Account ID و Token کلودفلر را در تنظیمات وارد کنید.")
                            viewModel.selectTab(AppTab.SETTINGS)
                        }
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("انتخاب سایت مسابقات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QUICK_SITES.forEach { (label, siteUrl) ->
                        SuggestionChip(onClick = { viewModel.updateUrlInput(siteUrl) }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = { viewModel.updateUrlInput(it) },
                    label = { Text("آدرس صفحه مسابقات") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.fetchFromSite() },
                        enabled = !state.isFetchingSite && !state.isAnalyzing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isFetchingSite) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("دریافت مستقیم")
                    }
                    OutlinedButton(onClick = { viewModel.selectTab(AppTab.BROWSER) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("باز کردن در مرورگر")
                    }
                }
                if (state.needsWebViewHint) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "نکته: برای Unibet و برخی صفحات TJK/اسبدوانی از دکمه «باز کردن در مرورگر» وارد شوید و دکمه «استخراج و پیش‌بینی همین صفحه» را بزنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (state.discoveredLinks.isNotEmpty()) {
            Column {
                Text("لینک‌های پیداشده در صفحه:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.discoveredLinks.forEach { link ->
                        AssistChip(
                            onClick = { viewModel.fetchFromSite(link.url) },
                            label = { Text(link.title) },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) }
                        )
                    }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showRawData = !showRawData },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("اطلاعات خام / جدول (${state.raceDataInput.length} کاراکتر)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                        if (state.raceDataInput.isNotEmpty()) {
                            TextButton(onClick = { viewModel.updateRaceDataInput("") }) { Text("پاک کردن") }
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

        if (state.livePreScores.isNotEmpty() && state.currentReport == null) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF1F8E9))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = Color(0xFF2E7D32))
                        Spacer(Modifier.width(6.dp))
                        Text("پیش‌امتیاز ریاضی فوری Equintel (قبل از AI)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    }
                    Spacer(Modifier.height(6.dp))
                    state.livePreScores.take(5).forEachIndexed { idx, h ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${idx + 1}. #${h.number} ${h.horseName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text("${h.mathWinProb}٪", style = MaterialTheme.typography.labelMedium, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        }
                        if (h.summary.isNotBlank()) Text(h.summary, style = MaterialTheme.typography.labelSmall)
                        if (idx < minOf(state.livePreScores.size, 5) - 1) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("جستجوی زنده اخبار در گوگل", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (state.geminiEnabled && hasGeminiKey) "توسط Gemini جستجو می‌شود" else "برای جستجو، Gemini باید روشن باشد",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(checked = state.useWebSearch, onCheckedChange = { viewModel.toggleWebSearch(it) })
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showNewsNotes = !showNewsNotes },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("افزودن خبر یا نکته پیست (اختیاری)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(if (showNewsNotes) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
                if (showNewsNotes || state.newsNotesInput.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.newsNotesInput,
                        onValueChange = { viewModel.updateNewsNotes(it) },
                        label = { Text("مثلاً: پیست سنگین است، تغییر چابکسوار لحظه آخری...") },
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
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.isAnalyzing) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("شورا در حال تحلیل همزمان...")
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("تحلیل همزمان با شورای هوش مصنوعی", fontWeight = FontWeight.Bold)
            }
        }

        state.currentReport?.let { report ->
            PredictionResultView(
                report = report,
                onExportPdf = {
                    PdfExporter.exportAndShare(context, report).fold(
                        onSuccess = { viewModel.showStatus("فایل PDF ساخته شد و آماده ارسال است.") },
                        onFailure = { err -> viewModel.showError("خطا در ساخت PDF: ${err.message}") }
                    )
                }
            )
        }
    }
}

@Composable
private fun CouncilToggleChip(name: String, hasKey: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val isActive = hasKey && enabled
    FilterChip(
        selected = isActive,
        onClick = onToggle,
        leadingIcon = {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = if (isActive) Color(0xFF1B8A5A) else Color(0xFF8A8A8A),
                modifier = Modifier.width(16.dp).height(16.dp)
            )
        },
        label = {
            val txt = when {
                !hasKey -> "$name (بدون کلید)"
                enabled -> "$name (روشن)"
                else -> "$name (خاموش)"
            }
            Text(txt, style = MaterialTheme.typography.labelMedium)
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveBrowserTab(
    initialUrl: String,
    onUrlChanged: (String) -> Unit,
    onExtractAndPredict: (title: String, url: String, extractedText: String) -> Unit
) {
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
                IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "عقب")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "رفرش")
                }
                OutlinedTextField(
                    value = urlBarText,
                    onValueChange = { urlBarText = it },
                    singleLine = true,
                    placeholder = { Text("آدرس سایت...") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { navigateTo(urlBarText) }) { Text("برو", fontWeight = FontWeight.Bold) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QUICK_SITES.forEach { (label, siteUrl) ->
                    FilterChip(
                        selected = currentUrl.contains(siteUrl.removePrefix("https://").substringBefore("/")),
                        onClick = { navigateTo(siteUrl) },
                        label = { Text(label) }
                    )
                }
            }

            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }

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
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    urlBarText = url
                                    onUrlChanged(url)
                                }
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
                            table.querySelectorAll("tr").forEach(function(row) {
                                var cells = [];
                                row.querySelectorAll("th, td").forEach(function(cell) {
                                    var t = (cell.innerText || "").replace(/\s+/g, " ").trim();
                                    if (t) cells.push(t);
                                });
                                if (cells.length > 0) parts.push(cells.join(" | "));
                            });
                        });
                        var roleRows = document.querySelectorAll("[role='row'], [role='listitem']");
                        if (tables.length === 0 && roleRows.length > 0) {
                            parts.push("--- ردیف‌های صفحه ---");
                            roleRows.forEach(function(r) {
                                var txt = (r.innerText || "").replace(/\s+/g, " ").trim();
                                if (txt.length > 3) parts.push(txt);
                            });
                        }
                        var bodyText = (document.body.innerText || "").replace(/\n{3,}/g, "\n\n").trim();
                        parts.push("--- متن کامل صفحه ---");
                        parts.push(bodyText);
                        return parts.join("\n").slice(0, 24000);
                    })();
                """.trimIndent()

                webView?.evaluateJavascript(js) { rawResult ->
                    val decoded = runCatching { JSONTokener(rawResult).nextValue() as? String }.getOrNull().orEmpty()
                    if (decoded.isNotBlank()) onExtractAndPredict(currentTitle, currentUrl, decoded)
                }
            },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            text = { Text("استخراج و پیش‌بینی همین صفحه") },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun HistoryTab(state: MainUiState, viewModel: MainViewModel) {
    if (state.history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("هنوز هیچ پیش‌بینی‌ای ذخیره نشده است.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        item.report.topThree.firstOrNull()?.let { topPick ->
                            Text("انتخاب اول: #${topPick.number} ${topPick.horseName} (${topPick.winProbability}٪)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(item.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var geminiEnabled by remember(state.geminiEnabled) { mutableStateOf(state.geminiEnabled) }
    var geminiKeyInput by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var geminiModelInput by remember(state.selectedModel) { mutableStateOf(state.selectedModel) }

    var groqEnabled by remember(state.groqEnabled) { mutableStateOf(state.groqEnabled) }
    var groqKeyInput by remember(state.groqApiKey) { mutableStateOf(state.groqApiKey) }
    var groqModelInput by remember(state.groqModel) { mutableStateOf(state.groqModel) }

    var thirdEnabled by remember(state.thirdEnabled) { mutableStateOf(state.thirdEnabled) }
    var selectedThirdProvider by remember(state.thirdProvider) { mutableStateOf(state.thirdProvider) }
    var thirdKeyInput by remember(state.thirdApiKey) { mutableStateOf(state.thirdApiKey) }
    var thirdModelInput by remember(state.thirdModel) { mutableStateOf(state.thirdModel) }

    var cfEnabled by remember(state.cfEnabled) { mutableStateOf(state.cfEnabled) }
    var cfAccountInput by remember(state.cfAccountId) { mutableStateOf(state.cfAccountId) }
    var cfTokenInput by remember(state.cfApiToken) { mutableStateOf(state.cfApiToken) }
    var cfModelInput by remember(state.cfModel) { mutableStateOf(state.cfModel) }

    var selectedRisk by remember(state.riskProfile) { mutableStateOf(state.riskProfile) }

    val thirdPresets = when (selectedThirdProvider) {
        ThirdProvider.COHERE -> listOf("command-a-plus-05-2026" to "قوی‌ترین کوهیر", "command-a-03-2025" to "چندزبانه ۱۱۱B")
        ThirdProvider.ZAI -> listOf("glm-4.7-flash" to "رایگان دائمی", "glm-4.6v-flash" to "سریع و رایگان")
        ThirdProvider.OPENROUTER -> listOf("nvidia/nemotron-3-ultra-550b-a55b:free" to "۵۵۰B انویدیا", "openrouter/free" to "روتر خودکار")
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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

        ProviderSettingsCard(
            title = "۱. Google Gemini",
            enabled = geminiEnabled,
            onEnabledChange = { geminiEnabled = it },
            helperText = "کلید رایگان از aistudio.google.com/apikey",
            keyLabel = "Gemini API Key",
            apiKey = geminiKeyInput,
            onApiKeyChange = { geminiKeyInput = it },
            model = geminiModelInput,
            onModelChange = { geminiModelInput = it },
            presetModels = listOf("gemini-3.5-flash-lite" to "خلوت‌ترین", "gemini-4-hydrogen" to "نسل ۴", "gemini-3.8-flash" to "نسل ۳"),
            isTesting = state.isTestingKey,
            onTestClick = { viewModel.testGeminiKey(geminiKeyInput, geminiModelInput) }
        )

        ProviderSettingsCard(
            title = "۲. Groq (رایگان و فوق‌سریع)",
            enabled = groqEnabled,
            onEnabledChange = { groqEnabled = it },
            helperText = "کلید رایگان از console.groq.com/keys (شروع با gsk_)",
            keyLabel = "Groq API Key",
            apiKey = groqKeyInput,
            onApiKeyChange = { groqKeyInput = it },
            model = groqModelInput,
            onModelChange = { groqModelInput = it },
            presetModels = listOf("qwen/qwen3.8-27b" to "عالی در فارسی", "openai/gpt-oss-120b" to "قدرتمند ۱۲۰B", "openai/gpt-oss-20b" to "سریع ۲۰B"),
            isTesting = state.isTestingKey,
            onTestClick = { viewModel.testGroqKey(groqKeyInput, groqModelInput) }
        )

        ProviderSettingsCard(
            title = "۳. عضو سوم (${selectedThirdProvider.shortName})",
            enabled = thirdEnabled,
            onEnabledChange = { thirdEnabled = it },
            helperText = selectedThirdProvider.helperText,
            keyLabel = "${selectedThirdProvider.shortName} API Key",
            apiKey = thirdKeyInput,
            onApiKeyChange = { thirdKeyInput = it },
            model = thirdModelInput,
            onModelChange = { thirdModelInput = it },
            presetModels = thirdPresets,
            isTesting = state.isTestingKey,
            onTestClick = { viewModel.testThirdKey(selectedThirdProvider, thirdKeyInput, thirdModelInput) },
            topSlot = {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThirdProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = selectedThirdProvider == provider,
                            onClick = {
                                selectedThirdProvider = provider
                                thirdModelInput = provider.defaultModel
                            },
                            label = { Text(provider.title) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        )

        ProviderSettingsCard(
            title = "۴. Cloudflare Workers AI (جایگزین پایدار)",
            enabled = cfEnabled,
            onEnabledChange = { cfEnabled = it },
            helperText = "از اکانت dash.cloudflare.com (روزانه ۱۰,۰۰۰ Neuron رایگان، بدون خطای ۵۰۳)",
            keyLabel = "Cloudflare Workers AI Token",
            apiKey = cfTokenInput,
            onApiKeyChange = { cfTokenInput = it },
            model = cfModelInput,
            onModelChange = { cfModelInput = it },
            presetModels = listOf(
                "@cf/deepseek-ai/deepseek-v4.1-flash-1017" to "DeepSeek V4.1",
                "@cf/google/gemma-4-26b-a4b-it" to "Gemma 4 26B",
                "@cf/qwen/qwen3.8-27b" to "Qwen 3.8 فارسی",
                "@cf/zai-org/glm-4.7-flash" to "GLM 4.7"
            ),
            isTesting = state.isTestingKey,
            onTestClick = { viewModel.testCloudflareKey(cfAccountInput, cfTokenInput, cfModelInput) },
            topSlot = {
                OutlinedTextField(
                    value = cfAccountInput,
                    onValueChange = { cfAccountInput = it },
                    label = { Text("Cloudflare Account ID (کد ۳۲ کاراکتری)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("استراتژی پیش‌بینی شورا", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                RiskProfile.entries.forEach { profile ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedRisk = profile }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedRisk == profile, onClick = { selectedRisk = profile })
                        Spacer(Modifier.width(6.dp))
                        Text(profile.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Button(
            onClick = {
                viewModel.saveAllSettings(
                    geminiEnabled, geminiKeyInput, geminiModelInput,
                    groqEnabled, groqKeyInput, groqModelInput,
                    thirdEnabled, selectedThirdProvider, thirdKeyInput, thirdModelInput,
                    cfEnabled, cfAccountInput, cfTokenInput, cfModelInput,
                    selectedRisk
                )
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("ذخیره همه تنظیمات", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProviderSettingsCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    helperText: String,
    keyLabel: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    presetModels: List<Pair<String, String>>,
    isTesting: Boolean,
    onTestClick: () -> Unit,
    topSlot: @Composable () -> Unit = {}
) {
    var showKey by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(6.dp))
            topSlot()
            Text(helperText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(keyLabel) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "نمایش کلید")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presetModels.forEach { (id, desc) ->
                    FilterChip(selected = model == id, onClick = { onModelChange(id) }, label = { Text("$id ($desc)") })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = model, onValueChange = onModelChange, label = { Text("نام مدل") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onTestClick, enabled = !isTesting) { Text("تست") }
            }
        }
    }
}
