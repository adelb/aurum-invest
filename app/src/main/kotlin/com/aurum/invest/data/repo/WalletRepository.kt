package com.aurum.invest.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aurum.invest.data.model.PortfolioSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.walletDataStore by preferencesDataStore(name = "aurum_wallet")

/**
 * The user's stated investing wallet: the one number they type in — "how much
 * money did I put in for this, total" — plus what the ledger already shows as
 * invested and as booked P/L. Everything else is derived, never asked twice.
 *
 * The whole model is one cash identity, replayed from the ledger:
 *
 *     liquidity = total − Σ buy cost + Σ sell proceeds
 *               = total − invested + realizedPl
 *
 * because a sell hands back the cost basis of the shares sold AND the profit
 * (or loss) booked on them. Deriving liquidity as `total − invested` alone —
 * the pre-v6.4 formula — returned only the cost basis and silently swallowed
 * every realized gain: sell AAPL at +$400 and the cash never appeared.
 *
 * total        = the number the user entered: the capital they committed
 *                (deposits). It does NOT move on its own — not with prices,
 *                and not with profits, which are tracked as P/L instead.
 * invested     = cost basis of open positions (from the ledger) — what of the
 *                capital is actually deployed into holdings right now.
 * realizedPl   = P/L booked by sells so far. Already inside [liquidity]:
 *                that is exactly what "the sell updated my cash" means.
 * unrealizedPl = mark-to-market on what is still held.
 * totalPl      = realized + unrealized — the accumulating number reports show
 *                as its OWN figure, never folded silently into [total].
 * holdingsValue= live market value of the open positions.
 * netWorth     = liquidity + holdingsValue, which is identically
 *                total + totalPl — the two readings of the wallet always agree.
 */
data class WalletState(
    val configured: Boolean,
    val total: Double,
    val invested: Double,
    val liquidity: Double,
    val realizedPl: Double,
    val unrealizedPl: Double,
    val totalPl: Double,
    val holdingsValue: Double,
    val netWorth: Double
) {
    /**
     * True when the ledger has more deployed than the stated wallet can
     * account for, so [liquidity] is negative. It is reported honestly rather
     * than floored at zero: a fabricated $0 would hide a wallet total that is
     * simply out of date (a deposit the user has not recorded yet).
     */
    val shortfall: Boolean get() = liquidity < -0.005

    /** What the deployment engines may actually spend: never below zero. */
    val deployable: Double get() = liquidity.coerceAtLeast(0.0)

    companion object {
        val UNSET = WalletState(
            configured = false,
            total = 0.0,
            invested = 0.0,
            liquidity = 0.0,
            realizedPl = 0.0,
            unrealizedPl = 0.0,
            totalPl = 0.0,
            holdingsValue = 0.0,
            netWorth = 0.0
        )

        /**
         * THE cash identity, in one place. Every screen that needs "how much
         * uninvested money is there" calls this, so the dashboard, the reports
         * card and the liquidity-deployment plan can never disagree.
         *
         * Not clamped — see [shortfall]; callers spending the number use
         * [deployable] (or the engines' own floor) instead of hiding it here.
         */
        fun liquidityOf(total: Double, invested: Double, realizedPl: Double): Double =
            total - invested + realizedPl

        /** Derives the full wallet picture from the user's stated total and the ledger's own math. */
        fun of(total: Double, configured: Boolean, summary: PortfolioSummary?): WalletState = of(
            total = total,
            configured = configured,
            invested = summary?.investedCost ?: 0.0,
            realizedPl = summary?.realizedPl ?: 0.0,
            unrealizedPl = summary?.unrealizedPl ?: 0.0,
            holdingsValue = summary?.marketValue ?: 0.0
        )

        fun of(
            total: Double,
            configured: Boolean,
            invested: Double,
            realizedPl: Double,
            unrealizedPl: Double,
            holdingsValue: Double
        ): WalletState {
            val liquidity = liquidityOf(total, invested, realizedPl)
            return WalletState(
                configured = configured,
                total = total,
                invested = invested,
                liquidity = liquidity,
                realizedPl = realizedPl,
                unrealizedPl = unrealizedPl,
                totalPl = realizedPl + unrealizedPl,
                holdingsValue = holdingsValue,
                netWorth = liquidity + holdingsValue
            )
        }
    }
}

/**
 * Stores the single number the user provides — the capital they committed —
 * and derives invested / liquidity / accumulated P/L from the portfolio
 * ledger. Replaces manual brokerage-cash-event tracking: the user states the
 * truth once, the app reasons about it forever after.
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

    /**
     * Liquidity straight off the ledger, for callers with no live summary at
     * hand (the Wealth tab's deployment plan, the buy-plan sizer). Cost basis
     * and realized P/L are both replayed from the same positions list the
     * dashboard uses, so every screen quotes the same cash. Realized P/L is
     * summed over ALL positions — a fully-closed winner is gone from the book
     * but its proceeds are still sitting in cash.
     *
     * Null until the user states a total: with no capital base, a run of
     * profitable sells would otherwise read as free money to deploy. An
     * unknown wallet must stay unknown, not become a number.
     */
    suspend fun liquidityNow(): Double? {
        if (!configured.first()) return null
        val all = portfolio.positionsNow()
        val invested = all.filter { PortfolioRepository.isOpen(it) }.sumOf { it.investedCost }
        val realized = all.sumOf { it.realizedPl }
        return WalletState.liquidityOf(total.first(), invested, realized)
    }

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
