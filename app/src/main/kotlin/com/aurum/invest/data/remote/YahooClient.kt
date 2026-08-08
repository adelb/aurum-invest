package com.aurum.invest.data.remote

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Quote
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
                fetchedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
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
                    else -> "1y"
                }
                val root = getJson(chartUrl(symbol, range = range, interval = "1d"))
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

    /** Executes a GET and parses the body as JSON. Returns null on any failure. */
    private fun getJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
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
