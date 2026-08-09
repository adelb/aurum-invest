package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.WealthPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WealthState(
    val loading: Boolean = true,
    val computing: Boolean = false,
    /** Null until the user provides the base amount + 4-month target. */
    val baseAmount: Double? = null,
    val targetProfit: Double? = null,
    val plan: WealthPlan? = null,
    /** True while the setup form is showing (first run or user editing). */
    val editing: Boolean = false
)

class WealthViewModel(app: Application) : AndroidViewModel(app) {

    private val wealth = (app as AurumApp).container.wealth

    private val _state = MutableStateFlow(WealthState())
    val state: StateFlow<WealthState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val inputs = wealth.getInputs()
            if (inputs == null) {
                _state.update { it.copy(loading = false, editing = true) }
                return@launch
            }
            _state.update {
                it.copy(baseAmount = inputs.first, targetProfit = inputs.second)
            }
            // Serve the stored plan instantly, then freshen if the week rolled.
            val stored = wealth.getPlan()
            if (stored != null) {
                _state.update { it.copy(loading = false, plan = stored) }
            }
            val fresh = wealth.ensurePlan()
            _state.update { it.copy(loading = false, plan = fresh ?: stored) }
        }
    }

    /** Saves the inputs and builds the first plan (or rebuilds after an edit). */
    fun save(base: Double, target: Double) {
        if (base <= 0.0 || target <= 0.0 || _state.value.computing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    computing = true,
                    editing = false,
                    baseAmount = base,
                    targetProfit = target
                )
            }
            wealth.setInputs(base, target)
            val plan = wealth.recompute()
            _state.update { it.copy(computing = false, loading = false, plan = plan) }
        }
    }

    /** Re-runs this week's full market scan. */
    fun refresh() {
        if (_state.value.computing) return
        viewModelScope.launch {
            _state.update { it.copy(computing = true) }
            val plan = wealth.recompute()
            _state.update { it.copy(computing = false, plan = plan ?: it.plan) }
        }
    }

    fun startEditing() {
        _state.update { it.copy(editing = true) }
    }

    fun cancelEditing() {
        if (_state.value.plan != null) {
            _state.update { it.copy(editing = false) }
        }
    }
}
