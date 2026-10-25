package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * What a gold purchase physically is. A lira is bought and sold by the piece
 * at its own Syndicate price; everything else is weighed by the gram.
 */
enum class GoldKind(
    val label: String,
    val perPiece: Boolean,
    /** Grams of gold in one unit — one gram for weighed gold. */
    val gramsEach: Double,
    /** A lira's karat is fixed by the coin; weighed gold is whatever was bought. */
    val fixedKarat: Int?
) {
    JEWELRY("Jewelry", false, 1.0, null),
    BAR("Bar", false, 1.0, null),
    RASHADI("Rashadi lira", true, 7.2, 21),
    ENGLISH("English lira", true, 8.0, 22);

    companion object {
        fun of(name: String): GoldKind = entries.firstOrNull { it.name == name } ?: JEWELRY
    }
}

/** One line of the Syndicate list: what a shop sells at and buys back at. */
data class KaratPrice(val karat: Int, val sell: Double, val buy: Double) {
    /** What is lost the moment the gold changes hands, as % of the sell price. */
    val spreadPct: Double get() = if (sell > 0.0) (sell - buy) / sell * 100.0 else 0.0
}

/**
 * The Jewellers' Syndicate price list (نقابة أصحاب محلات الحلي والمجوهرات) —
 * the prices every Jordan shop works from, before making charges.
 */
data class SyndicateList(
    /** When the Syndicate published this list; 0 when the page didn't say. */
    val listedAt: Long,
    /** Per gram, keyed by karat (24, 22, 21, 18). */
    val karats: Map<Int, KaratPrice>,
    /** Per piece. */
    val rashadi: KaratPrice?,
    val english: KaratPrice?,
    val fetchedAt: Long,
    /**
     * World gold (COMEX GC=F, $/oz) when the list was published — the anchor
     * the live estimate moves from. Null until it could be measured.
     */
    val worldAtList: Double? = null
)

/** The shop's own buy-back quote, used instead of the Syndicate's for one list. */
data class ShopQuote(val karat: Int, val buyPerGram: Double, val forListAt: Long)

/** One purchase. Prices are per unit (gram or piece), in dinars. */
data class GoldLot(
    val id: Long = 0,
    val kind: GoldKind,
    val karat: Int,
    val qty: Double,
    val pricePerUnit: Double,
    val makingPerUnit: Double,
    val boughtAt: Long,
    val name: String,
    val note: String = "",
    val soldAt: Long? = null,
    val soldPricePerUnit: Double? = null
) {
    val grams: Double get() = qty * kind.gramsEach
    /** Everything handed to the shop: gold plus making. */
    val paid: Double get() = qty * (pricePerUnit + makingPerUnit)
    val making: Double get() = qty * makingPerUnit
    val sold: Boolean get() = soldAt != null && soldPricePerUnit != null
    /** What the shop paid back, once sold. */
    val proceeds: Double? get() = soldPricePerUnit?.let { qty * it }
    /** The buy-back price per unit at which this lot returns exactly what it cost. */
    val breakEvenUnit: Double get() = pricePerUnit + makingPerUnit
}

/** A lot valued at a buy-back price. */
data class LotValue(
    val lot: GoldLot,
    /** The buy-back price used, per unit. */
    val unitBuy: Double,
    val value: Double,
    val pl: Double,
    val plPct: Double
)

/** The whole book of open lots, valued honestly: what a shop would pay today. */
data class GoldBook(
    val lots: List<LotValue>,
    val paid: Double,
    val value: Double,
    val pl: Double,
    val plPct: Double,
    val grams: Double,
    val making: Double,
    /** Dollars, at the dinar's peg. */
    val valueUsd: Double,
    /**
     * In profit: how far gold can fall before the book is in the red, as % of
     * today's value. Under water: how far it must rise. Same number, the sign
     * says which.
     */
    val cushionPct: Double,
    /** The world price ($/oz) at which the book would be exactly even. */
    val breakEvenWorld: Double?,
    /** Lots that could not be valued because the list lacked their line. */
    val unpriced: List<GoldLot>
) {
    val isEmpty: Boolean get() = lots.isEmpty() && unpriced.isEmpty()

    companion object {
        val EMPTY = GoldBook(emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, emptyList())
    }
}

/** "Before you pay": where a purchase starts, measured at the buy-back price. */
data class PurchasePreview(
    val pay: Double,
    val backToday: Double,
    /** Negative: the purchase starts this far under water. */
    val gap: Double,
    /** How far the buy-back price must rise to break even, in %. */
    val riseNeededPct: Double
)

enum class GoldTrendCall { TREND_UP, MIXED, DOWNTREND }

/** World gold's trend, read from its daily closes. */
data class GoldTrend(
    val last: Double,
    val sma50: Double?,
    val sma200: Double?,
    val momentum20Pct: Double?,
    val high52: Double?,
    val fromHighPct: Double?,
    val call: GoldTrendCall
)

/** Where a month of world gold has landed historically — not a forecast. */
data class MonthBand(
    val p10: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val p90: Double,
    /** Share of the windows that ended higher, in %. */
    val upSharePct: Double,
    val windows: Int,
    val horizon: Int
)

object GoldEngine {

    /** The Jordanian dinar is pegged: 1 USD = 0.709 JOD. */
    const val JOD_PER_USD = 0.709
    const val GRAMS_PER_OUNCE = 31.1034768

    val KARATS = listOf(24, 22, 21, 18)
    val PURITY = mapOf(24 to 0.999, 22 to 0.9167, 21 to 0.875, 18 to 0.75)

    fun jodToUsd(jod: Double): Double = jod / JOD_PER_USD

    /** The list line a lot is priced from. */
    fun lineFor(list: SyndicateList, kind: GoldKind, karat: Int): KaratPrice? = when (kind) {
        GoldKind.RASHADI -> list.rashadi
        GoldKind.ENGLISH -> list.english
        else -> list.karats[karat]
    }

    /**
     * How far the list has moved since it was published, judged by world
     * gold: worldNow / worldAtList. 1.0 when either end is unknown — the list
     * itself is then the best figure there is, and nothing is extrapolated.
     */
    fun liveFactor(list: SyndicateList?, worldNow: Double?): Double {
        val anchor = list?.worldAtList ?: return 1.0
        if (anchor <= 0.0 || worldNow == null || worldNow <= 0.0) return 1.0
        return worldNow / anchor
    }

    /** The buy-back price per unit for a lot, after the shop's own quote if it applies. */
    fun unitBuy(
        list: SyndicateList,
        kind: GoldKind,
        karat: Int,
        factor: Double = 1.0,
        shop: ShopQuote? = null
    ): Double? {
        if (!kind.perPiece && shop != null && shop.karat == karat &&
            shop.forListAt == list.listedAt && shop.buyPerGram > 0.0
        ) {
            return shop.buyPerGram * factor
        }
        val line = lineFor(list, kind, karat) ?: return null
        return line.buy * factor
    }

    fun valueLot(
        lot: GoldLot,
        list: SyndicateList,
        factor: Double = 1.0,
        shop: ShopQuote? = null
    ): LotValue? {
        val unit = unitBuy(list, lot.kind, lot.karat, factor, shop) ?: return null
        val value = lot.qty * unit
        val pl = value - lot.paid
        return LotValue(lot, unit, value, pl, if (lot.paid > 0.0) pl / lot.paid * 100.0 else 0.0)
    }

    /**
     * Values the OPEN lots at the buy-back price. [worldForValue] is the world
     * price the valuation corresponds to (the anchor for the list basis, the
     * live price for the live basis) and is what the break-even world price
     * is scaled from.
     */
    fun book(
        lots: List<GoldLot>,
        list: SyndicateList?,
        factor: Double = 1.0,
        shop: ShopQuote? = null,
        worldForValue: Double? = null
    ): GoldBook {
        val open = lots.filter { !it.sold }
        if (open.isEmpty()) return GoldBook.EMPTY
        if (list == null) {
            return GoldBook.EMPTY.copy(unpriced = open, paid = open.sumOf { it.paid })
        }
        val valued = mutableListOf<LotValue>()
        val unpriced = mutableListOf<GoldLot>()
        for (lot in open) {
            val v = valueLot(lot, list, factor, shop)
            if (v == null) unpriced += lot else valued += v
        }
        val paid = valued.sumOf { it.lot.paid }
        val value = valued.sumOf { it.value }
        val pl = value - paid
        val breakEvenWorld =
            if (worldForValue != null && worldForValue > 0.0 && value > 0.0) {
                worldForValue * paid / value
            } else null
        return GoldBook(
            lots = valued,
            paid = paid,
            value = value,
            pl = pl,
            plPct = if (paid > 0.0) pl / paid * 100.0 else 0.0,
            grams = valued.sumOf { it.lot.grams },
            making = valued.sumOf { it.lot.making },
            valueUsd = jodToUsd(value),
            cushionPct = if (value > 0.0) pl / value * 100.0 else 0.0,
            breakEvenWorld = breakEvenWorld,
            unpriced = unpriced
        )
    }

    /** Realised profit on every lot already sold back. */
    fun realized(lots: List<GoldLot>): Double =
        lots.filter { it.sold }.sumOf { (it.proceeds ?: 0.0) - it.paid }

    /** Karats by spread, cheapest round trip first. */
    fun spreads(list: SyndicateList): List<KaratPrice> =
        list.karats.values.sortedBy { it.spreadPct }

    fun preview(
        kind: GoldKind,
        karat: Int,
        qty: Double,
        pricePerUnit: Double,
        makingPerUnit: Double,
        list: SyndicateList?
    ): PurchasePreview? {
        if (qty <= 0.0 || pricePerUnit <= 0.0 || list == null) return null
        val buy = lineFor(list, kind, karat)?.buy ?: return null
        val pay = qty * (pricePerUnit + makingPerUnit)
        val back = qty * buy
        val gap = back - pay
        return PurchasePreview(
            pay = pay,
            backToday = back,
            gap = gap,
            riseNeededPct = if (gap < 0.0 && back > 0.0) -gap / back * 100.0 else 0.0
        )
    }

    /** The buy-back price per unit a lot needs to break even, vs today's. */
    fun riseToBreakEvenPct(lot: GoldLot, unitBuyNow: Double): Double =
        if (unitBuyNow > 0.0) (lot.breakEvenUnit / unitBuyNow - 1.0) * 100.0 else 0.0

    /**
     * World gold that the lot's break-even buy price corresponds to, from the
     * list's own ratio of world price to buy price.
     */
    fun worldForUnitBuy(unitBuy: Double, list: SyndicateList, kind: GoldKind, karat: Int): Double? {
        val anchor = list.worldAtList ?: return null
        val line = lineFor(list, kind, karat) ?: return null
        if (line.buy <= 0.0) return null
        return anchor * unitBuy / line.buy
    }

    /** How deep a purchase started under water on day one, and why. */
    data class DayOne(val spreadCost: Double, val makingCost: Double, val startPct: Double)

    /**
     * The day-one gap, split into its two parts: the sell-to-buy spread on
     * the gold itself, and the making charge. Measured against the list the
     * user's own price implies — the list of that day is not stored, so the
     * spread is taken at today's percentage for the same line.
     */
    fun dayOne(lot: GoldLot, list: SyndicateList): DayOne? {
        val line = lineFor(list, lot.kind, lot.karat) ?: return null
        val spreadCost = lot.qty * lot.pricePerUnit * line.spreadPct / 100.0
        val making = lot.making
        val start = if (lot.paid > 0.0) -(spreadCost + making) / lot.paid * 100.0 else 0.0
        return DayOne(spreadCost, making, start)
    }

    // ------------------------------------------------------------ the market

    fun trend(closes: List<Double>): GoldTrend? {
        if (closes.size < 21) return null
        val last = closes.last()
        val sma50 = if (closes.size >= 50) closes.takeLast(50).average() else null
        val sma200 = if (closes.size >= 200) closes.takeLast(200).average() else null
        val momentum = closes[closes.size - 21].let { if (it > 0.0) (last / it - 1.0) * 100.0 else null }
        val year = closes.takeLast(252)
        val high = year.maxOrNull()
        val fromHigh = high?.let { if (it > 0.0) (last / it - 1.0) * 100.0 else null }
        val above = listOfNotNull(sma50, sma200).count { last > it }
        val measured = listOfNotNull(sma50, sma200).size
        val call = when {
            measured == 0 -> GoldTrendCall.MIXED
            above == measured -> GoldTrendCall.TREND_UP
            above == 0 -> GoldTrendCall.DOWNTREND
            else -> GoldTrendCall.MIXED
        }
        return GoldTrend(last, sma50, sma200, momentum, high, fromHigh, call)
    }

    /**
     * Every [horizon]-session window in the history, measured: the spread of
     * where a month of world gold landed. Unconditional — it does not know
     * today's trend, and says so wherever it is shown.
     */
    fun monthBand(closes: List<Double>, horizon: Int = 21, minWindows: Int = 60): MonthBand? {
        if (closes.size <= horizon) return null
        val returns = (0 until closes.size - horizon).mapNotNull { i ->
            val a = closes[i]
            val b = closes[i + horizon]
            if (a > 0.0) (b / a - 1.0) * 100.0 else null
        }.sorted()
        if (returns.size < minWindows) return null
        return MonthBand(
            p10 = quantile(returns, 0.10),
            q1 = quantile(returns, 0.25),
            median = quantile(returns, 0.50),
            q3 = quantile(returns, 0.75),
            p90 = quantile(returns, 0.90),
            upSharePct = returns.count { it > 0.0 } * 100.0 / returns.size,
            windows = returns.size,
            horizon = horizon
        )
    }

    /** Linear-interpolated quantile of an ascending list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /**
     * Pearson correlation of daily returns, on the days both series traded.
     * Gold futures trade on days the stock market is shut, so the series are
     * joined by calendar day, never by position.
     */
    fun correlation(a: List<Candle>, b: List<Candle>, lastDays: Int = 252): Double? {
        val byDayB = b.associate { dayKey(it.ts) to it.close }
        val joined = a.mapNotNull { c -> byDayB[dayKey(c.ts)]?.let { c.close to it } }
            .takeLast(lastDays + 1)
        if (joined.size < 30) return null
        val ra = joined.zipWithNext { x, y -> y.first / x.first - 1.0 }
        val rb = joined.zipWithNext { x, y -> y.second / x.second - 1.0 }
        val ma = ra.average()
        val mb = rb.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in ra.indices) {
            val da = ra[i] - ma
            val db = rb[i] - mb
            cov += da * db
            va += da * da
            vb += db * db
        }
        if (va <= 0.0 || vb <= 0.0) return null
        return cov / sqrt(va * vb)
    }

    /** The last close on or before [ts] — the price "on the day you bought". */
    fun closeOnOrBefore(candles: List<Candle>, ts: Long): Double? {
        val day = dayKey(ts)
        return candles.lastOrNull { dayKey(it.ts) <= day }?.close
    }

    /**
     * The same money put into [shadow] (SPY) on each lot's purchase day, worth
     * this much now. Null when any lot predates the shadow's history — a
     * partial comparison would flatter one side.
     */
    fun shadowValue(lots: List<GoldLot>, shadow: List<Candle>): Double? {
        val now = shadow.lastOrNull()?.close ?: return null
        if (lots.isEmpty()) return null
        val lastDay = dayKey(shadow.last().ts)
        var total = 0.0
        var aged = false
        for (lot in lots) {
            if (shadow.first().ts > lot.boughtAt + DAY_MS) return null
            val then = closeOnOrBefore(shadow, lot.boughtAt) ?: return null
            if (then <= 0.0) return null
            if (dayKey(lot.boughtAt) < lastDay) aged = true
            total += lot.paid * now / then
        }
        // Bought since the last session: no trading day has passed for either
        // side yet, so there is nothing to compare.
        return if (aged) total else null
    }

    private const val DAY_MS = 86_400_000L

    private fun dayKey(ts: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = ts
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
