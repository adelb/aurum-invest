package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** What to do with one holding. */
enum class HoldingAction { HOLD, TAKE_PROFIT, TRIM, SELL, CUT_LOSS }

/** One holding's full verdict: the action, the why, and the when. */
data class HoldingVerdict(
    val symbol: String,
    val name: String,
    val sector: String,            // Yahoo sector, or "Unclassified"
    val action: HoldingAction,
    val headline: String,          // one sentence: what to do
    val whenText: String,          // when to do it, concretely
    val whyPoints: List<String>,   // measured reasons, numbers included
    val price: Double,
    val avgCost: Double,
    val marketValue: Double,
    val weightPct: Double,         // share of the invested book
    val unrealizedPl: Double,
    val unrealizedPlPct: Double,
    val target: Double,            // forward take-profit level
    val stop: Double,              // forward protective stop
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val techDirection: TechniqueVerdict,
    val rsi: Double,
    val newsScore: Int,
    val newsNote: String           // "" when nothing headline-worthy
)

/** One line of the allocation plan: where the position is vs where it should be. */
data class AllocationLine(
    val symbol: String,
    val currentPct: Double,
    val suggestedPct: Double,
    val note: String
)

/** One concrete rebalancing move: what to sell, what to buy instead, and why. */
data class RebalanceMove(
    val sellSymbol: String,
    val sellAmount: Double,        // dollars to move
    val sellReason: String,
    val buySymbol: String,         // "" when no candidate passed the board (hold cash)
    val buyName: String,
    val buySector: String,
    val buyReason: String
)

/** The whole portfolio review. */
data class PortfolioReview(
    val computedAt: Long,
    val totalValue: Double,
    val headline: String,
    val verdicts: List<HoldingVerdict>,   // by book weight, largest first
    val allocation: List<AllocationLine>,
    val suggestedCashPct: Double,         // what the allocation plan frees up
    val sectorNotes: List<String>,        // over/under-weight observations, measured
    val rebalance: List<RebalanceMove>,
    val caveat: String
) {
    companion object {
        fun toJson(r: PortfolioReview): String = JSONObject().apply {
            put("computedAt", r.computedAt)
            put("totalValue", r.totalValue)
            put("headline", r.headline)
            put("cashPct", r.suggestedCashPct)
            put("caveat", r.caveat)
            put("sectorNotes", JSONArray(r.sectorNotes))
            put("verdicts", JSONArray().apply {
                r.verdicts.forEach { v ->
                    put(JSONObject().apply {
                        put("symbol", v.symbol); put("name", v.name); put("sector", v.sector)
                        put("action", v.action.name); put("headline", v.headline)
                        put("when", v.whenText); put("why", JSONArray(v.whyPoints))
                        put("price", v.price); put("avgCost", v.avgCost)
                        put("value", v.marketValue); put("weight", v.weightPct)
                        put("pl", v.unrealizedPl); put("plPct", v.unrealizedPlPct)
                        put("target", v.target); put("stop", v.stop)
                        put("tb", v.techBullish); put("tt", v.techTotal)
                        put("conf", v.techConfidence); put("dir", v.techDirection.name)
                        put("rsi", v.rsi); put("news", v.newsScore); put("newsNote", v.newsNote)
                    })
                }
            })
            put("allocation", JSONArray().apply {
                r.allocation.forEach { a ->
                    put(JSONObject().apply {
                        put("symbol", a.symbol); put("cur", a.currentPct)
                        put("sug", a.suggestedPct); put("note", a.note)
                    })
                }
            })
            put("rebalance", JSONArray().apply {
                r.rebalance.forEach { m ->
                    put(JSONObject().apply {
                        put("sellSymbol", m.sellSymbol); put("sellAmount", m.sellAmount)
                        put("sellReason", m.sellReason); put("buySymbol", m.buySymbol)
                        put("buyName", m.buyName); put("buySector", m.buySector)
                        put("buyReason", m.buyReason)
                    })
                }
            })
        }.toString()

        fun fromJson(json: String): PortfolioReview? = try {
            val o = JSONObject(json)
            val verdicts = ArrayList<HoldingVerdict>()
            o.optJSONArray("verdicts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val why = ArrayList<String>()
                    v.optJSONArray("why")?.let { w ->
                        for (j in 0 until w.length()) why.add(w.optString(j))
                    }
                    verdicts.add(
                        HoldingVerdict(
                            symbol = v.getString("symbol"),
                            name = v.optString("name", ""),
                            sector = v.optString("sector", PortfolioLens.UNCLASSIFIED),
                            action = runCatching { HoldingAction.valueOf(v.optString("action")) }
                                .getOrDefault(HoldingAction.HOLD),
                            headline = v.optString("headline", ""),
                            whenText = v.optString("when", ""),
                            whyPoints = why,
                            price = v.optDouble("price", 0.0),
                            avgCost = v.optDouble("avgCost", 0.0),
                            marketValue = v.optDouble("value", 0.0),
                            weightPct = v.optDouble("weight", 0.0),
                            unrealizedPl = v.optDouble("pl", 0.0),
                            unrealizedPlPct = v.optDouble("plPct", 0.0),
                            target = v.optDouble("target", 0.0),
                            stop = v.optDouble("stop", 0.0),
                            techBullish = v.optInt("tb", 0),
                            techTotal = v.optInt("tt", 0),
                            techConfidence = v.optInt("conf", 0),
                            techDirection = runCatching {
                                TechniqueVerdict.valueOf(v.optString("dir"))
                            }.getOrDefault(TechniqueVerdict.NEUTRAL),
                            rsi = v.optDouble("rsi", 50.0),
                            newsScore = v.optInt("news", 0),
                            newsNote = v.optString("newsNote", "")
                        )
                    )
                }
            }
            val allocation = ArrayList<AllocationLine>()
            o.optJSONArray("allocation")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    allocation.add(
                        AllocationLine(
                            symbol = a.getString("symbol"),
                            currentPct = a.optDouble("cur", 0.0),
                            suggestedPct = a.optDouble("sug", 0.0),
                            note = a.optString("note", "")
                        )
                    )
                }
            }
            val rebalance = ArrayList<RebalanceMove>()
            o.optJSONArray("rebalance")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    rebalance.add(
                        RebalanceMove(
                            sellSymbol = m.getString("sellSymbol"),
                            sellAmount = m.optDouble("sellAmount", 0.0),
                            sellReason = m.optString("sellReason", ""),
                            buySymbol = m.optString("buySymbol", ""),
                            buyName = m.optString("buyName", ""),
                            buySector = m.optString("buySector", ""),
                            buyReason = m.optString("buyReason", "")
                        )
                    )
                }
            }
            val sectorNotes = ArrayList<String>()
            o.optJSONArray("sectorNotes")?.let { arr ->
                for (i in 0 until arr.length()) sectorNotes.add(arr.optString(i))
            }
            PortfolioReview(
                computedAt = o.optLong("computedAt", 0L),
                totalValue = o.optDouble("totalValue", 0.0),
                headline = o.optString("headline", ""),
                verdicts = verdicts,
                allocation = allocation,
                suggestedCashPct = o.optDouble("cashPct", 0.0),
                sectorNotes = sectorNotes,
                rebalance = rebalance,
                caveat = o.optString("caveat", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The portfolio-evaluation engine: reads every open position through the
 * 35-technique board, the news, and the book's own sector weights, then
 * answers the four questions that matter — what to hold and why, what to
 * sell and when, where to cut the loss, and how the money should be
 * allocated across positions and sectors.
 *
 * Integrity rules:
 *  - every verdict cites the measured numbers behind it (P/L, board votes,
 *    RSI, weight of book) — never a mood
 *  - the stop and target on every holding are computed from that stock's own
 *    support structure and ATR, not from round numbers
 *  - a rebalance is proposed only when a sector measurably exceeds its
 *    concentration threshold AND a replacement candidate actually passes the
 *    technique board; otherwise the honest advice is cash
 *  - sector weights come from the same [PortfolioLens] math every other
 *    screen uses, so the numbers agree app-wide
 */
class PortfolioAdvisor(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        /** A sector above this share of the book is overweight. */
        const val SECTOR_OVERWEIGHT_PCT = 35.0

        /** Rebalancing sells an overweight sector back toward this share. */
        const val SECTOR_TARGET_PCT = 30.0

        /** A single position above this share of the book is concentrated. */
        const val POSITION_TRIM_PCT = 30.0

        /** Suggested ceiling for any single position after rebalancing. */
        const val POSITION_CAP_PCT = 22.0

        private const val DEEP_CHUNK = 4
    }

    /**
     * [views] the open positions with live quotes; [sectors] Yahoo sector per
     * symbol; [flow] the sector money-flow report (for rebalance direction);
     * [strategy] this week's sector-gap answer (for concrete buy candidates).
     * Null when the book is empty.
     */
    suspend fun review(
        views: List<PositionView>,
        sectors: Map<String, String>,
        flow: MoneyFlowReport?,
        strategy: WeeklyStrategy?
    ): PortfolioReview? {
        val open = views.filter { it.marketValue > 0.0 && it.position.shares > 0.0 }
        if (open.isEmpty()) return null
        val book = PortfolioLens.build(open, sectors)
        if (book.isEmpty) return null

        // 1 — deep read of every holding, chunked.
        val verdicts = ArrayList<HoldingVerdict>()
        for (chunk in open.chunked(DEEP_CHUNK)) {
            val results = coroutineScope {
                chunk.map { v ->
                    async { judge(v, book, sectors[v.position.symbol] ?: PortfolioLens.UNCLASSIFIED) }
                }.awaitAll()
            }
            results.filterNotNull().forEach { verdicts.add(it) }
        }
        if (verdicts.isEmpty()) return null
        verdicts.sortByDescending { it.marketValue }

        // 2 — the allocation plan: current vs suggested weight per holding.
        val allocation = verdicts.map { v ->
            val suggested = when (v.action) {
                HoldingAction.CUT_LOSS -> 0.0
                HoldingAction.SELL -> 0.0
                HoldingAction.TAKE_PROFIT -> min(v.weightPct * 0.5, POSITION_CAP_PCT)
                HoldingAction.TRIM -> min(v.weightPct, POSITION_CAP_PCT)
                HoldingAction.HOLD -> min(v.weightPct, POSITION_TRIM_PCT)
            }
            AllocationLine(
                symbol = v.symbol,
                currentPct = round1(v.weightPct),
                suggestedPct = round1(suggested),
                note = when (v.action) {
                    HoldingAction.CUT_LOSS -> "Exit — the loss rule fired."
                    HoldingAction.SELL -> "Exit — the board turned against it."
                    HoldingAction.TAKE_PROFIT -> "Bank half, trail the rest."
                    HoldingAction.TRIM -> "Reduce to the ${fmt0(POSITION_CAP_PCT)}% position cap."
                    HoldingAction.HOLD -> if (v.weightPct > POSITION_TRIM_PCT) {
                        "Healthy but oversized — no adds."
                    } else "Keep as is."
                }
            )
        }
        val suggestedCashPct =
            (100.0 - allocation.sumOf { it.suggestedPct }).coerceIn(0.0, 100.0)

        // 3 — sector observations, measured from the same book math as everywhere.
        val sectorNotes = buildList {
            book.slices.filter { it.sector != PortfolioLens.UNCLASSIFIED }.forEach { slice ->
                when {
                    slice.weightPct >= 50.0 -> add(
                        String.format(
                            Locale.US,
                            "%s holds %.0f%% of the book (%s) — a single-sector shock hits more than half your money.",
                            slice.sector, slice.weightPct, slice.symbols.take(3).joinToString(", ")
                        )
                    )
                    slice.weightPct >= SECTOR_OVERWEIGHT_PCT -> add(
                        String.format(
                            Locale.US,
                            "%s is overweight at %.0f%% of the book — above the %.0f%% concentration line.",
                            slice.sector, slice.weightPct, SECTOR_OVERWEIGHT_PCT
                        )
                    )
                    else -> Unit
                }
            }
            val unclassified = book.slices.firstOrNull { it.sector == PortfolioLens.UNCLASSIFIED }
            if (unclassified != null) {
                add(
                    String.format(
                        Locale.US,
                        "%.0f%% of the book (%s) has no sector data and is left out of the sector math.",
                        unclassified.weightPct, unclassified.symbols.joinToString(", ")
                    )
                )
            }
            flow?.inflows?.firstOrNull()?.let { inflow ->
                val covered = PortfolioLens.themeCoveragePct(inflow.key, book)
                when {
                    covered != null && covered <= 5.0 -> add(
                        String.format(
                            Locale.US,
                            "Money is flowing into %s (flow %d/100) and the book holds %.0f%% there.",
                            inflow.label, inflow.flowScore, covered
                        )
                    )
                    covered == null -> add(
                        "Money is flowing into ${inflow.label} (flow ${inflow.flowScore}/100) — " +
                            "a cross-sector theme; coverage counts only exact holdings."
                    )
                    else -> Unit
                }
            }
        }

        // 4 — rebalancing: overweight sectors fund the strongest inflowing
        // sector the book is light in — with a concrete, board-approved name.
        val rebalance = buildRebalance(book, verdicts, flow, strategy)

        val actions = verdicts.count { it.action != HoldingAction.HOLD }
        val headline = when {
            actions == 0 -> "Every holding earns its place this week — nothing to sell."
            actions == 1 -> "One holding needs action this week; the rest hold."
            else -> "$actions of ${verdicts.size} holdings need action this week."
        }

        return PortfolioReview(
            computedAt = System.currentTimeMillis(),
            totalValue = round2(book.totalValue),
            headline = headline,
            verdicts = verdicts,
            allocation = allocation,
            suggestedCashPct = round1(suggestedCashPct),
            sectorNotes = sectorNotes,
            rebalance = rebalance,
            caveat = "Every verdict is computed from live prices, the 35-technique board, " +
                "each stock's own support structure and ATR, and public headlines. " +
                "Decision support, not financial advice."
        )
    }

    // ------------------------------------------------------------ one holding

    private suspend fun judge(
        view: PositionView,
        book: BookContext,
        sector: String
    ): HoldingVerdict? {
        return try {
            val symbol = view.position.symbol
            val candles = try {
                market.getDailyCandles(symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            val price = view.quote?.price ?: candles.lastOrNull()?.close ?: return null
            if (price <= 0.0) return null
            val avgCost = view.position.avgCost
            val weight = book.heldWeights[symbol] ?: 0.0
            val plPct = view.unrealizedPlPct

            val analysis = if (candles.size >= 30) Techniques.analyze(symbol, candles) else null
            val closes = candles.map { it.close }
            val rsi = Indicators.rsi(closes) ?: 50.0
            val atr = Indicators.atr(candles) ?: (price * 0.02)
            val sma50 = Indicators.sma(closes, 50)

            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            val confidence = analysis?.outlook?.confidence ?: 0
            val bullish = analysis?.outlook?.bullishCount ?: 0
            val total = analysis?.results?.size ?: 0

            // Forward levels from the stock's own structure, not round numbers.
            val structural = analysis?.srData?.supports?.filter { it < price }?.maxOrNull()
            val stop = round2(
                max(
                    structural?.let { min(it - 0.5 * atr, price - 1.5 * atr) }
                        ?: (price - 2.0 * atr),
                    price * 0.85
                )
            )
            val target = round2(
                max(analysis?.outlook?.expectedHigh ?: (price + 2.0 * atr), price + 1.2 * atr)
            )

            val newsItems = try {
                news.getNews(symbol, candles)
            } catch (_: Exception) {
                emptyList()
            }
            val newsScore = newsItems.sumOf { it.sentiment }.coerceIn(-3, 3)
            val newsNote = newsItems.firstOrNull { it.sentiment != 0 }
                ?.let { "${it.title} — ${it.source}" } ?: ""

            // ---- the decision, most defensive rule first ----
            val below50 = sma50 != null && price < sma50
            val action: HoldingAction
            val headline: String
            val whenText: String
            when {
                plPct <= -8.0 && (direction == TechniqueVerdict.BEARISH || below50) -> {
                    action = HoldingAction.CUT_LOSS
                    headline = "Cut the loss — down ${fmt1(-plPct)}% with the tape against it."
                    whenText = "Sell at the next session's open. Capital comes first; " +
                        "re-entry is always available later."
                }
                direction == TechniqueVerdict.BEARISH && confidence >= 60 -> {
                    action = HoldingAction.SELL
                    headline = "Sell — the board reads bearish at $confidence% confidence."
                    whenText = "Sell into the next strength, or at the close of any day that " +
                        "ends below ${Fmt.money(stop)} — whichever comes first this week."
                }
                plPct >= 15.0 && (rsi >= 70.0 || direction != TechniqueVerdict.BULLISH) -> {
                    action = HoldingAction.TAKE_PROFIT
                    headline = "Take profit — up ${fmt1(plPct)}% and the move is stretched."
                    whenText = "Sell half now; trail the rest with a stop raised to " +
                        "${Fmt.money(round2(max(stop, avgCost)))} so the win cannot become a loss."
                }
                weight >= POSITION_TRIM_PCT -> {
                    action = HoldingAction.TRIM
                    headline = "Trim — ${fmt0(weight)}% of the book is riding on one name."
                    whenText = "Reduce toward ${fmt0(POSITION_CAP_PCT)}% of the book this week, " +
                        "selling into strength rather than weakness."
                }
                else -> {
                    action = HoldingAction.HOLD
                    headline = when {
                        direction == TechniqueVerdict.BULLISH ->
                            "Hold — the board backs it at $confidence% confidence."
                        plPct >= 0.0 -> "Hold — in profit with no exit signal on the board."
                        else -> "Hold — the loss is inside the stop and the board has not turned."
                    }
                    whenText = "Re-check on a close below ${Fmt.money(stop)} (exit) or at " +
                        "${Fmt.money(target)} (take profit); the weekly review re-runs the board."
                }
            }

            val whyPoints = buildList {
                add(
                    String.format(
                        Locale.US,
                        "P/L %+.1f%% (%s) on an average cost of %s; live price %s.",
                        plPct, Fmt.signedMoney(view.unrealizedPl), Fmt.money(avgCost), Fmt.money(price)
                    )
                )
                if (total > 0) {
                    add("$bullish of $total techniques bullish — the board reads " +
                        direction.name.lowercase(Locale.US) + " at $confidence% confidence.")
                }
                add(String.format(Locale.US, "RSI %.0f; 14-day ATR %s.", rsi, Fmt.money(atr)))
                if (sma50 != null) {
                    add(
                        String.format(
                            Locale.US, "Price %.1f%% %s the 50-day average %s.",
                            abs(price / sma50 - 1.0) * 100.0,
                            if (price >= sma50) "above" else "below", Fmt.money(sma50)
                        )
                    )
                }
                add(String.format(Locale.US, "%.0f%% of the invested book.", weight))
                if (newsScore != 0) add("News tone ${if (newsScore > 0) "+" else ""}$newsScore over 5 days.")
            }

            HoldingVerdict(
                symbol = symbol,
                name = view.quote?.shortName?.ifBlank { symbol } ?: symbol,
                sector = sector,
                action = action,
                headline = headline,
                whenText = whenText,
                whyPoints = whyPoints,
                price = round2(price),
                avgCost = round2(avgCost),
                marketValue = round2(view.marketValue),
                weightPct = round1(weight),
                unrealizedPl = round2(view.unrealizedPl),
                unrealizedPlPct = round1(plPct),
                target = target,
                stop = stop,
                techBullish = bullish,
                techTotal = total,
                techConfidence = confidence,
                techDirection = direction,
                rsi = round1(rsi),
                newsScore = newsScore,
                newsNote = newsNote
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ rebalance

    private fun buildRebalance(
        book: BookContext,
        verdicts: List<HoldingVerdict>,
        flow: MoneyFlowReport?,
        strategy: WeeklyStrategy?
    ): List<RebalanceMove> {
        val moves = ArrayList<RebalanceMove>()
        val overweight = book.slices.filter {
            it.sector != PortfolioLens.UNCLASSIFIED && it.weightPct >= SECTOR_OVERWEIGHT_PCT
        }
        if (overweight.isEmpty()) return moves

        // Buy side: the strongest inflowing theme the book is light in, with a
        // board-approved pick from the sector-gap engine. No pick — no buy claim.
        val buyCandidate: Triple<SectorPick, String, SectorFlow>? = flow?.inflows
            ?.asSequence()
            ?.mapNotNull { inflow ->
                val covered = PortfolioLens.themeCoveragePct(inflow.key, book)
                    ?: book.heldWeights.filterKeys { sym ->
                        SectorTrends.WATCH[inflow.key].orEmpty().any { it.first == sym }
                    }.values.sum()
                if (covered > 15.0) return@mapNotNull null
                val pick = strategy?.gaps
                    ?.firstOrNull { it.themeKey == inflow.key }
                    ?.picks?.firstOrNull()
                    ?: return@mapNotNull null
                Triple(pick, inflow.label, inflow)
            }
            ?.firstOrNull()

        for (slice in overweight) {
            val excess = (slice.weightPct - SECTOR_TARGET_PCT) / 100.0 * book.totalValue
            if (excess < book.totalValue * 0.02) continue
            // Sell the weakest holding in the sector: worst action first, then
            // the lowest board confidence.
            val weakest = verdicts
                .filter { it.sector == slice.sector }
                .maxByOrNull { v ->
                    when (v.action) {
                        HoldingAction.CUT_LOSS -> 400
                        HoldingAction.SELL -> 300
                        HoldingAction.TAKE_PROFIT -> 200
                        HoldingAction.TRIM -> 100
                        HoldingAction.HOLD -> 0
                    } + (100 - v.techConfidence)
                } ?: continue

            val sellReason = String.format(
                Locale.US,
                "%s is %.0f%% of the book against a %.0f%% ceiling; %s is its weakest name " +
                    "(%d of %d techniques bullish, %+.1f%% P/L).",
                slice.sector, slice.weightPct, SECTOR_OVERWEIGHT_PCT,
                weakest.symbol, weakest.techBullish, weakest.techTotal, weakest.unrealizedPlPct
            )
            if (buyCandidate != null) {
                val (pick, themeLabel, inflow) = buyCandidate
                moves.add(
                    RebalanceMove(
                        sellSymbol = weakest.symbol,
                        sellAmount = round2(min(excess, weakest.marketValue)),
                        sellReason = sellReason,
                        buySymbol = pick.symbol,
                        buyName = pick.name,
                        buySector = themeLabel,
                        buyReason = String.format(
                            Locale.US,
                            "%s flow %d/100 (%s) and the book is light there; %s passes the " +
                                "board — %s.",
                            themeLabel, inflow.flowScore, inflow.reason.substringBefore(","),
                            pick.symbol, pick.reason
                        )
                    )
                )
            } else {
                moves.add(
                    RebalanceMove(
                        sellSymbol = weakest.symbol,
                        sellAmount = round2(min(excess, weakest.marketValue)),
                        sellReason = sellReason,
                        buySymbol = "",
                        buyName = "",
                        buySector = "",
                        buyReason = "No replacement passes the technique board right now — " +
                            "hold the proceeds in cash until one does."
                    )
                )
            }
        }
        return moves
    }

    // ------------------------------------------------------------ helpers

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
