package com.asbai.predictor.data

import android.content.Context
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("asb_davani_ai_prefs", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getModel(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL)?.trim().orEmpty()
        .ifEmpty { DEFAULT_MODEL }

    fun saveModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model.trim().ifEmpty { DEFAULT_MODEL }).apply()
    }

    fun getRiskProfile(): RiskProfile {
        val name = prefs.getString(KEY_RISK_PROFILE, RiskProfile.BALANCED.name)
        return runCatching { RiskProfile.valueOf(name ?: RiskProfile.BALANCED.name) }
            .getOrDefault(RiskProfile.BALANCED)
    }

    fun saveRiskProfile(profile: RiskProfile) {
        prefs.edit().putString(KEY_RISK_PROFILE, profile.name).apply()
    }

    fun getHistory(): List<SavedPrediction> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedPrediction>>(raw) }
            .getOrDefault(emptyList())
    }

    fun addHistory(item: SavedPrediction) {
        val updated = (listOf(item) + getHistory()).take(30)
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    fun deleteHistory(id: String) {
        val updated = getHistory().filterNot { it.id == id }
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_RISK_PROFILE = "risk_profile"
        private const val KEY_HISTORY = "prediction_history"
    }
}
