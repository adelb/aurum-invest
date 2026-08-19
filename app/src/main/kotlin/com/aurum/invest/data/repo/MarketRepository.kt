package com.aurum.invest.data.repo

import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.CandleFeed
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.FeedStatus
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
    ): List<Candle> = getDailyCandlesFeed(symbol, rangeDays, maxAgeMs).candles

    /**
     * Daily candles WITH provenance: an empty FRESH feed means the symbol was
     * reached and genuinely has that little history; FAILED means the fetch
     * broke and nothing is cached — different diagnoses, never conflated.
     *
     * Storage is SUPERSET-RANGED: one canonical series per symbol, kept at
     * the deepest range any caller has asked for. A 60-day caller of a symbol
     * whose year is already cached is served a slice of it — zero requests —
     * and the slice reproduces exactly the Yahoo bucket a direct fetch would
     * have returned, so no caller ever sees fewer bars than before.
     */
    suspend fun getDailyCandlesFeed(
        symbol: String,
        rangeDays: Int = 120,
        maxAgeMs: Long = 21_600_000L
    ): CandleFeed {
        val key = "candles:$symbol"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        val stored = cached?.let { candleStoreFromJson(it.json) }
        if (stored != null && stored.first >= bucketDays(rangeDays) &&
            now - cached.updatedAt <= maxAgeMs
        ) {
            val sliced = sliceToBucket(stored.second, rangeDays, cached.updatedAt)
            if (sliced.isNotEmpty()) return CandleFeed(sliced, FeedStatus.FRESH, cached.updatedAt)
        }
        // Fetch at least as deep as the canonical series already goes, so a
        // shallow caller can never narrow what a deep caller relies on.
        val fetchDays = maxOf(rangeDays, stored?.first ?: 0)
        val fresh = yahoo.fetchDailyCandles(symbol, fetchDays)
        if (fresh.isNotEmpty()) {
            writeCache(key, candleStoreToJson(bucketDays(fetchDays), fresh))
            return CandleFeed(sliceToBucket(fresh, rangeDays, now), FeedStatus.FRESH, now)
        }
        val staleCanonical = stored?.second
            ?.let { sliceToBucket(it, rangeDays, cached!!.updatedAt) }
            .orEmpty()
        if (staleCanonical.isNotEmpty()) {
            return CandleFeed(staleCanonical, FeedStatus.STALE, cached!!.updatedAt)
        }
        // Last resort: the pre-v10 per-range key, so an upgrade with the
        // network down still serves what it had.
        val legacy = readCache("candles:$symbol:$rangeDays")
        val legacyCandles = legacy?.let { candlesFromJson(it.json) }.orEmpty()
        return if (legacyCandles.isNotEmpty()) {
            CandleFeed(legacyCandles, FeedStatus.STALE, legacy!!.updatedAt)
        } else {
            CandleFeed(emptyList(), FeedStatus.FAILED, 0L)
        }
    }

    /**
     * Daily CLOSES for many symbols, in one request per [BATCH_SIZE] rather
     * than one per symbol. The returned candles carry the close in their
     * open/high/low too, and no volume at all — the spark endpoint simply
     * does not send those. So they are cached under their OWN key and never
     * touch `candles:`: anything reading a high or a volume must still go
     * through [getDailyCandles], and a caller that mixed the two would
     * silently read a zero volume as a genuine one.
     *
     * This is what a browse shelf loads. Reading a month of closes one
     * request per symbol is what puts a device over Yahoo's per-IP limit.
     */
    suspend fun getCloseSeries(
        symbols: List<String>,
        rangeDays: Int = 30,
        maxAgeMs: Long = 21_600_000L
    ): Map<String, List<Candle>> {
        if (symbols.isEmpty()) return emptyMap()
        val wanted = symbols.distinct()
        val now = System.currentTimeMillis()
        val out = HashMap<String, List<Candle>>(wanted.size)
        val stale = HashMap<String, List<Candle>>()
        val misses = ArrayList<String>()

        for (symbol in wanted) {
            val cached = readCache("closes:$symbol:$rangeDays")
            val series = cached?.let { candlesFromJson(it.json) }.orEmpty()
            if (series.isNotEmpty() && now - cached!!.updatedAt <= maxAgeMs) {
                out[symbol] = series
            } else {
                if (series.isNotEmpty()) stale[symbol] = series
                misses.add(symbol)
            }
        }
        if (misses.isEmpty()) return out

        val range = sparkRangeFor(rangeDays)
        val fetched = coroutineScope {
            misses.chunked(BATCH_SIZE)
                .map { chunk -> async { yahoo.fetchSparkCloses(chunk, range) } }
                .awaitAll()
        }.fold(HashMap<String, List<Candle>>()) { acc, map -> acc.apply { putAll(map) } }

        for (symbol in misses) {
            val fresh = fetched[symbol]
            if (fresh != null && fresh.isNotEmpty()) {
                writeCache("closes:$symbol:$rangeDays", candlesToJson(fresh).toString())
                out[symbol] = fresh
            } else {
                stale[symbol]?.let { out[symbol] = it }
            }
        }
        return out
    }

    /** Spark takes the same range words the chart API does. */
    private fun sparkRangeFor(rangeDays: Int): String = when {
        rangeDays <= 7 -> "5d"
        rangeDays <= 30 -> "1mo"
        rangeDays <= 95 -> "3mo"
        rangeDays <= 190 -> "6mo"
        rangeDays <= 400 -> "1y"
        else -> "2y"
    }

    /** The canonical per-symbol store: the depth it was fetched at, plus the bars. */
    private fun candleStoreToJson(rangeDays: Int, candles: List<Candle>): String =
        JSONObject().apply {
            put("range", rangeDays)
            put("candles", candlesToJson(candles))
        }.toString()

    private fun candleStoreFromJson(s: String): Pair<Int, List<Candle>>? = try {
        val o = JSONObject(s)
        val range = o.optInt("range", 0)
        val candles = candlesFromJson(o.optString("candles"))
        if (range <= 0 || candles.isEmpty()) null else range to candles
    } catch (_: Exception) {
        null
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

    /**
     * Next earnings dates for many symbols, cached 12 hours per symbol. A
     * cached entry with no date means "checked, none known" (ETFs, funds) and
     * is served without a refetch; a symbol whose lookup failed is absent and
     * retried next call — the earnings gate treats absent as unmeasured.
     */
    suspend fun getEarningsDates(
        symbols: List<String>,
        maxAgeMs: Long = 43_200_000L
    ): Map<String, com.aurum.invest.data.model.EarningsInfo> {
        if (symbols.isEmpty()) return emptyMap()
        val wanted = symbols.map { it.trim().uppercase() }.distinct()
        val now = System.currentTimeMillis()
        val out = HashMap<String, com.aurum.invest.data.model.EarningsInfo>(wanted.size)
        val misses = ArrayList<String>()
        for (symbol in wanted) {
            val cached = readCache("earnings:$symbol")
            val info = cached?.let { earningsFromJson(it.json) }
            // A known date that has already passed is stale whatever its age —
            // the next report's date needs a fresh look.
            val passed = info?.nextTs?.let { it < now - 86_400_000L } == true
            if (info != null && now - cached.updatedAt <= maxAgeMs && !passed) {
                out[symbol] = info
            } else {
                misses.add(symbol)
            }
        }
        if (misses.isEmpty()) return out
        val fetched = coroutineScope {
            misses.chunked(BATCH_SIZE)
                .map { chunk -> async { yahoo.fetchEarningsDates(chunk) } }
                .awaitAll()
        }.fold(HashMap<String, com.aurum.invest.data.model.EarningsInfo>()) { acc, map ->
            acc.apply { putAll(map) }
        }
        for ((symbol, info) in fetched) {
            writeCache("earnings:$symbol", earningsToJson(info).toString())
            out[symbol] = info
        }
        return out
    }

    private fun earningsToJson(e: com.aurum.invest.data.model.EarningsInfo): JSONObject =
        JSONObject().apply {
            put("symbol", e.symbol)
            if (e.nextTs != null) put("next", e.nextTs)
            put("est", e.estimate)
            put("at", e.fetchedAt)
        }

    private fun earningsFromJson(s: String): com.aurum.invest.data.model.EarningsInfo? = try {
        val o = JSONObject(s)
        com.aurum.invest.data.model.EarningsInfo(
            symbol = o.getString("symbol"),
            nextTs = if (o.has("next")) o.getLong("next") else null,
            estimate = o.optBoolean("est", false),
            fetchedAt = o.optLong("at", 0L)
        )
    } catch (_: Exception) {
        null
    }

    /**
     * A company's fundamental snapshot, cached 24 hours. Null when the
     * lookup failed and nothing is cached — the study engine reports the
     * whole fundamentals factor unmeasured rather than guessing.
     */
    suspend fun getFundamentals(
        symbol: String,
        maxAgeMs: Long = 86_400_000L
    ): com.aurum.invest.data.model.Fundamentals? {
        val key = "fundamentals:${symbol.trim().uppercase()}"
        val now = System.currentTimeMillis()
        val cached = readCache(key)
        val stored = cached?.let { fundamentalsFromJson(it.json) }
        if (stored != null && now - cached.updatedAt <= maxAgeMs) return stored
        val fresh = yahoo.fetchFundamentals(symbol.trim().uppercase())
        if (fresh != null) {
            writeCache(key, fundamentalsToJson(fresh).toString())
            return fresh
        }
        return stored
    }

    private fun fundamentalsToJson(f: com.aurum.invest.data.model.Fundamentals): JSONObject =
        JSONObject().apply {
            put("symbol", f.symbol)
            put("at", f.fetchedAt)
            putOpt("cap", f.marketCap)
            putOpt("tpe", f.trailingPE)
            putOpt("fpe", f.forwardPE)
            putOpt("beta", f.beta)
            putOpt("div", f.dividendYield)
            putOpt("debt", f.totalDebt)
            putOpt("cash", f.totalCash)
            putOpt("dte", f.debtToEquity)
            putOpt("margins", f.profitMargins)
            putOpt("revg", f.revenueGrowth)
            putOpt("earng", f.earningsGrowth)
            putOpt("fcf", f.freeCashflow)
            putOpt("pb", f.priceToBook)
            putOpt("shortf", f.shortPctFloat)
            putOpt("target", f.targetMeanPrice)
            putOpt("rec", f.recommendationMean)
            putOpt("analysts", f.analystCount)
        }

    private fun fundamentalsFromJson(s: String): com.aurum.invest.data.model.Fundamentals? = try {
        val o = JSONObject(s)
        fun d(key: String): Double? =
            if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
        com.aurum.invest.data.model.Fundamentals(
            symbol = o.getString("symbol"),
            marketCap = d("cap"),
            trailingPE = d("tpe"),
            forwardPE = d("fpe"),
            beta = d("beta"),
            dividendYield = d("div"),
            totalDebt = d("debt"),
            totalCash = d("cash"),
            debtToEquity = d("dte"),
            profitMargins = d("margins"),
            revenueGrowth = d("revg"),
            earningsGrowth = d("earng"),
            freeCashflow = d("fcf"),
            priceToBook = d("pb"),
            shortPctFloat = d("shortf"),
            targetMeanPrice = d("target"),
            recommendationMean = d("rec"),
            analystCount = d("analysts")?.toInt(),
            fetchedAt = o.optLong("at", 0L)
        )
    } catch (_: Exception) {
        null
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
        if (e.preMarketPrice != null) put("preMarketPrice", e.preMarketPrice)
        if (e.postMarketPrice != null) put("postMarketPrice", e.postMarketPrice)
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
                marketState = o.optString("marketState", ""),
                preMarketPrice = if (o.has("preMarketPrice")) o.getDouble("preMarketPrice") else null,
                postMarketPrice = if (o.has("postMarketPrice")) o.getDouble("postMarketPrice") else null
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

        private const val DAY_MS = 86_400_000L

        /**
         * The calendar days Yahoo's chart API actually returns for a
         * [rangeDays] request — its range parameter is a coarse bucket
         * (5d / 1mo / 3mo / 6mo / 1y / 2y), so a "60-day" fetch has always
         * come back with three months of bars. Slicing the canonical series
         * to the SAME bucket keeps every caller's bar count identical to
         * what a direct fetch gave it.
         */
        fun bucketDays(rangeDays: Int): Int = when {
            rangeDays <= 7 -> 7
            rangeDays <= 30 -> 30
            rangeDays <= 95 -> 95
            rangeDays <= 190 -> 190
            rangeDays <= 400 -> 400
            else -> 730
        }

        /**
         * The tail of [candles] a [rangeDays] request would have fetched
         * directly, measured back from [asOf] (the store's fetch time, so a
         * stale slice is judged against when the bars were read, not now).
         */
        fun sliceToBucket(candles: List<Candle>, rangeDays: Int, asOf: Long): List<Candle> {
            if (candles.isEmpty()) return candles
            val cutoff = asOf - bucketDays(rangeDays) * DAY_MS
            var start = candles.size
            while (start > 0 && candles[start - 1].ts >= cutoff) start--
            return if (start == 0) candles else candles.subList(start, candles.size)
        }

        /** Symbols per spark request. Yahoo handles this comfortably in one call. */
        private const val BATCH_SIZE = 40
    }
}
