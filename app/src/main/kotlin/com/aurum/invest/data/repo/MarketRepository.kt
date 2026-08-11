package com.aurum.invest.data.repo

import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.remote.YahooClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-through cached market data. Fresh cache entries (within maxAge) are served
 * directly; otherwise the network is tried and, on failure, the stale cache is
 * served as a fallback. Never throws — returns null/empty on total failure.
 */
class MarketRepository(
    private val yahoo: YahooClient,
    private val cacheDao: CacheDao
) {

    suspend fun getQuote(symbol: String, maxAgeMs: Long = 60_000L): Quote? {
        val key = "quote:$symbol"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            quoteFromJson(cached.json)?.let { return it }
        }
        val fresh = yahoo.fetchQuote(symbol)
        if (fresh != null) {
            writeCache(key, quoteToJson(fresh).toString())
            return fresh
        }
        // Network failed — serve stale cache when available.
        return cached?.let { quoteFromJson(it.json) }
    }

    /** Fetches quotes concurrently; symbols that fail are simply absent from the map. */
    suspend fun getQuotes(symbols: List<String>, maxAgeMs: Long = 60_000L): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        return coroutineScope {
            symbols.distinct()
                .map { symbol -> async { symbol to getQuote(symbol, maxAgeMs) } }
                .awaitAll()
                .mapNotNull { (symbol, quote) -> quote?.let { symbol to it } }
                .toMap()
        }
    }

    suspend fun getDailyCandles(
        symbol: String,
        rangeDays: Int = 120,
        maxAgeMs: Long = 21_600_000L
    ): List<Candle> {
        val key = "candles:$symbol:$rangeDays"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            val parsed = candlesFromJson(cached.json)
            if (parsed.isNotEmpty()) return parsed
        }
        val fresh = yahoo.fetchDailyCandles(symbol, rangeDays)
        if (fresh.isNotEmpty()) {
            writeCache(key, candlesToJson(fresh).toString())
            return fresh
        }
        return cached?.let { candlesFromJson(it.json) } ?: emptyList()
    }

    /**
     * Candles for an explicit Yahoo range/interval pair (chart-screen 1W and 1M
     * views). Cached like intraday data — briefly, keyed by range and interval.
     */
    suspend fun getRangeCandles(
        symbol: String,
        range: String,
        interval: String,
        maxAgeMs: Long = 900_000L
    ): List<Candle> {
        val key = "range:$symbol:$range:$interval"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            val parsed = candlesFromJson(cached.json)
            if (parsed.isNotEmpty()) return parsed
        }
        val fresh = yahoo.fetchRangeCandles(symbol, range, interval)
        if (fresh.isNotEmpty()) {
            writeCache(key, candlesToJson(fresh).toString())
            return fresh
        }
        return cached?.let { candlesFromJson(it.json) } ?: emptyList()
    }

    suspend fun getIntraday(symbol: String, maxAgeMs: Long = 300_000L): List<Candle> {
        val key = "intraday:$symbol"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            val parsed = candlesFromJson(cached.json)
            if (parsed.isNotEmpty()) return parsed
        }
        val fresh = yahoo.fetchIntraday(symbol)
        if (fresh.isNotEmpty()) {
            writeCache(key, candlesToJson(fresh).toString())
            return fresh
        }
        return cached?.let { candlesFromJson(it.json) } ?: emptyList()
    }

    suspend fun getGoldCandles(rangeDays: Int = 120): List<Candle> =
        getDailyCandles(GOLD_SYMBOL, rangeDays)

    /** Latest session's pre/post-market read; cached briefly like intraday data. */
    suspend fun getExtendedHours(symbol: String, maxAgeMs: Long = 300_000L): ExtendedHours? {
        val key = "exthours:$symbol"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            extendedFromJson(cached.json)?.let { return it }
        }
        val fresh = yahoo.fetchExtendedHours(symbol)
        if (fresh != null) {
            writeCache(key, extendedToJson(fresh).toString())
            return fresh
        }
        return cached?.let { extendedFromJson(it.json) }
    }

    /** One of Yahoo's market-wide predefined screens; cached briefly. */
    suspend fun getScreener(scrId: String, maxAgeMs: Long = 1_200_000L): List<ScreenerQuote> {
        val key = "screener:$scrId"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs) {
            val parsed = screenerFromJson(cached.json)
            if (parsed.isNotEmpty()) return parsed
        }
        val fresh = yahoo.fetchScreener(scrId)
        if (fresh.isNotEmpty()) {
            writeCache(key, screenerToJson(fresh).toString())
            return fresh
        }
        return cached?.let { screenerFromJson(it.json) } ?: emptyList()
    }

    suspend fun search(query: String): List<Pair<String, String>> =
        try {
            yahoo.searchSymbols(query)
        } catch (_: Exception) {
            emptyList()
        }

    // ---- cache plumbing ----------------------------------------------------

    private suspend fun readCache(key: String): CacheEntity? =
        try {
            cacheDao.get(key)
        } catch (_: Exception) {
            null
        }

    private suspend fun writeCache(key: String, json: String) {
        try {
            cacheDao.put(CacheEntity(key = key, json = json, updatedAt = System.currentTimeMillis()))
        } catch (_: Exception) {
            // Cache write failure is non-fatal.
        }
    }

    // ---- JSON serialization ------------------------------------------------

    private fun quoteToJson(q: Quote): JSONObject = JSONObject().apply {
        put("symbol", q.symbol)
        put("price", q.price)
        put("prevClose", q.prevClose)
        put("currency", q.currency)
        put("marketState", q.marketState)
        put("shortName", q.shortName)
        put("fetchedAt", q.fetchedAt)
        if (q.dayHigh != null) put("dayHigh", q.dayHigh)
        if (q.dayLow != null) put("dayLow", q.dayLow)
        if (q.fiftyTwoWeekHigh != null) put("w52High", q.fiftyTwoWeekHigh)
        if (q.fiftyTwoWeekLow != null) put("w52Low", q.fiftyTwoWeekLow)
        if (q.volume != null) put("volume", q.volume)
    }

    private fun quoteFromJson(s: String): Quote? =
        try {
            val o = JSONObject(s)
            Quote(
                symbol = o.getString("symbol"),
                price = o.getDouble("price"),
                prevClose = o.getDouble("prevClose"),
                currency = o.optString("currency", "USD"),
                marketState = o.optString("marketState", ""),
                shortName = o.optString("shortName", ""),
                fetchedAt = o.optLong("fetchedAt", 0L),
                dayHigh = if (o.has("dayHigh")) o.getDouble("dayHigh") else null,
                dayLow = if (o.has("dayLow")) o.getDouble("dayLow") else null,
                fiftyTwoWeekHigh = if (o.has("w52High")) o.getDouble("w52High") else null,
                fiftyTwoWeekLow = if (o.has("w52Low")) o.getDouble("w52Low") else null,
                volume = if (o.has("volume")) o.getLong("volume") else null
            )
        } catch (_: Exception) {
            null
        }

    private fun extendedToJson(e: ExtendedHours): JSONObject = JSONObject().apply {
        put("symbol", e.symbol)
        put("prevClose", e.prevClose)
        put("regularPrice", e.regularPrice)
        if (e.preMarketPct != null) put("preMarketPct", e.preMarketPct)
        if (e.postMarketPct != null) put("postMarketPct", e.postMarketPct)
        put("marketState", e.marketState)
    }

    private fun extendedFromJson(s: String): ExtendedHours? =
        try {
            val o = JSONObject(s)
            ExtendedHours(
                symbol = o.getString("symbol"),
                prevClose = o.getDouble("prevClose"),
                regularPrice = o.getDouble("regularPrice"),
                preMarketPct = if (o.has("preMarketPct")) o.getDouble("preMarketPct") else null,
                postMarketPct = if (o.has("postMarketPct")) o.getDouble("postMarketPct") else null,
                marketState = o.optString("marketState", "")
            )
        } catch (_: Exception) {
            null
        }

    private fun screenerToJson(quotes: List<ScreenerQuote>): JSONArray {
        val arr = JSONArray()
        for (q in quotes) {
            arr.put(
                JSONObject().apply {
                    put("symbol", q.symbol)
                    put("name", q.name)
                    put("price", q.price)
                    put("dayChangePct", q.dayChangePct)
                    put("avgVolume3M", q.avgVolume3M)
                    put("marketCap", q.marketCap)
                    put("fiftyDayAvg", q.fiftyDayAvg)
                    put("twoHundredDayAvg", q.twoHundredDayAvg)
                    put("fiftyTwoWeekHigh", q.fiftyTwoWeekHigh)
                    if (q.analystRating != null) put("analystRating", q.analystRating)
                    put("dayHigh", q.dayHigh)
                    put("dayLow", q.dayLow)
                    put("dayVolume", q.dayVolume)
                }
            )
        }
        return arr
    }

    private fun screenerFromJson(s: String): List<ScreenerQuote> =
        try {
            val arr = JSONArray(s)
            val out = ArrayList<ScreenerQuote>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ScreenerQuote(
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.getDouble("price"),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        avgVolume3M = o.optLong("avgVolume3M", 0L),
                        marketCap = o.optDouble("marketCap", 0.0),
                        fiftyDayAvg = o.optDouble("fiftyDayAvg", 0.0),
                        twoHundredDayAvg = o.optDouble("twoHundredDayAvg", 0.0),
                        fiftyTwoWeekHigh = o.optDouble("fiftyTwoWeekHigh", 0.0),
                        analystRating = if (o.has("analystRating")) o.getDouble("analystRating") else null,
                        dayHigh = o.optDouble("dayHigh", 0.0),
                        dayLow = o.optDouble("dayLow", 0.0),
                        dayVolume = o.optLong("dayVolume", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }

    private fun candlesToJson(candles: List<Candle>): JSONArray {
        val arr = JSONArray()
        for (c in candles) {
            arr.put(
                JSONObject().apply {
                    put("ts", c.ts)
                    put("open", c.open)
                    put("high", c.high)
                    put("low", c.low)
                    put("close", c.close)
                    put("volume", c.volume)
                }
            )
        }
        return arr
    }

    private fun candlesFromJson(s: String): List<Candle> =
        try {
            val arr = JSONArray(s)
            val out = ArrayList<Candle>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Candle(
                        ts = o.getLong("ts"),
                        open = o.getDouble("open"),
                        high = o.getDouble("high"),
                        low = o.getDouble("low"),
                        close = o.getDouble("close"),
                        volume = o.optLong("volume", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        const val GOLD_SYMBOL = "GLD"
    }
}
