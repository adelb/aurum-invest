package com.aurum.invest.data.remote

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.model.ScreenerQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin Yahoo Finance client over the public v8 chart and v1 search endpoints.
 * All calls run on [Dispatchers.IO] and never throw — failures return null/empty.
 */
class YahooClient {

    /** v8 chart API, range=1d interval=1m — latest price + previous close from meta. */
    suspend fun fetchQuote(symbol: String): Quote? = withContext(Dispatchers.IO) {
        try {
            val root = getJson(chartUrl(symbol, range = "1d", interval = "1m")) ?: return@withContext null
            val result = chartResult(root) ?: return@withContext null
            val meta = result.optJSONObject("meta") ?: return@withContext null
            val price = metaDouble(meta, "regularMarketPrice") ?: return@withContext null
            val prevClose = metaDouble(meta, "chartPreviousClose")
                ?: metaDouble(meta, "previousClose")
                ?: price
            Quote(
                symbol = symbol,
                price = price,
                prevClose = prevClose,
                currency = meta.optString("currency", "USD").ifEmpty { "USD" },
                marketState = meta.optString("marketState", ""),
                shortName = meta.optString("shortName", ""),
                fetchedAt = System.currentTimeMillis(),
                dayHigh = metaDouble(meta, "regularMarketDayHigh"),
                dayLow = metaDouble(meta, "regularMarketDayLow"),
                fiftyTwoWeekHigh = metaDouble(meta, "fiftyTwoWeekHigh"),
                fiftyTwoWeekLow = metaDouble(meta, "fiftyTwoWeekLow"),
                volume = meta.optLong("regularMarketVolume", -1L).takeIf { it >= 0L }
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * v8 spark API — MANY symbols in ONE request. Returns price + previous
     * close only, so results are marked [Quote.lite]. This is what keeps list
     * screens from firing one request per symbol (and tripping Yahoo's
     * per-IP throttling). Symbols that fail are simply absent.
     */
    suspend fun fetchQuotesBatch(symbols: List<String>): Map<String, Quote> =
        withContext(Dispatchers.IO) {
            if (symbols.isEmpty()) return@withContext emptyMap()
            try {
                val url = "https://query1.finance.yahoo.com/v8/finance/spark".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("symbols", symbols.joinToString(","))
                    .addQueryParameter("range", "1d")
                    // 1m, not 5m. This series IS the live price on every list
                    // screen, so the interval is the finest the price can ever
                    // move: at 5m a "live" figure re-read every second still
                    // could not change more than twelve times an hour, and sat
                    // visibly frozen between bars while the market traded.
                    .addQueryParameter("interval", "1m")
                    .build()
                    .toString()
                val root = getJson(url) ?: return@withContext emptyMap()
                val now = System.currentTimeMillis()
                val out = HashMap<String, Quote>(symbols.size)
                for (symbol in symbols) {
                    val o = root.optJSONObject(symbol) ?: continue
                    val closes = o.optJSONArray("close") ?: continue
                    // Walk back from the newest bar: the last entries can be null.
                    var price = Double.NaN
                    for (i in closes.length() - 1 downTo 0) {
                        if (closes.isNull(i)) continue
                        val v = closes.optDouble(i, Double.NaN)
                        if (!v.isNaN() && v > 0.0) {
                            price = v
                            break
                        }
                    }
                    if (price.isNaN()) continue
                    val prevClose = listOf("chartPreviousClose", "previousClose")
                        .firstNotNullOfOrNull { key ->
                            if (!o.has(key) || o.isNull(key)) null
                            else o.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
                        } ?: price
                    out[symbol] = Quote(
                        symbol = symbol,
                        price = price,
                        prevClose = prevClose,
                        fetchedAt = now,
                        lite = true
                    )
                }
                out
            } catch (_: Exception) {
                emptyMap()
            }
        }

    /** v8 chart API, interval=1d; range picked from [rangeDays]. */
    suspend fun fetchDailyCandles(symbol: String, rangeDays: Int): List<Candle> =
        withContext(Dispatchers.IO) {
            try {
                val range = when {
                    rangeDays <= 7 -> "5d"
                    rangeDays <= 30 -> "1mo"
                    rangeDays <= 95 -> "3mo"
                    rangeDays <= 190 -> "6mo"
                    rangeDays <= 400 -> "1y"
                    else -> "2y"
                }
                val root = getJson(chartUrl(symbol, range = range, interval = "1d"))
                    ?: return@withContext emptyList()
                parseCandles(root)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * v8 chart API with an explicit Yahoo [range] ("5d", "1mo", ...) and
     * [interval] ("30m", "60m", "1d", ...) — the chart-screen ranges that
     * don't fit the daily/intraday helpers.
     */
    suspend fun fetchRangeCandles(symbol: String, range: String, interval: String): List<Candle> =
        withContext(Dispatchers.IO) {
            try {
                val root = getJson(chartUrl(symbol, range = range, interval = interval))
                    ?: return@withContext emptyList()
                parseCandles(root)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** v8 chart API, range=1d interval=5m. */
    suspend fun fetchIntraday(symbol: String): List<Candle> = withContext(Dispatchers.IO) {
        try {
            val root = getJson(chartUrl(symbol, range = "1d", interval = "5m"))
                ?: return@withContext emptyList()
            parseCandles(root)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * v8 chart API, range=1d interval=5m with includePrePost — the latest
     * session's pre-market and post-market moves, bounded to the pre/post
     * windows of the day the candles belong to (meta.tradingPeriods). The
     * pre-market read lives only while its session runs; the after-hours read
     * survives overnight and weekends, because the last extended print stays
     * the freshest information until the next session starts.
     */
    suspend fun fetchExtendedHours(symbol: String): ExtendedHours? = withContext(Dispatchers.IO) {
        try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart".toHttpUrl()
                .newBuilder()
                .addPathSegment(symbol)
                .addQueryParameter("range", "1d")
                .addQueryParameter("interval", "5m")
                .addQueryParameter("includePrePost", "true")
                .build()
                .toString()
            val root = getJson(url) ?: return@withContext null
            val result = chartResult(root) ?: return@withContext null
            val meta = result.optJSONObject("meta") ?: return@withContext null
            val regularPrice = metaDouble(meta, "regularMarketPrice") ?: return@withContext null
            val prevClose = metaDouble(meta, "chartPreviousClose")
                ?: metaDouble(meta, "previousClose")
                ?: regularPrice

            // Two distinct sets of session windows live in the meta:
            //  - currentTradingPeriod: the CURRENT (or next) session. Overnight
            //    and on weekends this points at a session that has not traded
            //    yet, so its windows match NONE of the returned bars.
            //  - tradingPeriods: the windows of the day the candles actually
            //    belong to. This is the one the bars must be bounded by —
            //    keying off currentTradingPeriod made every pre/post read
            //    disappear overnight (the returned bars are yesterday's, the
            //    windows were tomorrow's).
            val current = meta.optJSONObject("currentTradingPeriod")
            fun windowOf(container: JSONObject?, key: String): Pair<Long, Long> {
                val direct = container?.optJSONObject(key)
                if (direct != null) {
                    return direct.optLong("start", 0L) * 1000L to
                        direct.optLong("end", 0L) * 1000L
                }
                // tradingPeriods nests each window as [[{start, end}]].
                val nested = container?.optJSONArray(key)
                    ?.optJSONArray(0)
                    ?.optJSONObject(0)
                    ?: return 0L to 0L
                return nested.optLong("start", 0L) * 1000L to
                    nested.optLong("end", 0L) * 1000L
            }

            val candleDay = meta.optJSONObject("tradingPeriods") ?: current
            val (preStart, preEnd) = windowOf(candleDay, "pre")
            val (regStart, regEnd) = windowOf(candleDay, "regular")
            val (_, postEnd) = windowOf(candleDay, "post")

            val candles = parseCandles(root)
            var preMarketPct: Double? = null
            var postMarketPct: Double? = null
            var preMarketPrice: Double? = null
            var postMarketPrice: Double? = null
            val now = System.currentTimeMillis()

            // Pre-market = bars inside the candle day's pre window, shown only
            // while that day's session is still running (pre through post).
            // Once the day is over the morning gap is stale news — the
            // after-hours read below is the one that still matters.
            //
            // Baseline: before the open Yahoo anchors this chart's meta to the
            // LAST regular session, so chartPreviousClose is one day too old
            // and the true "yesterday's close" is regularMarketPrice. Once the
            // day's regular session has traded, chartPreviousClose is correct.
            val regularTime = meta.optLong("regularMarketTime", 0L) * 1000L
            val sessionLive = postEnd <= 0L || now <= postEnd
            val preBase =
                if (regularTime in 1 until regStart) regularPrice else prevClose
            if (preStart in 1 until preEnd && preBase > 0.0 && sessionLive) {
                candles.lastOrNull { it.ts in preStart until preEnd }?.let { pre ->
                    preMarketPct = (pre.close - preBase) / preBase * 100.0
                    preMarketPrice = pre.close
                }
            }
            // After-hours = bars past the candle day's regular close. These
            // stay visible overnight and across the weekend — the last
            // extended print IS the freshest information until the next
            // session starts (a new day's chart replaces the bars naturally).
            if (regEnd > 0L) {
                val lastPost = candles.lastOrNull {
                    it.ts >= regEnd && (postEnd <= 0L || it.ts <= postEnd)
                }
                val regClose = candles.lastOrNull { it.ts in regStart until regEnd }?.close
                    ?: regularPrice
                if (lastPost != null && regClose > 0.0) {
                    postMarketPct = (lastPost.close - regClose) / regClose * 100.0
                    postMarketPrice = lastPost.close
                }
            }
            // Yahoo no longer sends marketState in chart meta; derive it from
            // the CURRENT session's windows so callers can label prints honestly.
            val (curPreStart, _) = windowOf(current, "pre")
            val (curRegStart, curRegEnd) = windowOf(current, "regular")
            val (_, curPostEnd) = windowOf(current, "post")
            val metaState = meta.optString("marketState", "")
            val marketState = when {
                metaState.isNotEmpty() -> metaState
                curPreStart > 0L && now in curPreStart until curRegStart -> "PRE"
                curRegStart > 0L && now in curRegStart until curRegEnd -> "REGULAR"
                curRegEnd > 0L && curPostEnd > curRegEnd &&
                    now in curRegEnd until curPostEnd -> "POST"
                else -> "CLOSED"
            }
            ExtendedHours(
                symbol = symbol,
                // The candle day's true prior close — preBase, not the raw
                // chartPreviousClose. During pre-market Yahoo anchors the
                // chart to the LAST regular session, so chartPreviousClose is
                // one day too old; passing it through made every "prev $X"
                // line disagree with the percentage printed beside it.
                prevClose = if (preBase > 0.0) preBase else prevClose,
                regularPrice = regularPrice,
                preMarketPct = preMarketPct,
                postMarketPct = postMarketPct,
                marketState = marketState,
                preMarketPrice = preMarketPrice,
                postMarketPrice = postMarketPrice
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * v1 predefined screener API — one of Yahoo's market-wide saved screens
     * (most_actives, day_gainers, undervalued_large_caps, ...). US equities
     * priced in USD only; entries missing a price are skipped.
     */
    suspend fun fetchScreener(scrId: String, count: Int = 100): List<ScreenerQuote> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("scrIds", scrId)
                    .addQueryParameter("count", count.toString())
                    .build()
                    .toString()
                val root = getJson(url) ?: return@withContext emptyList()
                val quotes = root.optJSONObject("finance")
                    ?.optJSONArray("result")
                    ?.optJSONObject(0)
                    ?.optJSONArray("quotes")
                    ?: return@withContext emptyList()
                val out = ArrayList<ScreenerQuote>(quotes.length())
                for (i in 0 until quotes.length()) {
                    val q = quotes.optJSONObject(i) ?: continue
                    if (!q.optString("quoteType").equals("EQUITY", ignoreCase = true)) continue
                    if (!q.optString("currency", "USD").equals("USD", ignoreCase = true)) continue
                    if (!q.optString("market", "us_market")
                            .equals("us_market", ignoreCase = true)
                    ) continue
                    val symbol = q.optString("symbol", "")
                    if (symbol.isEmpty()) continue
                    val price = q.optDouble("regularMarketPrice", Double.NaN)
                    if (price.isNaN() || price <= 0.0) continue
                    // "1.7 - Buy" -> 1.7; absent or malformed -> null.
                    val rating = q.optString("averageAnalystRating", "")
                        .substringBefore(" ").trim().toDoubleOrNull()
                    out.add(
                        ScreenerQuote(
                            symbol = symbol,
                            name = q.optString(
                                "displayName",
                                q.optString("shortName", q.optString("longName", ""))
                            ),
                            price = price,
                            dayChangePct = q.optDouble("regularMarketChangePercent", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            avgVolume3M = q.optLong("averageDailyVolume3Month", 0L),
                            marketCap = q.optDouble("marketCap", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            fiftyDayAvg = q.optDouble("fiftyDayAverage", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            twoHundredDayAvg = q.optDouble("twoHundredDayAverage", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            fiftyTwoWeekHigh = q.optDouble("fiftyTwoWeekHigh", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            analystRating = rating,
                            dayHigh = q.optDouble("regularMarketDayHigh", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            dayLow = q.optDouble("regularMarketDayLow", 0.0)
                                .takeIf { !it.isNaN() } ?: 0.0,
                            dayVolume = q.optLong("regularMarketVolume", 0L)
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Yahoo's sector classification for a symbol ("Technology", "Energy", ...),
     * read from the search API's exact-symbol match — the one cookie-free
     * endpoint that carries it. Null when Yahoo doesn't classify the name.
     */
    suspend fun fetchSector(symbol: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://query1.finance.yahoo.com/v1/finance/search".toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", symbol)
                .addQueryParameter("quotesCount", "6")
                .addQueryParameter("newsCount", "0")
                .build()
                .toString()
            val root = getJson(url) ?: return@withContext null
            val quotes = root.optJSONArray("quotes") ?: return@withContext null
            for (i in 0 until quotes.length()) {
                val q = quotes.optJSONObject(i) ?: continue
                if (!q.optString("symbol").equals(symbol, ignoreCase = true)) continue
                val sector = q.optString("sector", "").ifBlank { q.optString("sectorDisp", "") }
                return@withContext sector.ifBlank { null }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** v1 search API — (symbol, shortname) pairs, US equities only, max 8. */
    suspend fun searchSymbols(query: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) return@withContext emptyList()
                val url = "https://query1.finance.yahoo.com/v1/finance/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("quotesCount", "12")
                    .addQueryParameter("newsCount", "0")
                    .build()
                val root = getJson(url.toString()) ?: return@withContext emptyList()
                val quotes = root.optJSONArray("quotes") ?: return@withContext emptyList()
                val out = ArrayList<Pair<String, String>>()
                for (i in 0 until quotes.length()) {
                    val q = quotes.optJSONObject(i) ?: continue
                    val quoteType = q.optString("quoteType", "")
                    if (!quoteType.equals("EQUITY", ignoreCase = true)) continue
                    val market = q.optString("market", "")
                    val exchange = q.optString("exchange", "")
                    val isUs = market.equals("us_market", ignoreCase = true) ||
                        exchange.uppercase() in US_EXCHANGES
                    if (!isUs) continue
                    val symbol = q.optString("symbol", "")
                    if (symbol.isEmpty()) continue
                    val name = q.optString("shortname", q.optString("longname", ""))
                    out.add(symbol to name)
                    if (out.size >= 8) break
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

    // ---- internals ---------------------------------------------------------

    private fun chartUrl(symbol: String, range: String, interval: String): String =
        "https://query1.finance.yahoo.com/v8/finance/chart".toHttpUrl()
            .newBuilder()
            .addPathSegment(symbol)
            .addQueryParameter("range", range)
            .addQueryParameter("interval", interval)
            .build()
            .toString()

    /**
     * Executes a GET and parses the body as JSON. Returns null on any failure.
     * Yahoo answers 429 when a burst comes too fast from one IP; that case
     * gets one backoff retry rather than surfacing as missing data.
     */
    private fun getJson(url: String): JSONObject? {
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 429 && attempt == 0) {
                        Thread.sleep(700L)
                        return@use
                    }
                    if (!response.isSuccessful) return null
                    val body = response.body?.string() ?: return null
                    return JSONObject(body)
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun chartResult(root: JSONObject): JSONObject? {
        val chart = root.optJSONObject("chart") ?: return null
        val results = chart.optJSONArray("result") ?: return null
        if (results.length() == 0) return null
        return results.optJSONObject(0)
    }

    /** Reads a numeric meta field, returning null when absent or non-numeric. */
    private fun metaDouble(meta: JSONObject, key: String): Double? {
        if (!meta.has(key) || meta.isNull(key)) return null
        val v = meta.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    /**
     * Parses chart.result[0].timestamp[] + indicators.quote[0].{open,high,low,close,volume}
     * into candles. Timestamps are epoch seconds -> converted to millis. Bars with a null
     * close are skipped.
     */
    private fun parseCandles(root: JSONObject): List<Candle> {
        val result = chartResult(root) ?: return emptyList()
        val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
        val indicators = result.optJSONObject("indicators") ?: return emptyList()
        val quoteArr = indicators.optJSONArray("quote") ?: return emptyList()
        if (quoteArr.length() == 0) return emptyList()
        val quote = quoteArr.optJSONObject(0) ?: return emptyList()
        val opens = quote.optJSONArray("open")
        val highs = quote.optJSONArray("high")
        val lows = quote.optJSONArray("low")
        val closes = quote.optJSONArray("close") ?: return emptyList()
        val volumes = quote.optJSONArray("volume")

        val out = ArrayList<Candle>(timestamps.length())
        for (i in 0 until timestamps.length()) {
            if (i >= closes.length() || closes.isNull(i)) continue
            val close = closes.optDouble(i, Double.NaN)
            if (close.isNaN()) continue
            val ts = timestamps.optLong(i, -1L)
            if (ts <= 0L) continue
            val open = arrDouble(opens, i) ?: close
            val high = arrDouble(highs, i) ?: close
            val low = arrDouble(lows, i) ?: close
            val volume = arrLong(volumes, i)
            out.add(
                Candle(
                    ts = ts * 1000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
            )
        }
        return out
    }

    private fun arrDouble(arr: JSONArray?, index: Int): Double? {
        if (arr == null || index >= arr.length() || arr.isNull(index)) return null
        val v = arr.optDouble(index, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun arrLong(arr: JSONArray?, index: Int): Long {
        if (arr == null || index >= arr.length() || arr.isNull(index)) return 0L
        return arr.optLong(index, 0L)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        private val US_EXCHANGES = setOf(
            "NYQ", "NMS", "NGM", "NCM", "ASE", "PCX", "BTS", "NAS", "NYSE"
        )

        /** Single shared client — connection pool reused across all instances. */
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
