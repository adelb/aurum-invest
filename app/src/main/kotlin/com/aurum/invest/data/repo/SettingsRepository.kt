package com.aurum.invest.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aurum_settings")

/**
 * App settings. [bankPackages] is a comma-separated list of package-name
 * fragments; a notification is captured when its package contains any of them.
 */
class SettingsRepository(private val context: Context) {

    private val keyBankPackages = stringPreferencesKey("bank_packages")
    private val keyAutoImport = booleanPreferencesKey("auto_import")

    companion object {
        const val DEFAULT_BANK_PACKAGES = "etihad,aletihad"
    }

    val bankPackages: Flow<String> = context.dataStore.data
        .map { it[keyBankPackages] ?: DEFAULT_BANK_PACKAGES }

    val autoImport: Flow<Boolean> = context.dataStore.data
        .map { it[keyAutoImport] ?: false }

    suspend fun setBankPackages(value: String) {
        context.dataStore.edit { it[keyBankPackages] = value }
    }

    suspend fun setAutoImport(value: Boolean) {
        context.dataStore.edit { it[keyAutoImport] = value }
    }

    // ---- Wealth section inputs (0.0 = not configured yet) -------------------

    private val keyWealthBase = doublePreferencesKey("wealth_base")
    private val keyWealthTarget = doublePreferencesKey("wealth_target")

    val wealthBase: Flow<Double> = context.dataStore.data.map { it[keyWealthBase] ?: 0.0 }

    val wealthTarget: Flow<Double> = context.dataStore.data.map { it[keyWealthTarget] ?: 0.0 }

    suspend fun setWealthInputs(base: Double, target: Double) {
        context.dataStore.edit {
            it[keyWealthBase] = base
            it[keyWealthTarget] = target
        }
    }

    // ---- Language (UI locale) -----------------------------------------------

    private val keyLanguage = stringPreferencesKey("language")

    /** "en" (default) or "ar". Consumed by ProvideAppLocale in the UI tree. */
    val language: Flow<String> = context.dataStore.data.map { it[keyLanguage] ?: "en" }

    suspend fun setLanguage(value: String) {
        context.dataStore.edit { it[keyLanguage] = value }
    }
}
