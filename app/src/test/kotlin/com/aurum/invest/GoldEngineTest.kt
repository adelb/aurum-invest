package com.aurum.invest

import com.aurum.invest.analytics.GoldEngine
import com.aurum.invest.analytics.GoldKind
import com.aurum.invest.analytics.GoldLot
import com.aurum.invest.analytics.GoldTrendCall
import com.aurum.invest.analytics.KaratPrice
import com.aurum.invest.analytics.ShopQuote
import com.aurum.invest.analytics.SyndicateList
import com.aurum.invest.data.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gold book is valued at what a Jordan shop pays back (the Syndicate BUY
 * price), never at the sell price, and the making charge is a cost that is
 * never refunded. These checks pin that down with the real list of
 * Sat 24 Oct 2026, 10:15 Amman.
 */
class GoldEngineTest {

    private val list = SyndicateList(
        listedAt = 1_000L,
        karats = mapOf(
            24 to KaratPrice(24, 93.4, 89.7),
            22 to KaratPrice(22, 86.2, 81.6),
            21 to KaratPrice(21, 81.4, 77.6),
            18 to KaratPrice(18, 72.0, 66.3)
        ),
        rashadi = KaratPrice(21, 586.1, 558.7),
        english = KaratPrice(22, 689.6, 652.8),
        fetchedAt = 2_000L,
        worldAtList = 4007.9
    )

    private val bar = GoldLot(1, GoldKind.BAR, 24, 50.0, 80.4, 0.5, 0L, "Gold bar")
    private val liras = GoldLot(2, GoldKind.RASHADI, 21, 2.0, 557.28, 4.0, 0L, "Rashadi lira × 2")
    private val bracelet = GoldLot(3, GoldKind.JEWELRY, 21, 30.0, 82.0, 3.5, 0L, "Bracelet")

    @Test
    fun theBookIsValuedAtTheShopsBuyPrice() {
        val book = GoldEngine.book(listOf(bar, liras, bracelet), list, worldForValue = 4007.9)
        assertEquals(7732.56, book.paid, 1e-6)
        assertEquals(7930.40, book.value, 1e-6)
        assertEquals(197.84, book.pl, 1e-6)
        assertEquals(2.5585, book.plPct, 1e-3)
        assertEquals(94.4, book.grams, 1e-9)
        assertEquals(138.0, book.making, 1e-9)
        assertEquals(7930.40 / 0.709, book.valueUsd, 1e-6)
    }

    @Test
    fun aLiraIsPricedByThePieceAndWeighsSevenPointTwoGrams() {
        val v = GoldEngine.valueLot(liras, list)!!
        assertEquals(558.7, v.unitBuy, 1e-9)
        assertEquals(1117.4, v.value, 1e-9)
        assertEquals(14.4, liras.grams, 1e-9)
        assertEquals(-5.16, v.pl, 1e-9)
    }

    @Test
    fun makingChargesAreNeverRefunded() {
        val v = GoldEngine.valueLot(bracelet, list)!!
        // 30 g × 77.6 back, against 30 × (82 + 3.5) paid.
        assertEquals(2328.0, v.value, 1e-9)
        assertEquals(-237.0, v.pl, 1e-9)
        assertEquals(105.0, bracelet.making, 1e-9)
    }

    @Test
    fun breakEvenWorldPriceScalesFromTheAnchor() {
        val book = GoldEngine.book(listOf(bar, liras, bracelet), list, worldForValue = 4007.9)
        assertEquals(4007.9 * 7732.56 / 7930.40, book.breakEvenWorld!!, 1e-6)
        assertTrue(book.cushionPct > 0.0)
    }

    @Test
    fun theLiveEstimateMovesWithWorldGoldOnly() {
        assertEquals(1.0, GoldEngine.liveFactor(list, null), 0.0)
        assertEquals(1.01, GoldEngine.liveFactor(list, 4007.9 * 1.01), 1e-12)
        assertEquals(1.0, GoldEngine.liveFactor(list.copy(worldAtList = null), 4100.0), 0.0)
        val live = GoldEngine.valueLot(bar, list, factor = 1.01)!!
        assertEquals(50 * 89.7 * 1.01, live.value, 1e-9)
    }

    @Test
    fun theShopsOwnQuoteAppliesOnlyToItsKaratAndItsList() {
        val shop = ShopQuote(karat = 21, buyPerGram = 78.5, forListAt = 1_000L)
        assertEquals(78.5, GoldEngine.unitBuy(list, GoldKind.JEWELRY, 21, shop = shop)!!, 1e-9)
        assertEquals(89.7, GoldEngine.unitBuy(list, GoldKind.BAR, 24, shop = shop)!!, 1e-9)
        // A lira is always the Syndicate's per-piece price.
        assertEquals(558.7, GoldEngine.unitBuy(list, GoldKind.RASHADI, 21, shop = shop)!!, 1e-9)
        // A new list retires yesterday's shop quote.
        val stale = shop.copy(forListAt = 999L)
        assertEquals(77.6, GoldEngine.unitBuy(list, GoldKind.JEWELRY, 21, shop = stale)!!, 1e-9)
    }

    @Test
    fun soldLotsLeaveTheBookAndBookTheirRealProfit() {
        val sold = bracelet.copy(soldAt = 5L, soldPricePerUnit = 79.0)
        val book = GoldEngine.book(listOf(bar, sold), list)
        assertEquals(1, book.lots.size)
        assertEquals(30 * 79.0 - 30 * 85.5, GoldEngine.realized(listOf(bar, sold)), 1e-9)
    }

    @Test
    fun noListMeansNothingIsValuedNotAGuess() {
        val book = GoldEngine.book(listOf(bar), null)
        assertEquals(0.0, book.value, 0.0)
        assertEquals(1, book.unpriced.size)
    }

    @Test
    fun cheapestRoundTripIs24K() {
        val spreads = GoldEngine.spreads(list)
        assertEquals(24, spreads.first().karat)
        assertEquals(18, spreads.last().karat)
        assertEquals((93.4 - 89.7) / 93.4 * 100, spreads.first().spreadPct, 1e-9)
    }

    @Test
    fun aPurchaseStartsUnderWaterByItsSpreadAndMaking() {
        val p = GoldEngine.preview(GoldKind.JEWELRY, 21, 10.0, 81.4, 3.0, list)!!
        assertEquals(844.0, p.pay, 1e-9)
        assertEquals(776.0, p.backToday, 1e-9)
        assertEquals(-68.0, p.gap, 1e-9)
        assertEquals(68.0 / 776.0 * 100, p.riseNeededPct, 1e-9)
        assertNull(GoldEngine.preview(GoldKind.JEWELRY, 21, 0.0, 81.4, 3.0, list))
    }

    @Test
    fun aDowntrendIsCalledWhenBelowBothAverages() {
        val closes = (0 until 260).map { 5000.0 - it * 4.0 }
        val t = GoldEngine.trend(closes)!!
        assertEquals(GoldTrendCall.DOWNTREND, t.call)
        assertNotNull(t.sma200)
        assertTrue(t.fromHighPct!! < 0.0)
        val up = GoldEngine.trend(closes.reversed())!!
        assertEquals(GoldTrendCall.TREND_UP, up.call)
    }

    @Test
    fun theMonthBandIsOrderedAndCountsItsWindows() {
        val closes = (0 until 300).map { 4000.0 + 50.0 * kotlin.math.sin(it / 7.0) + it }
        val b = GoldEngine.monthBand(closes)!!
        assertEquals(300 - 21, b.windows)
        assertTrue(b.p10 <= b.q1 && b.q1 <= b.median && b.median <= b.q3 && b.q3 <= b.p90)
        assertNull(GoldEngine.monthBand(closes.take(50)))
    }

    @Test
    fun correlationJoinsByCalendarDay() {
        val day = 86_400_000L
        val a = (0 until 80).map { Candle(it * day, 0.0, 0.0, 0.0, 100.0 + it + (it % 3), 0) }
        val b = (0 until 80).map { Candle(it * day + 3_600_000L, 0.0, 0.0, 0.0, 50.0 + it + (it % 3), 0) }
        val r = GoldEngine.correlation(a, b)!!
        assertTrue(r > 0.9)
        assertNull(GoldEngine.correlation(a.take(10), b.take(10)))
    }

    @Test
    fun theSpyShadowCompoundsEachLotFromItsOwnDay() {
        val day = 86_400_000L
        val spy = listOf(
            Candle(0L, 0.0, 0.0, 0.0, 100.0, 0),
            Candle(day, 0.0, 0.0, 0.0, 110.0, 0),
            Candle(2 * day, 0.0, 0.0, 0.0, 121.0, 0)
        )
        val lot = bar.copy(boughtAt = day)
        assertEquals(lot.paid * 121.0 / 110.0, GoldEngine.shadowValue(listOf(lot), spy)!!, 1e-9)
        // Bought on the last session: no trading day has passed, nothing to compare.
        assertNull(GoldEngine.shadowValue(listOf(bar.copy(boughtAt = 2 * day + 3_600_000L)), spy))
        // A lot older than the series cannot be compared fairly.
        val early = spy.drop(1)
        assertNull(GoldEngine.shadowValue(listOf(bar.copy(boughtAt = 0L - 3 * day)), early))
    }
}
