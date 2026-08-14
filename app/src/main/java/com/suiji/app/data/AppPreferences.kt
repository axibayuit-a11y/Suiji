package com.suiji.app.data

import android.content.Context
import com.suiji.app.model.ThemeMode
import com.suiji.app.model.UiLanguage
import com.suiji.app.model.CloudTranscriptionConfig
import com.suiji.app.model.LocalModelId
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

    fun readCloudTranscriptionConfig(): CloudTranscriptionConfig = CloudTranscriptionConfig(
        enabled = preferences.getBoolean(KEY_CLOUD_ENABLED, false),
        baseUrl = preferences.getString(KEY_CLOUD_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
        apiKey = secureValueStore.decrypt(
            preferences.getString(KEY_CLOUD_API_KEY, "").orEmpty()
        ),
        model = preferences.getString(KEY_CLOUD_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
        speakerDiarization = preferences.getBoolean(KEY_SPEAKER_DIARIZATION, false)
    )

    fun writeCloudTranscriptionConfig(config: CloudTranscriptionConfig) {
        preferences.edit()
            .putBoolean(KEY_CLOUD_ENABLED, config.enabled)
            .putString(KEY_CLOUD_BASE_URL, config.baseUrl.trim())
            .putString(KEY_CLOUD_API_KEY, secureValueStore.encrypt(config.apiKey.trim()))
            .putString(KEY_CLOUD_MODEL, config.model.trim())
            .putBoolean(KEY_SPEAKER_DIARIZATION, config.speakerDiarization)
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

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CLOUD_ENABLED = "cloud_transcription_enabled"
        const val KEY_CLOUD_BASE_URL = "cloud_transcription_base_url"
        const val KEY_CLOUD_API_KEY = "cloud_transcription_api_key"
        const val KEY_CLOUD_MODEL = "cloud_transcription_model"
        const val KEY_SPEAKER_DIARIZATION = "cloud_speaker_diarization"
        const val KEY_TRANSCRIPTION_MODE = "transcription_mode"
        const val KEY_SELECTED_LOCAL_MODEL = "selected_local_model"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-transcribe"
    }
}
