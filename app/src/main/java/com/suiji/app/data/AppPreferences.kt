package com.suiji.app.data

import android.content.Context
import com.suiji.app.model.ThemeMode
import com.suiji.app.model.UiLanguage
import com.suiji.app.model.AiServiceConfig
import com.suiji.app.model.LocalModelId
import com.suiji.app.model.LsEendModelId
import com.suiji.app.model.TranscriptionMode

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("suiji_preferences", Context.MODE_PRIVATE)
    private val secureValueStore = SecureValueStore()

    fun readUiLanguage(): UiLanguage = enumValueOrDefault(
        preferences.getString(KEY_UI_LANGUAGE, null),
        UiLanguage.SIMPLIFIED_CHINESE
    )

    fun writeUiLanguage(language: UiLanguage) {
        preferences.edit().putString(KEY_UI_LANGUAGE, language.name).apply()
    }

    fun readThemeMode(): ThemeMode = enumValueOrDefault(
        preferences.getString(KEY_THEME_MODE, null),
        ThemeMode.SYSTEM
    )

    fun writeThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun readAiServiceConfig(): AiServiceConfig = AiServiceConfig(
        enabled = preferences.getBoolean(KEY_AI_ENABLED, false),
        baseUrl = preferences.getString(KEY_AI_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
        apiKey = secureValueStore.decrypt(
            preferences.getString(KEY_AI_API_KEY, "").orEmpty()
        ),
        model = preferences.getString(KEY_AI_MODEL, "").orEmpty()
    )

    fun writeAiServiceConfig(config: AiServiceConfig) {
        preferences.edit()
            .putBoolean(KEY_AI_ENABLED, config.enabled)
            .putString(KEY_AI_BASE_URL, config.baseUrl.trim())
            .putString(KEY_AI_API_KEY, secureValueStore.encrypt(config.apiKey.trim()))
            .putString(KEY_AI_MODEL, config.model.trim())
            .apply()
    }

    fun readTranscriptionMode(): TranscriptionMode = enumValueOrDefault(
        preferences.getString(KEY_TRANSCRIPTION_MODE, null),
        TranscriptionMode.OFF
    )

    fun writeTranscriptionMode(mode: TranscriptionMode) {
        preferences.edit().putString(KEY_TRANSCRIPTION_MODE, mode.name).apply()
    }

    fun readSelectedLocalModel(): LocalModelId = enumValueOrDefault(
        preferences.getString(KEY_SELECTED_LOCAL_MODEL, null),
        LocalModelId.SENSEVOICE_GENERAL
    )

    fun writeSelectedLocalModel(id: LocalModelId) {
        preferences.edit().putString(KEY_SELECTED_LOCAL_MODEL, id.name).apply()
    }

    fun readSpeakerDiarizationEnabled(): Boolean =
        preferences.getBoolean(KEY_SPEAKER_DIARIZATION_ENABLED, false)

    fun writeSpeakerDiarizationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SPEAKER_DIARIZATION_ENABLED, enabled).apply()
    }

    fun readSelectedSpeakerModel(): LsEendModelId = enumValueOrDefault(
        preferences.getString(KEY_SELECTED_SPEAKER_MODEL, null),
        LsEendModelId.GENERIC_1_8
    )

    fun writeSelectedSpeakerModel(id: LsEendModelId) {
        preferences.edit().putString(KEY_SELECTED_SPEAKER_MODEL, id.name).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AI_ENABLED = "ai_service_enabled"
        const val KEY_AI_BASE_URL = "ai_service_base_url"
        const val KEY_AI_API_KEY = "ai_service_api_key"
        const val KEY_AI_MODEL = "ai_service_model"
        const val KEY_SPEAKER_DIARIZATION_ENABLED = "speaker_diarization_enabled"
        const val KEY_SELECTED_SPEAKER_MODEL = "selected_speaker_model"
        const val KEY_TRANSCRIPTION_MODE = "transcription_mode"
        const val KEY_SELECTED_LOCAL_MODEL = "selected_local_model"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
