package com.asbai.predictor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asbai.predictor.data.SettingsRepository
import com.asbai.predictor.model.DiscoveredLink
import com.asbai.predictor.model.PreScoredHorse
import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import com.asbai.predictor.model.ThirdProvider
import com.asbai.predictor.network.AsbdavaniScraper
import com.asbai.predictor.network.CouncilAnalyzer
import com.asbai.predictor.network.CouncilConfig
import com.asbai.predictor.network.GeminiAnalyzer
import com.asbai.predictor.network.OpenAiCompatAnalyzer
import com.asbai.predictor.util.AppUpdateInfo
import com.asbai.predictor.util.AppUpdater
import com.asbai.predictor.util.PreScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class AppTab(val title: String) {
    PREDICT("پیش‌بینی"),
    BROWSER("سایت زنده"),
    HISTORY("تاریخچه"),
    SETTINGS("تنظیمات")
}

data class MainUiState(
    val selectedTab: AppTab = AppTab.PREDICT,
    val urlInput: String = "https://asbdavani.app/racecards",
    val pageTitle: String = "مسابقات اسبدوانی",
    val raceDataInput: String = "",
    val newsNotesInput: String = "",
    val useWebSearch: Boolean = true,
    val discoveredLinks: List<DiscoveredLink> = emptyList(),
    val livePreScores: List<PreScoredHorse> = emptyList(),
    val isFetchingSite: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isTestingKey: Boolean = false,
    val needsWebViewHint: Boolean = false,
    val currentReport: PredictionReport? = null,
    val history: List<SavedPrediction> = emptyList(),
    val apiKey: String = "",
    val selectedModel: String = SettingsRepository.DEFAULT_MODEL,
    val groqApiKey: String = "",
    val groqModel: String = SettingsRepository.DEFAULT_GROQ_MODEL,
    val thirdProvider: ThirdProvider = ThirdProvider.COHERE,
    val thirdApiKey: String = "",
    val thirdModel: String = ThirdProvider.COHERE.defaultModel,
    val riskProfile: RiskProfile = RiskProfile.BALANCED,
    val currentVersionName: String = "1.0",
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repo = SettingsRepository(appContext)
    private val scraper = AsbdavaniScraper()
    private val geminiAnalyzer = GeminiAnalyzer()
    private val openAiCompatAnalyzer = OpenAiCompatAnalyzer()
    private val councilAnalyzer = CouncilAnalyzer(geminiAnalyzer, openAiCompatAnalyzer)

    private val _uiState = MutableStateFlow(
        MainUiState(
            apiKey = repo.getApiKey(),
            selectedModel = repo.getModel(),
            groqApiKey = repo.getGroqApiKey(),
            groqModel = repo.getGroqModel(),
            thirdProvider = repo.getThirdProvider(),
            thirdApiKey = repo.getThirdApiKey(),
            thirdModel = repo.getThirdModel(),
            riskProfile = repo.getRiskProfile(),
            history = repo.getHistory(),
            currentVersionName = AppUpdater.getCurrentVersionName(appContext)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        checkForAppUpdate(silent = true)
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update {
                    it.copy(isCheckingUpdate = true, errorMessage = null, statusMessage = "در حال بررسی نسخه جدید...")
                }
            }
            AppUpdater.checkForUpdate(appContext).fold(
                onSuccess = { info ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            availableUpdate = info,
                            statusMessage = when {
                                info != null && !silent -> "نسخه جدید ${info.versionName} پیدا شد!"
                                info == null && !silent -> "شما در حال استفاده از آخرین نسخه (${it.currentVersionName}) هستید."
                                else -> it.statusMessage
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            errorMessage = if (silent) it.errorMessage else (err.message ?: "خطا در بررسی آپدیت"),
                            statusMessage = if (silent) it.statusMessage else null
                        )
                    }
                }
            )
        }
    }

    fun installAvailableUpdate() {
        val update = _uiState.value.availableUpdate ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingUpdate = true,
                    updateDownloadProgress = 0,
                    errorMessage = null,
                    statusMessage = "در حال دانلود نسخه ${update.versionName}..."
                )
            }
            AppUpdater.downloadAndInstall(
                context = appContext,
                downloadUrl = update.downloadUrl,
                onProgress = { pct -> _uiState.update { it.copy(updateDownloadProgress = pct) } }
            ).fold(
                onSuccess = { started ->
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            statusMessage = if (started) "فایل آماده شد؛ روی «به‌روزرسانی» بزنید." else "مجوز نصب از این منبع را فعال کنید."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isDownloadingUpdate = false, errorMessage = err.message ?: "خطای نصب آپدیت", statusMessage = null)
                    }
                }
            )
        }
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(availableUpdate = null) }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun updateUrlInput(url: String) {
        _uiState.update { it.copy(urlInput = url) }
    }

    fun updateRaceDataInput(text: String) {
        val pre = PreScorer.analyze(text)
        _uiState.update { it.copy(raceDataInput = text, livePreScores = pre.horses) }
    }

    fun updateNewsNotes(text: String) {
        _uiState.update { it.copy(newsNotesInput = text) }
    }

    fun toggleWebSearch(enabled: Boolean) {
        _uiState.update { it.copy(useWebSearch = enabled) }
    }

    fun showStatus(msg: String) {
        _uiState.update { it.copy(statusMessage = msg, errorMessage = null) }
    }

    fun showError(msg: String) {
        _uiState.update { it.copy(errorMessage = msg, statusMessage = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun fetchFromSite(customUrl: String? = null) {
        val targetUrl = (customUrl ?: _uiState.value.urlInput).trim()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFetchingSite = true,
                    urlInput = targetUrl,
                    errorMessage = null,
                    statusMessage = "در حال دریافت اطلاعات از سایت...",
                    needsWebViewHint = false
                )
            }
            scraper.fetchPage(targetUrl).fold(
                onSuccess = { page ->
                    val pre = withContext(Dispatchers.Default) { PreScorer.analyze(page.extractedText) }
                    _uiState.update {
                        it.copy(
                            isFetchingSite = false,
                            urlInput = page.url,
                            pageTitle = page.title,
                            raceDataInput = page.extractedText,
                            discoveredLinks = page.links,
                            livePreScores = pre.horses,
                            needsWebViewHint = page.likelyNeedsWebView,
                            statusMessage = if (page.likelyNeedsWebView) {
                                "این صفحه با جاوااسکریپت لود می‌شود؛ از تب «سایت زنده» بازش کنید و «استخراج و پیش‌بینی» را بزنید."
                            } else {
                                "دریافت شد (${page.tableCount} جدول، پیش‌امتیاز برای ${pre.horses.size} اسب)."
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isFetchingSite = false, errorMessage = err.message ?: "خطا در ارتباط با سایت", statusMessage = null)
                    }
                }
            )
        }
    }

    fun onWebViewExtracted(title: String, url: String, extractedText: String) {
        val pre = PreScorer.analyze(extractedText)
        _uiState.update {
            it.copy(
                selectedTab = AppTab.PREDICT,
                urlInput = url,
                pageTitle = title.ifBlank { "صفحه زنده مسابقات" },
                raceDataInput = extractedText,
                livePreScores = pre.horses,
                needsWebViewHint = false,
                errorMessage = null,
                statusMessage = "اطلاعات استخراج شد (پیش‌امتیاز ریاضی برای ${pre.horses.size} اسب)!"
            )
        }
        val state = _uiState.value
        val hasAnyKey = state.apiKey.isNotBlank() || state.groqApiKey.isNotBlank() || state.thirdApiKey.isNotBlank()
        if (hasAnyKey && extractedText.isNotBlank()) {
            analyzeCurrentRace()
        } else if (!hasAnyKey) {
            _uiState.update {
                it.copy(errorMessage = "اطلاعات استخراج شد؛ حداقل یک کلید هوش مصنوعی در تنظیمات وارد کنید.")
            }
        }
    }

    fun analyzeCurrentRace() {
        val state = _uiState.value
        val activeMembers = buildList {
            if (state.apiKey.isNotBlank()) add("Gemini")
            if (state.groqApiKey.isNotBlank()) add("Groq")
            if (state.thirdApiKey.isNotBlank()) add(state.thirdProvider.shortName)
        }

        if (activeMembers.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "ابتدا حداقل یک کلید هوش مصنوعی ثبت کنید.", selectedTab = AppTab.SETTINGS)
            }
            return
        }
        if (state.raceDataInput.isBlank()) {
            _uiState.update { it.copy(errorMessage = "ابتدا اطلاعات مسابقه را دریافت یا وارد کنید.") }
            return
        }

        viewModelScope.launch {
            val pre = withContext(Dispatchers.Default) { PreScorer.analyze(state.raceDataInput) }
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    livePreScores = pre.horses,
                    errorMessage = null,
                    statusMessage = "شورای (${activeMembers.joinToString(" + ")}) با کمک پیش‌امتیاز ریاضی در حال تحلیل است..."
                )
            }

            val enrichedData = if (pre.promptBlock.isNotBlank()) {
                "${pre.promptBlock}\n\n${state.raceDataInput}"
            } else {
                state.raceDataInput
            }

            val config = CouncilConfig(
                geminiKey = state.apiKey,
                geminiModel = state.selectedModel,
                groqKey = state.groqApiKey,
                groqModel = state.groqModel,
                thirdProvider = state.thirdProvider,
                thirdKey = state.thirdApiKey,
                thirdModel = state.thirdModel
            )

            councilAnalyzer.analyzeWithCouncil(
                config = config,
                riskProfile = state.riskProfile,
                raceTitle = state.pageTitle,
                rawRaceData = enrichedData,
                userNewsNotes = state.newsNotesInput,
                useWebSearch = state.useWebSearch
            ).fold(
                onSuccess = { output ->
                    val finalReport = output.report.copy(preScores = pre.horses)
                    val timeStr = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.US)
                        .format(Date(System.currentTimeMillis()))
                    val saved = SavedPrediction(
                        id = UUID.randomUUID().toString(),
                        timestamp = timeStr,
                        sourceUrl = state.urlInput,
                        rawExcerpt = state.raceDataInput.take(300),
                        report = finalReport
                    )
                    repo.addHistory(saved)
                    val warnNote = if (output.memberWarnings.isNotEmpty()) {
                        "\n(هشدار برخی اعضا: ${output.memberWarnings.joinToString(" | ")})"
                    } else ""
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            currentReport = finalReport,
                            history = repo.getHistory(),
                            statusMessage = "تحلیل آماده شد (${finalReport.memberOpinions.size} مدل فعال)!$warnNote"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isAnalyzing = false, errorMessage = err.message ?: "خطا در تحلیل شورا", statusMessage = null)
                    }
                }
            )
        }
    }

    fun saveAllSettings(
        geminiKey: String,
        geminiModel: String,
        groqKey: String,
        groqModel: String,
        thirdProvider: ThirdProvider,
        thirdKey: String,
        thirdModel: String,
        riskProfile: RiskProfile
    ) {
        repo.saveApiKey(geminiKey)
        repo.saveModel(geminiModel)
        repo.saveGroqApiKey(groqKey)
        repo.saveGroqModel(groqModel)
        repo.saveThirdProvider(thirdProvider)
        repo.saveThirdApiKey(thirdKey)
        repo.saveThirdModel(thirdModel)
        repo.saveRiskProfile(riskProfile)

        _uiState.update {
            it.copy(
                apiKey = repo.getApiKey(),
                selectedModel = repo.getModel(),
                groqApiKey = repo.getGroqApiKey(),
                groqModel = repo.getGroqModel(),
                thirdProvider = repo.getThirdProvider(),
                thirdApiKey = repo.getThirdApiKey(),
                thirdModel = repo.getThirdModel(),
                riskProfile = repo.getRiskProfile(),
                statusMessage = "تنظیمات ذخیره شد.",
                errorMessage = null
            )
        }
    }

    fun testGeminiKey(apiKey: String, model: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingKey = true, errorMessage = null, statusMessage = "در حال تست Gemini...") }
            geminiAnalyzer.testConnection(apiKey, model).fold(
                onSuccess = { msg ->
                    repo.saveApiKey(apiKey)
                    repo.saveModel(model)
                    _uiState.update {
                        it.copy(isTestingKey = false, apiKey = repo.getApiKey(), selectedModel = repo.getModel(), statusMessage = msg)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isTestingKey = false, errorMessage = err.message ?: "تست Gemini ناموفق بود.", statusMessage = null)
                    }
                }
            )
        }
    }

    fun testGroqKey(apiKey: String, model: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingKey = true, errorMessage = null, statusMessage = "در حال تست Groq...") }
            openAiCompatAnalyzer.testConnection(
                providerName = "Groq",
                endpointUrl = CouncilAnalyzer.GROQ_ENDPOINT,
                apiKey = apiKey,
                model = model
            ).fold(
                onSuccess = { msg ->
                    repo.saveGroqApiKey(apiKey)
                    repo.saveGroqModel(model)
                    _uiState.update {
                        it.copy(isTestingKey = false, groqApiKey = repo.getGroqApiKey(), groqModel = repo.getGroqModel(), statusMessage = msg)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isTestingKey = false, errorMessage = err.message ?: "تست Groq ناموفق بود.", statusMessage = null)
                    }
                }
            )
        }
    }

    fun testThirdKey(provider: ThirdProvider, apiKey: String, model: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingKey = true, errorMessage = null, statusMessage = "در حال تست ${provider.shortName}...") }
            val headers = if (provider == ThirdProvider.OPENROUTER) {
                mapOf("HTTP-Referer" to "https://asbdavani.app", "X-Title" to "AsbDavaniAI")
            } else {
                emptyMap()
            }
            openAiCompatAnalyzer.testConnection(
                providerName = provider.shortName,
                endpointUrl = provider.endpointUrl,
                apiKey = apiKey,
                model = model,
                extraHeaders = headers
            ).fold(
                onSuccess = { msg ->
                    repo.saveThirdProvider(provider)
                    repo.saveThirdApiKey(apiKey)
                    repo.saveThirdModel(model)
                    _uiState.update {
                        it.copy(
                            isTestingKey = false,
                            thirdProvider = repo.getThirdProvider(),
                            thirdApiKey = repo.getThirdApiKey(),
                            thirdModel = repo.getThirdModel(),
                            statusMessage = msg
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isTestingKey = false, errorMessage = err.message ?: "تست ${provider.shortName} ناموفق بود.", statusMessage = null)
                    }
                }
            )
        }
    }

    fun openHistoryItem(item: SavedPrediction) {
        _uiState.update {
            it.copy(
                currentReport = item.report,
                livePreScores = item.report.preScores,
                urlInput = item.sourceUrl,
                selectedTab = AppTab.PREDICT,
                statusMessage = "گزارش تاریخچه (${item.timestamp}) بارگذاری شد."
            )
        }
    }

    fun deleteHistoryItem(id: String) {
        repo.deleteHistory(id)
        _uiState.update { it.copy(history = repo.getHistory(), statusMessage = "از تاریخچه حذف شد.") }
    }
}
