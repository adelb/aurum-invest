package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.EarningsInfo
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.repo.InvestorProfile
import com.aurum.invest.data.repo.WalletState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------- outputs

/** What the engine tells the user to do with one holding. */
enum class HoldingMove { HOLD, ADD, TRIM, EXIT }

/** One holding, fully evaluated. */
data class HoldingEvaluation(
    val symbol: String,
    val weightPct: Double,            // of holdings value
    val marketValue: Double,
    val unrealizedPl: Double,
    val unrealizedPlPct: Double,
    /** 35-technique board read; totals 0 when the board could not run. */
    val boardBullish: Int,
    val boardTotal: Int,
    val boardVerdict: TechniqueVerdict,
    val rsi: Double?,                 // null = unmeasured
    val above50: Boolean?,
    val above200: Boolean?,
    /**
     * The ratchet: chandelier exit = 22-day highest high − 3×ATR(14),
     * clamped under the live price. Winners are ridden behind this trail,
     * never sold on a bare gain percentage — the design law of the house.
     */
    val trailStop: Double?,
    val stopDistancePct: Double?,     // how far the stop sits below the price
    /** Next earnings report, when one is known and near; null otherwise. */
    val nextEarningsTs: Long? = null,
    val earningsEstimate: Boolean = false,
    val move: HoldingMove,
    /** 0..100 — how strongly the measured evidence backs [move]. */
    val conviction: Int,
    val reasons: List<String>
)

/** One graded discipline of the wealth health score. */
data class WealthDiscipline(
    val key: String,
    val label: String,
    /** Earned points; null when the inputs could not be measured. */
    val score: Int?,
    val maxScore: Int,
    val detail: String,               // the measured numbers behind the grade
    val fix: String                   // "" when nothing needs fixing
)

/**
 * Risk & performance of the CURRENT book composition, replayed over the
 * window — honestly labeled: this is what today's weights would have done,
 * not the user's trade history.
 */
data class RiskStats(
    val windowDays: Int,
    val measuredValuePct: Double,     // share of holdings value the replay covered
    val returnPct: Double,
    val benchmarkReturnPct: Double?,  // SPY, same window; null when unreachable
    val volatilityPct: Double,        // annualized, σ·√252
    val beta: Double?,                // cov(rp, rspy) / var(rspy)
    val sharpe: Double?,              // mean·252 / σann, rf = 0 (stated)
    val maxDrawdownPct: Double
)

/** One concrete thing to do, in priority order. */
enum class ActionKind { EXIT, TRIM, SET_STOP, ADD, DEPLOY, RAISE_CASH, DIVERSIFY }

data class ManagementAction(
    val priority: Int,                // 1 = do first
    val kind: ActionKind,
    val symbol: String,               // "" for whole-book actions
    val headline: String,
    val detail: String,
    val amount: Double?               // dollars, when the action has a size
)

/** The whole answer: the wealth evaluated, and how to manage it. */
data class WealthReport(
    val computedAt: Long,
    /** 0..100 on a fixed scale; null when < [WealthEngine.MIN_COVERAGE_PCT]% of inputs measured. */
    val healthScore: Int?,
    val healthBand: String,
    val coveragePct: Double,
    val disciplines: List<WealthDiscipline>,
    val holdings: List<HoldingEvaluation>,
    val risk: RiskStats?,
    val actions: List<ManagementAction>,
    val notes: List<String>,
    val caveat: String
)

// ---------------------------------------------------------------- inputs

/** Everything the engine reasons over, pre-gathered by the repository layer. */
data class WealthInputs(
    val wallet: WalletState?,                    // null = total never stated
    val views: List<PositionView>,               // priced holdings
    val candles: Map<String, List<Candle>>,      // ~1y dailies per holding
    val sectors: Map<String, String>,
    val spy: List<Candle>,                       // ~1y dailies
    val vix: Double?,
    val profile: InvestorProfile,
    /** Realized outcome of every closed sell in the ledger, chronological. */
    val sellOutcomes: List<Double>,
    /** Next earnings dates per holding; absent = unmeasured, gate stands down. */
    val earnings: Map<String, EarningsInfo> = emptyMap()
)

/**
 * The wealth engine: evaluates the user's whole financial position — book,
 * cash, risk, trend, record — and produces the management plan for it. No
 * goal inputs, no target profits: it reads what IS (the ledger, the wallet,
 * the market) and answers what to do.
 *
 * The measured machinery, by name:
 *  - 35-technique board per holding, with indicator-agreement verdicts
 *  - Wilder ATR(14) and the chandelier exit (22-day high − 3×ATR) as the
 *    ratcheting trail — winners are ridden, never sold on a percentage
 *  - RSI(14), SMA(50), SMA(200) trend structure
 *  - Herfindahl–Hirschman concentration (Σwᵢ²) for positions AND sectors,
 *    reported as "effective N" (1/HHI)
 *  - value-weighted daily portfolio returns over the window, from which:
 *    annualized volatility σ√252 · beta = cov(rₚ,r_spy)/var(r_spy) ·
 *    Sharpe = μ·252/σ_ann (rf 0, stated) · max drawdown
 *  - Wilson lower bound on the closed-trade win rate, so five lucky sells
 *    can never masquerade as a proven record
 *  - VIX-regime cash bands for the liquidity posture
 *
 * Integrity rules (house law):
 *  - pure function over [WealthInputs]; no I/O, never throws
 *  - every component that could not be measured contributes NOTHING and is
 *    reported as unmeasured — no neutral-point padding
 *  - below [MIN_COVERAGE_PCT]% measured weight the health score is withheld
 *    entirely: a wealth grade built on placeholders is a guess in a suit
 *  - every action cites the measured numbers that demanded it
 */
object WealthEngine {

    /** Below this share of measured input weight, no health score is claimed. */
    const val MIN_COVERAGE_PCT = 60.0

    /** The replay window for the current-book risk stats. */
    const val RISK_WINDOW_DAYS = 120

    /** Chandelier exit parameters — 22-day high, 3 ATRs of room. */
    private const val CHANDELIER_LOOKBACK = 22
    private const val CHANDELIER_ATR_MULT = 3.0

    /**
     * No ADD inside this many days of a known earnings date — a report is a
     * binary event no board read survives. An UNKNOWN date never blocks:
     * absence of data is unmeasured, not "no earnings soon".
     */
    const val EARNINGS_BLACKOUT_DAYS = 7

    /** A known report inside this window is worth a line on the holding. */
    const val EARNINGS_SHOW_DAYS = 14

    fun evaluate(inputs: WealthInputs): WealthReport {
        val now = System.currentTimeMillis()
        val caveat = "Every figure is measured from the ledger, live prices, the " +
            "35-technique board, and the stated wallet — nothing is assumed. " +
            "Decision support, not financial advice."
        val notes = ArrayList<String>()

        val views = inputs.views.filter { it.marketValue > 0.0 }
        val holdingsValue = views.sumOf { it.marketValue }

        // ---- per-holding evaluations ------------------------------------
        val holdings = views.map { evaluateHolding(it, holdingsValue, inputs) }
            .sortedByDescending { it.marketValue }

        // ---- risk & performance of the current book ---------------------
        val risk = replayCurrentBook(views, inputs.candles, inputs.spy)

        // ---- disciplines -------------------------------------------------
        val disciplines = ArrayList<WealthDiscipline>()
        disciplines += concentrationDiscipline(views, inputs.sectors, holdingsValue, inputs.profile)
        disciplines += riskDiscipline(risk, inputs.profile)
        disciplines += trendDiscipline(holdings, holdingsValue)
        disciplines += cashDiscipline(inputs.wallet, inputs.vix)
        disciplines += performanceDiscipline(risk)
        disciplines += recordDiscipline(inputs.sellOutcomes)

        // ---- the score, fail-closed -------------------------------------
        val maxTotal = disciplines.sumOf { it.maxScore }
        val measuredMax = disciplines.filter { it.score != null }.sumOf { it.maxScore }
        val earned = disciplines.sumOf { it.score ?: 0 }
        val coverage = if (maxTotal > 0) measuredMax * 100.0 / maxTotal else 0.0
        val score =
            if (coverage >= MIN_COVERAGE_PCT && measuredMax > 0) {
                // Scaled to the measured universe so an unmeasured discipline
                // neither pads nor punishes — it just narrows the claim.
                (earned * 100.0 / measuredMax).roundToInt().coerceIn(0, 100)
            } else null
        if (score == null && views.isNotEmpty()) {
            notes += String.format(
                Locale.US,
                "Only %.0f%% of the health inputs could be measured this run — no score is " +
                    "claimed below %.0f%%.",
                coverage, MIN_COVERAGE_PCT
            )
        }
        if (inputs.wallet == null) {
            notes += "Set your total wallet on the Dashboard to bring cash posture into the " +
                "evaluation — an unknown cash balance stays unmeasured, never guessed."
        }

        // ---- the management plan ----------------------------------------
        val actions = buildActions(holdings, disciplines, inputs, holdingsValue)

        return WealthReport(
            computedAt = now,
            healthScore = score,
            healthBand = when {
                score == null -> "Not enough measured data"
                score >= 80 -> "Elite discipline"
                score >= 60 -> "Solid, with named gaps"
                score >= 40 -> "Needs work"
                else -> "At risk"
            },
            coveragePct = round1(coverage),
            disciplines = disciplines,
            holdings = holdings,
            risk = risk,
            actions = actions,
            notes = notes,
            caveat = caveat
        )
    }

    // ------------------------------------------------------------ holdings

    private fun evaluateHolding(
        view: PositionView,
        holdingsValue: Double,
        inputs: WealthInputs
    ): HoldingEvaluation {
        val symbol = view.position.symbol
        val candles = inputs.candles[symbol].orEmpty()
        val closes = candles.map { it.close }
        val price = view.quote?.price ?: closes.lastOrNull() ?: view.position.avgCost
        val weight = if (holdingsValue > 0.0) view.marketValue / holdingsValue * 100.0 else 0.0
        val reasons = ArrayList<String>()

        // The board — computed from the same candles every other engine reads.
        val analysis = if (candles.size >= 30) Techniques.analyze(symbol, candles) else null
        val bullish = analysis?.results?.count { it.verdict == TechniqueVerdict.BULLISH } ?: 0
        val total = analysis?.results?.count { it.verdict != TechniqueVerdict.NEUTRAL } ?: 0
        val boardVerdict = when {
            total == 0 -> TechniqueVerdict.NEUTRAL
            bullish * 2 > total -> TechniqueVerdict.BULLISH
            (total - bullish) * 2 > total -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }
        if (total > 0) {
            reasons += "$bullish of $total deciding techniques read bullish."
        } else {
            reasons += "Technique board unmeasured — too little history reached this run."
        }

        val rsi = Indicators.rsi(closes)
        val sma50 = Indicators.sma(closes, 50)
        val sma200 = Indicators.sma(closes, 200)
        val above50 = sma50?.let { price > it }
        val above200 = sma200?.let { price > it }
        if (sma50 != null && sma200 != null) {
            reasons += String.format(
                Locale.US, "Price %s the 50-day and %s the 200-day average.",
                if (above50 == true) "above" else "below",
                if (above200 == true) "above" else "below"
            )
        }
        rsi?.let { reasons += String.format(Locale.US, "RSI %.0f.", it) }

        // The ratcheting trail: chandelier exit, clamped under the price.
        val atr = Indicators.atr(candles)
        val trailStop = if (atr != null && candles.size >= CHANDELIER_LOOKBACK) {
            val highestHigh = candles.takeLast(CHANDELIER_LOOKBACK).maxOf { it.high }
            (highestHigh - CHANDELIER_ATR_MULT * atr)
                .coerceAtMost(price * 0.995)
                .takeIf { it > 0.0 }
        } else null
        val stopDistance = trailStop?.let { (price - it) / price * 100.0 }
        trailStop?.let {
            reasons += String.format(
                Locale.US,
                "Trail: 22-day high − 3×ATR(14) puts the stop at %s (%.1f%% below).",
                money(it), stopDistance ?: 0.0
            )
        }

        // ---- earnings proximity, when the date is known -----------------
        val now = System.currentTimeMillis()
        val earnings = inputs.earnings[symbol]
        val nextEarnings = earnings?.nextTs
        val earningsSoon = nextEarnings != null &&
            nextEarnings <= now + EARNINGS_BLACKOUT_DAYS * 86_400_000L
        val earningsNear = nextEarnings != null &&
            nextEarnings <= now + EARNINGS_SHOW_DAYS * 86_400_000L
        if (nextEarnings != null && earningsNear) {
            reasons += String.format(
                Locale.US, "Earnings %s%s.",
                earningsDate(nextEarnings),
                if (earnings.estimate) " (est.)" else ""
            )
        }

        // ---- the move, from measured evidence only ----------------------
        val maxPos = inputs.profile.maxPositionPct
        val bearishShare = if (total > 0) (total - bullish) * 100.0 / total else 0.0
        val bullishShare = if (total > 0) bullish * 100.0 / total else 0.0
        val addSetup = weight < maxPos * 0.5 && total >= 10 && bullishShare >= 60.0 &&
            above50 == true && above200 == true &&
            (inputs.wallet?.deployable ?: 0.0) > 0.0
        val move: HoldingMove
        val conviction: Int
        when {
            // EXIT: broad bearish agreement AND broken long-term trend.
            total >= 10 && bearishShare >= 60.0 && above200 == false && (rsi ?: 50.0) < 50.0 -> {
                move = HoldingMove.EXIT
                conviction = bearishShare.roundToInt().coerceIn(0, 100)
                reasons.add(
                    0,
                    String.format(
                        Locale.US,
                        "%.0f%% of the deciding board reads bearish with the 200-day trend broken.",
                        bearishShare
                    )
                )
            }
            // TRIM: the position breaches the user's own cap. A risk rule,
            // never a profit rule — gains alone never trigger a sale here.
            weight > maxPos -> {
                move = HoldingMove.TRIM
                conviction = 90
                reasons.add(
                    0,
                    String.format(
                        Locale.US,
                        "%.1f%% of the book — over your %.0f%% position cap; trim the excess, " +
                            "keep the winner.",
                        weight, maxPos
                    )
                )
            }
            // ADD: room under half the cap, strong board, intact trend, cash
            // available — and no earnings report inside the blackout window.
            addSetup && !earningsSoon -> {
                move = HoldingMove.ADD
                conviction = bullishShare.roundToInt().coerceIn(0, 100)
                reasons.add(
                    0,
                    String.format(
                        Locale.US,
                        "%.0f%% bullish board, both averages under the price, and only %.1f%% " +
                            "of the book — room to add within your cap.",
                        bullishShare, weight
                    )
                )
            }
            else -> {
                move = HoldingMove.HOLD
                conviction = when {
                    total == 0 -> 0
                    else -> max(bullishShare, 100.0 - bearishShare).roundToInt().coerceIn(0, 100)
                }
                if (addSetup && earningsSoon && nextEarnings != null) {
                    reasons.add(
                        0,
                        String.format(
                            Locale.US,
                            "The add setup is live, but earnings land %s%s — inside the " +
                                "%d-day blackout, adds wait for the print.",
                            earningsDate(nextEarnings),
                            if (earnings?.estimate == true) " (est.)" else "",
                            EARNINGS_BLACKOUT_DAYS
                        )
                    )
                }
            }
        }

        return HoldingEvaluation(
            symbol = symbol,
            weightPct = round1(weight),
            marketValue = view.marketValue,
            unrealizedPl = view.unrealizedPl,
            unrealizedPlPct = round1(view.unrealizedPlPct),
            boardBullish = bullish,
            boardTotal = total,
            boardVerdict = boardVerdict,
            rsi = rsi?.let { round1(it) },
            above50 = above50,
            above200 = above200,
            trailStop = trailStop?.let { round2(it) },
            stopDistancePct = stopDistance?.let { round1(it) },
            nextEarningsTs = nextEarnings.takeIf { earningsNear },
            earningsEstimate = earnings?.estimate == true && earningsNear,
            move = move,
            conviction = conviction,
            reasons = reasons
        )
    }

    // ------------------------------------------------------------ risk replay

    /**
     * Value-weighted daily returns of the CURRENT composition over the last
     * [RISK_WINDOW_DAYS] sessions. Holdings whose candles failed are excluded
     * and the measured share is reported — never silently assumed flat.
     */
    fun replayCurrentBook(
        views: List<PositionView>,
        candles: Map<String, List<Candle>>,
        spy: List<Candle>
    ): RiskStats? {
        val usable = views.mapNotNull { v ->
            val closes = candles[v.position.symbol]?.map { it.close }.orEmpty()
            if (closes.size >= RISK_WINDOW_DAYS / 2) v to closes else null
        }
        if (usable.isEmpty()) return null
        val totalValue = views.sumOf { it.marketValue }
        val measuredValue = usable.sumOf { it.first.marketValue }
        if (totalValue <= 0.0 || measuredValue <= 0.0) return null

        val window = usable.minOf { it.second.size - 1 }.coerceAtMost(RISK_WINDOW_DAYS)
        if (window < 20) return null

        // Weights renormalized over the measured names.
        val weights = usable.map { it.first.marketValue / measuredValue }
        val returnsPerHolding = usable.map { (_, closes) ->
            Indicators.dailyReturns(closes.takeLast(window + 1))
        }
        val n = returnsPerHolding.minOf { it.size }
        if (n < 20) return null
        val rp = DoubleArray(n) { day ->
            var r = 0.0
            for (i in weights.indices) {
                val series = returnsPerHolding[i]
                r += weights[i] * series[series.size - n + day]
            }
            r
        }.toList()

        val mean = rp.average()
        val variance = rp.sumOf { (it - mean) * (it - mean) } / (rp.size - 1)
        val sd = sqrt(variance)
        val volAnn = sd * sqrt(252.0) * 100.0
        val cumulative = rp.fold(1.0) { acc, r -> acc * (1.0 + r) } - 1.0
        val sharpe = if (sd > 1e-12) (mean * 252.0) / (sd * sqrt(252.0)) else null

        // Max drawdown of the compounded curve.
        var peak = 1.0
        var equity = 1.0
        var maxDd = 0.0
        for (r in rp) {
            equity *= (1.0 + r)
            if (equity > peak) peak = equity
            val dd = (peak - equity) / peak
            if (dd > maxDd) maxDd = dd
        }

        // Benchmark, same day count.
        val spyCloses = spy.map { it.close }
        val spyReturns = Indicators.dailyReturns(spyCloses.takeLast(n + 1))
        var beta: Double? = null
        var benchReturn: Double? = null
        if (spyReturns.size == n && n >= 20) {
            val bMean = spyReturns.average()
            val bVar = spyReturns.sumOf { (it - bMean) * (it - bMean) } / (n - 1)
            if (bVar > 1e-12) {
                var cov = 0.0
                for (i in 0 until n) cov += (rp[i] - mean) * (spyReturns[i] - bMean)
                cov /= (n - 1)
                beta = cov / bVar
            }
            benchReturn = spyReturns.fold(1.0) { acc, r -> acc * (1.0 + r) } - 1.0
        }

        return RiskStats(
            windowDays = n,
            measuredValuePct = round1(measuredValue / totalValue * 100.0),
            returnPct = round1(cumulative * 100.0),
            benchmarkReturnPct = benchReturn?.let { round1(it * 100.0) },
            volatilityPct = round1(volAnn),
            beta = beta?.let { round2(it) },
            sharpe = sharpe?.let { round2(it) },
            maxDrawdownPct = round1(maxDd * 100.0)
        )
    }

    // ------------------------------------------------------------ disciplines

    /** HHI = Σwᵢ² over weights as fractions; 1/HHI = effective position count. */
    fun hhi(weights: List<Double>): Double {
        val sum = weights.sum()
        if (sum <= 0.0) return 0.0
        return weights.sumOf { val w = it / sum; w * w }
    }

    private fun concentrationDiscipline(
        views: List<PositionView>,
        sectors: Map<String, String>,
        holdingsValue: Double,
        profile: InvestorProfile
    ): WealthDiscipline {
        if (views.isEmpty() || holdingsValue <= 0.0) {
            return WealthDiscipline(
                "concentration", "Diversification", null, 20,
                "No priced holdings to measure.", ""
            )
        }
        val posHhi = hhi(views.map { it.marketValue })
        val effN = if (posHhi > 0.0) 1.0 / posHhi else 0.0
        val sectorValues = views.groupBy { sectors[it.position.symbol] ?: PortfolioLens.UNCLASSIFIED }
            .mapValues { (_, group) -> group.sumOf { it.marketValue } }
        val secHhi = hhi(sectorValues.values.toList())
        val effSectors = if (secHhi > 0.0) 1.0 / secHhi else 0.0
        val topSector = sectorValues.maxByOrNull { it.value }
        val topSectorPct = (topSector?.value ?: 0.0) / holdingsValue * 100.0

        // Effective 5+ names earns full position points; effective 3+ sectors full sector points.
        val posPts = ((effN - 1.0) / 4.0 * 10.0).coerceIn(0.0, 10.0)
        val secPts = ((effSectors - 1.0) / 2.0 * 10.0).coerceIn(0.0, 10.0)
        val score = (posPts + secPts).roundToInt()
        val detail = String.format(
            Locale.US,
            "Herfindahl concentration: effective %.1f positions and %.1f sectors; " +
                "largest sector %s at %.0f%%.",
            effN, effSectors, topSector?.key ?: "—", topSectorPct
        )
        val fix = when {
            effN < 3.0 -> "Spread new money into names you don't already own — an effective " +
                "${"%.1f".format(effN)}-stock book rises and falls as one trade."
            topSectorPct > profile.maxSectorPct ->
                "${topSector?.key} is ${topSectorPct.roundToInt()}% of the book against your " +
                    "${profile.maxSectorPct.toInt()}% cap — steer the next buys elsewhere."
            else -> ""
        }
        return WealthDiscipline("concentration", "Diversification", score, 20, detail, fix)
    }

    private fun riskDiscipline(risk: RiskStats?, profile: InvestorProfile): WealthDiscipline {
        if (risk == null) {
            return WealthDiscipline(
                "risk", "Risk control", null, 20,
                "The current-book replay could not be measured this run.", ""
            )
        }
        // Tolerance bands: what annualized volatility the profile signed up for.
        val volCeiling = when (profile.riskTolerance) {
            InvestorProfile.TOL_CONSERVATIVE -> 15.0
            InvestorProfile.TOL_AGGRESSIVE -> 35.0
            else -> 25.0
        }
        val volPts = when {
            risk.volatilityPct <= volCeiling -> 10.0
            risk.volatilityPct <= volCeiling * 1.5 ->
                10.0 * (1.0 - (risk.volatilityPct - volCeiling) / (volCeiling * 0.5))
            else -> 0.0
        }
        val ddPts = when {
            risk.maxDrawdownPct <= 10.0 -> 10.0
            risk.maxDrawdownPct <= 30.0 -> 10.0 * (1.0 - (risk.maxDrawdownPct - 10.0) / 20.0)
            else -> 0.0
        }
        val score = (volPts + ddPts).roundToInt()
        val detail = String.format(
            Locale.US,
            "Annualized volatility %.1f%% against your %.0f%% tolerance ceiling; deepest " +
                "drawdown %.1f%% over the %d-session replay (%.0f%% of value measured).",
            risk.volatilityPct, volCeiling, risk.maxDrawdownPct, risk.windowDays,
            risk.measuredValuePct
        )
        val fix =
            if (risk.volatilityPct > volCeiling) {
                "The book swings harder than your stated tolerance — smaller positions or " +
                    "steadier names bring it back inside."
            } else ""
        return WealthDiscipline("risk", "Risk control", score, 20, detail, fix)
    }

    private fun trendDiscipline(
        holdings: List<HoldingEvaluation>,
        holdingsValue: Double
    ): WealthDiscipline {
        val measured = holdings.filter { it.above200 != null }
        if (measured.isEmpty() || holdingsValue <= 0.0) {
            return WealthDiscipline(
                "trend", "Trend health", null, 20,
                "No holding had enough history for a 200-day read.", ""
            )
        }
        val aboveValue = measured.filter { it.above200 == true }.sumOf { it.marketValue }
        val measuredValue = measured.sumOf { it.marketValue }
        val abovePct = aboveValue / measuredValue * 100.0
        val boardMeasured = holdings.filter { it.boardTotal > 0 }
        val bullishValue = boardMeasured.filter { it.boardVerdict == TechniqueVerdict.BULLISH }
            .sumOf { it.marketValue }
        val boardValue = boardMeasured.sumOf { it.marketValue }
        val bullishPct = if (boardValue > 0.0) bullishValue / boardValue * 100.0 else 0.0

        val score = (abovePct / 100.0 * 10.0 + bullishPct / 100.0 * 10.0).roundToInt()
        val detail = String.format(
            Locale.US,
            "%.0f%% of measured value trades above its 200-day average; %.0f%% sits on a " +
                "bullish technique board.",
            abovePct, bullishPct
        )
        val fix =
            if (abovePct < 50.0) {
                "More than half the book is under its long-term trend — the EXIT and TRIM " +
                    "calls below name the weakest."
            } else ""
        return WealthDiscipline("trend", "Trend health", score, 20, detail, fix)
    }

    /** VIX-regime cash bands: calm markets need less powder, stressed ones more. */
    fun cashBandFor(vix: Double?): ClosedFloatingPointRange<Double> = when {
        vix == null -> 5.0..25.0
        vix < 17.0 -> 5.0..15.0
        vix < 25.0 -> 10.0..25.0
        else -> 20.0..40.0
    }

    private fun cashDiscipline(wallet: WalletState?, vix: Double?): WealthDiscipline {
        if (wallet == null || !wallet.configured || wallet.netWorth <= 0.0) {
            return WealthDiscipline(
                "cash", "Cash posture", null, 15,
                "No stated wallet — the cash share cannot be measured.", ""
            )
        }
        val cashPct = (wallet.liquidity / wallet.netWorth * 100.0).coerceAtLeast(0.0)
        val band = cashBandFor(vix)
        val score = when {
            cashPct in band -> 15.0
            cashPct < band.start -> (15.0 * cashPct / band.start).coerceAtLeast(0.0)
            else -> {
                // Above the band: idle money, gently penalized, never below half.
                val over = (cashPct - band.endInclusive) / (100.0 - band.endInclusive)
                15.0 - over * 7.5
            }
        }.roundToInt()
        val vixNote = vix?.let {
            String.format(Locale.US, " for the current VIX %.1f regime", it)
        } ?: " (volatility unmeasured — the widest band applies)"
        val detail = String.format(
            Locale.US,
            "Liquidity is %.1f%% of net worth against a %.0f–%.0f%% band%s.",
            cashPct, band.start, band.endInclusive, vixNote
        )
        val fix = when {
            wallet.shortfall ->
                "The ledger has more deployed than the stated wallet covers — update the total."
            cashPct < band.start ->
                "Below the regime band — let sells settle as cash before redeploying everything."
            cashPct > band.endInclusive ->
                "Above the band — the deployment card below sizes the surplus into the " +
                    "trendy sectors under your caps."
            else -> ""
        }
        return WealthDiscipline("cash", "Cash posture", score, 15, detail, fix)
    }

    private fun performanceDiscipline(risk: RiskStats?): WealthDiscipline {
        if (risk == null) {
            return WealthDiscipline(
                "performance", "Performance", null, 15,
                "The current-book replay could not be measured this run.", ""
            )
        }
        val bench = risk.benchmarkReturnPct
        val edgePts = if (bench != null) {
            val edge = risk.returnPct - bench
            // ±10 points of edge sweep 0..10 discipline points, centered at parity.
            (5.0 + edge / 2.0).coerceIn(0.0, 10.0)
        } else 0.0
        val sharpePts = risk.sharpe?.let { s -> ((s + 0.5) / 2.5 * 5.0).coerceIn(0.0, 5.0) } ?: 0.0
        val score = (edgePts + sharpePts).roundToInt()
        val detail = buildString {
            append(
                String.format(
                    Locale.US, "Current book %+.1f%% over the %d-session replay",
                    risk.returnPct, risk.windowDays
                )
            )
            bench?.let { append(String.format(Locale.US, " vs SPY %+.1f%%", it)) }
            risk.sharpe?.let { append(String.format(Locale.US, "; Sharpe %.2f (rf 0)", it)) }
            append(". This replays today's composition, not your trade history.")
        }
        val fix =
            if (bench != null && risk.returnPct < bench - 5.0) {
                "The book trails the index by ${"%.1f".format(bench - risk.returnPct)} points " +
                    "over the window — the weakest boards below are where the lag lives."
            } else ""
        return WealthDiscipline("performance", "Performance", score, 15, detail, fix)
    }

    /** Wilson score lower bound at z=1.96 — the honest floor on a win rate. */
    fun wilsonLower(wins: Int, n: Int): Double {
        if (n <= 0) return 0.0
        val z = 1.96
        val p = wins.toDouble() / n
        val denom = 1.0 + z * z / n
        val center = p + z * z / (2.0 * n)
        val margin = z * sqrt((p * (1.0 - p) + z * z / (4.0 * n)) / n)
        return ((center - margin) / denom).coerceIn(0.0, 1.0)
    }

    private fun recordDiscipline(sellOutcomes: List<Double>): WealthDiscipline {
        if (sellOutcomes.size < 5) {
            return WealthDiscipline(
                "record", "Trade record", null, 10,
                "Fewer than 5 closed sells — no record is graded on a handful of trades.", ""
            )
        }
        val wins = sellOutcomes.count { it > 0.0 }
        val n = sellOutcomes.size
        val lower = wilsonLower(wins, n)
        val avgWin = sellOutcomes.filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.average()
        val avgLoss = sellOutcomes.filter { it < 0.0 }.takeIf { it.isNotEmpty() }?.average()
        val score = (lower * 10.0 / 0.6).coerceIn(0.0, 10.0).roundToInt()
        val payoff = if (avgWin != null && avgLoss != null && avgLoss != 0.0) {
            String.format(Locale.US, "; average win %s vs average loss %s", money(avgWin), money(avgLoss))
        } else ""
        val detail = String.format(
            Locale.US,
            "%d of %d closed sells booked a profit — at least %.0f%% win rate with 95%% " +
                "confidence (Wilson)%s.",
            wins, n, lower * 100.0, payoff
        )
        val fix =
            if (avgWin != null && avgLoss != null && abs(avgLoss) > avgWin) {
                "Losses run larger than wins — the trail stops below cap each loss while " +
                    "letting winners run."
            } else ""
        return WealthDiscipline("record", "Trade record", score, 10, detail, fix)
    }

    // ------------------------------------------------------------ actions

    private fun buildActions(
        holdings: List<HoldingEvaluation>,
        disciplines: List<WealthDiscipline>,
        inputs: WealthInputs,
        holdingsValue: Double
    ): List<ManagementAction> {
        val out = ArrayList<ManagementAction>()
        var priority = 1

        // 1) Exits — broken names first, largest exposure first.
        holdings.filter { it.move == HoldingMove.EXIT }
            .sortedByDescending { it.marketValue }
            .forEach { h ->
                out += ManagementAction(
                    priority = priority++,
                    kind = ActionKind.EXIT,
                    symbol = h.symbol,
                    headline = "Exit ${h.symbol} — the evidence has turned",
                    detail = h.reasons.firstOrNull().orEmpty(),
                    amount = h.marketValue
                )
            }

        // 2) Cap-breach trims, with the dollar excess named.
        holdings.filter { it.move == HoldingMove.TRIM }.forEach { h ->
            val excess = (h.weightPct - inputs.profile.maxPositionPct) / 100.0 * holdingsValue
            if (excess > 1.0) {
                out += ManagementAction(
                    priority = priority++,
                    kind = ActionKind.TRIM,
                    symbol = h.symbol,
                    headline = "Trim ${h.symbol} back inside your ${inputs.profile.maxPositionPct.toInt()}% cap",
                    detail = String.format(
                        Locale.US,
                        "%.1f%% of the book; selling ~%s returns it to the cap without giving " +
                            "up the position.",
                        h.weightPct, money(excess)
                    ),
                    amount = round2(excess)
                )
            }
        }

        // 3) Stops — one action naming every trail that should be standing.
        val stops = holdings.filter { it.move != HoldingMove.EXIT && it.trailStop != null }
        if (stops.isNotEmpty()) {
            out += ManagementAction(
                priority = priority++,
                kind = ActionKind.SET_STOP,
                symbol = "",
                headline = "Stand the trailing stops under every holding",
                detail = stops.joinToString(" · ") {
                    "${it.symbol} ${money(it.trailStop!!)}"
                } + " — 22-day high minus 3×ATR each; raise them as the highs rise, never lower them.",
                amount = null
            )
        }

        // 4) Adds — strongest boards with room, cash permitting.
        holdings.filter { it.move == HoldingMove.ADD }
            .sortedByDescending { it.conviction }
            .take(3)
            .forEach { h ->
                out += ManagementAction(
                    priority = priority++,
                    kind = ActionKind.ADD,
                    symbol = h.symbol,
                    headline = "Add to ${h.symbol} within your cap",
                    detail = h.reasons.firstOrNull().orEmpty() +
                        " The deployment card sizes the ticket against your liquidity.",
                    amount = null
                )
            }

        // 5) Cash posture, from the discipline's own verdict.
        disciplines.firstOrNull { it.key == "cash" && it.fix.isNotEmpty() }?.let { d ->
            val wallet = inputs.wallet
            val band = cashBandFor(inputs.vix)
            if (wallet != null && wallet.configured && wallet.netWorth > 0.0) {
                val cashPct = wallet.liquidity / wallet.netWorth * 100.0
                if (cashPct > band.endInclusive) {
                    val surplus = (cashPct - band.endInclusive) / 100.0 * wallet.netWorth
                    out += ManagementAction(
                        priority = priority++,
                        kind = ActionKind.DEPLOY,
                        symbol = "",
                        headline = "Deploy the idle surplus",
                        detail = d.detail + " " + d.fix,
                        amount = round2(surplus)
                    )
                } else if (cashPct < band.start && !wallet.shortfall) {
                    out += ManagementAction(
                        priority = priority++,
                        kind = ActionKind.RAISE_CASH,
                        symbol = "",
                        headline = "Rebuild the cash buffer",
                        detail = d.detail + " " + d.fix,
                        amount = null
                    )
                }
            }
        }

        // 6) Diversification, when concentration flagged it.
        disciplines.firstOrNull { it.key == "concentration" && it.fix.isNotEmpty() }?.let { d ->
            out += ManagementAction(
                priority = priority++,
                kind = ActionKind.DIVERSIFY,
                symbol = "",
                headline = "Spread the concentration",
                detail = d.detail + " " + d.fix,
                amount = null
            )
        }

        return out
    }

    // ------------------------------------------------------------ helpers

    private fun money(v: Double): String =
        if (abs(v) >= 10_000) String.format(Locale.US, "$%,.0f", v)
        else String.format(Locale.US, "$%,.2f", v)

    private fun earningsDate(ts: Long): String =
        SimpleDateFormat("MMM d", Locale.US).format(Date(ts))

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
