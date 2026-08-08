package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.bank.BankNotificationListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for Settings. [bankPackages] stays null until the first DataStore
 * emission so the screen can seed its local text field exactly once.
 */
data class SettingsState(
    val bankPackages: String? = null,
    val autoImport: Boolean = false,
    val listenerEnabled: Boolean = true
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val listenerEnabled =
        MutableStateFlow(BankNotificationListener.isEnabled(app))

    private var saveJob: Job? = null

    val state: StateFlow<SettingsState> = combine(
        container.settings.bankPackages,
        container.settings.autoImport,
        listenerEnabled
    ) { pkgs, auto, enabled ->
        SettingsState(bankPackages = pkgs, autoImport = auto, listenerEnabled = enabled)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsState(listenerEnabled = listenerEnabled.value)
    )

    /** Re-checks notification-listener access; call on every lifecycle resume. */
    fun refreshListenerState() {
        listenerEnabled.value = BankNotificationListener.isEnabled(getApplication())
    }

    /** Persists the comma-separated package fragments, debounced 500 ms. */
    fun saveBankPackages(value: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            try {
                container.settings.setBankPackages(value)
            } catch (_: Exception) {
            }
        }
    }

    fun setAutoImport(value: Boolean) {
        viewModelScope.launch {
            try {
                container.settings.setAutoImport(value)
            } catch (_: Exception) {
            }
        }
    }
}
