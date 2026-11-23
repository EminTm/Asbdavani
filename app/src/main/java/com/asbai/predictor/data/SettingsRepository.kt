package com.asbai.predictor.data

import android.content.Context
import com.asbai.predictor.model.RiskProfile
import com.asbai.predictor.model.SavedPrediction
import com.asbai.predictor.model.ThirdProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("asb_davani_ai_prefs", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ۱. Gemini
    fun isGeminiEnabled(): Boolean = prefs.getBoolean(KEY_GEMINI_ENABLED, true)
    fun setGeminiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GEMINI_ENABLED, enabled).apply()
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

    // ۲. Groq
    fun isGroqEnabled(): Boolean = prefs.getBoolean(KEY_GROQ_ENABLED, true)
    fun setGroqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GROQ_ENABLED, enabled).apply()
    }

    fun getGroqApiKey(): String = prefs.getString(KEY_GROQ_API_KEY, "")?.trim().orEmpty()
    fun saveGroqApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GROQ_API_KEY, apiKey.trim()).apply()
    }

    fun getGroqModel(): String = prefs.getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL)?.trim().orEmpty()
        .ifEmpty { DEFAULT_GROQ_MODEL }
    fun saveGroqModel(model: String) {
        prefs.edit().putString(KEY_GROQ_MODEL, model.trim().ifEmpty { DEFAULT_GROQ_MODEL }).apply()
    }

    // ۳. عضو سوم (Cohere / Z.ai / OpenRouter)
    fun isThirdEnabled(): Boolean = prefs.getBoolean(KEY_THIRD_ENABLED, true)
    fun setThirdEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_THIRD_ENABLED, enabled).apply()
    }

    fun getThirdProvider(): ThirdProvider {
        val name = prefs.getString(KEY_THIRD_PROVIDER, ThirdProvider.COHERE.name)
        return runCatching { ThirdProvider.valueOf(name ?: ThirdProvider.COHERE.name) }
            .getOrDefault(ThirdProvider.COHERE)
    }

    fun saveThirdProvider(provider: ThirdProvider) {
        prefs.edit().putString(KEY_THIRD_PROVIDER, provider.name).apply()
    }

    fun getThirdApiKey(): String = prefs.getString(KEY_THIRD_API_KEY, "")?.trim().orEmpty()
    fun saveThirdApiKey(apiKey: String) {
        prefs.edit().putString(KEY_THIRD_API_KEY, apiKey.trim()).apply()
    }

    fun getThirdModel(): String {
        val fallback = getThirdProvider().defaultModel
        return prefs.getString(KEY_THIRD_MODEL, fallback)?.trim().orEmpty().ifEmpty { fallback }
    }

    fun saveThirdModel(model: String) {
        val fallback = getThirdProvider().defaultModel
        prefs.edit().putString(KEY_THIRD_MODEL, model.trim().ifEmpty { fallback }).apply()
    }

    // ۴. Cloudflare Workers AI
    fun isCfEnabled(): Boolean = prefs.getBoolean(KEY_CF_ENABLED, true)
    fun setCfEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CF_ENABLED, enabled).apply()
    }

    fun getCfAccountId(): String = prefs.getString(KEY_CF_ACCOUNT_ID, "")?.trim().orEmpty()
    fun saveCfAccountId(accountId: String) {
        prefs.edit().putString(KEY_CF_ACCOUNT_ID, accountId.trim()).apply()
    }

    fun getCfApiToken(): String = prefs.getString(KEY_CF_API_TOKEN, "")?.trim().orEmpty()
    fun saveCfApiToken(token: String) {
        prefs.edit().putString(KEY_CF_API_TOKEN, token.trim()).apply()
    }

    fun getCfModel(): String = prefs.getString(KEY_CF_MODEL, DEFAULT_CF_MODEL)?.trim().orEmpty()
        .ifEmpty { DEFAULT_CF_MODEL }
    fun saveCfModel(model: String) {
        prefs.edit().putString(KEY_CF_MODEL, model.trim().ifEmpty { DEFAULT_CF_MODEL }).apply()
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
        const val DEFAULT_GROQ_MODEL = "qwen/qwen3.8-27b"
        const val DEFAULT_CF_MODEL = "@cf/deepseek-ai/deepseek-v4.1-flash-1017"

        private const val KEY_GEMINI_ENABLED = "gemini_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_GROQ_ENABLED = "groq_enabled"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_THIRD_ENABLED = "third_enabled"
        private const val KEY_THIRD_PROVIDER = "third_provider"
        private const val KEY_THIRD_API_KEY = "third_api_key"
        private const val KEY_THIRD_MODEL = "third_model"
        private const val KEY_CF_ENABLED = "cf_enabled"
        private const val KEY_CF_ACCOUNT_ID = "cf_account_id"
        private const val KEY_CF_API_TOKEN = "cf_api_token"
        private const val KEY_CF_MODEL = "cf_model"
        private const val KEY_RISK_PROFILE = "risk_profile"
        private const val KEY_HISTORY = "prediction_history"
    }
}
