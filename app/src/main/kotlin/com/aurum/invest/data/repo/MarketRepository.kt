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

    /**
     * The full read for one symbol (price, ranges, volume, name). A cached
     * LITE entry from the batch endpoint is not enough here, so it is
     * refreshed rather than returned.
     */
    suspend fun getQuote(symbol: String, maxAgeMs: Long = 60_000L): Quote? {
        val key = "quote:$symbol"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        val cachedQuote = cached?.let { quoteFromJson(it.json) }
        if (cachedQuote != null && !cachedQuote.lite && now - cached.updatedAt <= maxAgeMs) {
            return cachedQuote
        }
        val fresh = yahoo.fetchQuote(symbol)
        if (fresh != null) {
            writeCache(key, quoteToJson(fresh).toString())
            return fresh
        }
        // Network failed — serve stale cache when available.
        return cachedQuote
    }

    /**
     * Quotes for many symbols. Fresh cache entries are served directly and
     * everything else goes out in BATCHES (one request per [BATCH_SIZE]
     * symbols) instead of one request per symbol — the difference between a
     * list screen making 60 calls and making 2. Symbols that fail are absent.
     */
    suspend fun getQuotes(symbols: List<String>, maxAgeMs: Long = 60_000L): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        val wanted = symbols.distinct()
        val now = System.currentTimeMillis()
        val out = HashMap<String, Quote>(wanted.size)
        val stale = HashMap<String, Quote>()
        val misses = ArrayList<String>()

        for (symbol in wanted) {
            val cached = readCache("quote:$symbol")
            val quote = cached?.let { quoteFromJson(it.json) }
            if (quote != null && now - cached.updatedAt <= maxAgeMs) {
                out[symbol] = quote
            } else {
                if (quote != null) stale[symbol] = quote
                misses.add(symbol)
            }
        }
        if (misses.isEmpty()) return out

        val fetched = coroutineScope {
            misses.chunked(BATCH_SIZE)
                .map { chunk -> async { yahoo.fetchQuotesBatch(chunk) } }
                .awaitAll()
        }.fold(HashMap<String, Quote>()) { acc, map -> acc.apply { putAll(map) } }

        for (symbol in misses) {
            val fresh = fetched[symbol]
            if (fresh != null) {
                // Carry forward richer fields from a stale full entry so the
                // batch read never erases a name or range we already knew.
                val prior = stale[symbol]
                val merged =
                    if (prior != null && !prior.lite) {
                        fresh.copy(
                            currency = prior.currency,
                            shortName = prior.shortName,
                            dayHigh = prior.dayHigh,
                            dayLow = prior.dayLow,
                            fiftyTwoWeekHigh = prior.fiftyTwoWeekHigh,
                            fiftyTwoWeekLow = prior.fiftyTwoWeekLow,
                            volume = prior.volume
                        )
                    } else fresh
                writeCache("quote:$symbol", quoteToJson(merged).toString())
                out[symbol] = merged
            } else {
                // Batch failed for this symbol — stale beats nothing.
                stale[symbol]?.let { out[symbol] = it }
            }
        }
        return out
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

    /**
     * Yahoo's sector for a symbol. Effectively static data — cached 30 days;
     * only successful lookups are cached, so an unknown stays honestly null
     * (never guessed) and is retried on a later visit.
     */
    suspend fun getSector(symbol: String, maxAgeMs: Long = 30L * 24 * 3_600_000L): String? {
        val key = "sector:$symbol"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        if (cached != null && now - cached.updatedAt <= maxAgeMs && cached.json.isNotBlank()) {
            return cached.json
        }
        val fresh = yahoo.fetchSector(symbol)
        if (!fresh.isNullOrBlank()) {
            writeCache(key, fresh)
            return fresh
        }
        return cached?.json?.takeIf { it.isNotBlank() }
    }

    /** Sectors for many symbols, concurrently; unknown symbols are simply absent. */
    suspend fun getSectors(symbols: List<String>): Map<String, String> {
        if (symbols.isEmpty()) return emptyMap()
        return coroutineScope {
            symbols.distinct()
                .map { s -> async { s to getSector(s) } }
                .awaitAll()
                .mapNotNull { (s, sector) -> sector?.let { s to it } }
                .toMap()
        }
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
        if (q.lite) put("lite", true)
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
                volume = if (o.has("volume")) o.getLong("volume") else null,
                lite = o.optBoolean("lite", false)
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

        /** Symbols per spark request. Yahoo handles this comfortably in one call. */
        private const val BATCH_SIZE = 40
    }
}
