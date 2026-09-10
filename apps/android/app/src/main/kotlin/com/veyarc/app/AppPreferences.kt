package com.veyarc.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppUiPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val reducedDensity: Boolean = false,
)

private val Context.uiPreferencesStore by preferencesDataStore(name = "veyarc_ui")

object AppPreferences {
    private val themeKey = stringPreferencesKey("theme")
    private val reducedDensityKey = booleanPreferencesKey("reduced_density")

    fun observe(context: Context): Flow<AppUiPreferences> = context.applicationContext.uiPreferencesStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            AppUiPreferences(
                theme = preferences[themeKey]
                    ?.let { saved -> ThemePreference.entries.firstOrNull { it.name == saved } }
                    ?: ThemePreference.SYSTEM,
                reducedDensity = preferences[reducedDensityKey] ?: false,
            )
        }

    suspend fun setTheme(context: Context, theme: ThemePreference) {
        context.applicationContext.uiPreferencesStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setReducedDensity(context: Context, reduced: Boolean) {
        context.applicationContext.uiPreferencesStore.edit { it[reducedDensityKey] = reduced }
    }
}
