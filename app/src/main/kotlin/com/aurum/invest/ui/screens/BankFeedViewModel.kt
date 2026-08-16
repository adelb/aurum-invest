package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.bank.BankNotificationListener
import com.aurum.invest.data.model.BankEvent
import com.aurum.invest.data.model.TradeSide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Bank Feed screen. */
data class BankFeedState(
    val listenerEnabled: Boolean = true,
    val pending: List<BankEvent> = emptyList(),
    val history: List<BankEvent> = emptyList()
)

class BankFeedViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val listenerEnabled =
        MutableStateFlow(BankNotificationListener.isEnabled(app))

    val state: StateFlow<BankFeedState> =
        combine(container.bankFeed.observeEvents(), listenerEnabled) { events, enabled ->
            BankFeedState(
                listenerEnabled = enabled,
                pending = events.filter { it.status == BankEvent.STATUS_PENDING },
                history = events.filter { it.status != BankEvent.STATUS_PENDING }
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BankFeedState(listenerEnabled = listenerEnabled.value)
        )

    /** Re-checks notification-listener access; call on every lifecycle resume. */
    fun refreshListenerState() {
        listenerEnabled.value = BankNotificationListener.isEnabled(getApplication())
    }

    /**
     * [price] is in [currency]; a non-USD import must carry the [fxRate] used
     * so the stored USD price stays auditable.
     */
    fun importEvent(
        eventId: Long,
        symbol: String,
        side: TradeSide,
        shares: Double,
        price: Double,
        currency: String = "USD",
        fxRate: Double = 1.0
    ) {
        viewModelScope.launch {
            try {
                val usdPrice = if (currency == "USD") price else price * fxRate
                container.bankFeed.importEvent(
                    eventId, symbol.trim().uppercase(), side, shares, usdPrice,
                    currency = currency, fxRate = fxRate
                )
            } catch (_: Exception) {
                // repo never throws by contract; belt and braces
            }
        }
    }

    fun dismissEvent(eventId: Long) {
        viewModelScope.launch {
            try {
                container.bankFeed.dismissEvent(eventId)
            } catch (_: Exception) {
            }
        }
    }
}
