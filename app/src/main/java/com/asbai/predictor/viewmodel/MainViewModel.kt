package com.asbai.predictor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asbai.predictor.data.SettingsRepository
import com.asbai.predictor.model.DiscoveredLink
import com.asbai.predictor.model.PredictionReport
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import com.asbai.predictor.network.AsbdavaniScraper
import com.asbai.predictor.network.GeminiAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val discoveredLinks: List<DiscoveredLink> = emptyList(),
    val isFetchingSite: Boolean = false,
    val isAnalyzing: Boolean = false,
    val isTestingKey: Boolean = false,
    val needsWebViewHint: Boolean = false,
    val currentReport: PredictionReport? = null,
    val history: List<SavedPrediction> = emptyList(),
    val apiKey: String = "",
    val selectedModel: String = SettingsRepository.DEFAULT_MODEL,
    val riskProfile: RiskProfile = RiskProfile.BALANCED,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application.applicationContext)
    private val scraper = AsbdavaniScraper()
    private val analyzer = GeminiAnalyzer()

    private val _uiState = MutableStateFlow(
        MainUiState(
            apiKey = repo.getApiKey(),
            selectedModel = repo.getModel(),
            riskProfile = repo.getRiskProfile(),
            history = repo.getHistory()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun updateUrlInput(url: String) {
        _uiState.update { it.copy(urlInput = url) }
    }

    fun updateRaceDataInput(text: String) {
        _uiState.update { it.copy(raceDataInput = text) }
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
                    _uiState.update {
                        it.copy(
                            isFetchingSite = false,
                            urlInput = page.url,
                            pageTitle = page.title,
                            raceDataInput = page.extractedText,
                            discoveredLinks = page.links,
                            needsWebViewHint = page.likelyNeedsWebView,
                            statusMessage = if (page.likelyNeedsWebView) {
                                "داده‌های این صفحه با جاوااسکریپت لود می‌شوند؛ از تب «سایت زنده» صفحه دور را باز کرده و استخراج کنید."
                            } else {
                                "اطلاعات صفحه دریافت شد (${page.tableCount} جدول و ${page.links.size} لینک)."
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isFetchingSite = false,
                            errorMessage = err.message ?: "خطا در ارتباط با سایت",
                            statusMessage = null
                        )
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
                pageTitle = title.ifBlank { "صفحه زنده سایت اسبدوانی" },
                raceDataInput = extractedText,
                needsWebViewHint = false,
                errorMessage = null,
                statusMessage = "اطلاعات صفحه زنده استخراج شد!"
            )
        }
        if (_uiState.value.apiKey.isNotBlank() && extractedText.isNotBlank()) {
            analyzeCurrentRace()
        } else if (_uiState.value.apiKey.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "اطلاعات استخراج شد؛ برای شروع تحلیل، کلید API جمنای را در تب تنظیمات وارد کنید.")
            }
        }
    }

    fun analyzeCurrentRace() {
        val state = _uiState.value
        if (state.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "ابتدا کلید هوش مصنوعی را در تب تنظیمات ثبت کنید.",
                    selectedTab = AppTab.SETTINGS
                )
            }
            return
        }
        if (state.raceDataInput.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "ابتدا اطلاعات مسابقه را از سایت دریافت یا وارد کنید.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    errorMessage = null,
                    statusMessage = "هوش مصنوعی در حال تحلیل آمار اسب‌ها، وزن، مسافت و چابکسوارهاست..."
                )
            }
            analyzer.analyzeRace(
                apiKey = state.apiKey,
                model = state.selectedModel,
                riskProfile = state.riskProfile,
                raceTitle = state.pageTitle,
                rawRaceData = state.raceDataInput
            ).fold(
                onSuccess = { report ->
                    val timeStr = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.US)
                        .format(Date(System.currentTimeMillis()))
                    val saved = SavedPrediction(
                        id = UUID.randomUUID().toString(),
                        timestamp = timeStr,
                        sourceUrl = state.urlInput,
                        rawExcerpt = state.raceDataInput.take(300),
                        report = report
                    )
                    repo.addHistory(saved)
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            currentReport = report,
                            history = repo.getHistory(),
                            statusMessage = "تحلیل و پیش‌بینی مسابقه آماده شد!"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            errorMessage = err.message ?: "خطا در تحلیل هوش مصنوعی",
                            statusMessage = null
                        )
                    }
                }
            )
        }
    }

    fun saveSettings(apiKey: String, model: String, riskProfile: RiskProfile) {
        repo.saveApiKey(apiKey)
        repo.saveModel(model)
        repo.saveRiskProfile(riskProfile)
        _uiState.update {
            it.copy(
                apiKey = repo.getApiKey(),
                selectedModel = repo.getModel(),
                riskProfile = repo.getRiskProfile(),
                statusMessage = "تنظیمات با موفقیت ذخیره شد.",
                errorMessage = null
            )
        }
    }

    fun testApiKey(apiKey: String, model: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingKey = true,
                    errorMessage = null,
                    statusMessage = "در حال تست اتصال به جمنای..."
                )
            }
            analyzer.testConnection(apiKey, model).fold(
                onSuccess = { msg ->
                    repo.saveApiKey(apiKey)
                    repo.saveModel(model)
                    _uiState.update {
                        it.copy(
                            isTestingKey = false,
                            apiKey = repo.getApiKey(),
                            selectedModel = repo.getModel(),
                            statusMessage = msg
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isTestingKey = false,
                            errorMessage = err.message ?: "تست اتصال ناموفق بود.",
                            statusMessage = null
                        )
                    }
                }
            )
        }
    }

    fun openHistoryItem(item: SavedPrediction) {
        _uiState.update {
            it.copy(
                currentReport = item.report,
                urlInput = item.sourceUrl,
                selectedTab = AppTab.PREDICT,
                statusMessage = "گزارش تاریخچه (${item.timestamp}) بارگذاری شد."
            )
        }
    }

    fun deleteHistoryItem(id: String) {
        repo.deleteHistory(id)
        _uiState.update {
            it.copy(history = repo.getHistory(), statusMessage = "از تاریخچه حذف شد.")
        }
    }
}
