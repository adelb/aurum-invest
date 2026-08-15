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
    val newsNote: String,          // "" when nothing headline-worthy
    /** Latest session move %, from the live quote (or the last two daily closes); null when not measurable. */
    val sessionMovePct: Double? = null
)

/** A holding the engine could NOT measure this run — named, with the reason, never guessed around. */
data class UnverifiedHolding(
    val symbol: String,
    val shares: Double,
    val reason: String
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
    val caveat: String,
    /** Holdings excluded from this run because they could not be measured. */
    val unverified: List<UnverifiedHolding> = emptyList(),
    /** The whole-market regime line from the pulse engine; "" when unavailable. */
    val marketNote: String = ""
) {
    companion object {
        fun toJson(r: PortfolioReview): String = JSONObject().apply {
            put("computedAt", r.computedAt)
            put("totalValue", r.totalValue)
            put("headline", r.headline)
            put("cashPct", r.suggestedCashPct)
            put("caveat", r.caveat)
            put("marketNote", r.marketNote)
            put("sectorNotes", JSONArray(r.sectorNotes))
            put("unverified", JSONArray().apply {
                r.unverified.forEach { u ->
                    put(JSONObject().apply {
                        put("symbol", u.symbol); put("shares", u.shares); put("reason", u.reason)
                    })
                }
            })
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
                        v.sessionMovePct?.let { put("session", it) }
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
                            newsNote = v.optString("newsNote", ""),
                            sessionMovePct =
                                if (v.has("session")) v.optDouble("session") else null
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
            val unverified = ArrayList<UnverifiedHolding>()
            o.optJSONArray("unverified")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val u = arr.optJSONObject(i) ?: continue
                    unverified.add(
                        UnverifiedHolding(
                            symbol = u.getString("symbol"),
                            shares = u.optDouble("shares", 0.0),
                            reason = u.optString("reason", "")
                        )
                    )
                }
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
                caveat = o.optString("caveat", ""),
                unverified = unverified,
                marketNote = o.optString("marketNote", "")
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
 *    RSI, session move, relative strength, sector money flow, weight of
 *    book) — never a mood
 *  - the stop and target on every holding are computed from that stock's own
 *    support structure and ATR, not from round numbers
 *  - a holding that cannot be measured is reported by name with the reason in
 *    [PortfolioReview.unverified] — it never silences the holdings that can,
 *    and nothing about it is guessed
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

        /** The 35-technique board's own minimum history. */
        const val MIN_SESSIONS = 30

        private const val DEEP_CHUNK = 4
    }

    /** One holding's deep read: a verdict, or the measured reason there is none. */
    private sealed interface Judged {
        data class Ok(val verdict: HoldingVerdict) : Judged
        data class Failed(val symbol: String, val shares: Double, val reason: String) : Judged
    }

    /**
     * [views] the open positions with live quotes; [sectors] Yahoo sector per
     * symbol; [flow] the sector money-flow report (for rebalance direction and
     * per-holding flow context); [strategy] this week's sector-gap answer (for
     * concrete buy candidates); [pulse] the whole-market rating (for the
     * regime note); [unpriced] ledger positions the caller could not price —
     * they are reported by name, never silently dropped.
     *
     * Verdicts are produced for every holding that CAN be measured; holdings
     * that cannot are listed in [PortfolioReview.unverified] with the reason.
     * Null only when not a single holding could be verified.
     */
    suspend fun review(
        views: List<PositionView>,
        sectors: Map<String, String>,
        flow: MoneyFlowReport?,
        strategy: WeeklyStrategy?,
        pulse: MarketRating? = null,
        unpriced: List<UnverifiedHolding> = emptyList()
    ): PortfolioReview? {
        val open = views.filter { it.marketValue > 0.0 && it.position.shares > 0.0 }
        if (open.isEmpty()) return null
        val book = PortfolioLens.build(open, sectors)
        if (book.isEmpty) return null

        // 1 — deep read of every holding, chunked. A holding that cannot be
        // measured becomes a named unverified entry — it never silences the rest.
        val verdicts = ArrayList<HoldingVerdict>()
        val unverified = ArrayList(unpriced)
        for (chunk in open.chunked(DEEP_CHUNK)) {
            val results = coroutineScope {
                chunk.map { v ->
                    async {
                        judge(v, book, sectors[v.position.symbol] ?: PortfolioLens.UNCLASSIFIED, flow)
                    }
                }.awaitAll()
            }
            results.forEach { judged ->
                when (judged) {
                    is Judged.Ok -> verdicts.add(judged.verdict)
                    is Judged.Failed -> unverified.add(
                        UnverifiedHolding(judged.symbol, judged.shares, judged.reason)
                    )
                }
            }
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
            actions == 0 && unverified.isEmpty() ->
                "Every holding earns its place this week — nothing to sell."
            actions == 0 -> "Every verified holding earns its place this week."
            actions == 1 -> "One holding needs action this week; the rest hold."
            else -> "$actions of ${verdicts.size} holdings need action this week."
        }

        val marketNote = pulse?.let { p ->
            val regime = when (p.call) {
                MarketCall.INVEST -> "a tape that supports new money"
                MarketCall.SELECTIVE -> "a selective tape — add only to the strongest setups"
                MarketCall.DEFENSIVE -> "a defensive tape — protect first, add later"
            }
            String.format(
                Locale.US,
                "Market pulse %d/100 (%s): %.0f%% of scanned names above their 50-day average%s — %s.",
                p.score, p.call.name.lowercase(Locale.US), p.breadthAbove50Pct,
                p.vix?.let { String.format(Locale.US, ", VIX %.1f", it) } ?: "",
                regime
            )
        } ?: ""

        return PortfolioReview(
            computedAt = System.currentTimeMillis(),
            totalValue = round2(book.totalValue),
            headline = headline,
            verdicts = verdicts,
            allocation = allocation,
            suggestedCashPct = round1(suggestedCashPct),
            sectorNotes = sectorNotes,
            rebalance = rebalance,
            caveat = "Every verdict is computed from the latest available market prices, the 35-technique board, " +
                "sector money flow, each stock's own support structure and ATR, and public headlines. " +
                "Decision support, not financial advice.",
            unverified = unverified,
            marketNote = marketNote
        )
    }

    // ------------------------------------------------------------ one holding

    private suspend fun judge(
        view: PositionView,
        book: BookContext,
        sector: String,
        flow: MoneyFlowReport?
    ): Judged {
        val symbol = view.position.symbol
        val shares = view.position.shares
        return try {
            val candles = try {
                market.getDailyCandles(symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < MIN_SESSIONS) {
                return Judged.Failed(
                    symbol, shares,
                    if (candles.isEmpty()) "no daily history could be fetched"
                    else "only ${candles.size} sessions of daily history — the technique board needs $MIN_SESSIONS"
                )
            }
            val price = view.quote?.price ?: candles.last().close
            if (price <= 0.0) return Judged.Failed(symbol, shares, "no verifiable price")
            val avgCost = view.position.avgCost
            val weight = book.heldWeights[symbol] ?: 0.0
            val marketValue = shares * price
            val unrealizedPl = shares * (price - avgCost)
            val plPct =
                if (view.position.investedCost > 1e-9) {
                    unrealizedPl / view.position.investedCost * 100.0
                } else 0.0

            val analysis = Techniques.analyze(symbol, candles)
                ?: return Judged.Failed(symbol, shares, "the 35-technique board could not be computed")
            if (analysis.results.size != Techniques.TECHNIQUE_COUNT) {
                return Judged.Failed(symbol, shares, "the technique board came back incomplete")
            }
            val closes = candles.map { it.close }
            val rsi = Indicators.rsi(closes)
                ?: return Judged.Failed(symbol, shares, "momentum (RSI) could not be measured")
            val atr = Indicators.atr(candles)
                ?: return Judged.Failed(symbol, shares, "volatility (ATR) could not be measured")
            // The 50-day average is context, not a gate — a younger listing
            // simply gets no vs-50-day read instead of no verdict at all.
            val sma50 = Indicators.sma(closes, 50)

            val direction = analysis.outlook.direction
            val confidence = analysis.outlook.confidence
            val bullish = analysis.outlook.bullishCount
            val total = analysis.results.size

            // ---- live-market context, all measured ----
            // Latest session move: live quote when its previous close is real,
            // else the last two daily closes.
            val sessionMovePct = view.quote
                ?.takeIf { it.prevClose > 0.0 && it.prevClose != it.price }
                ?.let { (it.price / it.prevClose - 1.0) * 100.0 }
                ?: candles.takeLast(2)
                    .takeIf { it.size == 2 && it[0].close > 0.0 }
                    ?.let { (it[1].close / it[0].close - 1.0) * 100.0 }
            // 20-day return and relative strength vs the S&P 500 (only when the
            // flow report carries a measured SPY baseline).
            val r20 =
                if (closes.size >= 21 && closes[closes.size - 21] > 0.0) {
                    (price / closes[closes.size - 21] - 1.0) * 100.0
                } else null
            val rel20 = if (r20 != null && flow != null) r20 - flow.spyR20Pct else null
            // The holding's own sector money flow, mapped honestly.
            val (sectorFlow, flowNote) = flowContext(symbol, sector, flow)

            // Forward levels from the stock's own structure, not round numbers.
            val structural = analysis.srData.supports.filter { it < price }.maxOrNull()
            val stop = round2(
                max(
                    structural?.let { min(it - 0.5 * atr, price - 1.5 * atr) }
                        ?: (price - 2.0 * atr),
                    price * 0.85
                )
            )
            val target = round2(
                max(analysis.outlook.expectedHigh, price + 1.2 * atr)
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
            val flowLeaving = sectorFlow != null &&
                sectorFlow.verdict == FlowVerdict.OUTFLOW && sectorFlow.confidence >= 75
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
                flowLeaving && direction != TechniqueVerdict.BULLISH &&
                    plPct < 0.0 && (rel20 ?: 0.0) < 0.0 -> {
                    val f = sectorFlow!!
                    action = HoldingAction.TRIM
                    headline = "Trim — money is leaving ${f.label} " +
                        "(flow ${f.flowScore}/100) and this name lags the market."
                    whenText = "Reduce into the next bounce this week; revisit when the " +
                        "sector's flow turns neutral or the board turns bullish."
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
                        "${Fmt.money(target)} (take profit); the review re-runs the board live."
                }
            }

            val whyPoints = buildList {
                add(
                    String.format(
                        Locale.US,
                        "P/L %+.1f%% (%s) on an average cost of %s; latest price %s.",
                        plPct, Fmt.signedMoney(unrealizedPl), Fmt.money(avgCost), Fmt.money(price)
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
                } else {
                    add("50-day average not yet measurable — only ${closes.size} sessions listed.")
                }
                if (rel20 != null && r20 != null) {
                    add(
                        String.format(
                            Locale.US,
                            "20-day move %+.1f%% vs the S&P 500's %+.1f%% — %+.1fpp relative.",
                            r20, flow!!.spyR20Pct, rel20
                        )
                    )
                }
                if (flowNote.isNotEmpty()) add(flowNote)
                add(String.format(Locale.US, "%.0f%% of the invested book.", weight))
                if (newsScore != 0) add("News tone ${if (newsScore > 0) "+" else ""}$newsScore over 5 days.")
            }

            Judged.Ok(
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
                    marketValue = round2(marketValue),
                    weightPct = round1(weight),
                    unrealizedPl = round2(unrealizedPl),
                    unrealizedPlPct = round1(plPct),
                    target = target,
                    stop = stop,
                    techBullish = bullish,
                    techTotal = total,
                    techConfidence = confidence,
                    techDirection = direction,
                    rsi = round1(rsi),
                    newsScore = newsScore,
                    newsNote = newsNote,
                    sessionMovePct = sessionMovePct?.let { round1(it) }
                )
            )
        } catch (_: Exception) {
            Judged.Failed(symbol, shares, "the read failed mid-computation — pull to retry")
        }
    }

    /**
     * The holding's own sector money flow, mapped honestly: exact watchlist or
     * catalog membership first; else the Yahoo sector's theme(s). When several
     * themes map to the sector and their verdicts disagree, no single flow is
     * claimed — the disagreement itself is reported.
     */
    private fun flowContext(
        symbol: String,
        sector: String,
        flow: MoneyFlowReport?
    ): Pair<SectorFlow?, String> {
        if (flow == null || flow.sectors.isEmpty()) return null to ""
        val exactKey = SectorTrends.WATCH.entries
            .firstOrNull { (_, members) -> members.any { it.first == symbol } }?.key
            ?: StockCatalog.SYMBOL_THEME[symbol]?.first
        val keys = exactKey?.let { listOf(it) }
            ?: PortfolioLens.themesForSector(sector)
        val flows = keys.mapNotNull { k -> flow.sectors.firstOrNull { it.key == k } }
        return when {
            flows.isEmpty() -> null to ""
            flows.size == 1 || flows.all { it.verdict == flows.first().verdict } -> {
                val f = flows.maxBy { it.flowScore }
                f to "Money flow: ${f.label} ${f.verdict.name.lowercase(Locale.US)} " +
                    "${f.flowScore}/100 (${f.confidence}% signal agreement)."
            }
            else -> {
                val parts = flows.joinToString(", ") {
                    "${it.label} ${it.verdict.name.lowercase(Locale.US)} ${it.flowScore}/100"
                }
                null to "Money flow within $sector is mixed: $parts."
            }
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
