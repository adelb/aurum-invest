package com.aurum.invest

import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.repo.PortfolioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit/delete guard judges a DIFFERENCE, not the ledger's absolute
 * soundness. A history that already sells shares it never bought — a bank
 * SELL imported without its BUY — used to reject every edit to every symbol,
 * quoting the broken symbol in an error about a trade the user was not
 * touching, and left the user no way to repair it. These tests lock the fix
 * and the protections it must not weaken.
 */
class LedgerGuardTest {

    private var id = 0L
    private fun tx(
        symbol: String,
        side: String,
        shares: Double,
        price: Double,
        ts: Long? = null
    ): TransactionEntity {
        ++id
        return TransactionEntity(
            id = id, symbol = symbol, side = side, shares = shares,
            price = price, ts = ts ?: (id * 1000L)
        )
    }

    /** A ledger carrying an inherited FSLY gap plus a sound NVDA history. */
    private fun ledgerWithGap(): List<TransactionEntity> = listOf(
        tx("FSLY", TxSide.BUY, 12.0, 20.0),
        tx("FSLY", TxSide.SELL, 40.0, 25.0),   // 28 shares no buy ever backed
        tx("NVDA", TxSide.BUY, 10.0, 150.0)
    )

    @Test
    fun `unbacked quantity is measured per symbol`() {
        val gaps = PortfolioRepository.unbackedBySymbol(ledgerWithGap())
        assertEquals(28.0, gaps["FSLY"]!!, 1e-9)
        assertNull(gaps["NVDA"])
    }

    @Test
    fun `a gap on one symbol never blocks an edit to another`() {
        val before = ledgerWithGap()
        val nvda = before.last()
        val after = before.dropLast(1) + nvda.copy(price = 155.0)
        assertNull(PortfolioRepository.worsenedGap(before, after, "This edit"))
    }

    @Test
    fun `the symbol carrying the gap stays repairable`() {
        val before = ledgerWithGap()
        val sell = before[1]
        // Correcting the sell down to what the history supports closes the gap.
        val after = listOf(before[0], sell.copy(shares = 12.0), before[2])
        assertNull(PortfolioRepository.worsenedGap(before, after, "This edit"))

        // Adding the missing buy closes it too.
        val withBuy = (before + tx("FSLY", TxSide.BUY, 28.0, 19.0, ts = 500L))
            .sortedWith(compareBy({ it.ts }, { it.id }))
        assertNull(PortfolioRepository.worsenedGap(before, withBuy, "This edit"))
        assertTrue(PortfolioRepository.unbackedBySymbol(withBuy).isEmpty())
    }

    @Test
    fun `widening an existing gap is still refused`() {
        val before = ledgerWithGap()
        val sell = before[1]
        val after = listOf(before[0], sell.copy(shares = 60.0), before[2])
        val problem = PortfolioRepository.worsenedGap(before, after, "This edit")
        assertNotNull(problem)
        assertTrue(problem!!.contains("FSLY"))
        assertTrue(problem.contains("widen"))
    }

    @Test
    fun `a sound ledger still refuses an edit that oversells`() {
        val before = listOf(
            tx("AAPL", TxSide.BUY, 5.0, 100.0),
            tx("AAPL", TxSide.SELL, 5.0, 110.0)
        )
        val after = listOf(before[0], before[1].copy(shares = 8.0))
        val problem = PortfolioRepository.worsenedGap(before, after, "This edit")
        assertNotNull(problem)
        assertTrue(problem!!.contains("AAPL"))
    }

    @Test
    fun `deleting the buy behind a recorded sell is still refused`() {
        val before = listOf(
            tx("AMD", TxSide.BUY, 10.0, 100.0),
            tx("AMD", TxSide.SELL, 10.0, 110.0)
        )
        val after = before.filter { it.side != TxSide.BUY }
        assertNotNull(PortfolioRepository.worsenedGap(before, after, "Deleting this trade"))
    }

    @Test
    fun `deleting an unrelated trade is allowed while a gap sits elsewhere`() {
        val before = ledgerWithGap()
        val after = before.filter { it.symbol != "NVDA" }
        assertNull(PortfolioRepository.worsenedGap(before, after, "Deleting this trade"))
    }

    @Test
    fun `moving a buy after the sell it backed is refused`() {
        val before = listOf(
            tx("TSLA", TxSide.BUY, 5.0, 200.0, ts = 1_000L),
            tx("TSLA", TxSide.SELL, 5.0, 210.0, ts = 2_000L)
        )
        val after = listOf(before[1], before[0].copy(ts = 3_000L))
        assertNotNull(PortfolioRepository.worsenedGap(before, after, "This edit"))
    }

    @Test
    fun `a split before the sell it enables is not a gap`() {
        val ledger = listOf(
            tx("AAPL", TxSide.BUY, 10.0, 100.0),
            tx("AAPL", TxSide.SPLIT, 2.0, 0.0),
            tx("AAPL", TxSide.SELL, 20.0, 60.0)
        )
        assertTrue(PortfolioRepository.unbackedBySymbol(ledger).isEmpty())
    }
}
