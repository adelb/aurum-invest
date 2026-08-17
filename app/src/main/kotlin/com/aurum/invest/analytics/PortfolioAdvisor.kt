package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.PositionView
import com.aurum.invest.data.repo.InvestorProfile
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** A holding the engine could NOT measure this run — named, with the reason, never guessed around. */
data class UnverifiedHolding(
    val symbol: String,
    val shares: Double,
    val reason: String
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
    val sectorNotes: List<String>,        // over/under-weight observations, measured
    val rebalance: List<RebalanceMove>,
    val caveat: String,
    /** Holdings excluded from this run because they could not be measured. */
    val unverified: List<UnverifiedHolding> = emptyList(),
    /** The whole-market regime line from the pulse engine; "" when unavailable. */
    val marketNote: String = "",
    /** The book graded 0-100 against the published rules of elite investors. */
    val grade: PortfolioGrade? = null,
    /**
     * Which investor policy shaped these verdicts (C1 traceability) — the
     * caps, loss rule, and profit rule all derive from it, and a review made
     * under default policy says so.
     */
    val policyNote: String = "",
    /** The standalone allocation engine's whole-book answer; null when nothing could be sized. */
    val allocationPlan: AllocationPlan? = null,
    /** The account line the verdicts were sized against. */
    val equityNote: String = "",
    /** Total risk-to-stop across the book as a share of equity; null when unmeasurable. */
    val openRiskPct: Double? = null,
    /** Inputs the verdict engine could not measure this run, named. */
    val verdictNotes: List<String> = emptyList()
) {
    companion object {
        fun toJson(r: PortfolioReview): String = JSONObject().apply {
            put("computedAt", r.computedAt)
            put("totalValue", r.totalValue)
            put("headline", r.headline)
            put("caveat", r.caveat)
            put("marketNote", r.marketNote)
            put("policyNote", r.policyNote)
            put("equityNote", r.equityNote)
            r.openRiskPct?.let { put("openRiskPct", it) }
            r.grade?.let { put("grade", PortfolioGrade.toJson(it)) }
            r.allocationPlan?.let { put("allocationPlan", AllocationPlanJson.toJson(it)) }
            put("sectorNotes", JSONArray(r.sectorNotes))
            put("verdictNotes", JSONArray(r.verdictNotes))
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
                        v.above50?.let { put("above50", it) }
                        v.rel20Pct?.let { put("rel20", it) }
                        if (v.flowVerdictName.isNotEmpty()) put("flowVerdict", v.flowVerdictName)
                        put("conv", v.conviction); put("convMax", v.convictionMax)
                        put("risk", v.riskScore); put("riskMax", v.riskScoreMax)
                        put("stage", v.stage.name)
                        v.trailStop?.let { put("trail", it) }
                        v.lockedGainPct?.let { put("locked", it) }
                        v.runwayPct?.let { put("runway", it) }
                        v.riskReward?.let { put("rr", it) }
                        v.riskAtStop?.let { put("riskAtStop", it) }
                        v.riskAtStopEquityPct?.let { put("riskAtStopPct", it) }
                        if (v.ridingNote.isNotEmpty()) put("riding", v.ridingNote)
                        put("keep", v.keepSharePct)
                        if (v.notMeasured.isNotEmpty()) put("blind", JSONArray(v.notMeasured))
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
                    val blind = ArrayList<String>()
                    v.optJSONArray("blind")?.let { b ->
                        for (j in 0 until b.length()) blind.add(b.optString(j))
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
                                if (v.has("session")) v.optDouble("session") else null,
                            above50 =
                                if (v.has("above50")) v.optBoolean("above50") else null,
                            rel20Pct =
                                if (v.has("rel20")) v.optDouble("rel20") else null,
                            flowVerdictName = v.optString("flowVerdict", ""),
                            conviction = v.optInt("conv", 0),
                            convictionMax = v.optInt("convMax", 0),
                            riskScore = v.optInt("risk", 0),
                            riskScoreMax = v.optInt("riskMax", 0),
                            stage = runCatching { HoldingStage.valueOf(v.optString("stage")) }
                                .getOrDefault(HoldingStage.UNMEASURED),
                            trailStop = if (v.has("trail")) v.optDouble("trail") else null,
                            lockedGainPct = if (v.has("locked")) v.optDouble("locked") else null,
                            runwayPct = if (v.has("runway")) v.optDouble("runway") else null,
                            riskReward = if (v.has("rr")) v.optDouble("rr") else null,
                            riskAtStop = if (v.has("riskAtStop")) v.optDouble("riskAtStop") else null,
                            riskAtStopEquityPct =
                                if (v.has("riskAtStopPct")) v.optDouble("riskAtStopPct") else null,
                            ridingNote = v.optString("riding", ""),
                            keepSharePct = v.optDouble("keep", 100.0),
                            notMeasured = blind
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
            val verdictNotes = ArrayList<String>()
            o.optJSONArray("verdictNotes")?.let { arr ->
                for (i in 0 until arr.length()) verdictNotes.add(arr.optString(i))
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
                sectorNotes = sectorNotes,
                rebalance = rebalance,
                caveat = o.optString("caveat", ""),
                unverified = unverified,
                marketNote = o.optString("marketNote", ""),
                grade = o.optJSONObject("grade")?.let { PortfolioGrade.fromJson(it) },
                policyNote = o.optString("policyNote", ""),
                allocationPlan = o.optJSONObject("allocationPlan")
                    ?.let { AllocationPlanJson.fromJson(it) },
                equityNote = o.optString("equityNote", ""),
                openRiskPct = if (o.has("openRiskPct")) o.optDouble("openRiskPct") else null,
                verdictNotes = verdictNotes
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The portfolio review's data-gathering layer.
 *
 * This class does the I/O — candles, the 35-technique board, headlines, the
 * S&P 500 baseline — and turns each holding into a [HoldingEvidence] bundle in
 * which every field is either MEASURED or explicitly null. The reasoning then
 * belongs to three standalone, pure engines that never touch the network:
 *
 *   [PortfolioVerdictEngine]  — what to do with each holding, and how to ride
 *                               the winners
 *   [AllocationPlanEngine]    — what the whole book should be worth, and where
 *                               the freed money goes
 *   [PortfolioGradeEngine]    — how the book scores against the published rules
 *                               of elite investors
 *
 * Keeping the gathering here and the judgement there is what makes the
 * verdicts testable without a network and impossible to quietly fill in with
 * defaults: a field this class cannot measure arrives at the engines as null.
 *
 * Integrity rules:
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
    private val news: NewsRepository,
    /**
     * The investor's own policy (C1): every concentration cap, trim target,
     * loss rule, and profit rule below derives from it — the same book must
     * NOT produce the same orders for a conservative long-horizon investor
     * and an aggressive short-horizon trader. Defaults are labeled defaults.
     */
    private val policy: InvestorProfile = InvestorProfile.DEFAULT
) {

    companion object {
        /** The 35-technique board's own minimum history. */
        const val MIN_SESSIONS = 30

        private const val DEEP_CHUNK = 4

        // The one place the profile becomes thresholds — the advisor's
        // verdicts and the grade card's scoring must read identical caps.
        fun positionCapPct(policy: InvestorProfile): Double = policy.maxPositionPct
        fun positionTrimPct(policy: InvestorProfile): Double = policy.maxPositionPct + 8.0
        fun sectorOverweightPct(policy: InvestorProfile): Double = policy.maxSectorPct
        fun sectorTargetPct(policy: InvestorProfile): Double =
            (policy.maxSectorPct - 5.0).coerceAtLeast(10.0)
        fun cutLossPct(policy: InvestorProfile): Double = when (policy.riskTolerance) {
            InvestorProfile.TOL_CONSERVATIVE -> -6.0
            InvestorProfile.TOL_AGGRESSIVE -> -10.0
            else -> -8.0
        }
    }

    private val sectorOverweightPct: Double get() = sectorOverweightPct(policy)
    private val sectorTargetPct: Double get() = sectorTargetPct(policy)

    /** One holding's gathered evidence, or the measured reason there is none. */
    private sealed interface Gathered {
        data class Ok(val evidence: HoldingEvidence) : Gathered
        data class Failed(val symbol: String, val shares: Double, val reason: String) : Gathered
    }

    /**
     * [views] the open positions with live quotes; [sectors] Yahoo sector per
     * symbol; [flow] the sector money-flow report; [strategy] this week's
     * sector-gap answer (for concrete buy candidates); [pulse] the whole-market
     * rating; [unpriced] ledger positions the caller could not price — they are
     * reported by name, never silently dropped.
     *
     * [equity] is the money behind the book (invested, live value, uninvested
     * cash). [priorStops] carries the stop each holding was given last run, so
     * the trailing stop can ratchet instead of drifting. [candidates] and
     * [sectorTrends] feed the allocation plan's market scan.
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
        unpriced: List<UnverifiedHolding> = emptyList(),
        equity: EquityContext = EquityContext.UNKNOWN,
        priorStops: Map<String, Double> = emptyMap(),
        entryTs: Map<String, Long> = emptyMap(),
        sectorTrends: List<SectorTrend> = emptyList(),
        candidates: List<LiquidityCandidate> = emptyList()
    ): PortfolioReview? {
        val open = views.filter { it.marketValue > 0.0 && it.position.shares > 0.0 }
        if (open.isEmpty()) return null
        val book = PortfolioLens.build(open, sectors)
        if (book.isEmpty) return null

        // The S&P 500 baseline for relative strength, fetched once. Null when
        // unreachable — relative-strength bands are then simply not measured.
        val spy = try {
            market.getDailyCandles("SPY", 140)
        } catch (_: Exception) {
            emptyList()
        }
        val spyR20 = windowReturnPct(spy.map { it.close }, 20) ?: flow?.spyR20Pct
        val spyR60 = windowReturnPct(spy.map { it.close }, 60)

        // 1 — gather every holding's evidence, chunked. A holding that cannot
        // be measured becomes a named unverified entry — it never silences the rest.
        val evidence = ArrayList<HoldingEvidence>()
        val unverified = ArrayList(unpriced)
        for (chunk in open.chunked(DEEP_CHUNK)) {
            val results = coroutineScope {
                chunk.map { v ->
                    async {
                        gather(
                            view = v,
                            book = book,
                            sector = sectors[v.position.symbol] ?: PortfolioLens.UNCLASSIFIED,
                            flow = flow,
                            spyR20 = spyR20,
                            spyR60 = spyR60,
                            priorStop = priorStops[v.position.symbol],
                            entryTs = entryTs[v.position.symbol]
                        )
                    }
                }.awaitAll()
            }
            results.forEach { g ->
                when (g) {
                    is Gathered.Ok -> evidence.add(g.evidence)
                    is Gathered.Failed -> unverified.add(
                        UnverifiedHolding(g.symbol, g.shares, g.reason)
                    )
                }
            }
        }
        if (evidence.isEmpty()) return null

        // 2 — the standalone verdict engine decides. It is pure: everything it
        // knows arrived in the evidence bundles above.
        // Both sides of the equity read describe the SAME set of holdings — the
        // ones actually measured. Mixing a full-ledger cost basis with a
        // partial market value would misstate the open P/L on every card.
        val measuredEquity = equity.copy(
            invested = evidence.sumOf { it.investedCost },
            holdingsValue = evidence.sumOf { it.shares * it.price }
        )
        val verdictReport = PortfolioVerdictEngine.evaluate(
            evidence = evidence,
            equity = measuredEquity,
            policy = policy,
            pulse = pulse
        )
        val verdicts = verdictReport.verdicts
        if (verdicts.isEmpty()) return null

        // 3 — the standalone allocation engine sizes the whole book and scans
        // the market for what the plan frees up.
        val allocationPlan = AllocationPlanEngine.build(
            verdicts = verdicts,
            equity = measuredEquity,
            moneyFlow = flow,
            sectorTrends = sectorTrends,
            candidates = candidates,
            profile = policy,
            pulse = pulse,
            marketNote = pulse?.headline ?: ""
        )

        // 4 — sector observations, measured from the same book math as everywhere.
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
                    slice.weightPct >= sectorOverweightPct -> add(
                        String.format(
                            Locale.US,
                            "%s is overweight at %.0f%% of the book — above your %.0f%% concentration line.",
                            slice.sector, slice.weightPct, sectorOverweightPct
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

        // 5 — rebalancing: overweight sectors fund the strongest inflowing
        // sector the book is light in — with a concrete, board-approved name.
        val rebalance = buildRebalance(book, verdicts, flow, strategy)

        val marketNote = pulse?.let { p ->
            val regime = when (p.call) {
                MarketCall.INVEST -> "a tape that supports new money"
                MarketCall.SELECTIVE -> "a selective tape — add only to the strongest setups"
                MarketCall.DEFENSIVE -> "a defensive tape — protect first, add later"
                MarketCall.INCOMPLETE -> "an unmeasured tape — the pulse could not verify enough inputs"
            }
            // Score and breadth print only when they were measured — an
            // INCOMPLETE pulse (or an unmeasurable breadth pool) must not be
            // quoted as numbers.
            val scorePart = p.score?.let {
                String.format(Locale.US, "Market pulse %d/100 (%s)", it, p.call.name.lowercase(Locale.US))
            } ?: "Market pulse: no call (${p.call.name.lowercase(Locale.US)})"
            val breadthPart = p.breadthAbove50Pct?.let {
                String.format(Locale.US, ": %.0f%% of scanned names above their 50-day average", it)
            } ?: ""
            scorePart + breadthPart +
                (p.vix?.let { String.format(Locale.US, ", VIX %.1f", it) } ?: "") +
                " — $regime."
        } ?: ""

        return PortfolioReview(
            computedAt = verdictReport.computedAt,
            totalValue = round2(book.totalValue),
            // The verdict engine only sees what could be measured; the count of
            // what could not is the advisor's to disclose.
            headline = verdictReport.headline + if (unverified.isEmpty()) "" else String.format(
                Locale.US, " %d more could not be measured this run.", unverified.size
            ),
            verdicts = verdicts,
            sectorNotes = sectorNotes,
            rebalance = rebalance,
            caveat = verdictReport.caveat,
            unverified = unverified,
            marketNote = marketNote,
            grade = PortfolioGradeEngine.evaluate(
                verdicts, book, flow, pulse, strategy, policy, measuredEquity
            ),
            policyNote = policy.label(),
            allocationPlan = allocationPlan,
            equityNote = verdictReport.equityNote,
            openRiskPct = verdictReport.openRiskPct,
            verdictNotes = verdictReport.notes
        )
    }

    // ------------------------------------------------- one holding's evidence

    private suspend fun gather(
        view: PositionView,
        book: BookContext,
        sector: String,
        flow: MoneyFlowReport?,
        spyR20: Double?,
        spyR60: Double?,
        priorStop: Double?,
        entryTs: Long?
    ): Gathered {
        val symbol = view.position.symbol
        val shares = view.position.shares
        return try {
            val candles = try {
                market.getDailyCandles(symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < MIN_SESSIONS) {
                return Gathered.Failed(
                    symbol, shares,
                    if (candles.isEmpty()) "no daily history could be fetched"
                    else "only ${candles.size} sessions of daily history — the technique board needs $MIN_SESSIONS"
                )
            }
            val price = view.quote?.price ?: candles.last().close
            if (price <= 0.0) return Gathered.Failed(symbol, shares, "no verifiable price")

            val analysis = Techniques.analyze(symbol, candles)
                ?: return Gathered.Failed(symbol, shares, "the 35-technique board could not be computed")
            if (analysis.results.size != Techniques.TECHNIQUE_COUNT) {
                return Gathered.Failed(symbol, shares, "the technique board came back incomplete")
            }

            val closes = candles.map { it.close }
            // Averages are context, not gates — a younger listing simply gets
            // fewer measured bands instead of no verdict at all.
            val sma20 = Indicators.sma(closes, 20)
            val sma50 = Indicators.sma(closes, 50)
            val sma200 = Indicators.sma(closes, 200)
            // Is the 50-day itself rising? Compare it with where it stood ten
            // sessions ago; null when there is not enough history for both.
            val sma50Rising = if (closes.size >= 60) {
                val then = Indicators.sma(closes.dropLast(10), 50)
                if (then != null && sma50 != null) sma50 > then else null
            } else null

            // The peak the trail hangs from: since the position was opened when
            // the ledger gives an entry stamp, else the visible window — and the
            // difference is carried into the evidence, never blurred.
            val fromEntry = entryTs?.let { ts -> candles.filter { it.ts >= ts } }
                ?.takeIf { it.isNotEmpty() }
            val peak = (fromEntry ?: candles.takeLast(120)).maxOfOrNull { it.close }

            val newsResult = runCatching { news.getNews(symbol, candles) }
            val newsItems = newsResult.getOrDefault(emptyList())
            val newsScore = newsItems.sumOf { it.sentiment }.coerceIn(-3, 3)
            val newsNote = newsItems.firstOrNull { it.sentiment != 0 }
                ?.let { "${it.title} — ${it.source}" } ?: ""

            val (sectorFlow, flowNote) = MoneyFlowEngine.flowFor(symbol, sector, flow)

            val r20 = windowReturnPct(closes, 20, price)
            val r60 = windowReturnPct(closes, 60, price)

            Gathered.Ok(
                HoldingEvidence(
                    symbol = symbol,
                    name = view.quote?.shortName?.ifBlank { symbol } ?: symbol,
                    sector = sector,
                    shares = shares,
                    avgCost = view.position.avgCost,
                    investedCost = view.position.investedCost,
                    price = price,
                    weightPct = book.heldWeights[symbol] ?: 0.0,
                    atr = Indicators.atr(candles),
                    rsi = Indicators.rsi(closes),
                    sma20 = sma20,
                    sma50 = sma50,
                    sma200 = sma200,
                    sma50Rising = sma50Rising,
                    peakSinceEntry = peak,
                    peakMeasuredFromEntry = fromEntry != null,
                    support = analysis.srData.supports.filter { it < price }.maxOrNull(),
                    resistance = analysis.srData.resistances.filter { it > price }.minOrNull(),
                    high52 = closes.takeLast(252).maxOrNull(),
                    donchianLow20 = candles.takeLast(20).minOfOrNull { it.low },
                    r5Pct = windowReturnPct(closes, 5, price),
                    r20Pct = r20,
                    r60Pct = r60,
                    rel20Pct = if (r20 != null && spyR20 != null) r20 - spyR20 else null,
                    rel60Pct = if (r60 != null && spyR60 != null) r60 - spyR60 else null,
                    sessionMovePct = sessionMovePct(view, candles),
                    volumeRatio = volumeRatio(candles),
                    upDayVolumeSharePct = upDayVolumeSharePct(candles),
                    distributionDays = distributionDays(candles),
                    techDirection = analysis.outlook.direction,
                    techBullish = analysis.outlook.bullishCount,
                    techTotal = analysis.results.size,
                    techConfidence = analysis.outlook.confidence,
                    expectedHigh = analysis.outlook.expectedHigh.takeIf { it > 0.0 },
                    newsScore = newsScore,
                    newsNote = newsNote,
                    // A failed feed and a verified-quiet feed must not read the same.
                    newsMeasured = newsResult.isSuccess,
                    sectorFlow = sectorFlow,
                    flowNote = flowNote,
                    priorStop = priorStop?.takeIf { it > 0.0 }
                )
            )
        } catch (_: Exception) {
            Gathered.Failed(symbol, shares, "the read failed mid-computation — pull to retry")
        }
    }

    // ------------------------------------------------------ measured helpers

    /** Return over [sessions] completed sessions, in percent; null without the history. */
    private fun windowReturnPct(
        closes: List<Double>,
        sessions: Int,
        latest: Double? = null
    ): Double? {
        if (closes.size < sessions + 1) return null
        val base = closes[closes.size - 1 - sessions]
        if (base <= 0.0) return null
        val end = latest ?: closes.last()
        return (end / base - 1.0) * 100.0
    }

    /** Latest session move: the live quote when its previous close is real, else the last two closes. */
    private fun sessionMovePct(view: PositionView, candles: List<Candle>): Double? =
        view.quote
            ?.takeIf { it.prevClose > 0.0 && it.prevClose != it.price }
            ?.let { (it.price / it.prevClose - 1.0) * 100.0}
            ?: candles.takeLast(2)
                .takeIf { it.size == 2 && it[0].close > 0.0 }
                ?.let { (it[1].close / it[0].close - 1.0) * 100.0 }

    /** Latest completed session's volume against its own 20-day average; null without both. */
    private fun volumeRatio(candles: List<Candle>): Double? {
        if (candles.size < 21) return null
        val recent = candles.takeLast(21)
        val avg = recent.dropLast(1).map { it.volume.toDouble() }.average()
        if (avg <= 0.0) return null
        return recent.last().volume.toDouble() / avg
    }

    /**
     * Share of the last 20 sessions' volume that traded on up days — the
     * cleanest read of whether buyers or sellers are the urgent side.
     */
    private fun upDayVolumeSharePct(candles: List<Candle>): Double? {
        if (candles.size < 21) return null
        val window = candles.takeLast(21)
        var up = 0.0
        var total = 0.0
        for (i in 1 until window.size) {
            val v = window[i].volume.toDouble()
            if (v <= 0.0) continue
            total += v
            if (window[i].close > window[i - 1].close) up += v
        }
        if (total <= 0.0) return null
        return up / total * 100.0
    }

    /**
     * Down sessions of more than 0.2% on above-average volume in the last 25 —
     * the classic footprint of institutions distributing stock.
     */
    private fun distributionDays(candles: List<Candle>): Int? {
        if (candles.size < 46) return null
        val window = candles.takeLast(26)
        var count = 0
        for (i in 1 until window.size) {
            val prior = candles.subList(
                (candles.size - 26 + i - 20).coerceAtLeast(0),
                candles.size - 26 + i
            )
            val avgVol = prior.map { it.volume.toDouble() }.filter { it > 0.0 }.average()
            if (avgVol.isNaN() || avgVol <= 0.0) continue
            val prev = window[i - 1].close
            if (prev <= 0.0) continue
            if (window[i].close <= prev * 0.998 && window[i].volume.toDouble() > avgVol) count++
        }
        return count
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
            it.sector != PortfolioLens.UNCLASSIFIED && it.weightPct >= sectorOverweightPct
        }
        if (overweight.isEmpty()) return moves

        // Buy side: the strongest inflowing themes the book is light in, each
        // with a board-approved pick from the sector-gap engine. No pick — no
        // buy claim. Kept as a QUEUE: each overweight sector consumes its own
        // candidate, so two sectors never both propose buying the same name
        // (which could stack past the position cap if executed as written).
        val buyQueue: MutableList<Triple<SectorPick, String, SectorFlow>> = flow?.inflows
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
            ?.toMutableList() ?: mutableListOf()
        val proposedBuys = HashSet<String>()

        for (slice in overweight) {
            val excess = (slice.weightPct - sectorTargetPct) / 100.0 * book.totalValue
            if (excess < book.totalValue * 0.02) continue
            // Sell the weakest holding in the sector: worst action first, then
            // the lowest board confidence.
            // Bucket spacing 1000 vs a 0..100 tie-break: the action class must
            // always outrank confidence, or a 0%-confidence HOLD would tie a
            // 100%-confidence TRIM.
            val weakest = verdicts
                .filter { it.sector == slice.sector }
                .maxByOrNull { v ->
                    when (v.action) {
                        HoldingAction.CUT_LOSS -> 4000
                        HoldingAction.SELL -> 3000
                        HoldingAction.TAKE_PROFIT -> 2000
                        HoldingAction.TRIM -> 1000
                        HoldingAction.HOLD -> 0
                    } + (100 - v.techConfidence)
                } ?: continue

            val sellReason = String.format(
                Locale.US,
                "%s is %.0f%% of the book against a %.0f%% ceiling; %s is its weakest name " +
                    "(%d of %d techniques bullish, %+.1f%% P/L).",
                slice.sector, slice.weightPct, sectorOverweightPct,
                weakest.symbol, weakest.techBullish, weakest.techTotal, weakest.unrealizedPlPct
            )
            val buyCandidate = buyQueue.firstOrNull { (pick, _, _) ->
                pick.symbol !in proposedBuys
            }
            if (buyCandidate != null) {
                val (pick, themeLabel, inflow) = buyCandidate
                proposedBuys.add(pick.symbol)
                buyQueue.remove(buyCandidate)
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

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
