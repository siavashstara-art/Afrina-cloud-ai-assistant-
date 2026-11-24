package com.example.afrina.core.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.afrina.core.domain.PreferencesRepository
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ThemePreference
import com.example.afrina.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.afrinaDataStore: DataStore<Preferences> by preferencesDataStore(name = "afrina_preferences")

class DataStorePreferencesRepository(private val context: Context) : PreferencesRepository {

    override fun observePreferences(): Flow<UserPreferences> =
        context.afrinaDataStore.data.map { prefs ->
            val lang = prefs[KEY_LANGUAGE]?.let { code ->
                AppLanguage.entries.firstOrNull { it.code == code }
            } ?: AppLanguage.PERSIAN
            val theme = prefs[KEY_THEME]?.let { name ->
                runCatching { ThemePreference.valueOf(name) }.getOrNull()
            } ?: ThemePreference.DARK
            val modelId = prefs[KEY_AI_MODEL] ?: "provider-agnostic/architect-v1"
            UserPreferences(
                language = lang,
                theme = theme,
                selectedAiModelId = modelId,
            )
        }

    override suspend fun setLanguage(language: AppLanguage) {
        context.afrinaDataStore.edit { it[KEY_LANGUAGE] = language.code }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        context.afrinaDataStore.edit { it[KEY_THEME] = theme.name }
    }

    override suspend fun setSelectedAiModel(modelId: String) {
        context.afrinaDataStore.edit { it[KEY_AI_MODEL] = modelId }
    }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_THEME = stringPreferencesKey("theme_preference")
        val KEY_AI_MODEL = stringPreferencesKey("selected_ai_model")
    }
}
