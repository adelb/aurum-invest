package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Which way the money is moving through a sector. */
enum class FlowVerdict { INFLOW, NEUTRAL, OUTFLOW }

/**
 * One sector's measured money-flow read. Every number is computed from real
 * candles — nothing is a guess, and the score sits on a FIXED 0..100 scale so
 * 70 means the same thing next month.
 */
data class SectorFlow(
    val key: String,
    val label: String,
    val etf: String,
    val r5Pct: Double,             // 5-day ETF return
    val r20Pct: Double,            // 20-day ETF return
    val relStrength20: Double,     // r20 minus the S&P 500's r20, percentage points
    val cmf: Double,               // Chaikin Money Flow(20) on the ETF, -1..1
    val mfi: Double,               // Money Flow Index(14) on the ETF, 0..100
    val obvSlopeNorm: Double,      // 20-bar OBV slope / average daily volume
    val upDollarSharePct: Double,  // share of 10-session dollar volume on up days
    val volumeRatio: Double,       // last completed session vs its 20-day average
    val breadthPct: Double,        // % of tracked members above their 20-day avg; -1 = not measured
    val memberMedian5dPct: Double, // median 5-day move of tracked members; 0 when unmeasured
    val newsTone: Int,             // -5..5 summed headline sentiment; 0 when unmeasured
    val flowScore: Int,            // 0..100 on a fixed scale
    val verdict: FlowVerdict,
    val confidence: Int,           // 0..100 — how many of the 4 money signals agree
    val reason: String
)

/** The whole-market money-flow answer for this week and the setup for next. */
data class MoneyFlowReport(
    val computedAt: Long,
    val spyR20Pct: Double,
    val sectors: List<SectorFlow>,  // ranked by flowScore, best first
    val headline: String,
    val notes: List<String>
) {
    /** Sectors the money is measurably entering, strongest first. */
    val inflows: List<SectorFlow> get() = sectors.filter { it.verdict == FlowVerdict.INFLOW }

    /** Sectors the money is measurably leaving. */
    val outflows: List<SectorFlow> get() = sectors.filter { it.verdict == FlowVerdict.OUTFLOW }
}

/**
 * The standalone money-flow engine: measures where the money and the volume
 * are actually moving, sector by sector, with the standard institutional
 * equations — Chaikin Money Flow, the Money Flow Index, on-balance-volume
 * slope, up-day dollar-volume share, relative strength against the S&P 500,
 * and member breadth — instead of price momentum alone.
 *
 * Integrity rules:
 *  - every component is measured from real candles; a sector that fails to
 *    fetch is skipped, never invented
 *  - the composite score is a FIXED 0..100 scale (no rank normalization), so
 *    scores are comparable across weeks
 *  - an INFLOW/OUTFLOW verdict needs at least 3 of the 4 money signals to
 *    agree; anything less is reported as NEUTRAL with the disagreement visible
 *  - member breadth is computed only for the leading sectors (to bound the
 *    scan) and shown as "not measured" elsewhere rather than defaulted
 *
 * Never throws — null on total failure.
 */
class MoneyFlowEngine(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        private const val SPY = "SPY"
        private const val NEWS_TOP = 6
        private const val BREADTH_TOP = 8
        private const val MEMBER_CHUNK = 8

        // ---------------- JSON ----------------

        fun toJson(r: MoneyFlowReport): String = JSONObject().apply {
            put("computedAt", r.computedAt)
            put("spyR20", r.spyR20Pct)
            put("headline", r.headline)
            put("notes", JSONArray(r.notes))
            put("sectors", JSONArray().apply {
                r.sectors.forEach { s ->
                    put(JSONObject().apply {
                        put("key", s.key); put("label", s.label); put("etf", s.etf)
                        put("r5", s.r5Pct); put("r20", s.r20Pct)
                        put("rel", s.relStrength20); put("cmf", s.cmf); put("mfi", s.mfi)
                        put("obv", s.obvSlopeNorm); put("upShare", s.upDollarSharePct)
                        put("volRatio", s.volumeRatio); put("breadth", s.breadthPct)
                        put("median5", s.memberMedian5dPct); put("tone", s.newsTone)
                        put("score", s.flowScore); put("verdict", s.verdict.name)
                        put("conf", s.confidence); put("reason", s.reason)
                    })
                }
            })
        }.toString()

        fun fromJson(json: String): MoneyFlowReport? = try {
            val o = JSONObject(json)
            val sectors = ArrayList<SectorFlow>()
            val arr = o.optJSONArray("sectors") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                sectors.add(
                    SectorFlow(
                        key = s.getString("key"),
                        label = s.optString("label", ""),
                        etf = s.optString("etf", ""),
                        r5Pct = s.optDouble("r5", 0.0),
                        r20Pct = s.optDouble("r20", 0.0),
                        relStrength20 = s.optDouble("rel", 0.0),
                        cmf = s.optDouble("cmf", 0.0),
                        mfi = s.optDouble("mfi", 50.0),
                        obvSlopeNorm = s.optDouble("obv", 0.0),
                        upDollarSharePct = s.optDouble("upShare", 50.0),
                        volumeRatio = s.optDouble("volRatio", 1.0),
                        breadthPct = s.optDouble("breadth", -1.0),
                        memberMedian5dPct = s.optDouble("median5", 0.0),
                        newsTone = s.optInt("tone", 0),
                        flowScore = s.optInt("score", 0),
                        verdict = runCatching { FlowVerdict.valueOf(s.optString("verdict")) }
                            .getOrDefault(FlowVerdict.NEUTRAL),
                        confidence = s.optInt("conf", 0),
                        reason = s.optString("reason", "")
                    )
                )
            }
            MoneyFlowReport(
                computedAt = o.optLong("computedAt", 0L),
                spyR20Pct = o.optDouble("spyR20", 0.0),
                sectors = sectors,
                headline = o.optString("headline", ""),
                notes = buildList {
                    val n = o.optJSONArray("notes") ?: JSONArray()
                    for (i in 0 until n.length()) add(n.optString(i))
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun compute(): MoneyFlowReport? {
        return try {
            val spyCandles = try {
                market.getDailyCandles(SPY, 60)
            } catch (_: Exception) {
                emptyList()
            }
            val spyR20 = r20Of(spyCandles) ?: 0.0

            // 1 — every sector ETF's raw flow read, in parallel.
            val raw = coroutineScope {
                SectorTrends.SECTORS.map { (key, label, etf) ->
                    async { etfRead(key, label, etf, spyR20) }
                }.awaitAll()
            }.filterNotNull()
            if (raw.isEmpty()) return null

            // 2 — news tone for the leaders only (bounded).
            val preRanked = raw.sortedByDescending { it.preScore }
            val tones = HashMap<String, Int>()
            coroutineScope {
                preRanked.take(NEWS_TOP).map { p ->
                    async {
                        val items = try {
                            news.getTopicNews(
                                query = "${p.label} stocks",
                                cacheKey = "sector-${p.key}",
                                tag = p.etf
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                        tones[p.key] = items.sumOf { it.sentiment }.coerceIn(-5, 5)
                    }
                }.awaitAll()
            }

            // 3 — member breadth for the leading sectors (bounded).
            val breadth = HashMap<String, Pair<Double, Double>>() // key -> (breadthPct, median5)
            val leaders = preRanked.take(BREADTH_TOP)
            val memberJobs = leaders.flatMap { p ->
                SectorTrends.WATCH[p.key].orEmpty().map { (sym, _) -> p.key to sym }
            }
            val memberReads = ArrayList<Triple<String, Boolean, Double>>() // key, above20, r5
            for (chunk in memberJobs.chunked(MEMBER_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (key, sym) ->
                        async {
                            try {
                                val candles = market.getDailyCandles(sym, 60)
                                val closes = candles.map { it.close }
                                val last = closes.lastOrNull() ?: return@async null
                                val sma20 = Indicators.sma(closes, 20) ?: return@async null
                                val r5 = if (closes.size >= 6 && closes[closes.size - 6] > 0.0) {
                                    (last / closes[closes.size - 6] - 1.0) * 100.0
                                } else 0.0
                                Triple(key, last > sma20, r5)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }
                results.filterNotNull().forEach { memberReads.add(it) }
            }
            memberReads.groupBy { it.first }.forEach { (key, reads) ->
                if (reads.isNotEmpty()) {
                    val pct = reads.count { it.second } * 100.0 / reads.size
                    val sorted = reads.map { it.third }.sorted()
                    val median = sorted[sorted.size / 2]
                    breadth[key] = pct to median
                }
            }

            // 4 — compose.
            val sectors = raw.map { p ->
                val tone = tones[p.key] ?: 0
                val (b, m) = breadth[p.key] ?: (-1.0 to 0.0)
                compose(p, tone, b, m)
            }.sortedByDescending { it.flowScore }

            val lead = sectors.firstOrNull()
            val headline = when {
                lead != null && lead.verdict == FlowVerdict.INFLOW -> String.format(
                    Locale.US,
                    "Money is flowing into %s — %.0f%% of its 10-session dollar volume printed on up days.",
                    lead.label.lowercase(Locale.US), lead.upDollarSharePct
                )
                lead != null -> "No sector shows a confirmed money inflow right now — flows are balanced."
                else -> "Sector flow read unavailable."
            }
            val notes = buildList {
                add(
                    "Each sector is scored 0–100 on a fixed scale from measured inputs: " +
                        "up-day dollar-volume share, Chaikin Money Flow, the Money Flow Index, " +
                        "OBV slope, relative strength vs the S&P 500, volume pace, and news tone."
                )
                add(
                    "An inflow or outflow verdict needs at least 3 of the 4 money signals to " +
                        "agree; a mixed read is reported as balanced, not forced into a story."
                )
                if (sectors.any { it.breadthPct < 0.0 }) {
                    add("Member breadth is measured for the leading sectors only; elsewhere it is shown as not measured, never assumed.")
                }
            }

            MoneyFlowReport(
                computedAt = System.currentTimeMillis(),
                spyR20Pct = round1(spyR20),
                sectors = sectors,
                headline = headline,
                notes = notes
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ ETF read

    private class EtfRead(
        val key: String,
        val label: String,
        val etf: String,
        val r5: Double,
        val r20: Double,
        val rel: Double,
        val cmf: Double,
        val mfi: Double,
        val obvSlopeNorm: Double,
        val upDollarShare: Double,
        val volumeRatio: Double,
        val preScore: Double
    )

    private suspend fun etfRead(key: String, label: String, etf: String, spyR20: Double): EtfRead? {
        return try {
            val candles = market.getDailyCandles(etf, 60)
            if (candles.size < 25) return null
            val closes = candles.map { it.close }
            val n = closes.size
            val last = closes.last()
            if (last <= 0.0) return null
            val r5 = if (closes[n - 6] > 0.0) (last / closes[n - 6] - 1.0) * 100.0 else 0.0
            val r20 = r20Of(candles) ?: return null

            // Exclude today's partial bar from every volume-based read.
            val lastIsToday = Dates.sameEtDay(candles.last().ts, System.currentTimeMillis())
            val complete = if (lastIsToday && candles.size >= 2) candles.dropLast(1) else candles

            val cmf = cmf20(complete) ?: 0.0
            val mfi = mfi14(complete) ?: 50.0
            val obvNorm = obvSlopeNorm(complete)
            val upShare = upDollarShare(complete, 10)
            val volumes = complete.map { it.volume.toDouble() }
            val vol20 = volumes.takeLast(21).dropLast(1).ifEmpty { volumes }.average()
            val volumeRatio = if (vol20 > 0.0 && volumes.isNotEmpty()) volumes.last() / vol20 else 1.0

            val rel = r20 - spyR20
            val preScore = rel * 2.0 + r5 + cmf * 30.0 + (upShare - 50.0) * 0.2
            EtfRead(key, label, etf, r5, r20, rel, cmf, mfi, obvNorm, upShare, volumeRatio, preScore)
        } catch (_: Exception) {
            null
        }
    }

    private fun compose(p: EtfRead, tone: Int, breadthPct: Double, median5: Double): SectorFlow {
        // Fixed-scale components, each capped — total 100.
        val relPts = ((p.rel + 6.0) / 12.0).coerceIn(0.0, 1.0) * 20.0
        val r5Pts = ((p.r5 + 5.0) / 10.0).coerceIn(0.0, 1.0) * 10.0
        val cmfPts = ((p.cmf + 0.25) / 0.5).coerceIn(0.0, 1.0) * 15.0
        val mfiPts = (p.mfi / 100.0).coerceIn(0.0, 1.0) * 10.0
        val sharePts = (p.upDollarShare / 100.0).coerceIn(0.0, 1.0) * 15.0
        val obvPts = ((p.obvSlopeNorm + 1.0) / 2.0).coerceIn(0.0, 1.0) * 10.0
        val volPts = ((p.volumeRatio - 0.7) / 0.9).coerceIn(0.0, 1.0) * 10.0
        val tonePts = ((tone + 5.0) / 10.0).coerceIn(0.0, 1.0) * 10.0
        val score = (relPts + r5Pts + cmfPts + mfiPts + sharePts + obvPts + volPts + tonePts)
            .toInt().coerceIn(0, 100)

        // The four money signals; the verdict needs 3 to agree.
        val inSignals = listOf(
            p.cmf > 0.02,
            p.mfi > 52.0,
            p.obvSlopeNorm > 0.0,
            p.upDollarShare > 52.0
        ).count { it }
        val outSignals = listOf(
            p.cmf < -0.02,
            p.mfi < 48.0,
            p.obvSlopeNorm < 0.0,
            p.upDollarShare < 48.0
        ).count { it }
        val verdict = when {
            score >= 58 && inSignals >= 3 -> FlowVerdict.INFLOW
            score <= 42 && outSignals >= 3 -> FlowVerdict.OUTFLOW
            else -> FlowVerdict.NEUTRAL
        }
        val confidence = (maxOf(inSignals, outSignals) * 25).coerceIn(0, 100)

        val reasonParts = mutableListOf(
            String.format(Locale.US, "%.0f%% of 10-session $-volume on up days", p.upDollarShare),
            String.format(Locale.US, "CMF %+.2f", p.cmf),
            String.format(Locale.US, "MFI %.0f", p.mfi),
            String.format(
                Locale.US, "%+.1f%% vs S&P over 20 days (%s)", p.rel, p.etf
            )
        )
        if (p.volumeRatio >= 1.2) {
            reasonParts += String.format(Locale.US, "%.1fx volume", p.volumeRatio)
        }
        if (breadthPct >= 0.0) {
            reasonParts += String.format(
                Locale.US, "%.0f%% of tracked members above their 20-day average", breadthPct
            )
        }
        if (tone != 0) reasonParts += String.format(Locale.US, "news tone %+d", tone)

        return SectorFlow(
            key = p.key,
            label = p.label,
            etf = p.etf,
            r5Pct = round1(p.r5),
            r20Pct = round1(p.r20),
            relStrength20 = round1(p.rel),
            cmf = round2(p.cmf),
            mfi = round1(p.mfi),
            obvSlopeNorm = round2(p.obvSlopeNorm),
            upDollarSharePct = round1(p.upDollarShare),
            volumeRatio = round1(p.volumeRatio),
            breadthPct = if (breadthPct >= 0.0) round1(breadthPct) else -1.0,
            memberMedian5dPct = round1(median5),
            newsTone = tone,
            flowScore = score,
            verdict = verdict,
            confidence = confidence,
            reason = reasonParts.joinToString(", ")
        )
    }

    // ------------------------------------------------------------ flow math

    private fun r20Of(candles: List<Candle>): Double? {
        val closes = candles.map { it.close }
        val n = closes.size
        if (n < 21 || closes[n - 21] <= 0.0) return null
        return (closes.last() / closes[n - 21] - 1.0) * 100.0
    }

    /** Chaikin Money Flow over the last 20 completed bars. */
    private fun cmf20(candles: List<Candle>): Double? {
        val window = candles.takeLast(20)
        if (window.size < 20) return null
        var sumMfv = 0.0
        var sumVol = 0.0
        for (c in window) {
            val range = c.high - c.low
            val mult = if (range > 1e-12) ((c.close - c.low) - (c.high - c.close)) / range else 0.0
            val vol = c.volume.toDouble()
            sumMfv += mult * vol
            sumVol += vol
        }
        return if (sumVol > 0.0) sumMfv / sumVol else null
    }

    /** Money Flow Index over the last 14 completed bars. */
    private fun mfi14(candles: List<Candle>): Double? {
        if (candles.size < 15) return null
        val window = candles.takeLast(15)
        var pos = 0.0
        var neg = 0.0
        for (i in 1 until window.size) {
            val tp = (window[i].high + window[i].low + window[i].close) / 3.0
            val prevTp = (window[i - 1].high + window[i - 1].low + window[i - 1].close) / 3.0
            val mf = tp * window[i].volume.toDouble()
            if (tp > prevTp) pos += mf else if (tp < prevTp) neg += mf
        }
        return when {
            pos == 0.0 && neg == 0.0 -> 50.0
            neg == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + pos / neg)
        }
    }

    /** Least-squares OBV slope over 20 bars, normalized by average daily volume. */
    private fun obvSlopeNorm(candles: List<Candle>): Double {
        if (candles.size < 21) return 0.0
        val window = candles.takeLast(21)
        val obv = ArrayList<Double>(window.size)
        var acc = 0.0
        obv.add(0.0)
        for (i in 1 until window.size) {
            val d = window[i].close - window[i - 1].close
            if (d > 0.0) acc += window[i].volume.toDouble()
            else if (d < 0.0) acc -= window[i].volume.toDouble()
            obv.add(acc)
        }
        val n = obv.size
        val meanX = (n - 1) / 2.0
        val meanY = obv.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            num += dx * (obv[i] - meanY)
            den += dx * dx
        }
        val slope = if (den > 0.0) num / den else 0.0
        val avgVol = window.sumOf { it.volume.toDouble() } / window.size
        return if (avgVol > 0.0) (slope / avgVol).coerceIn(-1.0, 1.0) else 0.0
    }

    /** Share of the last [sessions] completed sessions' dollar volume on up days. */
    private fun upDollarShare(candles: List<Candle>, sessions: Int): Double {
        val window = candles.takeLast(min(sessions + 1, candles.size))
        if (window.size < 3) return 50.0
        var up = 0.0
        var total = 0.0
        for (i in 1 until window.size) {
            val dollars = window[i].close * window[i].volume.toDouble()
            total += dollars
            if (window[i].close > window[i - 1].close) up += dollars
        }
        return if (total > 0.0) up / total * 100.0 else 50.0
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
