package com.asbai.predictor.data

import android.content.Context
import com.asbai.predictor.model.ModelWeights
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("asb_davani_quant_prefs", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun getWeights(): ModelWeights {
        val raw = prefs.getString(KEY_WEIGHTS, null) ?: return ModelWeights()
        return runCatching { json.decodeFromString<ModelWeights>(raw) }.getOrDefault(ModelWeights())
    }

    fun saveWeights(weights: ModelWeights) {
        prefs.edit().putString(KEY_WEIGHTS, json.encodeToString(weights)).apply()
    }

    fun resetWeights(): ModelWeights {
        val def = ModelWeights()
        saveWeights(def)
        return def
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
        val updated = (listOf(item) + getHistory()).take(50)
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    fun updateHistoryItem(updatedItem: SavedPrediction) {
        val list = getHistory().map { if (it.id == updatedItem.id) updatedItem else it }
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(list)).apply()
    }

    fun deleteHistory(id: String) {
        val updated = getHistory().filterNot { it.id == id }
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    companion object {
        private const val KEY_WEIGHTS = "quant_model_weights"
        private const val KEY_RISK_PROFILE = "risk_profile"
        private const val KEY_HISTORY = "quant_prediction_history"
    }
}
