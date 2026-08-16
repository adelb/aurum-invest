package com.aurum.invest.data.repo

import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.Fundamentals
import com.aurum.invest.data.model.FundamentalsFeed
import com.aurum.invest.data.remote.FundamentalsClient
import org.json.JSONObject

/**
 * Cached company fundamentals (C6). Statement-level data moves slowly, so a
 * successful read serves for 24 hours; on failure the stale copy serves with
 * its honest timestamp, or the feed reports FAILED. Never throws.
 */
class FundamentalsRepository(private val cacheDao: CacheDao) {

    private val client = FundamentalsClient()

    suspend fun getFundamentals(
        symbol: String,
        maxAgeMs: Long = 24L * 3_600_000L
    ): FundamentalsFeed {
        val key = "fundamentals:v1:${symbol.trim().uppercase()}"
        val now = System.currentTimeMillis()
        val cached = try {
            cacheDao.get(key)
        } catch (_: Exception) {
            null
        }
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            fromJson(cached.json)?.let {
                return FundamentalsFeed(it, FeedStatus.FRESH, cached.updatedAt)
            }
        }
        val fresh = try {
            client.fetchFundamentals(symbol.trim().uppercase())
        } catch (_: Exception) {
            null
        }
        if (fresh != null) {
            try {
                cacheDao.put(CacheEntity(key = key, json = toJson(fresh), updatedAt = now))
            } catch (_: Exception) {
            }
            return FundamentalsFeed(fresh, FeedStatus.FRESH, now)
        }
        val stale = cached?.let { fromJson(it.json) }
        return if (stale != null) {
            FundamentalsFeed(stale, FeedStatus.STALE, cached.updatedAt)
        } else {
            FundamentalsFeed(null, FeedStatus.FAILED, 0L)
        }
    }

    // ---- JSON (cache round-trip; nulls stay null) ---------------------------

    private fun toJson(f: Fundamentals): String = JSONObject().apply {
        put("symbol", f.symbol)
        put("fetchedAt", f.fetchedAt)
        putOpt("sector", f.sector); putOpt("industry", f.industry)
        putOpt("description", f.description); putOpt("employees", f.employees)
        putOpt("website", f.website)
        putOpt("marketCap", f.marketCap); putOpt("trailingPE", f.trailingPE)
        putOpt("forwardPE", f.forwardPE); putOpt("priceToBook", f.priceToBook)
        putOpt("pegRatio", f.pegRatio); putOpt("epsTrailing", f.epsTrailing)
        putOpt("epsForward", f.epsForward); putOpt("beta", f.beta)
        putOpt("dividendYieldPct", f.dividendYieldPct); putOpt("dividendRate", f.dividendRate)
        putOpt("exDividendTs", f.exDividendTs); putOpt("payoutRatioPct", f.payoutRatioPct)
        putOpt("totalRevenue", f.totalRevenue); putOpt("revenueGrowthPct", f.revenueGrowthPct)
        putOpt("grossMarginPct", f.grossMarginPct); putOpt("operatingMarginPct", f.operatingMarginPct)
        putOpt("profitMarginPct", f.profitMarginPct); putOpt("totalCash", f.totalCash)
        putOpt("totalDebt", f.totalDebt); putOpt("debtToEquity", f.debtToEquity)
        putOpt("currentRatio", f.currentRatio); putOpt("freeCashflow", f.freeCashflow)
        putOpt("operatingCashflow", f.operatingCashflow)
        putOpt("returnOnEquityPct", f.returnOnEquityPct)
        putOpt("targetMean", f.targetMean); putOpt("targetHigh", f.targetHigh)
        putOpt("targetLow", f.targetLow); putOpt("analystCount", f.analystCount)
        putOpt("recommendationMean", f.recommendationMean)
        putOpt("recommendationKey", f.recommendationKey)
        putOpt("nextEarningsTs", f.nextEarningsTs); putOpt("dividendDateTs", f.dividendDateTs)
    }.toString()

    private fun fromJson(json: String): Fundamentals? = try {
        val o = JSONObject(json)
        fun d(key: String): Double? = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
        fun l(key: String): Long? = if (o.has(key) && !o.isNull(key)) o.getLong(key) else null
        fun i(key: String): Int? = if (o.has(key) && !o.isNull(key)) o.getInt(key) else null
        fun s(key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.getString(key).ifBlank { null } else null
        Fundamentals(
            symbol = o.getString("symbol"),
            fetchedAt = o.optLong("fetchedAt", 0L),
            sector = s("sector"), industry = s("industry"),
            description = s("description"), employees = i("employees"), website = s("website"),
            marketCap = d("marketCap"), trailingPE = d("trailingPE"), forwardPE = d("forwardPE"),
            priceToBook = d("priceToBook"), pegRatio = d("pegRatio"),
            epsTrailing = d("epsTrailing"), epsForward = d("epsForward"), beta = d("beta"),
            dividendYieldPct = d("dividendYieldPct"), dividendRate = d("dividendRate"),
            exDividendTs = l("exDividendTs"), payoutRatioPct = d("payoutRatioPct"),
            totalRevenue = d("totalRevenue"), revenueGrowthPct = d("revenueGrowthPct"),
            grossMarginPct = d("grossMarginPct"), operatingMarginPct = d("operatingMarginPct"),
            profitMarginPct = d("profitMarginPct"), totalCash = d("totalCash"),
            totalDebt = d("totalDebt"), debtToEquity = d("debtToEquity"),
            currentRatio = d("currentRatio"), freeCashflow = d("freeCashflow"),
            operatingCashflow = d("operatingCashflow"), returnOnEquityPct = d("returnOnEquityPct"),
            targetMean = d("targetMean"), targetHigh = d("targetHigh"), targetLow = d("targetLow"),
            analystCount = i("analystCount"), recommendationMean = d("recommendationMean"),
            recommendationKey = s("recommendationKey"),
            nextEarningsTs = l("nextEarningsTs"), dividendDateTs = l("dividendDateTs")
        )
    } catch (_: Exception) {
        null
    }
}
