package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

/** One ledger row, stripped to what the replay needs. */
data class LedgerTrade(
    val ts: Long,
    val symbol: String,
    val side: String,          // BUY / SELL / SPLIT (ratio in [shares])
    val shares: Double,
    val price: Double,
    val fees: Double
)

/** One session of the verdict's equity curve; both series indexed to 100. */
data class CurvePoint(val ts: Long, val book: Double, val spy: Double)

/**
 * The verdict: what the user's ACTUAL trading did, measured against what the
 * same dollars in SPY would have done. This is the trade history judged —
 * not the current composition replayed (that is [RiskStats]' job).
 */
data class PerformanceReport(
    val computedAt: Long,
    val startTs: Long,             // first measured session
    val endTs: Long,               // last measured session
    val sessions: Int,             // trading sessions in the replay
    /** Time-weighted return — flow-immune: judges the picking, not the timing of deposits. */
    val twrPct: Double,
    /** Annualized TWR; null (stated) under [PerformanceEngine.MIN_ANNUALIZE_DAYS] days. */
    val twrAnnPct: Double?,
    /** SPY buy-and-hold over the same sessions. */
    val spyTwrPct: Double,
    val edgePct: Double,           // twrPct - spyTwrPct
    /** Money-weighted (XIRR) annual rate over the WHOLE ledger; null when unsolvable. */
    val mwrAnnPct: Double?,
    val investedIn: Double,        // total dollars the buys put in (fees included)
    val takenOut: Double,          // total dollars the sells took out (fees deducted)
    val bookNow: Double,           // measured holdings marked at the latest price
    /** The same session flows pushed into SPY instead — the alternate universe's value now. */
    val spyAltNow: Double,
    val spyAltClamped: Boolean,    // a withdrawal exceeded the SPY shadow — stated, not hidden
    val maxDrawdownPct: Double,    // deepest fall of the book's TWR curve
    val curve: List<CurvePoint>,
    val coveragePct: Double,       // share of current book value the replay measured
    val droppedSymbols: List<String>,
    val headline: String,
    val notes: List<String>,
    val caveat: String
)

/**
 * The performance engine: replays the ledger's actual trades day by day
 * against real closes and answers the first question an investor has —
 * "am I beating the market?" — with measured numbers.
 *
 * The machinery, by name:
 *  - daily time-weighted return chain: r_d = (V_d − F_d) / V_{d−1} − 1,
 *    where F_d is the day's net trade flow — the standard that makes the
 *    result immune to WHEN money arrived, so it grades the picking
 *  - SPY buy-and-hold over the same sessions as the benchmark index
 *  - a dollar-for-dollar SPY shadow: every inflow buys SPY at that day's
 *    close, every outflow sells it — "if every trade had been SPY instead"
 *  - XIRR (money-weighted annual rate) over the whole ledger, solved by
 *    bisection — the return the user's actual dollars experienced
 *  - max drawdown of the TWR curve
 *
 * Integrity rules (house law):
 *  - pure function over its inputs; no I/O, never throws
 *  - ledgers carry raw share counts while Yahoo's candles are split-adjusted,
 *    so trades are re-based into current share units first — without this a
 *    pre-split position replays at half its true value
 *  - a symbol with no price history is EXCLUDED and named, never valued at a
 *    guess; below [MIN_COVERAGE_PCT]% measured value no verdict is claimed
 *  - trades older than the reachable history clamp the replay start and the
 *    clamp is stated; the money-weighted figure still covers every trade
 */
object PerformanceEngine {

    /** Below this share of measured book value, no verdict is claimed. */
    const val MIN_COVERAGE_PCT = 60.0

    /** Under this many calendar days, an annualized TWR is noise — withheld. */
    const val MIN_ANNUALIZE_DAYS = 90

    /** Under this span the XIRR is withheld too. */
    const val MIN_MWR_DAYS = 30

    /** The curve is downsampled to at most this many points. */
    const val MAX_CURVE_POINTS = 180

    private const val DAY_MS = 86_400_000L
    private const val SIDE_BUY = "BUY"
    private const val SIDE_SPLIT = "SPLIT"

    fun evaluate(
        trades: List<LedgerTrade>,
        candles: Map<String, List<Candle>>,
        spy: List<Candle>,
        quotes: Map<String, Double> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): PerformanceReport? {
        val caveat = "Measured from your ledger's real trades and real closing prices. " +
            "Past performance proves nothing about next month. Decision support, not " +
            "financial advice."
        if (trades.isEmpty() || spy.size < 2) return null
        val notes = ArrayList<String>()

        // ---- usable universe: symbols the market data can actually price ----
        val symbols = trades.map { it.symbol.trim().uppercase() }.distinct()
        val usable = symbols.filter { (candles[it]?.size ?: 0) >= 2 }.toSet()
        val dropped = symbols.filterNot { it in usable }

        val cleanTrades = trades
            .map { it.copy(symbol = it.symbol.trim().uppercase()) }
            .sortedBy { it.ts }
        val usableTrades = adjustForSplits(cleanTrades.filter { it.symbol in usable })
        if (usableTrades.none { it.side != SIDE_SPLIT }) return null

        // ---- coverage: how much of TODAY's book the replay can see ----------
        val openShares = finalShares(usableTrades)
        val droppedShares = finalShares(adjustForSplits(cleanTrades.filter { it.symbol !in usable }))
        fun priceNow(sym: String): Double? =
            quotes[sym] ?: candles[sym]?.lastOrNull()?.close
        val measuredValue = openShares.entries.sumOf { (s, sh) -> sh * (priceNow(s) ?: 0.0) }
        val droppedValue = droppedShares.entries.sumOf { (s, sh) -> sh * (priceNow(s) ?: 0.0) }
        val totalValue = measuredValue + droppedValue
        val coverage = if (totalValue > 0.0) measuredValue / totalValue * 100.0 else 100.0
        if (coverage < MIN_COVERAGE_PCT) return null
        if (dropped.isNotEmpty()) {
            notes += String.format(
                Locale.US,
                "%s could not be priced this run and %s excluded from the replay " +
                    "(%.0f%% of the book measured).",
                dropped.joinToString(", "),
                if (dropped.size == 1) "is" else "are",
                coverage
            )
        }

        // ---- replay window: clamped to the history that actually exists -----
        val firstTradeTs = usableTrades.first { it.side != SIDE_SPLIT }.ts
        var replayFrom = firstTradeTs
        var clamped = false
        for (sym in usable) {
            val firstCandle = candles[sym]?.firstOrNull()?.ts ?: continue
            val firstTrade = usableTrades.firstOrNull { it.symbol == sym && it.side != SIDE_SPLIT }
                ?.ts ?: continue
            if (firstCandle > firstTrade + DAY_MS) {
                if (firstCandle > replayFrom) replayFrom = firstCandle
                clamped = true
            }
        }
        if (spy.first().ts > replayFrom) {
            replayFrom = spy.first().ts
            clamped = true
        }

        // Sessions come from SPY's own trading calendar; the session holding
        // the first measured trade is included via a day of slack.
        val startIdx = spy.indexOfFirst { it.ts >= replayFrom - DAY_MS }
        if (startIdx < 0 || spy.size - startIdx < 2) return null
        val sessions = spy.subList(startIdx, spy.size)

        // ---- the daily replay ----------------------------------------------
        val shares = HashMap<String, Double>()
        val pointers = HashMap<String, Int>()
        val lastClose = HashMap<String, Double>()
        var tradeIdx = 0
        // Trades before the replay window build the opening book silently;
        // their value enters the SPY shadow as the opening inflow.
        while (tradeIdx < usableTrades.size && usableTrades[tradeIdx].ts < sessions.first().ts) {
            applyTrade(usableTrades[tradeIdx], shares)
            tradeIdx++
        }
        val preStartTrades = tradeIdx

        var bookIdx = 1.0
        var chainStarted = false
        var peak = 1.0
        var maxDd = 0.0
        var prevValue = 0.0
        var shadowUnits = 0.0
        var shadowClamped = false
        var spyBase = 0.0
        val curveRaw = ArrayList<CurvePoint>(sessions.size)
        var chainStartSpy = 0.0
        var lastSessionValue = 0.0

        for ((i, bar) in sessions.withIndex()) {
            val isLast = i == sessions.lastIndex
            val sessionEnd = if (isLast) Long.MAX_VALUE else sessions[i + 1].ts

            // Flows: every trade belonging to this session.
            var flow = 0.0
            while (tradeIdx < usableTrades.size && usableTrades[tradeIdx].ts < sessionEnd) {
                val t = usableTrades[tradeIdx]
                if (t.side != SIDE_SPLIT) {
                    val qty =
                        if (t.side == SIDE_BUY) t.shares
                        else minOf(t.shares, shares[t.symbol] ?: 0.0)
                    flow +=
                        if (t.side == SIDE_BUY) t.shares * t.price + t.fees
                        else -(qty * t.price - t.fees)
                }
                applyTrade(t, shares)
                tradeIdx++
            }

            // Value the book at this session's closes.
            for (sym in usable) {
                val series = candles[sym] ?: continue
                var p = pointers[sym] ?: 0
                while (p < series.size && series[p].ts <= bar.ts + DAY_MS / 2) {
                    lastClose[sym] = series[p].close
                    p++
                }
                pointers[sym] = p
            }
            var value = 0.0
            for ((sym, sh) in shares) {
                if (sh <= 1e-9) continue
                val px =
                    if (isLast) (quotes[sym] ?: lastClose[sym] ?: continue)
                    else lastClose[sym] ?: continue
                value += sh * px
            }
            // The SPY shadow opens with the SAME measured value the TWR chain
            // opens with — the chain-start session's close value — so both
            // universes start from one number; every later flow mirrors
            // dollar-for-dollar.
            val opening = !chainStarted && value > 1e-6
            val shadowFlow = when {
                opening -> value
                chainStarted -> flow
                else -> 0.0
            }

            // TWR chain.
            if (chainStarted && prevValue > 1e-6) {
                val r = (value - flow) / prevValue - 1.0
                bookIdx *= (1.0 + r)
                if (bookIdx > peak) peak = bookIdx
                val dd = (peak - bookIdx) / peak
                if (dd > maxDd) maxDd = dd
            }
            if (opening) {
                chainStarted = true
                chainStartSpy = bar.close
            }
            prevValue = value
            lastSessionValue = value

            // The SPY shadow: same dollars, same days.
            if (shadowFlow > 0.0 && bar.close > 0.0) {
                shadowUnits += shadowFlow / bar.close
            } else if (shadowFlow < 0.0 && bar.close > 0.0) {
                val sellUnits = -shadowFlow / bar.close
                if (sellUnits > shadowUnits + 1e-9) {
                    shadowClamped = true
                    shadowUnits = 0.0
                } else {
                    shadowUnits -= sellUnits
                }
            }

            if (chainStarted) {
                curveRaw += CurvePoint(
                    ts = bar.ts,
                    book = round2(bookIdx * 100.0),
                    spy = round2(
                        if (chainStartSpy > 0.0) bar.close / chainStartSpy * 100.0 else 100.0
                    )
                )
            }
        }
        if (curveRaw.size < 2) return null
        if (clamped && preStartTrades > 0) {
            notes += String.format(
                Locale.US,
                "%d trade%s predate%s the reachable price history — the time-weighted " +
                    "replay starts %s with the book as it stood. The money-weighted " +
                    "figure still covers every trade.",
                preStartTrades, if (preStartTrades == 1) "" else "s",
                if (preStartTrades == 1) "s" else "",
                Fmt(curveRaw.first().ts)
            )
        }

        val twr = bookIdx - 1.0
        val spyTwr = if (chainStartSpy > 0.0) sessions.last().close / chainStartSpy - 1.0 else 0.0
        val spanDays = ((curveRaw.last().ts - curveRaw.first().ts) / DAY_MS).toInt().coerceAtLeast(1)
        val twrAnn =
            if (spanDays >= MIN_ANNUALIZE_DAYS && twr > -1.0) {
                (1.0 + twr).pow(365.0 / spanDays) - 1.0
            } else null
        if (twrAnn == null && spanDays < MIN_ANNUALIZE_DAYS) {
            notes += "Under $MIN_ANNUALIZE_DAYS days measured — no annualized figure is " +
                "claimed on a span this short."
        }
        if (shadowClamped) {
            notes += "A withdrawal exceeded what the SPY alternative held at the time — " +
                "its final value is understated in your favor's opposite; read it as a floor."
        }

        // ---- money-weighted: the whole ledger's XIRR ------------------------
        val flows = ArrayList<Pair<Long, Double>>()
        var investedIn = 0.0
        var takenOut = 0.0
        run {
            val running = HashMap<String, Double>()
            for (t in usableTrades) {
                if (t.side == SIDE_SPLIT) {
                    applyTrade(t, running)
                    continue
                }
                if (t.side == SIDE_BUY) {
                    val cost = t.shares * t.price + t.fees
                    investedIn += cost
                    flows += t.ts to -cost
                } else {
                    val qty = minOf(t.shares, running[t.symbol] ?: 0.0)
                    val proceeds = qty * t.price - t.fees
                    takenOut += proceeds
                    flows += t.ts to proceeds
                }
                applyTrade(t, running)
            }
        }
        val bookNow = lastSessionValue
        val mwrSpanDays = ((now - flows.first().first) / DAY_MS).toInt()
        val mwr =
            if (mwrSpanDays >= MIN_MWR_DAYS) {
                xirr(flows + (now to bookNow))
            } else null

        val spyAltNow = shadowUnits * sessions.last().close

        // ---- the verdict ----------------------------------------------------
        val edge = (twr - spyTwr) * 100.0
        val headline = when {
            abs(edge) < 1.0 -> String.format(
                Locale.US, "A dead heat with the market over %d sessions.", curveRaw.size
            )
            edge > 0.0 -> String.format(
                Locale.US, "Your trading beat SPY by %.1f points over %d sessions.",
                edge, curveRaw.size
            )
            else -> String.format(
                Locale.US, "SPY would have beaten your trading by %.1f points over %d sessions.",
                -edge, curveRaw.size
            )
        }

        return PerformanceReport(
            computedAt = now,
            startTs = curveRaw.first().ts,
            endTs = curveRaw.last().ts,
            sessions = curveRaw.size,
            twrPct = round1(twr * 100.0),
            twrAnnPct = twrAnn?.let { round1(it * 100.0) },
            spyTwrPct = round1(spyTwr * 100.0),
            edgePct = round1(edge),
            mwrAnnPct = mwr?.let { round1(it * 100.0) },
            investedIn = round2(investedIn),
            takenOut = round2(takenOut),
            bookNow = round2(bookNow),
            spyAltNow = round2(spyAltNow),
            spyAltClamped = shadowClamped,
            maxDrawdownPct = round1(maxDd * 100.0),
            curve = downsample(curveRaw),
            coveragePct = round1(coverage),
            droppedSymbols = dropped,
            headline = headline,
            notes = notes,
            caveat = caveat
        )
    }

    // ---------------------------------------------------------------- pieces

    /**
     * Rebases every trade into CURRENT share units. Yahoo's daily closes are
     * split-adjusted retroactively, while the ledger records the share counts
     * of their day — valuing raw pre-split shares against adjusted closes
     * halves (or worse) the position. Each trade's quantity is multiplied by
     * the product of all LATER split ratios, its price divided by the same,
     * so every dollar figure is untouched. SPLIT rows are consumed here.
     */
    fun adjustForSplits(trades: List<LedgerTrade>): List<LedgerTrade> {
        if (trades.none { it.side == SIDE_SPLIT }) return trades
        val futureRatio = HashMap<String, Double>()
        val out = ArrayList<LedgerTrade>(trades.size)
        for (t in trades.asReversed()) {
            if (t.side == SIDE_SPLIT) {
                if (t.shares > 0.0) {
                    futureRatio[t.symbol] = (futureRatio[t.symbol] ?: 1.0) * t.shares
                }
                continue
            }
            val ratio = futureRatio[t.symbol] ?: 1.0
            out += if (ratio == 1.0) t else t.copy(
                shares = t.shares * ratio,
                price = t.price / ratio
            )
        }
        out.reverse()
        return out
    }

    /** Applies one (already split-rebased) trade to the running share state. */
    private fun applyTrade(t: LedgerTrade, shares: HashMap<String, Double>) {
        when (t.side) {
            SIDE_SPLIT -> {
                val cur = shares[t.symbol] ?: 0.0
                if (t.shares > 0.0 && cur > 0.0) shares[t.symbol] = cur * t.shares
            }
            SIDE_BUY -> shares[t.symbol] = (shares[t.symbol] ?: 0.0) + t.shares
            else -> {
                val cur = shares[t.symbol] ?: 0.0
                shares[t.symbol] = (cur - minOf(t.shares, cur)).coerceAtLeast(0.0)
            }
        }
    }

    private fun finalShares(trades: List<LedgerTrade>): Map<String, Double> {
        val state = HashMap<String, Double>()
        for (t in trades) applyTrade(t, state)
        return state.filterValues { it > 1e-9 }
    }

    /**
     * The annual rate at which every flow discounts to zero against the
     * terminal value — solved by bisection over [-99%, +1000%]. Null when the
     * flows never change sign (nothing to solve) or no root exists in range.
     */
    fun xirr(flows: List<Pair<Long, Double>>): Double? {
        val cleaned = flows.filter { abs(it.second) > 1e-9 }.sortedBy { it.first }
        if (cleaned.size < 2) return null
        if (cleaned.none { it.second < 0.0 } || cleaned.none { it.second > 0.0 }) return null
        val t0 = cleaned.first().first
        fun npv(rate: Double): Double {
            var sum = 0.0
            for ((ts, cf) in cleaned) {
                val years = (ts - t0).toDouble() / (365.0 * DAY_MS)
                sum += cf / (1.0 + rate).pow(years)
            }
            return sum
        }
        var lo = -0.99
        var hi = 10.0
        var fLo = npv(lo)
        val fHi = npv(hi)
        if (fLo * fHi > 0.0) return null
        repeat(80) {
            val mid = (lo + hi) / 2.0
            val fMid = npv(mid)
            if (fLo * fMid <= 0.0) hi = mid else {
                lo = mid
                fLo = fMid
            }
        }
        return (lo + hi) / 2.0
    }

    private fun downsample(points: List<CurvePoint>): List<CurvePoint> {
        if (points.size <= MAX_CURVE_POINTS) return points
        val step = ceil(points.size.toDouble() / MAX_CURVE_POINTS).toInt()
        val out = ArrayList<CurvePoint>(MAX_CURVE_POINTS + 1)
        for (i in points.indices step step) out += points[i]
        if (out.last() !== points.last()) out += points.last()
        return out
    }

    private fun Fmt(ts: Long): String =
        java.text.SimpleDateFormat("MMM d, yyyy", Locale.US).format(java.util.Date(ts))

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    // ---------------------------------------------------------------- JSON

    fun toJson(r: PerformanceReport): String = JSONObject().apply {
        put("computedAt", r.computedAt)
        put("startTs", r.startTs)
        put("endTs", r.endTs)
        put("sessions", r.sessions)
        put("twr", r.twrPct)
        putOpt("twrAnn", r.twrAnnPct)
        put("spyTwr", r.spyTwrPct)
        put("edge", r.edgePct)
        putOpt("mwrAnn", r.mwrAnnPct)
        put("in", r.investedIn)
        put("out", r.takenOut)
        put("book", r.bookNow)
        put("spyAlt", r.spyAltNow)
        put("spyAltClamped", r.spyAltClamped)
        put("maxDd", r.maxDrawdownPct)
        put("coverage", r.coveragePct)
        put("dropped", JSONArray(r.droppedSymbols))
        put("headline", r.headline)
        put("notes", JSONArray(r.notes))
        put("caveat", r.caveat)
        put("curve", JSONArray().apply {
            r.curve.forEach { p ->
                put(JSONArray().apply { put(p.ts); put(p.book); put(p.spy) })
            }
        })
    }.toString()

    fun fromJson(s: String): PerformanceReport? = try {
        val o = JSONObject(s)
        val curve = ArrayList<CurvePoint>()
        o.optJSONArray("curve")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                curve += CurvePoint(p.getLong(0), p.getDouble(1), p.getDouble(2))
            }
        }
        val dropped = ArrayList<String>()
        o.optJSONArray("dropped")?.let { arr ->
            for (i in 0 until arr.length()) dropped += arr.getString(i)
        }
        val notes = ArrayList<String>()
        o.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) notes += arr.getString(i)
        }
        PerformanceReport(
            computedAt = o.getLong("computedAt"),
            startTs = o.getLong("startTs"),
            endTs = o.getLong("endTs"),
            sessions = o.getInt("sessions"),
            twrPct = o.getDouble("twr"),
            twrAnnPct = if (o.has("twrAnn")) o.getDouble("twrAnn") else null,
            spyTwrPct = o.getDouble("spyTwr"),
            edgePct = o.getDouble("edge"),
            mwrAnnPct = if (o.has("mwrAnn")) o.getDouble("mwrAnn") else null,
            investedIn = o.getDouble("in"),
            takenOut = o.getDouble("out"),
            bookNow = o.getDouble("book"),
            spyAltNow = o.getDouble("spyAlt"),
            spyAltClamped = o.optBoolean("spyAltClamped", false),
            maxDrawdownPct = o.getDouble("maxDd"),
            curve = curve,
            coveragePct = o.getDouble("coverage"),
            droppedSymbols = dropped,
            headline = o.getString("headline"),
            notes = notes,
            caveat = o.optString("caveat", "")
        )
    } catch (_: Exception) {
        null
    }
}
