package com.aurum.invest.analytics

import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * One stock caught in the turn: MACD deep under zero and a hair below its
 * signal with the gap closing, the close already back above an HMA(20) that
 * is still falling. Every number is the raw reading — no score, no target.
 */
data class TurnPick(
    val date: String,
    val rank: Int,
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double,
    val macd: Double,
    val signal: Double,
    val histogram: Double,
    /** MACD as a percent of price — negative; how deep the washout runs. */
    val macdPct: Double,
    /** The MACD-to-signal gap as a percent of price — negative, near zero. */
    val gapPct: Double,
    val hma: Double,
    /** How far the close sits above the HMA(20), percent. */
    val hmaDistPct: Double,
    /** Consecutive sessions the histogram has risen. */
    val histRisingDays: Int,
    /** Close against the highest close of the last 63 sessions, percent. */
    val offHighPct: Double,
    /** Close against the lowest close of the last 20 sessions, percent. */
    val offLowPct: Double,
    val reason: String
)

/** A finished scan: the matches and how many stocks were actually read. */
data class TurnScan(val picks: List<TurnPick>, val scanned: Int) {
    companion object {
        val EMPTY = TurnScan(emptyList(), 0)
    }
}

/**
 * The turn read itself — pure, on closes alone, sharing MACD(12,26,9) and
 * HMA(20) with the 35-technique board so a stock listed here shows the same
 * two "Neutral" cards on its analysis screen:
 *
 *  - MACD: below zero by at least [MIN_DEPTH_PCT] of price, still under its
 *    signal by no more than [MAX_GAP_PCT] of price, histogram rising.
 *  - Hull: close above the HMA(20) while the HMA itself has not turned up.
 *
 * It is the session before the confirmation, not the confirmation.
 */
object TurnEngine {

    /** MACD must sit at least this far under zero, percent of price. */
    const val MIN_DEPTH_PCT = 1.0

    /** MACD may trail its signal by at most this much, percent of price. */
    const val MAX_GAP_PCT = 0.30

    /** Sessions needed before the EMAs behind MACD have settled. */
    const val MIN_BARS = 60

    data class Read(
        val price: Double,
        val dayChangePct: Double,
        val macd: Double,
        val signal: Double,
        val histogram: Double,
        val macdPct: Double,
        val gapPct: Double,
        val hma: Double,
        val hmaDistPct: Double,
        val histRisingDays: Int,
        val offHighPct: Double,
        val offLowPct: Double
    )

    /** The rule on one session's numbers. */
    fun isTurn(
        price: Double,
        macd: Double,
        signal: Double,
        prevHistogram: Double,
        hma: Double,
        prevHma: Double
    ): Boolean {
        if (price <= 0.0) return false
        val hist = macd - signal
        val deep = macd / price * 100.0 <= -MIN_DEPTH_PCT
        val closing = hist < 0.0 && hist / price * 100.0 >= -MAX_GAP_PCT && hist > prevHistogram
        val hullDisagrees = price > hma && hma <= prevHma
        return deep && closing && hullDisagrees
    }

    /** The read for the latest session, or null when the stock is not in the turn. */
    fun read(closes: List<Double>): Read? {
        val n = closes.size
        if (n < MIN_BARS) return null
        val price = closes[n - 1]
        if (price <= 0.0) return null
        val (macdLine, signalLine, histLine) = Techniques.macdLines(closes)
        val hull = Techniques.hullLine(closes)
        val macd = macdLine[n - 1] ?: return null
        val signal = signalLine[n - 1] ?: return null
        val hist = histLine[n - 1] ?: return null
        val prevHist = histLine[n - 2] ?: return null
        val hma = hull[n - 1] ?: return null
        val prevHma = hull[n - 2] ?: return null
        if (!isTurn(price, macd, signal, prevHist, hma, prevHma)) return null

        var rising = 0
        var i = n - 1
        while (i >= 1) {
            val a = histLine[i]
            val b = histLine[i - 1]
            if (a == null || b == null || a <= b) break
            rising++
            i--
        }
        val high = closes.takeLast(63).max()
        val low = closes.takeLast(20).min()
        val prev = closes[n - 2]
        return Read(
            price = price,
            dayChangePct = if (prev > 0.0) (price / prev - 1.0) * 100.0 else 0.0,
            macd = macd,
            signal = signal,
            histogram = hist,
            macdPct = macd / price * 100.0,
            gapPct = hist / price * 100.0,
            hma = hma,
            hmaDistPct = (price / hma - 1.0) * 100.0,
            histRisingDays = rising,
            offHighPct = (price / high - 1.0) * 100.0,
            offLowPct = (price / low - 1.0) * 100.0
        )
    }
}

/**
 * The market-wide turn scan. The pool is Yahoo's market-wide screens (kept
 * only when the price has not run away above its 50-day average — a MACD
 * that deep under zero never sits there) plus the fixed large-cap universe.
 * Each survivor's daily candles are read by [TurnEngine]; matches are ranked
 * by how deep the MACD sits under zero. Never throws.
 */
class TurnPicker(private val market: MarketRepository) {

    companion object {
        private const val CANDLE_DAYS = 365
        private const val CANDLE_CHUNK = 8
        private const val MAX_PICKS = 15
        private const val MIN_PRICE = 2.0
        private const val MIN_DOLLAR_VOLUME = 15_000_000.0

        fun toJson(scan: TurnScan): String {
            val arr = JSONArray()
            scan.picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("date", p.date)
                    put("rank", p.rank)
                    put("symbol", p.symbol)
                    put("name", p.name)
                    put("price", p.price)
                    put("dayChangePct", p.dayChangePct)
                    put("macd", p.macd)
                    put("signal", p.signal)
                    put("histogram", p.histogram)
                    put("macdPct", p.macdPct)
                    put("gapPct", p.gapPct)
                    put("hma", p.hma)
                    put("hmaDistPct", p.hmaDistPct)
                    put("histRisingDays", p.histRisingDays)
                    put("offHighPct", p.offHighPct)
                    put("offLowPct", p.offLowPct)
                    put("reason", p.reason)
                })
            }
            return JSONObject().apply {
                put("scanned", scan.scanned)
                put("picks", arr)
            }.toString()
        }

        fun fromJson(s: String): TurnScan = try {
            val root = JSONObject(s)
            val arr = root.getJSONArray("picks")
            val out = ArrayList<TurnPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    TurnPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.getDouble("price"),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        macd = o.getDouble("macd"),
                        signal = o.getDouble("signal"),
                        histogram = o.getDouble("histogram"),
                        macdPct = o.getDouble("macdPct"),
                        gapPct = o.getDouble("gapPct"),
                        hma = o.getDouble("hma"),
                        hmaDistPct = o.getDouble("hmaDistPct"),
                        histRisingDays = o.optInt("histRisingDays", 0),
                        offHighPct = o.optDouble("offHighPct", 0.0),
                        offLowPct = o.optDouble("offLowPct", 0.0),
                        reason = o.optString("reason", "")
                    )
                )
            }
            TurnScan(out, root.optInt("scanned", out.size))
        } catch (_: Exception) {
            TurnScan.EMPTY
        }

        fun reasonFor(r: TurnEngine.Read): String = String.format(
            Locale.US,
            "MACD %.2f sits %.2f under its signal %.2f and has closed the gap for %d session%s. " +
                "The close is %.1f%% above an HMA(20) of %.2f that has not turned up yet. " +
                "%.1f%% off the 3-month high, %.1f%% off the 20-day low.",
            r.macd, -r.histogram, r.signal, r.histRisingDays,
            if (r.histRisingDays == 1) "" else "s",
            r.hmaDistPct, r.hma, -r.offHighPct, r.offLowPct
        )
    }

    suspend fun computeScan(dateIso: String): TurnScan {
        return try {
            // Pool — the market-wide screens, then the fixed universe.
            val names = LinkedHashMap<String, String>()
            for (chunk in EntryPicker.MARKET_SCREENS.chunked(4)) {
                coroutineScope {
                    chunk.map { id -> async { market.getScreener(id) } }.awaitAll()
                }.forEach { list ->
                    list.filter(::worthReading).forEach { q -> names.putIfAbsent(q.symbol, q.name) }
                }
            }
            (WeeklyPicker.UNIVERSE + WeeklyPicker.BUDGET_EXTRA).forEach { (symbol, name) ->
                names.putIfAbsent(symbol, name)
            }
            if (names.isEmpty()) return TurnScan.EMPTY

            var scanned = 0
            val hits = ArrayList<Triple<String, String, TurnEngine.Read>>()
            for (chunk in names.entries.toList().chunked(CANDLE_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (symbol, name) ->
                        async {
                            val candles = try {
                                market.getDailyCandles(symbol, CANDLE_DAYS)
                            } catch (_: Exception) {
                                emptyList()
                            }
                            if (candles.size < TurnEngine.MIN_BARS) return@async null
                            val recent = candles.takeLast(20)
                            val dollarVolume = recent.sumOf { it.close * it.volume } / recent.size
                            val liquid = candles.last().close >= MIN_PRICE &&
                                dollarVolume >= MIN_DOLLAR_VOLUME
                            Triple(symbol, name, if (liquid) TurnEngine.read(candles.map { it.close }) else null)
                        }
                    }.awaitAll()
                }
                for (r in results) {
                    if (r == null) continue
                    scanned++
                    val read = r.third ?: continue
                    hits.add(Triple(r.first, r.second, read))
                }
            }

            // Deepest washout first — the fixed, measured ordering.
            val picks = hits.sortedBy { it.third.macdPct }.take(MAX_PICKS)
                .mapIndexed { index, (symbol, name, r) ->
                    TurnPick(
                        date = dateIso,
                        rank = index + 1,
                        symbol = symbol,
                        name = name,
                        price = r.price,
                        dayChangePct = r.dayChangePct,
                        macd = r.macd,
                        signal = r.signal,
                        histogram = r.histogram,
                        macdPct = r.macdPct,
                        gapPct = r.gapPct,
                        hma = r.hma,
                        hmaDistPct = r.hmaDistPct,
                        histRisingDays = r.histRisingDays,
                        offHighPct = r.offHighPct,
                        offLowPct = r.offLowPct,
                        reason = reasonFor(r)
                    )
                }
            TurnScan(picks, scanned)
        } catch (_: Exception) {
            TurnScan.EMPTY
        }
    }

    /** Liquid common stock that has not run away above its 50-day average. */
    private fun worthReading(q: ScreenerQuote): Boolean =
        q.price in MIN_PRICE..2500.0 &&
            q.price * q.avgVolume3M >= MIN_DOLLAR_VOLUME &&
            q.fiftyDayAvg > 0.0 && q.price < q.fiftyDayAvg * 1.03 &&
            q.symbol.all { it.isLetterOrDigit() }
}
