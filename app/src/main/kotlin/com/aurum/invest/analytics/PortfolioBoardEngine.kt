package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.AdviceAction
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.Quote
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The portfolio board engine: every open holding, one at a time, through the
 * full 35-technique board — re-voted with each technique's measured 1-year
 * record on that exact stock when the grade exists — merged with the sell-side
 * advice (stops, targets, trailing exits), and folded into ONE ranked list of
 * what to do now. The most urgent call leads; a healthy holder sits last.
 *
 * Integrity rules (house law):
 *  - pure Kotlin over prepared inputs; no I/O, never throws
 *  - a holding without candles or a live price is reported UNMEASURED, never
 *    scored by silence
 *  - the portfolio temperature is value-weighted over measured boards only,
 *    and is withheld entirely below [MIN_TEMP_COVERAGE_PCT]% measured weight
 *  - ADD is never suggested into a position already at or past
 *    [CONCENTRATION_CAP_PCT]% of the book — conviction does not override
 *    concentration
 */

/** Everything the engine may know about one holding. Candles may be empty. */
data class BoardHoldingInput(
    val position: Position,
    val quote: Quote?,
    val candles: List<Candle>,
    /** Measured 1-year technique record on this stock; null while ungraded. */
    val evaluation: TechniqueEvaluation?,
    val newsScore: Int = 0,
    val sector: String? = null
)

/** What the user should do with one holding, most urgent kind first. */
enum class BoardAction { EXIT, TRIM, WATCH, ADD, HOLD }

data class HoldingBoardReview(
    val symbol: String,
    val shares: Double,
    val avgCost: Double,
    /** 0.0 when no price could be established — [measured] is then false. */
    val price: Double,
    val marketValue: Double,
    /** Share of the priced book, percent. */
    val weightPct: Double,
    val plPct: Double?,
    val plDollars: Double?,
    /** True when the 35-technique board could actually read this holding. */
    val measured: Boolean,
    /** True when the board was re-voted with the measured 1-year record. */
    val graded: Boolean,
    val boardBullish: Int,
    val boardBearish: Int,
    val boardNeutral: Int,
    val boardVerdict: TechniqueVerdict,
    val outlook: FiveDayOutlook?,
    /** Names of TRUSTED techniques (measured edge on this stock) siding each way now. */
    val trustedBull: List<String>,
    val trustedBear: List<String>,
    val advice: Advice?,
    val action: BoardAction,
    /** Number-backed reasons, most decisive first. */
    val why: List<String>
)

data class PortfolioBoardReview(
    val computedAt: Long,
    /** Most urgent first: EXIT, TRIM, WATCH, ADD, HOLD; heavier weight breaks ties. */
    val holdings: List<HoldingBoardReview>,
    val totalValue: Double,
    /** Share of book value in board-bullish / board-bearish names, percent. */
    val bullishWeightPct: Double,
    val bearishWeightPct: Double,
    /** Share of book value whose board could be measured, percent. */
    val measuredWeightPct: Double,
    /**
     * Value-weighted bullish share of deciding techniques, 0..100 fixed scale;
     * null when under [PortfolioBoardEngine.MIN_TEMP_COVERAGE_PCT]% of the
     * book's value carries a measured board.
     */
    val boardTempPct: Int?,
    val headline: String,
    /** The ranked to-do list, one line per non-HOLD holding. */
    val actions: List<String>,
    val notes: List<String>
)

object PortfolioBoardEngine {

    /** Below this share of measured book weight, no portfolio temperature is claimed. */
    const val MIN_TEMP_COVERAGE_PCT = 60.0

    /** At or past this share of the book, ADD is withheld with the reason printed. */
    const val CONCENTRATION_CAP_PCT = 35.0

    /** Deciding (non-neutral) techniques needed before a board verdict is claimed. */
    const val MIN_DECIDING = 10

    fun review(
        inputs: List<BoardHoldingInput>,
        now: Long = System.currentTimeMillis()
    ): PortfolioBoardReview {
        val notes = ArrayList<String>()
        if (inputs.isEmpty()) {
            return PortfolioBoardReview(
                computedAt = now,
                holdings = emptyList(),
                totalValue = 0.0,
                bullishWeightPct = 0.0,
                bearishWeightPct = 0.0,
                measuredWeightPct = 0.0,
                boardTempPct = null,
                headline = "No open positions — there is nothing for the board to read.",
                actions = emptyList(),
                notes = emptyList()
            )
        }

        // ---- price the book once, so every weight agrees ------------------
        data class Prepared(val input: BoardHoldingInput, val price: Double, val value: Double)
        val prepared = inputs.map { input ->
            val price = input.quote?.price?.takeIf { it > 0.0 && it.isFinite() }
                ?: input.candles.lastOrNull()?.close?.takeIf { it > 0.0 && it.isFinite() }
                ?: 0.0
            Prepared(input, price, input.position.shares * price)
        }
        val totalValue = prepared.sumOf { it.value }

        val reviews = prepared.map { p ->
            reviewHolding(
                p.input, p.price, p.value,
                weightPct = if (totalValue > 0.0) p.value / totalValue * 100.0 else 0.0
            )
        }

        // ---- the value-weighted temperature, measured boards only ---------
        val measuredWeight = reviews.filter { it.measured }.sumOf { it.weightPct }
        val bullishWeight = reviews
            .filter { it.measured && it.boardVerdict == TechniqueVerdict.BULLISH }
            .sumOf { it.weightPct }
        val bearishWeight = reviews
            .filter { it.measured && it.boardVerdict == TechniqueVerdict.BEARISH }
            .sumOf { it.weightPct }
        val boardTemp = if (measuredWeight >= MIN_TEMP_COVERAGE_PCT) {
            var num = 0.0
            var den = 0.0
            for (r in reviews) {
                val deciding = r.boardBullish + r.boardBearish
                if (!r.measured || deciding == 0) continue
                num += r.weightPct * (r.boardBullish * 100.0 / deciding)
                den += r.weightPct
            }
            if (den > 0.0) (num / den).roundToInt().coerceIn(0, 100) else null
        } else null
        if (boardTemp == null && totalValue > 0.0) {
            notes += String.format(
                Locale.US,
                "Only %.0f%% of the book's value carries a measured board — no portfolio " +
                    "temperature is claimed below %.0f%%.",
                measuredWeight, MIN_TEMP_COVERAGE_PCT
            )
        }

        val unmeasured = reviews.filter { !it.measured }
        if (unmeasured.isNotEmpty()) {
            notes += String.format(
                Locale.US,
                "%s could not be read this run (no price history reached the board) and " +
                    "%s excluded from every measured figure.",
                unmeasured.joinToString(", ") { it.symbol },
                if (unmeasured.size == 1) "is" else "are"
            )
        }

        // ---- concentration, computed once over the priced book ------------
        val bySector = prepared
            .filter { it.value > 0.0 && !it.input.sector.isNullOrBlank() }
            .groupBy { it.input.sector!! }
        if (totalValue > 0.0) {
            val top = bySector.entries
                .map { (sector, group) -> sector to group.sumOf { it.value } / totalValue * 100.0 }
                .maxByOrNull { it.second }
            if (top != null && top.second >= 50.0) {
                notes += String.format(
                    Locale.US,
                    "Concentrated: %.0f%% of the book sits in %s — a single-sector shock " +
                        "hits everything at once.",
                    top.second, top.first
                )
            } else if (top != null && top.second >= CONCENTRATION_CAP_PCT) {
                notes += String.format(
                    Locale.US, "%s is the largest sector exposure at %.0f%% of the book.",
                    top.first, top.second
                )
            }
        }

        // ---- urgency order and the to-do list -----------------------------
        val ordered = reviews.sortedWith(
            compareBy<HoldingBoardReview> { it.action.ordinal }
                .thenByDescending { it.weightPct }
        )
        val actions = ordered
            .filter { it.action != BoardAction.HOLD }
            .map { r ->
                val lead = r.why.firstOrNull() ?: "the board's read"
                "${actionWord(r.action)} ${r.symbol} — $lead"
            }

        val urgent = reviews.count { it.action == BoardAction.EXIT || it.action == BoardAction.TRIM }
        val measuredCount = reviews.count { it.measured }
        val bullCount = reviews.count { it.measured && it.boardVerdict == TechniqueVerdict.BULLISH }
        val headline = when {
            totalValue <= 0.0 ->
                "No holding could be priced this run — the board has nothing measured to say."
            measuredCount == 0 ->
                "None of the ${reviews.size} holdings could be read this run."
            urgent > 0 -> String.format(
                Locale.US,
                "%d of %d measured holdings read bullish on the board; %d call%s for money " +
                    "to move.",
                bullCount, measuredCount, urgent, if (urgent == 1) "s" else ""
            )
            else -> String.format(
                Locale.US,
                "%d of %d measured holdings read bullish on the board; nothing demands an " +
                    "exit today.",
                bullCount, measuredCount
            )
        }

        return PortfolioBoardReview(
            computedAt = now,
            holdings = ordered,
            totalValue = round2(totalValue),
            bullishWeightPct = round1(bullishWeight),
            bearishWeightPct = round1(bearishWeight),
            measuredWeightPct = round1(measuredWeight),
            boardTempPct = boardTemp,
            headline = headline,
            actions = actions,
            notes = notes
        )
    }

    // ------------------------------------------------------------ one holding

    private fun reviewHolding(
        input: BoardHoldingInput,
        price: Double,
        value: Double,
        weightPct: Double
    ): HoldingBoardReview {
        val position = input.position
        val symbol = position.symbol

        // The board, re-voted with the measured record when it exists.
        val weights = input.evaluation
            ?.takeIf { TechniqueEvaluator.isComplete(it, symbol) }
            ?.weights()
        val analysis = runCatching {
            Techniques.analyze(symbol, input.candles, weights)
        }.getOrNull()
        val bullish = analysis?.results?.count { it.verdict == TechniqueVerdict.BULLISH } ?: 0
        val bearish = analysis?.results?.count { it.verdict == TechniqueVerdict.BEARISH } ?: 0
        val neutral = analysis?.results?.count { it.verdict == TechniqueVerdict.NEUTRAL } ?: 0
        val deciding = bullish + bearish
        val measured = analysis != null && deciding >= MIN_DECIDING
        val boardVerdict = when {
            !measured -> TechniqueVerdict.NEUTRAL
            bullish * 2 > deciding -> TechniqueVerdict.BULLISH
            bearish * 2 > deciding -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }

        // Trusted techniques siding each way right now.
        val trustedKeys = input.evaluation
            ?.takeIf { TechniqueEvaluator.isComplete(it, symbol) }
            ?.trustedKeys.orEmpty()
        val trustedBull = ArrayList<String>()
        val trustedBear = ArrayList<String>()
        if (analysis != null) {
            for (r in analysis.results) {
                if (r.key !in trustedKeys) continue
                when (r.verdict) {
                    TechniqueVerdict.BULLISH -> trustedBull.add(r.name)
                    TechniqueVerdict.BEARISH -> trustedBear.add(r.name)
                    TechniqueVerdict.NEUTRAL -> Unit
                }
            }
        }

        val advice = if (input.quote != null && input.quote.price > 0.0) {
            runCatching {
                AdviceEngine.sellAdvice(position, input.quote, input.candles, input.newsScore)
            }.getOrNull()
        } else null

        val plPct = if (position.avgCost > 0.0 && price > 0.0) {
            (price - position.avgCost) / position.avgCost * 100.0
        } else null
        val plDollars = if (plPct != null) (price - position.avgCost) * position.shares else null

        val (action, why) = decide(
            advice, measured, boardVerdict, analysis?.outlook,
            bullish, bearish, trustedBull, trustedBear, weightPct, plPct, price
        )

        return HoldingBoardReview(
            symbol = symbol,
            shares = position.shares,
            avgCost = round2(position.avgCost),
            price = round2(price),
            marketValue = round2(value),
            weightPct = round1(weightPct),
            plPct = plPct?.let { round1(it) },
            plDollars = plDollars?.let { round2(it) },
            measured = measured,
            graded = weights != null,
            boardBullish = bullish,
            boardBearish = bearish,
            boardNeutral = neutral,
            boardVerdict = boardVerdict,
            outlook = analysis?.outlook,
            trustedBull = trustedBull,
            trustedBear = trustedBear,
            advice = advice,
            action = action,
            why = why
        )
    }

    /**
     * The decision table. The sell-side advice (stops, trailing exits, targets)
     * ALWAYS outranks the board — defense first — and the board decides only
     * between the quiet outcomes: ADD, WATCH or HOLD.
     */
    private fun decide(
        advice: Advice?,
        measured: Boolean,
        boardVerdict: TechniqueVerdict,
        outlook: FiveDayOutlook?,
        bullish: Int,
        bearish: Int,
        trustedBull: List<String>,
        trustedBear: List<String>,
        weightPct: Double,
        plPct: Double?,
        price: Double
    ): Pair<BoardAction, List<String>> {
        val why = ArrayList<String>()
        val boardLine = if (measured) {
            "the board reads $bullish–$bearish " +
                when (boardVerdict) {
                    TechniqueVerdict.BULLISH -> "bullish"
                    TechniqueVerdict.BEARISH -> "bearish"
                    TechniqueVerdict.NEUTRAL -> "split"
                }
        } else null

        if (advice == null || price <= 0.0) {
            why += "no live price reached this run — the holding is unmeasured, not judged"
            return BoardAction.WATCH to why
        }

        when (advice.action) {
            AdviceAction.CUT_LOSS -> {
                why += advice.headline.trimEnd('.')
                boardLine?.let { why += it }
                return BoardAction.EXIT to why
            }
            AdviceAction.SELL -> {
                why += advice.headline.trimEnd('.')
                boardLine?.let { why += it }
                return if (boardVerdict == TechniqueVerdict.BEARISH) {
                    why.add(1, "the measured board sides with the sell signal")
                    BoardAction.EXIT to why
                } else BoardAction.TRIM to why
            }
            AdviceAction.TAKE_PROFIT -> {
                why += advice.headline.trimEnd('.')
                boardLine?.let { why += it }
                return BoardAction.TRIM to why
            }
            else -> Unit
        }

        // No exit signal — the board speaks.
        if (!measured) {
            why += "no exit signal, but under $MIN_DECIDING deciding techniques — " +
                "the board can't read it yet"
            return BoardAction.HOLD to why
        }

        val outlookBull = outlook?.direction == TechniqueVerdict.BULLISH
        val outlookBear = outlook?.direction == TechniqueVerdict.BEARISH
        val trustedAgainst = trustedBear.size > trustedBull.size

        if (boardVerdict == TechniqueVerdict.BEARISH || (outlookBear && trustedAgainst)) {
            why += boardLine!!
            if (outlookBear && outlook != null) {
                why += String.format(
                    Locale.US,
                    "the 5-day outlook leans lower (%s–%s)",
                    Fmt.money(outlook.expectedLow), Fmt.money(outlook.expectedHigh)
                )
            }
            if (trustedAgainst) {
                why += "trusted techniques side ${trustedBear.size}–${trustedBull.size} against it"
            }
            advice.stopLoss?.let { why += "no exit signal yet — the stop to watch is ${Fmt.money(it)}" }
            return BoardAction.WATCH to why
        }

        if (
            boardVerdict == TechniqueVerdict.BULLISH && outlookBull &&
            trustedBull.size >= trustedBear.size
        ) {
            if (weightPct >= CONCENTRATION_CAP_PCT) {
                why += boardLine!!
                why += String.format(
                    Locale.US,
                    "the board would add, but at %.0f%% of the book the %.0f%% concentration " +
                        "cap holds it — conviction does not override concentration",
                    weightPct, CONCENTRATION_CAP_PCT
                )
                return BoardAction.HOLD to why
            }
            why += boardLine!!
            if (outlook != null) {
                why += String.format(
                    Locale.US,
                    "the 5-day outlook leans higher (%s–%s)",
                    Fmt.money(outlook.expectedLow), Fmt.money(outlook.expectedHigh)
                )
            }
            if (trustedBull.isNotEmpty()) {
                why += "${trustedBull.size} trusted technique" +
                    (if (trustedBull.size == 1) "" else "s") +
                    " with measured edge on this stock side" +
                    (if (trustedBull.size == 1) "s" else "") + " bullish"
            }
            plPct?.let {
                why += String.format(Locale.US, "position stands %+.1f%% vs cost", it)
            }
            return BoardAction.ADD to why
        }

        why += "no exit signal and " + boardLine!!
        advice.stopLoss?.let { why += "stop to keep: ${Fmt.money(it)}" }
        advice.targetPrice?.let { why += "target in force: ${Fmt.money(it)}" }
        return BoardAction.HOLD to why
    }

    fun actionWord(action: BoardAction): String = when (action) {
        BoardAction.EXIT -> "Exit"
        BoardAction.TRIM -> "Trim"
        BoardAction.WATCH -> "Watch"
        BoardAction.ADD -> "Add to"
        BoardAction.HOLD -> "Hold"
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
