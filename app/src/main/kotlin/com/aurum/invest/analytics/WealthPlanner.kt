package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Wealth plan: the user names a base amount and a profit target for the
 * next 4 months; every week this engine re-reads the market and answers with
 * a concrete allocation — which stocks, how many dollars each, the entry,
 * the 4-month target, the stop, the expected profit, and when to buy and sell.
 *
 * Every number is derived from real analysis, honestly:
 *  - the 20-technique board votes on every candidate (bearish boards are out)
 *  - this week's trending sectors (via ETF momentum + news tone) boost their members
 *  - news sentiment and insider/institutional headlines adjust conviction
 *  - expected 4-month moves extrapolate observed 20/60-day momentum with decay,
 *    never a promise — the feasibility check says so out loud
 */

data class WealthAllocation(
    val symbol: String,
    val name: String,
    val sectorLabel: String,     // "" when the symbol maps to no tracked theme
    val amount: Double,
    val shares: Double,
    val entry: Double,
    val target: Double,
    val stop: Double,
    val expectedProfit: Double,
    val expectedPct: Double,
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val reason: String,
    val buyAdvice: String,
    val sellAdvice: String,
    val insiderNote: String,     // "" when no insider/institutional headline was found
    /** (target - entry) / (entry - stop); 0 when the stop math degenerates. */
    val rewardRisk: Double = 0.0,
    /** Dollars lost on this position if the stop hits. */
    val riskDollars: Double = 0.0,
    /** "" unless the user already holds this symbol; then says how much. */
    val heldNote: String = ""
)

data class WealthPlan(
    val weekStart: String,
    val updatedAt: Long,
    val baseAmount: Double,
    val targetProfit: Double,
    val horizonMonths: Int,
    val horizonEndIso: String,
    val requiredTotalPct: Double,
    val requiredMonthlyPct: Double,
    val feasibility: String,     // REALISTIC / AGGRESSIVE / UNREALISTIC
    val feasibilityNote: String,
    val sectorHeadline: String,
    val topSectors: List<SectorTrend>,
    val allocations: List<WealthAllocation>,
    val cashReserve: Double,
    val expectedProfitTotal: Double,
    val expectedPctTotal: Double,
    val gapNote: String,
    val weeklyActions: List<String>,
    val marketNotes: List<NewsItem>,
    val caveat: String,
    /** "" normally; set when the plan deliberately holds back (bearish tape, no positive expectations). */
    val planNote: String = "",
    /** Total dollars lost if every position's stop hits — the Elder-style worst case. */
    val riskIfAllStopsHit: Double = 0.0
)

class WealthPlanner(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        private const val FINALISTS = 14
        private const val POSITIONS = 5
        private const val CASH_FRACTION = 0.10
        private const val SCREEN_CHUNK = 10
        private const val DEEP_CHUNK = 4

        /** Universe symbol -> SectorTrends key, for the trending-sector boost. */
        private val SYMBOL_SECTOR: Map<String, String> = buildMap {
            listOf("NVDA", "AMD", "AVGO", "QCOM", "INTC", "MU", "TXN", "SMCI")
                .forEach { put(it, "semis") }
            listOf("PLTR", "TSLA").forEach { put(it, "ai") }
            listOf(
                "AAPL", "MSFT", "GOOGL", "AMZN", "META", "ORCL", "CRM", "ADBE",
                "IBM", "CSCO", "NOW", "SNOW", "SHOP", "NFLX"
            ).forEach { put(it, "software") }
            listOf("XOM", "CVX", "COP", "SLB", "OXY", "RIG").forEach { put(it, "oil") }
            listOf("VALE").forEach { put(it, "materials") }
            listOf("NEM", "B", "AEM", "FNV", "RGLD", "WPM", "KGC", "AU", "HMY")
                .forEach { put(it, "goldminers") }
            listOf(
                "JPM", "BAC", "GS", "MS", "V", "MA", "AXP", "C", "WFC",
                "SOFI", "HBAN", "KEY", "BBD", "COIN", "PYPL", "XYZ"
            ).forEach { put(it, "banks") }
            listOf("UNH", "JNJ", "LLY", "PFE", "MRK", "ABBV", "TMO").forEach { put(it, "health") }
            listOf("BA", "LMT", "RTX", "GE").forEach { put(it, "defense") }
            listOf("CAT", "DE", "HON", "UPS", "FDX", "MMM").forEach { put(it, "industrials") }
            listOf(
                "WMT", "COST", "TGT", "HD", "LOW", "MCD", "SBUX", "NKE", "DIS",
                "UBER", "ABNB", "KO", "PEP", "PG", "CL", "F", "CCL", "NCLH",
                "AAL", "M", "SNAP", "WBD", "NIO", "RIVN", "LCID"
            ).forEach { put(it, "consumer") }
            listOf("PLUG").forEach { put(it, "solar") }
            // TLRY (cannabis) and T/VZ/TMUS (telecom) have no tracked theme —
            // left unmapped so no misleading sector label is shown.
        }

        private val INSIDER_KEYWORDS = listOf(
            "insider", "stake", "13f", "hedge fund", "berkshire", "buys shares",
            "sells shares", "institutional", "position in", "billionaire"
        )

        // ---------------- JSON ----------------

        fun toJson(p: WealthPlan): String = JSONObject().apply {
            put("weekStart", p.weekStart)
            put("updatedAt", p.updatedAt)
            put("baseAmount", p.baseAmount)
            put("targetProfit", p.targetProfit)
            put("horizonMonths", p.horizonMonths)
            put("horizonEndIso", p.horizonEndIso)
            put("requiredTotalPct", p.requiredTotalPct)
            put("requiredMonthlyPct", p.requiredMonthlyPct)
            put("feasibility", p.feasibility)
            put("feasibilityNote", p.feasibilityNote)
            put("sectorHeadline", p.sectorHeadline)
            put("topSectors", SectorTrends.toJson(p.topSectors))
            put("allocations", JSONArray().apply {
                p.allocations.forEach { a ->
                    put(JSONObject().apply {
                        put("symbol", a.symbol)
                        put("name", a.name)
                        put("sectorLabel", a.sectorLabel)
                        put("amount", a.amount)
                        put("shares", a.shares)
                        put("entry", a.entry)
                        put("target", a.target)
                        put("stop", a.stop)
                        put("expectedProfit", a.expectedProfit)
                        put("expectedPct", a.expectedPct)
                        put("techBullish", a.techBullish)
                        put("techTotal", a.techTotal)
                        put("techConfidence", a.techConfidence)
                        put("reason", a.reason)
                        put("buyAdvice", a.buyAdvice)
                        put("sellAdvice", a.sellAdvice)
                        put("insiderNote", a.insiderNote)
                        put("rewardRisk", a.rewardRisk)
                        put("riskDollars", a.riskDollars)
                        put("heldNote", a.heldNote)
                    })
                }
            })
            put("cashReserve", p.cashReserve)
            put("expectedProfitTotal", p.expectedProfitTotal)
            put("expectedPctTotal", p.expectedPctTotal)
            put("gapNote", p.gapNote)
            put("planNote", p.planNote)
            put("riskIfAllStopsHit", p.riskIfAllStopsHit)
            put("weeklyActions", JSONArray(p.weeklyActions))
            put("marketNotes", JSONArray().apply {
                p.marketNotes.forEach { n ->
                    put(JSONObject().apply {
                        put("id", n.id)
                        put("symbol", n.symbol)
                        put("title", n.title)
                        put("source", n.source)
                        put("url", n.url)
                        put("publishedAt", n.publishedAt)
                        put("sentiment", n.sentiment)
                    })
                }
            })
            put("caveat", p.caveat)
        }.toString()

        fun fromJson(s: String): WealthPlan? = try {
            val o = JSONObject(s)
            val allocs = ArrayList<WealthAllocation>()
            val aArr = o.optJSONArray("allocations") ?: JSONArray()
            for (i in 0 until aArr.length()) {
                val a = aArr.optJSONObject(i) ?: continue
                allocs.add(
                    WealthAllocation(
                        symbol = a.getString("symbol"),
                        name = a.optString("name", ""),
                        sectorLabel = a.optString("sectorLabel", ""),
                        amount = a.getDouble("amount"),
                        shares = a.getDouble("shares"),
                        entry = a.getDouble("entry"),
                        target = a.getDouble("target"),
                        stop = a.getDouble("stop"),
                        expectedProfit = a.getDouble("expectedProfit"),
                        expectedPct = a.getDouble("expectedPct"),
                        techBullish = a.optInt("techBullish", 0),
                        techTotal = a.optInt("techTotal", 15),
                        techConfidence = a.optInt("techConfidence", 0),
                        reason = a.optString("reason", ""),
                        buyAdvice = a.optString("buyAdvice", ""),
                        sellAdvice = a.optString("sellAdvice", ""),
                        insiderNote = a.optString("insiderNote", ""),
                        rewardRisk = a.optDouble("rewardRisk", 0.0),
                        riskDollars = a.optDouble("riskDollars", 0.0),
                        heldNote = a.optString("heldNote", "")
                    )
                )
            }
            val notes = ArrayList<NewsItem>()
            val nArr = o.optJSONArray("marketNotes") ?: JSONArray()
            for (i in 0 until nArr.length()) {
                val n = nArr.optJSONObject(i) ?: continue
                notes.add(
                    NewsItem(
                        id = n.optString("id"),
                        symbol = n.optString("symbol"),
                        title = n.optString("title"),
                        source = n.optString("source"),
                        url = n.optString("url"),
                        publishedAt = n.optLong("publishedAt"),
                        sentiment = n.optInt("sentiment"),
                        priceImpactPct = null
                    )
                )
            }
            val actions = ArrayList<String>()
            val actArr = o.optJSONArray("weeklyActions") ?: JSONArray()
            for (i in 0 until actArr.length()) actions.add(actArr.optString(i))
            WealthPlan(
                weekStart = o.getString("weekStart"),
                updatedAt = o.optLong("updatedAt"),
                baseAmount = o.getDouble("baseAmount"),
                targetProfit = o.getDouble("targetProfit"),
                horizonMonths = o.optInt("horizonMonths", 4),
                horizonEndIso = o.optString("horizonEndIso", ""),
                requiredTotalPct = o.getDouble("requiredTotalPct"),
                requiredMonthlyPct = o.getDouble("requiredMonthlyPct"),
                feasibility = o.optString("feasibility", "AGGRESSIVE"),
                feasibilityNote = o.optString("feasibilityNote", ""),
                sectorHeadline = o.optString("sectorHeadline", ""),
                topSectors = SectorTrends.fromJson(o.optJSONArray("topSectors") ?: JSONArray()),
                allocations = allocs,
                cashReserve = o.optDouble("cashReserve", 0.0),
                expectedProfitTotal = o.getDouble("expectedProfitTotal"),
                expectedPctTotal = o.getDouble("expectedPctTotal"),
                gapNote = o.optString("gapNote", ""),
                weeklyActions = actions,
                marketNotes = notes,
                caveat = o.optString("caveat", ""),
                planNote = o.optString("planNote", ""),
                riskIfAllStopsHit = o.optDouble("riskIfAllStopsHit", 0.0)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Builds this week's plan. Null only when the market is fully unreachable.
     * [held] maps each open-position symbol to its cost value in dollars so
     * the plan sizes around the user's real book instead of ignoring it.
     * [sectorTrends] lets the caller share one sector scan across engines.
     */
    suspend fun build(
        weekStart: String,
        baseAmount: Double,
        targetProfit: Double,
        held: Map<String, Double> = emptyMap(),
        sectorTrends: List<SectorTrend>? = null
    ): WealthPlan? {
        return try {
            if (baseAmount <= 0.0) return null

            // 1 — feasibility of the user's 4-month goal, stated honestly.
            // Compounded, not divided: +X% over 4 months needs (1+X)^(1/4)-1
            // per month, which is more than X/4 for any real target.
            val requiredTotalPct = targetProfit / baseAmount * 100.0
            val requiredMonthlyPct =
                ((1.0 + requiredTotalPct / 100.0).pow(0.25) - 1.0) * 100.0
            val (feasibility, feasibilityNote) = feasibility(baseAmount, requiredTotalPct, requiredMonthlyPct)

            // 2 — this week's trending sectors.
            val sectors = sectorTrends ?: SectorTrends(market, news).compute()
            val topSectorKeys = sectors.take(3).map { it.key }
            val sectorHeadline = sectors.firstOrNull()?.let {
                String.format(
                    Locale.US, "%s lead this week: %+.1f%% in 5 days on %s.",
                    it.label, it.r5Pct, it.etf
                )
            } ?: "Sector read unavailable this week."

            // 3 — screen the universe plus the trending themes' watch names,
            // so a leading theme (quantum, nuclear, …) can actually reach the
            // allocation instead of merely decorating the sector card.
            val themed = topSectorKeys.flatMap { SectorTrends.WATCH[it].orEmpty() }
            val candidates = (WeeklyPicker.UNIVERSE + WeeklyPicker.BUDGET_EXTRA + themed)
                .distinctBy { it.first }
            val candlesBySymbol = HashMap<String, List<Candle>>()
            for (chunk in candidates.chunked(SCREEN_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (symbol, _) ->
                        async {
                            symbol to try {
                                market.getDailyCandles(symbol, 60)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                for ((symbol, candles) in results) {
                    if (candles.size >= 21) candlesBySymbol[symbol] = candles
                }
            }
            val screened = candidates.mapNotNull { (symbol, name) ->
                val candles = candlesBySymbol[symbol] ?: return@mapNotNull null
                screen(symbol, name, candles, topSectorKeys)
            }
            if (screened.isEmpty()) return null

            // 4 — deep read of the finalists with the 20-technique board + news.
            val finalists = screened.sortedByDescending { it.raw }.take(FINALISTS)
            val deep = ArrayList<Deep>()
            for (chunk in finalists.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { s -> async { deepRead(s) } }.awaitAll()
                }
                results.filterNotNull().forEach { deep.add(it) }
            }
            if (deep.isEmpty()) return null

            // 5 — keep only names that EARN money: non-bearish board AND a
            // positive momentum expectation. A stock with nothing left to
            // expect gets nothing — cash is a position too.
            var planNote = ""
            val nonBearish = deep.filter { it.direction != TechniqueVerdict.BEARISH }
            if (nonBearish.isEmpty()) {
                planNote = "Every finalist's technique board reads bearish this week. " +
                    "No stock earns the money — the plan holds cash and re-checks next Monday."
            }
            val kept = nonBearish
                .map { it to expectedMove(it) }
                .filter { it.second > 1.0 }
                .sortedByDescending { it.first.finalScore }
                .take(POSITIONS)
            if (planNote.isEmpty() && kept.isEmpty()) {
                planNote = "No finalist carries a positive momentum expectation this week — " +
                    "the plan holds cash rather than forcing a trade."
            }

            // 6 — allocate with the cap ENFORCED: no name may take more than
            // 30% of the base, held names are downweighted, and any dollars
            // the caps refuse flow to cash instead of being forced in.
            val cashFloor = round2(baseAmount * CASH_FRACTION)
            val investable = baseAmount - cashFloor
            val perNameCap = baseAmount * 0.30
            val amounts: List<Double>
            if (kept.isNotEmpty()) {
                val scoreFloor = kept.minOf { it.first.finalScore } - 1.0
                val weightsRaw = kept.map { (d, _) ->
                    val w = (d.finalScore - scoreFloor).coerceAtLeast(1.0)
                    // Already-held names add to an existing position, so they
                    // earn a smaller add, not a second full-size buy.
                    if (held.containsKey(d.s.symbol)) w * 0.6 else w
                }
                val weightSum = weightsRaw.sum()
                val proportional = weightsRaw.map { investable * it / weightSum }
                val capped = proportional.map { min(it, perNameCap) }.toMutableList()
                // One redistribution pass: leftover from capped names goes to
                // the uncapped, still respecting the cap; the rest is cash.
                val leftover = investable - capped.sum()
                if (leftover > 1.0) {
                    val headroom = capped.map { perNameCap - it }
                    val headroomSum = headroom.sum()
                    if (headroomSum > 0.0) {
                        for (i in capped.indices) {
                            capped[i] += min(headroom[i], leftover * headroom[i] / headroomSum)
                        }
                    }
                }
                amounts = capped.map { round2(it) }
            } else {
                amounts = emptyList()
            }
            val cashReserve = round2(baseAmount - amounts.sum())

            val horizonEnd = LocalDate.now().plusMonths(4).toString()
            val allocations = kept.mapIndexed { i, (d, expectedPct) ->
                toAllocation(d, amounts[i], horizonEnd, expectedPct, held[d.s.symbol])
            }

            val expectedProfitTotal = round2(allocations.sumOf { it.expectedProfit })
            val expectedPctTotal = round1(expectedProfitTotal / baseAmount * 100.0)
            val riskIfAllStopsHit = round2(allocations.sumOf { it.riskDollars })
            val gapNote = gapNote(expectedProfitTotal, targetProfit, feasibility)

            // 7 — insider & institutional flow headlines (global + per pick).
            val globalNotes = try {
                news.getTopicNews(
                    query = "insider buying OR hedge fund stake stocks",
                    cacheKey = "insider-weekly",
                    tag = "Flow"
                )
            } catch (_: Exception) {
                emptyList()
            }
            val pickNotes = deep.flatMap { it.insiderItems }
            val marketNotes = (pickNotes + globalNotes)
                .distinctBy { it.id }
                .sortedByDescending { it.publishedAt }
                .take(8)

            WealthPlan(
                weekStart = weekStart,
                updatedAt = System.currentTimeMillis(),
                baseAmount = baseAmount,
                targetProfit = targetProfit,
                horizonMonths = 4,
                horizonEndIso = horizonEnd,
                requiredTotalPct = round1(requiredTotalPct),
                requiredMonthlyPct = round1(requiredMonthlyPct),
                feasibility = feasibility,
                feasibilityNote = feasibilityNote,
                sectorHeadline = sectorHeadline,
                topSectors = sectors.take(5),
                allocations = allocations,
                cashReserve = cashReserve,
                expectedProfitTotal = expectedProfitTotal,
                expectedPctTotal = expectedPctTotal,
                gapNote = gapNote,
                weeklyActions = weeklyActions(
                    cashReserve, riskIfAllStopsHit, baseAmount,
                    allocations.filter { it.heldNote.isNotEmpty() }.map { it.symbol }
                ),
                marketNotes = marketNotes,
                caveat = "Recomputed every week from live prices, the 20-technique board, sector " +
                    "momentum, and public news. Expected profits are dampened momentum " +
                    "extrapolations, not promises — decision support, not financial advice.",
                planNote = planNote,
                riskIfAllStopsHit = riskIfAllStopsHit
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ pieces

    private fun feasibility(
        base: Double,
        totalPct: Double,
        monthlyPct: Double
    ): Pair<String, String> = when {
        monthlyPct <= 1.5 -> "REALISTIC" to String.format(
            Locale.US,
            "The goal needs %.1f%% per month — in line with what strong broad-market stretches " +
                "deliver. A diversified plan can reach it without forcing risk.",
            monthlyPct
        )
        monthlyPct <= 4.0 -> "AGGRESSIVE" to String.format(
            Locale.US,
            "The goal needs %.1f%% per month — achievable in a good tape with concentrated " +
                "momentum names, but expect drawdowns along the way and respect every stop.",
            monthlyPct
        )
        else -> "UNREALISTIC" to String.format(
            Locale.US,
            "The goal needs %.1f%% per month. For honesty's sake: the best investors in history " +
                "average roughly 1.5–2.5%% a month over time. A grounded 4-month expectation for " +
                "this base is %s–%s. The plan still targets the strongest setups — without leverage " +
                "or all-in bets that could destroy the base.",
            monthlyPct,
            money(base * 0.05),
            money(base * 0.12)
        )
    }

    private class Screened(
        val symbol: String,
        val name: String,
        val raw: Double,
        val sectorKey: String?,
        val r20: Double,
        val r60: Double,
        /** False when history is too short for a real 60-day read — [r60] then just echoes [r20]. */
        val hasR60: Boolean,
        val volumeRatio: Double
    )

    private fun screen(
        symbol: String,
        name: String,
        candles: List<Candle>,
        topSectorKeys: List<String>
    ): Screened? {
        val closes = candles.map { it.close }
        val n = closes.size
        if (n < 21) return null
        val last = closes.last()
        if (last <= 0.0) return null
        val c20 = closes[n - 21]
        if (c20 <= 0.0) return null
        val r20 = (last / c20 - 1.0) * 100.0
        val hasR60 = n >= 60 && closes[n - 60] > 0.0
        val r60 = if (hasR60) (last / closes[n - 60] - 1.0) * 100.0 else r20
        val rsi = Indicators.rsi(closes) ?: return null

        // Today's in-progress bar has partial volume — exclude it from the surge read.
        val volumes = candles.map { it.volume.toDouble() }
        val lastIsToday =
            com.aurum.invest.core.Dates.sameDay(candles.last().ts, System.currentTimeMillis())
        val volEnd = if (lastIsToday && volumes.size >= 2) volumes.size - 1 else volumes.size
        val vol5 = volumes.subList((volEnd - 5).coerceAtLeast(0), volEnd).average()
        val vol20 = volumes.subList((volEnd - 20).coerceAtLeast(0), volEnd).average()
        val volumeRatio = if (vol20 > 0.0) vol5 / vol20 else 1.0

        val high20 = Indicators.recentHigh(closes, 20) ?: last
        val distFromHigh = if (high20 > 0.0) (high20 - last) / high20 * 100.0 else 0.0

        // The hand-kept map first, then the SectorTrends watch-list reverse
        // map — covers the themed candidates (quantum, nuclear, …) too.
        val sectorKey = SYMBOL_SECTOR[symbol] ?: SectorTrends.SYMBOL_THEME[symbol]?.first
        val sectorBoost = when (topSectorKeys.indexOf(sectorKey)) {
            0 -> 8.0
            1 -> 5.0
            2 -> 3.0
            else -> 0.0
        }

        val raw = (r20 * 1.2).coerceIn(-12.0, 18.0) +
            (r60 * 0.5).coerceIn(-8.0, 12.0) +
            (10.0 - abs(rsi - 55.0) / 3.0).coerceIn(0.0, 10.0) +
            ((volumeRatio - 1.0) * 10.0).coerceIn(0.0, 10.0) +
            (8.0 - distFromHigh).coerceIn(0.0, 8.0) +
            sectorBoost

        return Screened(symbol, name, raw, sectorKey, r20, r60, hasR60, volumeRatio)
    }

    private class Deep(
        val s: Screened,
        val finalScore: Double,
        val price: Double,
        val sma20: Double?,
        val atr: Double,
        val supports: List<Double>,
        val direction: TechniqueVerdict,
        val bullishCount: Int,
        val techTotal: Int,
        val confidence: Int,
        val newsTone: Int,
        val insiderItems: List<NewsItem>
    )

    private suspend fun deepRead(s: Screened): Deep? {
        return try {
            val candles = try {
                market.getDailyCandles(s.symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < 30) return null
            val analysis = Techniques.analyze(s.symbol, candles) ?: return null
            val quote = try {
                market.getQuote(s.symbol)
            } catch (_: Exception) {
                null
            }
            val price = quote?.price ?: candles.last().close
            if (price <= 0.0) return null
            val newsItems = try {
                news.getNews(s.symbol, candles)
            } catch (_: Exception) {
                emptyList()
            }
            val newsTone = newsItems.sumOf { it.sentiment }.coerceIn(-5, 5)
            val insiderItems = newsItems.filter { item ->
                val t = item.title.lowercase()
                INSIDER_KEYWORDS.any { t.contains(it) }
            }

            val direction = analysis.outlook.direction
            val confidence = analysis.outlook.confidence
            val techBonus = when (direction) {
                TechniqueVerdict.BULLISH -> confidence * 0.30
                TechniqueVerdict.NEUTRAL -> 0.0
                TechniqueVerdict.BEARISH -> -confidence * 0.35
            }
            val insiderBoost =
                if (insiderItems.any { it.sentiment > 0 }) 6.0
                else if (insiderItems.any { it.sentiment < 0 }) -6.0
                else 0.0

            Deep(
                s = s,
                finalScore = s.raw + techBonus + newsTone * 3.0 + insiderBoost,
                price = price,
                sma20 = analysis.maData.sma20.lastOrNull { it != null },
                atr = Indicators.atr(candles, 14) ?: (price * 0.02),
                supports = analysis.srData.supports,
                direction = direction,
                bullishCount = analysis.outlook.bullishCount,
                techTotal = analysis.results.size,
                confidence = confidence,
                newsTone = newsTone,
                insiderItems = insiderItems
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Expected 4-month move, in %: a DAMPENED continuation of observed
     * momentum. The 60-day return (≈3 months) extrapolates at 0.85x and the
     * 20-day adds a 0.35x kicker — deliberately projecting LESS than what was
     * observed, because momentum decays. Nudged by the technique board and
     * news tone. Can be negative: a falling name shows a falling expectation,
     * and the allocator gives it nothing instead of a fabricated +3%.
     */
    private fun expectedMove(d: Deep): Double {
        val momentum =
            if (d.s.hasR60) d.s.r60 * 0.85 + d.s.r20 * 0.35
            else d.s.r20 * 0.9   // short history: extrapolate only what exists
        val adj = (if (d.direction == TechniqueVerdict.BULLISH) (d.confidence - 50) * 0.08 else 0.0) +
            d.newsTone * 0.8
        return round1((momentum + adj).coerceIn(-15.0, 30.0))
    }

    private fun toAllocation(
        d: Deep,
        amount: Double,
        horizonEnd: String,
        expectedPct: Double,
        heldDollars: Double?
    ): WealthAllocation {
        val entry = round2(d.price)
        val shares = if (entry > 0.0) round4(amount / entry) else 0.0
        val target = round2(entry * (1.0 + expectedPct / 100.0))
        val expectedProfit = round2(amount * expectedPct / 100.0)

        val structural = d.supports.filter { it < entry }.maxOrNull()
        val stop = round2(
            max(
                structural?.let { min(it - 0.75 * d.atr, entry - 1.5 * d.atr) }
                    ?: (entry - 2.2 * d.atr),
                entry * 0.80
            )
        )

        val pullback = d.sma20?.takeIf { it < entry }?.let { round2(it) }
        val buyAdvice = when {
            d.direction == TechniqueVerdict.BULLISH && d.confidence >= 65 ->
                "Buy this week — the board is aligned. A market order is fine" +
                    (pullback?.let { ", or work a limit near ${money(it)} (the 20-day average) for a better fill" } ?: "") + "."
            d.direction == TechniqueVerdict.BULLISH ->
                "Buy in two halves this week: half now, half " +
                    (pullback?.let { "with a limit at ${money(it)}" } ?: "after any down day") + "."
            else ->
                "Scale in slowly: one third now, the rest " +
                    (pullback?.let { "only at ${money(it)} or better" } ?: "on weakness") +
                    " — the board is mixed, so let price come to you."
        }
        val sellAdvice = "Sell at ${money(target)} (+${fmt1(expectedPct)}%), or exit if a day closes " +
            "below the ${money(stop)} stop — whichever comes first. Otherwise hold and re-check at " +
            "each weekly review; the horizon closes $horizonEnd."

        val sectorLabel = d.s.sectorKey?.let { key ->
            SectorTrends.SECTORS.firstOrNull { it.first == key }?.second
        } ?: ""

        val reasonParts = mutableListOf(
            if (d.s.hasR60)
                String.format(Locale.US, "%+.1f%% in 20 days, %+.1f%% in 60", d.s.r20, d.s.r60)
            else
                String.format(Locale.US, "%+.1f%% in 20 days (history too short for a 60-day read)", d.s.r20),
            "${d.bullishCount} of ${d.techTotal} techniques bullish (${d.confidence}%)"
        )
        if (d.s.volumeRatio >= 1.2) {
            reasonParts += String.format(Locale.US, "%.1fx volume", d.s.volumeRatio)
        }
        if (d.newsTone != 0) {
            reasonParts += String.format(Locale.US, "news tone %+d", d.newsTone)
        }

        // Defense first: what the trade risks, next to what it promises.
        val riskPerShare = (entry - stop).coerceAtLeast(0.0)
        val rewardRisk = if (riskPerShare > 1e-9)
            round1((target - entry) / riskPerShare).coerceIn(0.0, 9.9) else 0.0
        val riskDollars = round2(shares * riskPerShare)
        val heldNote = heldDollars?.let {
            String.format(
                Locale.US,
                "Already in your book (~%s at cost) — this is an add, sized down so the " +
                    "combined position stays reasonable.",
                money(it)
            )
        } ?: ""

        return WealthAllocation(
            symbol = d.s.symbol,
            name = d.s.name,
            sectorLabel = sectorLabel,
            amount = amount,
            shares = shares,
            entry = entry,
            target = target,
            stop = stop,
            expectedProfit = expectedProfit,
            expectedPct = expectedPct,
            techBullish = d.bullishCount,
            techTotal = d.techTotal,
            techConfidence = d.confidence,
            reason = reasonParts.joinToString(", "),
            buyAdvice = buyAdvice,
            sellAdvice = sellAdvice,
            insiderNote = d.insiderItems.firstOrNull()?.let { "${it.title} — ${it.source}" } ?: "",
            rewardRisk = rewardRisk,
            riskDollars = riskDollars,
            heldNote = heldNote
        )
    }

    private fun gapNote(expected: Double, target: Double, feasibility: String): String = when {
        target <= 0.0 -> "No profit target set."
        expected >= target && feasibility == "UNREALISTIC" -> String.format(
            Locale.US,
            "The dampened projection reads %s against the %s target — but the feasibility check " +
                "already called this goal unrealistic, and a projection that flatters an " +
                "unrealistic goal deserves suspicion, not celebration. Trust the stops, not the sum.",
            money(expected), money(target)
        )
        expected >= target -> String.format(
            Locale.US,
            "The current allocation projects %s — covering the %s target with %s of margin. " +
                "Projections are dampened momentum, not promises; corrections shrink them fast.",
            money(expected), money(target), money(expected - target)
        )
        else -> String.format(
            Locale.US,
            "The current allocation projects %s — %s short of the %s target. The weekly rescans " +
                "hunt for that gap as sectors rotate; do not close it with leverage or oversizing.",
            money(expected), money(target - expected), money(target)
        )
    }

    private fun weeklyActions(
        cashReserve: Double,
        riskIfAllStopsHit: Double,
        base: Double,
        heldOverlap: List<String>
    ): List<String> = buildList {
        add(
            "Place each buy and its stop in the same sitting — a stop decided at entry is " +
                "Elder's rule working for you. If every stop hits, this plan loses " +
                "${money(riskIfAllStopsHit)} (${fmt1(if (base > 0) riskIfAllStopsHit / base * 100 else 0.0)}% " +
                "of the base): that number is the plan's real downside, know it before buying."
        )
        add(
            "Sell any position that closes below its stop, the same day. No averaging down — " +
                "Livermore: never add to a position that is proving you wrong."
        )
        add(
            "Add only to winners (O'Neil): if a name runs 3%+ above entry on volume, it has " +
                "earned the next slice of cash reserve — losers have not."
        )
        if (heldOverlap.isNotEmpty()) {
            add(
                "You already hold ${heldOverlap.joinToString(", ")} — those allocations are ADDS " +
                    "sized around the existing position, not fresh full-size buys."
            )
        }
        add(
            "Re-open Wealth each Monday: the plan re-reads sectors, the 20-technique board, and " +
                "the news, then re-ranks. A plan that never changes is a plan nobody is checking."
        )
        add(
            "Keep the ${money(cashReserve)} cash reserve untouched — it buys the dip the weekly " +
                "scan flags, not the one that merely feels cheap."
        )
    }

    // ------------------------------------------------------------ helpers

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0

    private fun money(v: Double): String = com.aurum.invest.core.Fmt.money(v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
