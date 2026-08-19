package com.aurum.invest.data.remote

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Fundamentals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Yahoo quoteSummary client for company fundamentals (C6). Unlike the chart
 * and screener endpoints, quoteSummary demands a session cookie plus a crumb
 * token; both are fetched lazily and reused until Yahoo rejects them. Never
 * throws — null means the fetch FAILED (and the UI must say so, not show
 * zeros).
 */
class FundamentalsClient {

    private val cookieStore = HashMap<String, List<Cookie>>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookieStore) {
                    cookieStore.values.flatten().filter { it.matches(url) }
                }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieStore) { cookieStore[url.host] = cookies }
            }
        })
        .build()

    @Volatile
    private var crumb: String? = null

    private fun get(url: String): okhttp3.Response = http.newCall(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
    ).execute()

    /** Obtains (or reuses) the crumb Yahoo requires for quoteSummary. */
    private fun ensureCrumb(): String? {
        crumb?.let { return it }
        return try {
            // Hitting this host sets the anonymous session cookies.
            get("https://fc.yahoo.com").use { }
            get("https://query1.finance.yahoo.com/v1/test/getcrumb").use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string()?.trim().orEmpty()
                text.takeIf { it.isNotEmpty() && it.length < 64 && !it.contains('{') }
                    ?.also { crumb = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Full company read; null when Yahoo could not be reached or refused. */
    suspend fun fetchFundamentals(symbol: String): Fundamentals? = withContext(Dispatchers.IO) {
        // First try with a crumb; on a 401/invalid-crumb response, refresh the
        // crumb once and retry.
        repeat(2) { attempt ->
            val c = ensureCrumb()
            val url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary".toHttpUrl()
                .newBuilder()
                .addPathSegment(symbol)
                .addQueryParameter("modules", MODULES)
                .apply { if (c != null) addQueryParameter("crumb", c) }
                .build()
                .toString()
            try {
                get(url).use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        crumb = null
                        if (attempt == 0) return@use
                        return@withContext null
                    }
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    return@withContext parse(symbol, JSONObject(body))
                }
            } catch (_: Exception) {
                return@withContext null
            }
        }
        null
    }

    // ------------------------------------------------------------ parsing

    private fun parse(symbol: String, root: JSONObject): Fundamentals? {
        val result = root.optJSONObject("quoteSummary")
            ?.optJSONArray("result")
            ?.optJSONObject(0)
            ?: return null

        val profile = result.optJSONObject("summaryProfile")
        val detail = result.optJSONObject("summaryDetail")
        val keyStats = result.optJSONObject("defaultKeyStatistics")
        val finData = result.optJSONObject("financialData")
        val calendar = result.optJSONObject("calendarEvents")
        val trend = result.optJSONObject("recommendationTrend")
            ?.optJSONArray("trend")
            ?.optJSONObject(0)

        // Analyst count = sum of the current-period recommendation buckets.
        val analystCount = trend?.let {
            listOf("strongBuy", "buy", "hold", "sell", "strongSell").sumOf { k -> it.optInt(k, 0) }
        }?.takeIf { it > 0 }

        // Earnings dates, read honestly. `earningsDate` is an ARRAY, and Yahoo
        // uses it two ways: a single confirmed date, or a two-element
        // [start, end] window when the date is only estimated. It also keeps
        // serving the LAST report's date until the next one is scheduled, so
        // element 0 is frequently in the past. Split on "now" and only call a
        // future date "next".
        val earningsNode = calendar?.optJSONObject("earnings")
        val earningsDates = earningsNode?.optJSONArray("earningsDate")
            ?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx) } }
            ?.mapNotNull { it.optLong("raw", 0L).takeIf { s -> s > 0L }?.times(1000L) }
            ?.sorted()
            .orEmpty()
        val window = pickEarnings(earningsDates, Dates.todayStartMs())

        return Fundamentals(
            symbol = symbol,
            fetchedAt = System.currentTimeMillis(),
            // The display name comes from the quote; this payload doesn't carry it.
            name = null,
            sector = profile?.optString("sector")?.ifBlank { null },
            industry = profile?.optString("industry")?.ifBlank { null },
            description = profile?.optString("longBusinessSummary")?.ifBlank { null },
            employees = profile?.optInt("fullTimeEmployees", -1)?.takeIf { it > 0 },
            website = profile?.optString("website")?.ifBlank { null },
            marketCap = raw(detail, "marketCap") ?: raw(keyStats, "enterpriseValue"),
            trailingPE = raw(detail, "trailingPE"),
            forwardPE = raw(detail, "forwardPE") ?: raw(keyStats, "forwardPE"),
            priceToBook = raw(keyStats, "priceToBook"),
            pegRatio = raw(keyStats, "pegRatio"),
            epsTrailing = raw(keyStats, "trailingEps"),
            epsForward = raw(keyStats, "forwardEps"),
            beta = raw(keyStats, "beta"),
            dividendYieldPct = raw(detail, "dividendYield")?.times(100.0),
            dividendRate = raw(detail, "dividendRate"),
            exDividendTs = raw(detail, "exDividendDate")?.toLong()?.takeIf { it > 0 }?.times(1000L),
            payoutRatioPct = raw(detail, "payoutRatio")?.times(100.0),
            totalRevenue = raw(finData, "totalRevenue"),
            revenueGrowthPct = raw(finData, "revenueGrowth")?.times(100.0),
            grossMarginPct = raw(finData, "grossMargins")?.times(100.0),
            operatingMarginPct = raw(finData, "operatingMargins")?.times(100.0),
            profitMarginPct = raw(finData, "profitMargins")?.times(100.0),
            totalCash = raw(finData, "totalCash"),
            totalDebt = raw(finData, "totalDebt"),
            debtToEquity = raw(finData, "debtToEquity"),
            currentRatio = raw(finData, "currentRatio"),
            freeCashflow = raw(finData, "freeCashflow"),
            operatingCashflow = raw(finData, "operatingCashflow"),
            returnOnEquityPct = raw(finData, "returnOnEquity")?.times(100.0),
            targetMean = raw(finData, "targetMeanPrice"),
            targetHigh = raw(finData, "targetHighPrice"),
            targetLow = raw(finData, "targetLowPrice"),
            analystCount = analystCount ?: raw(finData, "numberOfAnalystOpinions")?.toInt(),
            recommendationMean = raw(finData, "recommendationMean"),
            recommendationKey = finData?.optString("recommendationKey")?.ifBlank { null },
            nextEarningsTs = window.next,
            nextEarningsEndTs = window.nextEnd,
            earningsDateEstimated = earningsNode?.optBoolean("isEarningsDateEstimate", false) == true,
            lastEarningsTs = window.last,
            dividendDateTs = raw(calendar, "dividendDate")?.toLong()?.takeIf { it > 0 }?.times(1000L)
        )
    }

    /** quoteSummary wraps numbers as {raw, fmt}; this unwraps raw, or reads a bare number. */
    private fun raw(container: JSONObject?, key: String): Double? {
        val node = container?.opt(key) ?: return null
        return when (node) {
            is JSONObject -> {
                val v = node.optDouble("raw", Double.NaN)
                if (v.isNaN()) null else v
            }
            is Number -> node.toDouble()
            else -> null
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        private const val MODULES =
            "summaryProfile,summaryDetail,defaultKeyStatistics,financialData," +
                "calendarEvents,recommendationTrend"
    }
}

/** What [pickEarnings] resolved from Yahoo's `earningsDate` array. */
internal data class EarningsWindow(
    /** The next date still ahead; null when nothing upcoming is published. */
    val next: Long?,
    /** Far end of an estimated window when Yahoo gives two future dates. */
    val nextEnd: Long?,
    /** The most recent date already behind us. */
    val last: Long?
)

/**
 * Chooses the earnings dates to show from Yahoo's `earningsDate` array.
 *
 * The array is not "the next earnings date". Yahoo keeps serving the LAST
 * report's date until the next is scheduled, and returns TWO entries when the
 * date is an unconfirmed window. Reading element 0 therefore announced a date
 * that had already passed as "Next earnings". Anything before [todayStartMs]
 * is the past and is reported as such.
 */
internal fun pickEarnings(datesMs: List<Long>, todayStartMs: Long): EarningsWindow {
    val sorted = datesMs.filter { it > 0L }.distinct().sorted()
    val upcoming = sorted.filter { it >= todayStartMs }
    return EarningsWindow(
        next = upcoming.firstOrNull(),
        nextEnd = upcoming.getOrNull(1),
        last = sorted.lastOrNull { it < todayStartMs }
    )
}
