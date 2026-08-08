package com.aurum.invest.data.repo

import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Quote
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
                fetchedAt = o.optLong("fetchedAt", 0L)
            )
        } catch (_: Exception) {
            null
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
