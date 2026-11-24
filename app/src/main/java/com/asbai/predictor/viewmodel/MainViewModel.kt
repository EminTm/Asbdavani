package com.asbai.predictor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asbai.predictor.data.SettingsRepository
import com.asbai.predictor.engine.MultiSiteParser
import com.asbai.predictor.engine.QuantPredictor
import com.asbai.predictor.model.DiscoveredLink
import com.asbai.predictor.model.ModelWeights
import com.asbai.predictor.model.PreScoredHorse
import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import com.asbai.predictor.network.AsbdavaniScraper
import com.asbai.predictor.util.AppUpdateInfo
import com.asbai.predictor.util.AppUpdater
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
    BROWSER("مرورگر سایت"),
    HISTORY("تاریخچه"),
    SETTINGS("کالیبراسیون")
}

data class MainUiState(
    val selectedTab: AppTab = AppTab.PREDICT,
    val urlInput: String = "https://asbdavani.app/racecards",
    val pageTitle: String = "مسابقات اسبدوانی",
    val raceDataInput: String = "",
    val newsNotesInput: String = "",
    val discoveredLinks: List<DiscoveredLink> = emptyList(),
    val livePreScores: List<PreScoredHorse> = emptyList(),
    val isFetchingSite: Boolean = false,
    val isAnalyzing: Boolean = false,
    val needsWebViewHint: Boolean = false,
    val currentReport: PredictionReport? = null,
    val currentPredictionId: String? = null,
    val history: List<SavedPrediction> = emptyList(),
    val weights: ModelWeights = ModelWeights(),
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

    private val _uiState = MutableStateFlow(
        MainUiState(
            weights = repo.getWeights(),
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
                _uiState.update { it.copy(isCheckingUpdate = true, errorMessage = null, statusMessage = "در حال بررسی نسخه جدید...") }
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
            _uiState.update { it.copy(isDownloadingUpdate = true, updateDownloadProgress = 0, errorMessage = null, statusMessage = "در حال دانلود...") }
            AppUpdater.downloadAndInstall(appContext, update.downloadUrl) { pct ->
                _uiState.update { it.copy(updateDownloadProgress = pct) }
            }.fold(
                onSuccess = { started ->
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            statusMessage = if (started) "فایل آماده شد؛ روی «به‌روزرسانی» بزنید." else "مجوز نصب از این منبع را فعال کنید."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isDownloadingUpdate = false, errorMessage = err.message ?: "خطای نصب آپدیت", statusMessage = null) }
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
        _uiState.update { it.copy(raceDataInput = text) }
        viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) {
                QuantPredictor.predict(
                    rawText = text,
                    pageTitle = _uiState.value.pageTitle,
                    sourceUrl = _uiState.value.urlInput,
                    weights = _uiState.value.weights,
                    riskProfile = _uiState.value.riskProfile,
                    newsNotes = ""
                ).getOrNull()?.report?.preScores.orEmpty()
            }
            _uiState.update { it.copy(livePreScores = preview) }
        }
    }

    fun updateNewsNotes(text: String) {
        _uiState.update { it.copy(newsNotesInput = text) }
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
                it.copy(isFetchingSite = true, urlInput = targetUrl, errorMessage = null, statusMessage = "در حال دریافت صفحه...", needsWebViewHint = false)
            }
            scraper.fetchPage(targetUrl).fold(
                onSuccess = { page ->
                    val parsedCount = withContext(Dispatchers.Default) {
                        MultiSiteParser.parse(page.extractedText, page.title, page.url).horses.size
                    }
                    _uiState.update {
                        it.copy(
                            isFetchingSite = false,
                            urlInput = page.url,
                            pageTitle = page.title,
                            raceDataInput = page.extractedText,
                            discoveredLinks = page.links,
                            needsWebViewHint = page.likelyNeedsWebView || parsedCount < 2,
                            statusMessage = if (parsedCount >= 2) {
                                "اطلاعات دریافت شد ($parsedCount اسب شناسایی شد)."
                            } else {
                                "جدول اسب‌ها با جاوااسکریپت لود می‌شود؛ از تب «مرورگر سایت» بازش کنید."
                            }
                        )
                    }
                    if (parsedCount >= 2) {
                        analyzeCurrentRace()
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
        _uiState.update {
            it.copy(
                selectedTab = AppTab.PREDICT,
                urlInput = url,
                pageTitle = title.ifBlank { "صفحه زنده مسابقات" },
                raceDataInput = extractedText,
                needsWebViewHint = false,
                errorMessage = null
            )
        }
        analyzeCurrentRace()
    }

    fun analyzeCurrentRace() {
        val state = _uiState.value
        if (state.raceDataInput.isBlank()) {
            _uiState.update { it.copy(errorMessage = "ابتدا اطلاعات مسابقه را دریافت یا وارد کنید.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, errorMessage = null) }
            val result = withContext(Dispatchers.Default) {
                QuantPredictor.predict(
                    rawText = state.raceDataInput,
                    pageTitle = state.pageTitle,
                    sourceUrl = state.urlInput,
                    weights = state.weights,
                    riskProfile = state.riskProfile,
                    newsNotes = state.newsNotesInput
                )
            }
            result.fold(
                onSuccess = { out ->
                    val timeStr = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.US)
                        .format(Date(System.currentTimeMillis()))
                    val id = UUID.randomUUID().toString()
                    val saved = SavedPrediction(
                        id = id,
                        timestamp = timeStr,
                        sourceUrl = state.urlInput,
                        rawExcerpt = state.raceDataInput.take(300),
                        report = out.report,
                        horseFeatures = out.featureVectors
                    )
                    repo.addHistory(saved)
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            currentReport = out.report,
                            currentPredictionId = id,
                            livePreScores = out.report.preScores,
                            history = repo.getHistory(),
                            statusMessage = "تحلیل کمی با سرعت لحظه‌ای آماده شد!"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isAnalyzing = false, errorMessage = err.message ?: "خطا در پردازش جدول", statusMessage = null)
                    }
                }
            )
        }
    }

    fun recordActualWinner(winnerNumber: String, winnerName: String) {
        val predId = _uiState.value.currentPredictionId ?: return
        val saved = repo.getHistory().firstOrNull { it.id == predId } ?: return
        if (saved.report.actualWinnerName.isNotBlank()) {
            showStatus("برنده این مسابقه قبلاً ثبت شده و مدل آموزش دیده است.")
            return
        }

        val winnerKey = "$winnerNumber:$winnerName"
        val (newWeights, hitTxt) = QuantPredictor.calibrate(_uiState.value.weights, saved, winnerKey)
        repo.saveWeights(newWeights)

        val updatedReport = saved.report.copy(actualWinnerName = "#$winnerNumber $winnerName")
        val updatedSaved = saved.copy(report = updatedReport)
        repo.updateHistoryItem(updatedSaved)

        _uiState.update {
            it.copy(
                weights = newWeights,
                currentReport = updatedReport,
                history = repo.getHistory(),
                statusMessage = "$hitTxt (دقت ۳تای اول: ${newWeights.top3Hits}/${newWeights.totalRacesLogged})"
            )
        }
    }

    fun saveCalibration(weights: ModelWeights, riskProfile: RiskProfile) {
        repo.saveWeights(weights)
        repo.saveRiskProfile(riskProfile)
        _uiState.update {
            it.copy(weights = weights, riskProfile = riskProfile, statusMessage = "تنظیمات و وزن‌های مدل ذخیره شدند.", errorMessage = null)
        }
    }

    fun resetWeights() {
        val def = repo.resetWeights()
        _uiState.update { it.copy(weights = def, statusMessage = "وزن‌ها به حالت استاندارد بازنشانی شدند.") }
    }

    fun openHistoryItem(item: SavedPrediction) {
        _uiState.update {
            it.copy(
                currentReport = item.report,
                currentPredictionId = item.id,
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
