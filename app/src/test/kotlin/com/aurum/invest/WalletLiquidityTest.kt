package com.aurum.invest

import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.WalletState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallet cash identity: liquidity is tied to the ledger, so a sell moves
 * cash by its FULL proceeds — the cost basis returned plus the P/L booked —
 * and net worth always reconciles two ways.
 *
 *     liquidity = total − invested + realizedPl
 *     netWorth  = liquidity + holdings = total + totalPl
 */
class WalletLiquidityTest {

    private var id = 0L

    private fun tx(symbol: String, side: String, shares: Double, price: Double, fees: Double = 0.0) =
        TransactionEntity(
            id = ++id, symbol = symbol, side = side, shares = shares,
            price = price, fees = fees, ts = id * 1000L
        )

    private fun quote(symbol: String, price: Double, prevClose: Double = price) =
        Quote(symbol = symbol, price = price, prevClose = prevClose)

    /** Replays a ledger the way the dashboard does and derives the wallet from it. */
    private fun wallet(
        total: Double,
        ledger: List<TransactionEntity>,
        prices: Map<String, Double> = emptyMap()
    ): WalletState {
        val all = PortfolioRepository.computePositions(ledger)
        val open = all.filter { PortfolioRepository.isOpen(it) }
        val views = open.map { p ->
            PortfolioRepository.toView(p, prices[p.symbol]?.let { quote(p.symbol, it) })
        }
        val summary = PortfolioRepository.summarize(views, all)
        return WalletState.of(total, configured = true, summary = summary)
    }

    // ---------------------------------------------------------------- buying

    @Test
    fun `a buy moves money from liquidity into invested and books no P-L`() {
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(tx("AAPL", TxSide.BUY, 10.0, 200.0)),
            prices = mapOf("AAPL" to 200.0)
        )
        assertEquals(2_000.0, w.invested, 1e-9)
        assertEquals(8_000.0, w.liquidity, 1e-9)
        assertEquals(0.0, w.realizedPl, 1e-9)
        assertEquals(10_000.0, w.netWorth, 1e-9)
    }

    @Test
    fun `buy fees leave the wallet with the cost basis`() {
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(tx("AAPL", TxSide.BUY, 10.0, 200.0, fees = 15.0)),
            prices = mapOf("AAPL" to 200.0)
        )
        assertEquals(2_015.0, w.invested, 1e-9)
        assertEquals(7_985.0, w.liquidity, 1e-9)
    }

    // --------------------------------------------------------------- selling

    @Test
    fun `a profitable sell returns cost basis AND the profit to liquidity`() {
        // Buy 10 @ $200 = $2,000. Sell 4 @ $250 -> proceeds $1,000,
        // of which $800 is basis returned and $200 is realized profit.
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(
                tx("AAPL", TxSide.BUY, 10.0, 200.0),
                tx("AAPL", TxSide.SELL, 4.0, 250.0)
            ),
            prices = mapOf("AAPL" to 250.0)
        )
        assertEquals(200.0, w.realizedPl, 1e-9)
        assertEquals(1_200.0, w.invested, 1e-9)        // 6 shares still at $200
        // 10,000 − 2,000 spent + 1,000 proceeds — the profit is IN the cash.
        assertEquals(9_000.0, w.liquidity, 1e-9)
    }

    @Test
    fun `a losing sell takes the loss out of liquidity`() {
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(
                tx("AAPL", TxSide.BUY, 10.0, 200.0),
                tx("AAPL", TxSide.SELL, 4.0, 150.0)
            ),
            prices = mapOf("AAPL" to 150.0)
        )
        assertEquals(-200.0, w.realizedPl, 1e-9)
        assertEquals(8_600.0, w.liquidity, 1e-9)       // 10,000 − 2,000 + 600
    }

    @Test
    fun `sell fees come out of the proceeds that reach liquidity`() {
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(
                tx("AAPL", TxSide.BUY, 10.0, 200.0),
                tx("AAPL", TxSide.SELL, 4.0, 250.0, fees = 5.0)
            ),
            prices = mapOf("AAPL" to 250.0)
        )
        assertEquals(195.0, w.realizedPl, 1e-9)
        assertEquals(8_995.0, w.liquidity, 1e-9)
    }

    @Test
    fun `closing a position entirely leaves nothing invested and all cash back with profit`() {
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(
                tx("AAPL", TxSide.BUY, 10.0, 200.0),
                tx("AAPL", TxSide.SELL, 10.0, 260.0)
            )
        )
        assertEquals(0.0, w.invested, 1e-9)
        assertEquals(600.0, w.realizedPl, 1e-9)
        assertEquals(10_600.0, w.liquidity, 1e-9)
        assertEquals(10_600.0, w.netWorth, 1e-9)
    }

    @Test
    fun `a closed winner still funds liquidity once it is gone from the book`() {
        // The realized P/L of a CLOSED position must survive into the wallet:
        // summing only open positions would lose the $600 in cash below.
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(
                tx("AAPL", TxSide.BUY, 10.0, 200.0),
                tx("AAPL", TxSide.SELL, 10.0, 260.0),
                tx("MSFT", TxSide.BUY, 5.0, 400.0)
            ),
            prices = mapOf("MSFT" to 400.0)
        )
        assertEquals(2_000.0, w.invested, 1e-9)
        assertEquals(600.0, w.realizedPl, 1e-9)
        assertEquals(8_600.0, w.liquidity, 1e-9)       // 10,000 + 600 − 2,000
    }

    @Test
    fun `a pinned broker outcome drives liquidity too`() {
        val pinned = TransactionEntity(
            id = 99, symbol = "AAPL", side = TxSide.SELL, shares = 4.0,
            price = 250.0, ts = 99_000L, plOverride = 150.0
        )
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(tx("AAPL", TxSide.BUY, 10.0, 200.0), pinned),
            prices = mapOf("AAPL" to 250.0)
        )
        assertEquals(150.0, w.realizedPl, 1e-9)
        assertEquals(8_950.0, w.liquidity, 1e-9)       // 10,000 − 1,200 held + 150 pinned
    }

    // ------------------------------------------------------------- identity

    @Test
    fun `net worth reconciles both ways after buys and sells`() {
        val w = wallet(
            total = 25_000.0,
            ledger = listOf(
                tx("AAPL", TxSide.BUY, 20.0, 200.0),
                tx("MSFT", TxSide.BUY, 10.0, 400.0),
                tx("AAPL", TxSide.SELL, 8.0, 240.0, fees = 3.0)
            ),
            prices = mapOf("AAPL" to 230.0, "MSFT" to 380.0)
        )
        // Two independent readings of the same wallet must agree.
        assertEquals(w.liquidity + w.holdingsValue, w.netWorth, 1e-9)
        assertEquals(w.total + w.totalPl, w.netWorth, 1e-9)
        assertEquals(w.realizedPl + w.unrealizedPl, w.totalPl, 1e-9)
    }

    @Test
    fun `unrealized moves net worth but never liquidity`() {
        val ledger = listOf(tx("AAPL", TxSide.BUY, 10.0, 200.0))
        val flat = wallet(10_000.0, ledger, mapOf("AAPL" to 200.0))
        val up = wallet(10_000.0, ledger, mapOf("AAPL" to 300.0))

        // A price move is not cash: liquidity and invested are untouched.
        assertEquals(flat.liquidity, up.liquidity, 1e-9)
        assertEquals(flat.invested, up.invested, 1e-9)
        assertEquals(1_000.0, up.unrealizedPl, 1e-9)
        assertEquals(11_000.0, up.netWorth, 1e-9)
    }

    @Test
    fun `a wallet top-up raises liquidity and leaves P-L alone`() {
        val ledger = listOf(
            tx("AAPL", TxSide.BUY, 10.0, 200.0),
            tx("AAPL", TxSide.SELL, 5.0, 240.0)
        )
        val before = wallet(10_000.0, ledger, mapOf("AAPL" to 240.0))
        val after = wallet(12_000.0, ledger, mapOf("AAPL" to 240.0))

        assertEquals(before.liquidity + 2_000.0, after.liquidity, 1e-9)
        assertEquals(before.realizedPl, after.realizedPl, 1e-9)
        assertEquals(before.totalPl, after.totalPl, 1e-9)
    }

    // ------------------------------------------------------------- honesty

    @Test
    fun `an under-stated wallet reports the shortfall instead of a fabricated zero`() {
        val w = wallet(
            total = 1_000.0,
            ledger = listOf(tx("AAPL", TxSide.BUY, 10.0, 200.0)),
            prices = mapOf("AAPL" to 200.0)
        )
        assertEquals(-1_000.0, w.liquidity, 1e-9)
        assertTrue(w.shortfall)
        assertEquals(0.0, w.deployable, 1e-9)   // engines still never overspend
    }

    @Test
    fun `a funded wallet is not a shortfall`() {
        val w = wallet(
            total = 10_000.0,
            ledger = listOf(tx("AAPL", TxSide.BUY, 10.0, 200.0)),
            prices = mapOf("AAPL" to 200.0)
        )
        assertFalse(w.shortfall)
        assertEquals(w.liquidity, w.deployable, 1e-9)
    }

    @Test
    fun `an unset wallet stays at zero across the board`() {
        val w = WalletState.UNSET
        assertFalse(w.configured)
        assertEquals(0.0, w.liquidity, 1e-9)
        assertEquals(0.0, w.netWorth, 1e-9)
        assertFalse(w.shortfall)
    }

    @Test
    fun `a null summary derives an all-cash wallet`() {
        val w = WalletState.of(5_000.0, configured = true, summary = null)
        assertEquals(5_000.0, w.liquidity, 1e-9)
        assertEquals(0.0, w.invested, 1e-9)
        assertEquals(5_000.0, w.netWorth, 1e-9)
    }

    @Test
    fun `the shared identity is what every screen calls`() {
        // ReportsViewModel and WealthViewModel derive liquidity through this
        // one function; if it drifts from WalletState.of the screens disagree.
        val summary = PortfolioSummary(
            marketValue = 3_000.0, investedCost = 2_000.0, unrealizedPl = 1_000.0,
            realizedPl = 250.0, totalPl = 1_250.0, dayPl = 0.0
        )
        val w = WalletState.of(10_000.0, configured = true, summary = summary)
        assertEquals(
            WalletState.liquidityOf(10_000.0, summary.investedCost, summary.realizedPl),
            w.liquidity,
            1e-9
        )
        assertEquals(8_250.0, w.liquidity, 1e-9)
    }
}
