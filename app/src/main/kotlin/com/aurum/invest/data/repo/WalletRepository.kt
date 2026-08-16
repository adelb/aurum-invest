package com.aurum.invest.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aurum.invest.data.model.PortfolioSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.walletDataStore by preferencesDataStore(name = "aurum_wallet")

/**
 * The user's stated investing wallet: the one number they type in — "how much
 * money do I hold for this, total" — plus what the ledger already shows as
 * invested. Everything else (liquidity, accumulated P/L) is derived, never
 * asked for twice.
 *
 * total       = the number the user entered (deposits + whatever they still
 *               plan to invest); it does NOT move on its own as prices change.
 * invested    = sum of open positions' cost basis (from the ledger) — what of
 *               [total] is actually deployed into holdings right now.
 * liquidity   = total - invested, clamped at 0 — uninvested cash still sitting
 *               idle, available for the advisor engine to suggest deploying.
 * totalPl     = unrealized + realized P/L across the whole ledger — the
 *               running, accumulating number that reports must show as its
 *               OWN separate figure, never folded silently into [total].
 * netWorth    = total + totalPl — "what the wallet is worth today" including
 *               every gain/loss booked so far.
 */
data class WalletState(
    val configured: Boolean,
    val total: Double,
    val invested: Double,
    val liquidity: Double,
    val totalPl: Double,
    val netWorth: Double
) {
    companion object {
        val UNSET = WalletState(
            configured = false,
            total = 0.0,
            invested = 0.0,
            liquidity = 0.0,
            totalPl = 0.0,
            netWorth = 0.0
        )

        /** Derives the full wallet picture from the user's stated total and the ledger's own math. */
        fun of(total: Double, configured: Boolean, summary: PortfolioSummary?): WalletState {
            val invested = summary?.investedCost ?: 0.0
            val totalPl = summary?.totalPl ?: 0.0
            val liquidity = (total - invested).coerceAtLeast(0.0)
            return WalletState(
                configured = configured,
                total = total,
                invested = invested,
                liquidity = liquidity,
                totalPl = totalPl,
                netWorth = total + totalPl
            )
        }
    }
}

/**
 * Stores the single number the user provides — total wallet cash — and
 * derives invested / liquidity / accumulated P/L from the portfolio ledger.
 * Replaces manual brokerage-cash-event tracking: the user states the truth
 * once, the app reasons about it forever after.
 */
class WalletRepository(
    private val context: Context,
    private val portfolio: PortfolioRepository
) {
    private val keyConfigured = booleanPreferencesKey("wallet_configured")
    private val keyTotal = doublePreferencesKey("wallet_total")

    /** The raw user-entered total; 0.0 / not configured until they set it. */
    val total: Flow<Double> = context.walletDataStore.data.map { it[keyTotal] ?: 0.0 }

    val configured: Flow<Boolean> = context.walletDataStore.data.map { it[keyConfigured] ?: false }

    /** The full derived picture: total, invested, liquidity, accumulated P/L, net worth. */
    fun observeState(summaryFlow: Flow<PortfolioSummary?>): Flow<WalletState> =
        combine(total, configured, summaryFlow) { t, c, s -> WalletState.of(t, c, s) }

    suspend fun setTotal(amount: Double) {
        context.walletDataStore.edit {
            it[keyTotal] = amount.coerceAtLeast(0.0)
            it[keyConfigured] = true
        }
    }

    /** Adds to (or subtracts from, with a negative delta) the stated wallet total — e.g. a top-up. */
    suspend fun adjustTotal(delta: Double) {
        context.walletDataStore.edit {
            val current = it[keyTotal] ?: 0.0
            it[keyTotal] = (current + delta).coerceAtLeast(0.0)
            it[keyConfigured] = true
        }
    }

    suspend fun clear() {
        context.walletDataStore.edit {
            it[keyTotal] = 0.0
            it[keyConfigured] = false
        }
    }
}
