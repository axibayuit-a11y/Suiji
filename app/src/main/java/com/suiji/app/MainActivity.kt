package com.suiji.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suiji.app.data.AppPreferences
import com.suiji.app.model.UiLanguage
import com.suiji.app.ui.SuijiApp
import com.suiji.app.ui.SuijiViewModel
import com.suiji.app.ui.theme.SuijiTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyUiLanguage(AppPreferences(this).readUiLanguage())
        enableEdgeToEdge()
        setContent {
            val viewModel: SuijiViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            SuijiTheme(themeMode = uiState.themeMode) {
                SuijiApp(
                    uiState = uiState,
                    viewModel = viewModel,
                    onUiLanguageSelected = ::applyUiLanguage
                )
            }
        }
    }

    private fun applyUiLanguage(language: UiLanguage) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.languageTag)
        )
    }
}
