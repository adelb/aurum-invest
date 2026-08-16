package com.aurum.invest

import com.aurum.invest.bank.TradeParser
import com.aurum.invest.data.model.TradeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors for the bank-alert parser (C2/C7). */
class TradeParserTest {

    @Test
    fun `english buy with shares and price`() {
        val t = TradeParser.parse(null, "Bought 10 shares of AAPL at $150.25")
        assertNotNull(t)
        assertEquals(TradeSide.BUY, t!!.side)
        assertEquals("AAPL", t.symbol)
        assertEquals(10.0, t.shares!!, 1e-9)
        assertEquals(150.25, t.price!!, 1e-9)
        assertTrue(t.confidence >= 80)
    }

    @Test
    fun `sell with amount derives the price`() {
        val t = TradeParser.parse(null, "Sold 5 shares of TSLA for USD 1,502.50")
        assertNotNull(t)
        assertEquals(TradeSide.SELL, t!!.side)
        assertEquals(300.5, t.price!!, 1e-9)
        assertEquals("USD", t.currency)
    }

    @Test
    fun `arabic buy with jordanian dinar`() {
        val t = TradeParser.parse(null, "شراء ١٠ أسهم من AAPL بسعر ١٥٠٫٢٥ د.أ")
        assertNotNull(t)
        assertEquals(TradeSide.BUY, t!!.side)
        assertEquals("AAPL", t.symbol)
        assertEquals(10.0, t.shares!!, 1e-9)
        assertEquals(150.25, t.price!!, 1e-9)
        assertEquals("JOD", t.currency)
    }

    @Test
    fun `clock time is never taken as a price`() {
        val t = TradeParser.parse(null, "At 10:30 buy executed: AAPL x10 @ 150.25")
        assertNotNull(t)
        assertEquals(150.25, t!!.price!!, 1e-9)
    }

    @Test
    fun `broker reference is extracted`() {
        val t = TradeParser.parse(null, "Bought 10 shares of AAPL at $150.25 Ref: AB12345")
        assertEquals("AB12345", t?.ref)
        val t2 = TradeParser.parse(null, "Order #98765 — sold 3 shares of MSFT at $410")
        assertEquals("98765", t2?.ref)
    }

    @Test
    fun `no reference stays null`() {
        val t = TradeParser.parse(null, "Bought 10 shares of AAPL at $150.25")
        assertNull(t?.ref)
    }

    @Test
    fun `non-trade text returns null`() {
        assertNull(TradeParser.parse(null, "Your OTP code is 123456"))
        assertNull(TradeParser.parse("Promo", "Great savings rates this month!"))
    }

    @Test
    fun `json round trip preserves every field`() {
        val original = TradeParser.parse(null, "Bought 10 shares of AAPL at $150.25 Ref: XY99")!!
        val restored = TradeParser.fromJson(TradeParser.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun `currency detection covers symbols and words`() {
        assertEquals("EUR", TradeParser.parse(null, "Bought 1 share of SAP at €120")?.currency)
        assertEquals("USD", TradeParser.parse(null, "شراء ٥ أسهم AAPL بسعر ١٥٠ دولار")?.currency)
    }
}
