package com.aurum.invest.analytics

import com.aurum.invest.data.db.CashEventEntity
import com.aurum.invest.data.db.CashType
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
import com.aurum.invest.data.model.Candle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** One point of the reconstructed equity curve. */
data class EquityPoint(val ts: Long, val equity: Double)

/** A highly-correlated pair of current holdings — diversification that isn't. */
data class CorrelatedPair(val a: String, val b: String, val correlation: Double)

/**
 * Broker-grade performance and risk read (H2), reconstructed from the actual
 * transaction ledger replayed against daily closes — not from a hypothetical
 * buy-and-hold of the current book.
 */
data class PortfolioPerformance(
    val computedAt: Long,
    val windowStartTs: Long,
    val windowEndTs: Long,
    val tradingDays: Int,
    /** Time-weighted return over the window, percent (flows removed daily). */
    val twrPct: Double,
    /** SPY's TWR over the same days, percent. */
    val benchmarkTwrPct: Double,
    /** Annualized daily volatility, percent. */
    val volatilityPct: Double,
    /** Max peak-to-trough drawdown of the equity curve, percent (negative). */
    val maxDrawdownPct: Double,
    /** Beta of daily returns vs SPY; null when overlap was too thin. */
    val beta: Double?,
    /** Annualized mean/stdev of daily returns (rf = 0, labeled as such). */
    val sharpe: Double?,
    val equityCurve: List<EquityPoint>,
    /** Current-holding pairs with 60-day return correlation ≥ 0.8. */
    val correlatedPairs: List<CorrelatedPair>,
    /** True when brokerage cash was part of the measured equity. */
    val cashIncluded: Boolean,
    /** Symbols whose price history was missing — their days are approximated. */
    val unpricedSymbols: List<String>,
    val methodNote: String
) {
    companion object {
        fun toJson(p: PortfolioPerformance): String = JSONObject().apply {
            put("computedAt", p.computedAt)
            put("start", p.windowStartTs); put("end", p.windowEndTs)
            put("days", p.tradingDays)
            put("twr", p.twrPct); put("bench", p.benchmarkTwrPct)
            put("vol", p.volatilityPct); put("dd", p.maxDrawdownPct)
            putOpt("beta", p.beta); putOpt("sharpe", p.sharpe)
            put("cash", p.cashIncluded)
            put("note", p.methodNote)
            put("unpriced", JSONArray(p.unpricedSymbols))
            put("curve", JSONArray().apply {
                p.equityCurve.forEach { pt ->
                    put(JSONObject().apply { put("ts", pt.ts); put("v", pt.equity) })
                }
            })
            put("pairs", JSONArray().apply {
                p.correlatedPairs.forEach { pr ->
                    put(JSONObject().apply {
                        put("a", pr.a); put("b", pr.b); put("c", pr.correlation)
                    })
                }
            })
        }.toString()

        fun fromJson(s: String): PortfolioPerformance? = try {
            val o = JSONObject(s)
            val curve = ArrayList<EquityPoint>()
            o.optJSONArray("curve")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val pt = arr.optJSONObject(i) ?: continue
                    curve.add(EquityPoint(pt.getLong("ts"), pt.getDouble("v")))
                }
            }
            val pairs = ArrayList<CorrelatedPair>()
            o.optJSONArray("pairs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val pr = arr.optJSONObject(i) ?: continue
                    pairs.add(CorrelatedPair(pr.getString("a"), pr.getString("b"), pr.getDouble("c")))
                }
            }
            val unpriced = ArrayList<String>()
            o.optJSONArray("unpriced")?.let { arr ->
                for (i in 0 until arr.length()) unpriced.add(arr.optString(i))
            }
            PortfolioPerformance(
                computedAt = o.optLong("computedAt", 0L),
                windowStartTs = o.optLong("start", 0L),
                windowEndTs = o.optLong("end", 0L),
                tradingDays = o.optInt("days", 0),
                twrPct = o.optDouble("twr", 0.0),
                benchmarkTwrPct = o.optDouble("bench", 0.0),
                volatilityPct = o.optDouble("vol", 0.0),
                maxDrawdownPct = o.optDouble("dd", 0.0),
                beta = if (o.has("beta") && !o.isNull("beta")) o.getDouble("beta") else null,
                sharpe = if (o.has("sharpe") && !o.isNull("sharpe")) o.getDouble("sharpe") else null,
                equityCurve = curve,
                correlatedPairs = pairs,
                cashIncluded = o.optBoolean("cash", false),
                unpricedSymbols = unpriced,
                methodNote = o.optString("note", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Pure computation: replays the ledger over a daily grid to reconstruct the
 * account's actual equity path, then measures TWR, volatility, drawdown,
 * beta, Sharpe, and current-holding correlation. Deterministic, never throws.
 */
object PortfolioPerformanceEngine {

    /** Minimum measured days before any statistic is reported. */
    const val MIN_DAYS = 20

    /**
     * [ordered] the full transaction ledger (ts asc); [cashEvents] the cash
     * ledger; [candlesBySymbol] daily closes per traded symbol (oldest first);
     * [benchmark] SPY daily candles defining the trading-day grid.
     */
    fun compute(
        ordered: List<TransactionEntity>,
        cashEvents: List<CashEventEntity>,
        candlesBySymbol: Map<String, List<Candle>>,
        benchmark: List<Candle>,
        cashTracked: Boolean
    ): PortfolioPerformance? {
        if (ordered.isEmpty() || benchmark.size < MIN_DAYS) return null
        val firstTradeTs = ordered.first().ts
        // Grid: benchmark trading days from the first trade onward (max 252).
        val grid = benchmark.filter { it.ts >= firstTradeTs - 86_400_000L }.takeLast(252)
        if (grid.size < MIN_DAYS) return null

        val symbols = ordered.map { it.symbol.trim().uppercase() }.distinct()
        val unpriced = symbols.filter { candlesBySymbol[it].isNullOrEmpty() }

        // Closes per symbol indexed for carry-forward lookup.
        val closesBySymbol = candlesBySymbol.mapValues { (_, candles) ->
            candles.sortedBy { it.ts }
        }

        fun closeOn(symbol: String, dayEnd: Long): Double? {
            val list = closesBySymbol[symbol] ?: return null
            var best: Double? = null
            for (c in list) {
                if (c.ts <= dayEnd) best = c.close else break
            }
            return best
        }

        data class DayState(
            val equity: Double,
            val externalFlow: Double
        )

        val states = ArrayList<Pair<Long, DayState>>(grid.size)
        var txIdx = 0
        var cashIdx = 0
        val sortedCash = cashEvents.sortedBy { it.ts }
        val held = HashMap<String, Double>()
        var cashBalance = 0.0

        for (day in grid) {
            val dayEnd = day.ts + 86_399_000L
            var flow = 0.0
            // Apply the day's transactions to the held map (clamped sells,
            // splits) and record the cash they move.
            while (txIdx < ordered.size && ordered[txIdx].ts <= dayEnd) {
                val tx = ordered[txIdx]
                val sym = tx.symbol.trim().uppercase()
                val cur = held[sym] ?: 0.0
                when (tx.side) {
                    TxSide.BUY -> {
                        held[sym] = cur + tx.shares
                        val cost = tx.shares * tx.price + tx.fees
                        cashBalance -= cost
                        if (!cashTracked) flow += cost
                    }
                    TxSide.SPLIT -> if (tx.shares > 0.0) held[sym] = cur * tx.shares
                    else -> {
                        val qty = minOf(tx.shares, cur)
                        held[sym] = (cur - qty).coerceAtLeast(0.0)
                        val proceeds = qty * tx.price - tx.fees
                        cashBalance += proceeds
                        if (!cashTracked) flow -= proceeds
                    }
                }
                txIdx++
            }
            while (cashIdx < sortedCash.size && sortedCash[cashIdx].ts <= dayEnd) {
                val e = sortedCash[cashIdx]
                when (e.type) {
                    CashType.DEPOSIT -> {
                        cashBalance += e.amount
                        if (cashTracked) flow += e.amount
                    }
                    CashType.WITHDRAW -> {
                        cashBalance -= e.amount
                        if (cashTracked) flow -= e.amount
                    }
                    CashType.DIVIDEND, CashType.INTEREST -> cashBalance += e.amount
                    CashType.FEE -> cashBalance -= e.amount
                }
                cashIdx++
            }

            var holdings = 0.0
            for ((sym, shares) in held) {
                if (shares <= 1e-9) continue
                val close = closeOn(sym, dayEnd) ?: continue
                holdings += shares * close
            }
            val equity = holdings + if (cashTracked) cashBalance else 0.0
            states.add(day.ts to DayState(equity, flow))
        }

        // Daily returns with flows removed: r = (E_t - F_t - E_{t-1}) / E_{t-1}.
        val returns = ArrayList<Double>()
        val benchReturns = ArrayList<Double>()
        val benchCloses = grid.map { it.close }
        var wealthIndex = 1.0
        var peak = 1.0
        var maxDd = 0.0
        val curve = ArrayList<EquityPoint>(states.size)
        for (i in states.indices) {
            val (ts, st) = states[i]
            curve.add(EquityPoint(ts, st.equity))
            if (i == 0) continue
            val prev = states[i - 1].second.equity
            if (prev <= 1.0) continue // no measurable base that day
            val r = (st.equity - st.externalFlow - prev) / prev
            // Guard against data glitches: a ±60% single-day print is a feed
            // artifact at this granularity, not a real portfolio move.
            if (!r.isFinite() || abs(r) > 0.6) continue
            returns.add(r)
            val bPrev = benchCloses[i - 1]
            benchReturns.add(if (bPrev > 0.0) benchCloses[i] / bPrev - 1.0 else 0.0)
            wealthIndex *= (1.0 + r)
            peak = max(peak, wealthIndex)
            val dd = wealthIndex / peak - 1.0
            if (dd < maxDd) maxDd = dd
        }
        if (returns.size < MIN_DAYS) return null

        val twr = (wealthIndex - 1.0) * 100.0
        // Benchmark TWR over the SAME measured days.
        var benchIndex = 1.0
        benchReturns.forEach { benchIndex *= (1.0 + it) }
        val benchTwr = (benchIndex - 1.0) * 100.0

        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        val stdev = sqrt(variance)
        val vol = stdev * sqrt(252.0) * 100.0
        val sharpe = if (stdev > 1e-9) mean / stdev * sqrt(252.0) else null

        val bMean = benchReturns.average()
        val bVar = benchReturns.sumOf { (it - bMean) * (it - bMean) } / (benchReturns.size - 1)
        val cov = returns.indices.sumOf { (returns[it] - mean) * (benchReturns[it] - bMean) } /
            (returns.size - 1)
        val beta = if (bVar > 1e-12) cov / bVar else null

        // Correlation among CURRENT holdings over the last 60 grid days.
        val openSymbols = held.filterValues { it > 1e-9 }.keys.toList()
        val pairs = correlationPairs(openSymbols, closesBySymbol, grid.takeLast(61))

        return PortfolioPerformance(
            computedAt = System.currentTimeMillis(),
            windowStartTs = states.first().first,
            windowEndTs = states.last().first,
            tradingDays = returns.size,
            twrPct = round1(twr),
            benchmarkTwrPct = round1(benchTwr),
            volatilityPct = round1(vol),
            maxDrawdownPct = round1(maxDd * 100.0),
            beta = beta?.let { round2(it) },
            sharpe = sharpe?.let { round2(it) },
            equityCurve = curve,
            correlatedPairs = pairs,
            cashIncluded = cashTracked,
            unpricedSymbols = unpriced,
            methodNote = buildString {
                append("Reconstructed by replaying your actual trades against daily closes over ")
                append("${returns.size} measured trading days. Time-weighted (daily flows removed), ")
                append(
                    if (cashTracked) "including tracked cash. "
                    else "holdings only — cash not tracked, so trades count as external flows. "
                )
                append("Sharpe uses a 0% risk-free rate. Benchmark: SPY over the same days.")
                if (unpriced.isNotEmpty()) {
                    append(" No price history for ${unpriced.joinToString(", ")} — their days are incomplete.")
                }
            }
        )
    }

    private fun correlationPairs(
        symbols: List<String>,
        closesBySymbol: Map<String, List<Candle>>,
        grid: List<Candle>
    ): List<CorrelatedPair> {
        if (symbols.size < 2 || grid.size < 21) return emptyList()
        // Daily returns per symbol on the shared grid (carry-forward closes).
        val returnsBySymbol = HashMap<String, DoubleArray>()
        for (sym in symbols) {
            val list = closesBySymbol[sym] ?: continue
            if (list.size < 21) continue
            val closes = DoubleArray(grid.size)
            var last = Double.NaN
            for (i in grid.indices) {
                val dayEnd = grid[i].ts + 86_399_000L
                for (c in list) {
                    if (c.ts <= dayEnd) last = c.close else break
                }
                closes[i] = last
            }
            if (closes.any { it.isNaN() || it <= 0.0 }) continue
            val rets = DoubleArray(grid.size - 1)
            for (i in 1 until grid.size) rets[i - 1] = closes[i] / closes[i - 1] - 1.0
            returnsBySymbol[sym] = rets
        }
        val out = ArrayList<CorrelatedPair>()
        val keys = returnsBySymbol.keys.toList()
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                val a = returnsBySymbol[keys[i]]!!
                val b = returnsBySymbol[keys[j]]!!
                val c = pearson(a, b)
                if (c >= 0.8) out.add(CorrelatedPair(keys[i], keys[j], round2(c)))
            }
        }
        return out.sortedByDescending { it.correlation }.take(6)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 10) return 0.0
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db; va += da * da; vb += db * db
        }
        val denom = sqrt(va * vb)
        return if (denom > 1e-12) cov / denom else 0.0
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
