package com.aurum.invest.data.model

/** Latest tradable price for a symbol. */
data class Quote(
    val symbol: String,
    val price: Double,
    val prevClose: Double,
    val currency: String = "USD",
    val marketState: String = "",
    val shortName: String = "",
    val fetchedAt: Long = 0L
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
    val dayPl: Double
)

data class PortfolioSummary(
    val marketValue: Double,
    val investedCost: Double,
    val unrealizedPl: Double,
    val realizedPl: Double,
    val totalPl: Double,
    val dayPl: Double
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
    val correlation: Double,
    val link: GoldLink,
    val description: String,
    val sampleDays: Int
)

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
    val marketState: String
)

/** One same-day pick: a stock the engine reads as capable of a 3-10%+ up-move today. */
data class DailyPick(
    val date: String,             // ISO local date the pick was computed for
    val rank: Int,
    val symbol: String,
    val name: String,
    val score: Double,            // 0..100
    val expectedLowPct: Double,   // potential day move, low bound (>= 3)
    val expectedHighPct: Double,  // potential day move, high bound
    val reason: String,
    val price: Double,            // price at pick time
    val prevClose: Double,
    val dayChangePct: Double,     // regular-session move at pick time
    val preMarketPct: Double?,
    val postMarketPct: Double?,
    val marketState: String,
    val techDirection: String,    // BULLISH / BEARISH / NEUTRAL from the 11 techniques
    val techBullish: Int,         // bullish technique count of 11
    val techConfidence: Int,
    val volumeRatio: Double,      // latest session volume vs 20-day average
    val newsScore: Int,           // summed headline sentiment, clamped
    val headline: String,         // newest related headline ("" when none)
    val headlineSource: String,
    val headlineSentiment: Int
)

/** A trade extracted from a bank notification. */
data class ParsedTrade(
    val side: TradeSide,
    val symbol: String?,
    val shares: Double?,
    val price: Double?,
    val amount: Double?,
    val currency: String?,
    val confidence: Int
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
