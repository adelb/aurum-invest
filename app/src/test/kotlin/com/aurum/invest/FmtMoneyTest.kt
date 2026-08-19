package com.aurum.invest

import com.aurum.invest.core.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wallet balances must keep their cents at every magnitude. [Fmt.money] drops
 * the decimals above $10,000 to keep dense lists narrow — correct there, wrong
 * for a balance, which is why the wallet figures use [Fmt.moneyExact].
 */
class FmtMoneyTest {

    @Test
    fun `moneyExact keeps cents above the ten-thousand threshold`() {
        // The exact regression: money() rounds these away.
        assertEquals("$25,477.30", Fmt.moneyExact(25_477.30))
        assertEquals("$10,000.00", Fmt.moneyExact(10_000.0))
        assertEquals("$1,250,000.55", Fmt.moneyExact(1_250_000.55))
    }

    @Test
    fun `moneyExact keeps cents below it too`() {
        assertEquals("$0.00", Fmt.moneyExact(0.0))
        assertEquals("$0.07", Fmt.moneyExact(0.07))
        assertEquals("$9,999.99", Fmt.moneyExact(9_999.99))
    }

    @Test
    fun `moneyExact carries the minus sign outside the symbol`() {
        assertEquals("-$1,000.50", Fmt.moneyExact(-1_000.50))
        assertEquals("-$25,477.30", Fmt.moneyExact(-25_477.30))
    }

    @Test
    fun `signedMoneyExact always shows a sign and two decimals`() {
        assertEquals("+$25,477.30", Fmt.signedMoneyExact(25_477.30))
        assertEquals("-$25,477.30", Fmt.signedMoneyExact(-25_477.30))
        assertEquals("+$0.00", Fmt.signedMoneyExact(0.0))
    }

    @Test
    fun `money still compacts large amounts for dense lists`() {
        // The old behaviour is deliberately unchanged everywhere else.
        assertEquals("$25,477", Fmt.money(25_477.30))
        assertEquals("$9,999.99", Fmt.money(9_999.99))
    }
}
