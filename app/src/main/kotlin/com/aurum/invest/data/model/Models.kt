package com.aurum.invest.data.model

/** Latest tradable price for a symbol. */
data class Quote(
    val symbol: String,
    val price: Double,
    val prevClose: Double,
    val currency: String = "USD",
    val marketState: String = "",
    val shortName: String = "",
    val fetchedAt: Long = 0L,
    // Session stats from the chart meta; null when Yahoo omits them.
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val volume: Long? = null,
    /**
     * True when this came from the batch (spark) endpoint, which carries only
     * price and previous close. Screens needing the full read (detail's key
     * stats) re-fetch instead of trusting a lite entry.
     */
    val lite: Boolean = false
) {
    val dayChangeAbs: Double get() = price - prevClose
    val dayChangePct: Double get() = if (prevClose > 0.0) (price - prevClose) / prevClose * 100.0 else 0.0
}

/** One price bar. [ts] is epoch millis of the bar start. */
data class Candle(
    val ts: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

enum class TradeSide { BUY, SELL }

/** An open (or fully closed) position derived from the transaction ledger. */
data class Position(
    val symbol: String,
    val shares: Double,
    val avgCost: Double,
    val investedCost: Double,
    val realizedPl: Double
)

/** Position enriched with a live quote for display. */
data class PositionView(
    val position: Position,
    val quote: Quote?,
    val marketValue: Double,
    val unrealizedPl: Double,
    val unrealizedPlPct: Double,
    val dayPl: Double,
    /**
     * False when no quote could be fetched: [marketValue] is then the COST of
     * the position, not a market price, and the UI must say so.
     */
    val priced: Boolean = true
)

data class PortfolioSummary(
    val marketValue: Double,
    val investedCost: Double,
    val unrealizedPl: Double,
    val realizedPl: Double,
    val totalPl: Double,
    val dayPl: Double,
    /** Open positions with no live quote — carried at cost inside [marketValue]. */
    val unpricedCount: Int = 0,
    val unpricedCost: Double = 0.0
)

enum class AdviceAction { STRONG_BUY, BUY, WAIT, HOLD, TAKE_PROFIT, CUT_LOSS, SELL }

/** Output of the advice engine for a symbol (buy-side or sell-side). */
data class Advice(
    val action: AdviceAction,
    val headline: String,
    val reasons: List<String>,
    val score: Int,
    val suggestedBuyPrice: Double? = null,
    val targetPrice: Double? = null,
    val stopLoss: Double? = null
)

enum class GoldLink { WITH_GOLD, INVERSE_GOLD, NEUTRAL }

/** How a stock's daily returns correlate with gold (GLD proxy). */
data class GoldRelation(
    /** Pearson r; null when the relationship could not be measured — a 0.00 would read as a measured "no link". */
    val correlation: Double?,
    val link: GoldLink,
    val description: String,
    val sampleDays: Int
)

/**
 * Provenance of a served feed. "No data", "stale data", and "fetch failed"
 * are different answers and must never be conflated: an empty FRESH news feed
 * means verified-no-articles; an empty FAILED one means we simply don't know.
 */
enum class FeedStatus { FRESH, STALE, FAILED }

/** Symbol news plus how much to trust it. [asOf] is when the items were actually fetched. */
data class NewsFeed(
    val items: List<NewsItem>,
    val status: FeedStatus,
    val asOf: Long
) {
    companion object {
        val FAILED = NewsFeed(emptyList(), FeedStatus.FAILED, 0L)
    }
}

/** Daily candles plus provenance — distinguishes "short listing history" from "fetch failed". */
data class CandleFeed(
    val candles: List<Candle>,
    val status: FeedStatus,
    val asOf: Long
)

/**
 * Health of the screener universe behind a "market-wide" scan (H4): how many
 * of the requested Yahoo screens were actually served live, from stale cache,
 * or not at all. An empty pick list only means "no setup" when the screens
 * were actually reachable.
 */
data class ScanCoverage(
    val screensRequested: Int,
    val screensLive: Int,
    val screensStale: Int,
    val screensMissing: Int,
    val rowsSeen: Int,
    /** Oldest fetch time among served screens; 0 when nothing was served. */
    val oldestAsOf: Long
) {
    val healthy: Boolean get() = screensMissing == 0 && screensStale == 0
    val reachable: Boolean get() = screensLive + screensStale > 0

    fun summary(): String = buildString {
        append("$screensRequested Yahoo screens · $rowsSeen rows")
        if (screensLive == screensRequested) {
            append(" · all live")
        } else {
            append(" · $screensLive live")
            if (screensStale > 0) append(" · $screensStale stale")
            if (screensMissing > 0) append(" · $screensMissing unreachable")
        }
    }
}

data class NewsItem(
    val id: String,
    val symbol: String,
    val title: String,
    val source: String,
    val url: String,
    val publishedAt: Long,
    val sentiment: Int,
    val priceImpactPct: Double? = null
)

data class WeeklyPick(
    val weekStart: String,
    val rank: Int,
    val symbol: String,
    val name: String,
    val score: Double,
    val reason: String,
    val priceAtPick: Double
)

/** Pre/post-market read for a symbol's latest session, from extended-hours candles. */
data class ExtendedHours(
    val symbol: String,
    val prevClose: Double,
    val regularPrice: Double,
    /** Last pre-market print vs the previous close, percent; null when no pre-market trades. */
    val preMarketPct: Double?,
    /** Last post-market print vs the regular close, percent; null when no post-market trades. */
    val postMarketPct: Double?,
    val marketState: String,
    /**
     * The actual last pre-market PRICE. This is what a stock trades at before
     * the open — the screener's regularMarketPrice is still yesterday's close
     * during that window, so anything priced off it (entries, targets) would
     * be wrong by the size of the gap.
     */
    val preMarketPrice: Double? = null,
    /** The actual last post-market price, same reasoning. */
    val postMarketPrice: Double? = null
) {
    /** The live price right now: extended-hours print when there is one, else regular. */
    val livePrice: Double
        get() = preMarketPrice ?: postMarketPrice ?: regularPrice
}

/** One row from a Yahoo predefined screener — the market-wide candidate pool. */
data class ScreenerQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double,        // last regular-session move
    val avgVolume3M: Long,
    val marketCap: Double,
    val fiftyDayAvg: Double,
    val twoHundredDayAvg: Double,
    val fiftyTwoWeekHigh: Double,
    val analystRating: Double?,      // 1.0 (Strong Buy) .. 5.0 (Sell); null when unrated
    val dayHigh: Double = 0.0,       // today's session high (0 when absent)
    val dayLow: Double = 0.0,        // today's session low (0 when absent)
    val dayVolume: Long = 0L         // today's traded volume so far
)

/**
 * A power-hour pick: bought in the last 90 minutes of the session, positioned
 * for next-day strength off the last 4 trading days' behavior.
 */
data class PowerPick(
    val date: String,
    val rank: Int,
    val symbol: String,
    val name: String,
    val score: Double,               // 0..100
    val price: Double,
    val dayChangePct: Double,
    val r4Pct: Double,               // move over the last 4 trading days
    val upDays: Int,                 // up closes among those 4 days (0..4)
    val closePosPct: Double,         // where price sits in today's range, 0..100; -1 = range unknown
    val volumeRatio: Double,         // 4-day avg volume vs 20-day average
    val expectedLowPct: Double,      // honest next-day potential, low bound
    val expectedHighPct: Double,
    val target: Double,              // morning-strength exit level
    val stop: Double,                // hard stop under today's low / ATR
    val rsi: Double,
    val techDirection: String,
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val reason: String,
    val newsScore: Int = 0,          // -3..+3 summed headline tone, 5 days
    val headline: String = "",       // "" when the week had no headline worth citing
    val headlineSource: String = ""
)

/** A stock the market-wide scan reads as sitting at a good entry price right now. */
data class EntryPick(
    val date: String,                // ISO local date the scan ran
    val rank: Int,
    val symbol: String,
    val name: String,
    val score: Double,               // 0..100
    val price: Double,
    val dayChangePct: Double,
    val entryLimit: Double,          // patient limit near support (== price when already there)
    val target: Double,              // nearest resistance / 20-day high
    val stop: Double,                // ATR-padded under support
    val upsidePct: Double,
    val riskPct: Double,
    val rewardRisk: Double,
    val rsi: Double,
    val dipPct: Double,              // off the 20-day high
    val vs50DayPct: Double,          // price vs the 50-day average
    val techDirection: String,
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val analystRating: Double?,      // 1..5 average analyst rating, null when unrated
    val reason: String
)

/** A trade extracted from a bank notification. */
data class ParsedTrade(
    val side: TradeSide,
    val symbol: String?,
    val shares: Double?,
    val price: Double?,
    val amount: Double?,
    val currency: String?,
    val confidence: Int,
    /**
     * The broker's own transaction reference when the alert carries one
     * ("Ref: AB12345", "Order #98765"). Auto-import requires it — a unique
     * broker id is what makes an unattended ledger write traceable.
     */
    val ref: String? = null
)

/** A captured bank notification with its parse result. */
data class BankEvent(
    val id: Long,
    val pkg: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val status: String,
    val parsed: ParsedTrade?
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_IMPORTED = "IMPORTED"
        const val STATUS_DISMISSED = "DISMISSED"
    }
}
