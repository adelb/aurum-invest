package com.aurum.invest.data.model

/**
 * Company research data (C6) from Yahoo's quoteSummary endpoint. EVERY field
 * is nullable: an absent value renders as explicitly unavailable — never as a
 * silent zero. [fetchedAt]/[source] make each card auditable.
 */
data class Fundamentals(
    val symbol: String,
    val fetchedAt: Long,
    val source: String = "Yahoo Finance",
    // profile
    val name: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val description: String? = null,
    val employees: Int? = null,
    val website: String? = null,
    // valuation & key stats
    val marketCap: Double? = null,
    val trailingPE: Double? = null,
    val forwardPE: Double? = null,
    val priceToBook: Double? = null,
    val pegRatio: Double? = null,
    val epsTrailing: Double? = null,
    val epsForward: Double? = null,
    val beta: Double? = null,
    val dividendYieldPct: Double? = null,
    val dividendRate: Double? = null,
    val exDividendTs: Long? = null,
    val payoutRatioPct: Double? = null,
    // financial health
    val totalRevenue: Double? = null,
    val revenueGrowthPct: Double? = null,
    val grossMarginPct: Double? = null,
    val operatingMarginPct: Double? = null,
    val profitMarginPct: Double? = null,
    val totalCash: Double? = null,
    val totalDebt: Double? = null,
    val debtToEquity: Double? = null,
    val currentRatio: Double? = null,
    val freeCashflow: Double? = null,
    val operatingCashflow: Double? = null,
    val returnOnEquityPct: Double? = null,
    // analyst consensus (count + dispersion, not just a bare average)
    val targetMean: Double? = null,
    val targetHigh: Double? = null,
    val targetLow: Double? = null,
    val analystCount: Int? = null,
    val recommendationMean: Double? = null,
    val recommendationKey: String? = null,
    // catalysts
    /**
     * The next earnings date that is still ahead. Yahoo's `earningsDate`
     * array can hold dates that have ALREADY passed (the last report, until
     * the next one is scheduled) and can hold TWO entries when the date is an
     * unconfirmed window. Taking element 0 blindly therefore printed a past
     * date under the heading "Next earnings". Null here means genuinely
     * nothing upcoming is published — see [lastEarningsTs].
     */
    val nextEarningsTs: Long? = null,
    /** End of Yahoo's estimated window when it gives a range; null for a single date. */
    val nextEarningsEndTs: Long? = null,
    /** Yahoo's own `isEarningsDateEstimate` — an estimate is not a confirmed date. */
    val earningsDateEstimated: Boolean = false,
    /** The most recent earnings date already past; lets the UI say when it last reported. */
    val lastEarningsTs: Long? = null,
    val dividendDateTs: Long? = null
)

/** Fundamentals plus provenance — the same FRESH/STALE/FAILED contract as news. */
data class FundamentalsFeed(
    val data: Fundamentals?,
    val status: FeedStatus,
    val asOf: Long
)
