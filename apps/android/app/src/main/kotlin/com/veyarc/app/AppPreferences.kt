package com.veyarc.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.veyarc.feature.assistant.DeepSeekEffort
import com.veyarc.feature.assistant.DeepSeekModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppUiPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val assistantModel: DeepSeekModel = DeepSeekModel.FLASH,
    val assistantEffort: DeepSeekEffort = DeepSeekEffort.OFF,
)

private val Context.uiPreferencesStore by preferencesDataStore(name = "veyarc_ui")

object AppPreferences {
    private val themeKey = stringPreferencesKey("theme")
    private val assistantModelKey = stringPreferencesKey("assistant_model")
    private val assistantEffortKey = stringPreferencesKey("assistant_effort")
    private val workflowFolderOrderKey = stringPreferencesKey("workflow_folder_order")
    private val runFolderOrderKey = stringPreferencesKey("run_folder_order")

    fun observe(context: Context): Flow<AppUiPreferences> = context.applicationContext.uiPreferencesStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            AppUiPreferences(
                theme = preferences[themeKey]
                    ?.let { saved -> ThemePreference.entries.firstOrNull { it.name == saved } }
                    ?: ThemePreference.SYSTEM,
                assistantModel = DeepSeekModel.entries.firstOrNull { it.name == preferences[assistantModelKey] } ?: DeepSeekModel.FLASH,
                assistantEffort = DeepSeekEffort.entries.firstOrNull { it.name == preferences[assistantEffortKey] } ?: DeepSeekEffort.OFF,
            )
        }

    suspend fun setTheme(context: Context, theme: ThemePreference) {
        context.applicationContext.uiPreferencesStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setAssistantModel(context: Context, model: DeepSeekModel) {
        context.applicationContext.uiPreferencesStore.edit { it[assistantModelKey] = model.name }
    }

    suspend fun setAssistantEffort(context: Context, effort: DeepSeekEffort) {
        context.applicationContext.uiPreferencesStore.edit { it[assistantEffortKey] = effort.name }
    }

    suspend fun workflowFolderOrder(context: Context): List<String> = folderOrder(context, workflowFolderOrderKey)

    suspend fun setWorkflowFolderOrder(context: Context, order: List<String>) {
        setFolderOrder(context, workflowFolderOrderKey, order)
    }

    suspend fun runFolderOrder(context: Context): List<String> = folderOrder(context, runFolderOrderKey)

    suspend fun setRunFolderOrder(context: Context, order: List<String>) {
        setFolderOrder(context, runFolderOrderKey, order)
    }

    private suspend fun folderOrder(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>): List<String> = context.applicationContext.uiPreferencesStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .first()[key]
        ?.lineSequence()
        ?.filter(String::isNotBlank)
        ?.toList()
        .orEmpty()

    private suspend fun setFolderOrder(
        context: Context,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        order: List<String>,
    ) {
        context.applicationContext.uiPreferencesStore.edit { preferences ->
            preferences[key] = order.joinToString("\n")
        }
    }
}
