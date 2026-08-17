package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ScreenerQuote
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

/**
 * The next-week preview: built Thursday through Monday, it answers "what
 * should I be looking at when the new week opens?" with sectors, stocks, and
 * a suggested split of next week's buying power (the invested book at cost;
 * percentages only when the book is empty).
 *
 * Reads the whole market (Yahoo's saved screens), the sector MONEY FLOWS
 * (dollar volume, CMF, MFI, OBV, relative strength — via [MoneyFlowEngine]),
 * each finalist's news tone, the 35-technique board, and the latest
 * pre/post-market prints — and it knows the user's book, so an already-held
 * name is marked as an add, not a fresh discovery.
 */

data class NextWeekSector(
    val key: String,
    val label: String,
    val etf: String,
    val r5Pct: Double,
    val newsTone: Int,
    val note: String
)

data class NextWeekStock(
    val symbol: String,
    val name: String,
    val sectorLabel: String,     // "" when no tracked theme matches
    val score: Int,              // 0..100 on a FIXED scale — comparable across weeks
    val price: Double,           // live print at build time
    val entry: Double,           // suggested entry for Monday
    val target: Double,          // next-week objective (5-day technique range)
    val stop: Double,
    val rewardRisk: Double,
    val expectedPct: Double,
    val amount: Double,          // suggested dollars from the investable base
    val allocationPct: Double,   // amount / investable * 100
    val techBullish: Int,
    val techTotal: Int,
    val newsScore: Int,          // -3..+3 summed headline tone, 5 days
    val newsNote: String,        // "" when the week had no headline worth citing
    val extNote: String,         // "" unless a pre/post-market print says something
    val heldNote: String,        // "" unless the user already holds it
    val reason: String
)

data class NextWeekPlan(
    val weekStart: String,       // ISO Monday being previewed
    val builtOn: String,         // ISO date the preview was computed
    val updatedAt: Long,
    val headline: String,
    val marketNote: String,      // "" when the market pulse was unavailable
    val sectors: List<NextWeekSector>,
    val stocks: List<NextWeekStock>,
    val investable: Double,
    val cashLeft: Double,
    val portfolioNote: String,   // "" when the book and the preview don't touch
    val actions: List<String>,
    val caveat: String
)

class NextWeekPlanner(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        private const val FINALISTS = 12
        private const val POSITIONS = 5
        private const val SCREEN_CHUNK = 10
        private const val DEEP_CHUNK = 4
        /** Fixed score denominator so 80 means 80 next month too, not "rank 2 of 10". */
        private const val SCORE_SCALE = 80.0

        // ---------------- JSON ----------------

        fun toJson(p: NextWeekPlan): String = JSONObject().apply {
            put("weekStart", p.weekStart)
            put("builtOn", p.builtOn)
            put("updatedAt", p.updatedAt)
            put("headline", p.headline)
            put("marketNote", p.marketNote)
            put("sectors", JSONArray().apply {
                p.sectors.forEach { s ->
                    put(JSONObject().apply {
                        put("key", s.key); put("label", s.label); put("etf", s.etf)
                        put("r5Pct", s.r5Pct); put("newsTone", s.newsTone); put("note", s.note)
                    })
                }
            })
            put("stocks", JSONArray().apply {
                p.stocks.forEach { s ->
                    put(JSONObject().apply {
                        put("symbol", s.symbol); put("name", s.name)
                        put("sectorLabel", s.sectorLabel); put("score", s.score)
                        put("price", s.price); put("entry", s.entry)
                        put("target", s.target); put("stop", s.stop)
                        put("rewardRisk", s.rewardRisk); put("expectedPct", s.expectedPct)
                        put("amount", s.amount); put("allocationPct", s.allocationPct)
                        put("techBullish", s.techBullish); put("techTotal", s.techTotal)
                        put("newsScore", s.newsScore); put("newsNote", s.newsNote)
                        put("extNote", s.extNote); put("heldNote", s.heldNote)
                        put("reason", s.reason)
                    })
                }
            })
            put("investable", p.investable)
            put("cashLeft", p.cashLeft)
            put("portfolioNote", p.portfolioNote)
            put("actions", JSONArray(p.actions))
            put("caveat", p.caveat)
        }.toString()

        fun fromJson(s: String): NextWeekPlan? = try {
            val o = JSONObject(s)
            val sectors = ArrayList<NextWeekSector>()
            val sArr = o.optJSONArray("sectors") ?: JSONArray()
            for (i in 0 until sArr.length()) {
                val x = sArr.optJSONObject(i) ?: continue
                sectors.add(
                    NextWeekSector(
                        key = x.optString("key"), label = x.optString("label"),
                        etf = x.optString("etf"), r5Pct = x.optDouble("r5Pct", 0.0),
                        newsTone = x.optInt("newsTone", 0), note = x.optString("note", "")
                    )
                )
            }
            val stocks = ArrayList<NextWeekStock>()
            val tArr = o.optJSONArray("stocks") ?: JSONArray()
            for (i in 0 until tArr.length()) {
                val x = tArr.optJSONObject(i) ?: continue
                stocks.add(
                    NextWeekStock(
                        symbol = x.getString("symbol"), name = x.optString("name", ""),
                        sectorLabel = x.optString("sectorLabel", ""),
                        score = x.optInt("score", 0),
                        price = x.optDouble("price", 0.0), entry = x.optDouble("entry", 0.0),
                        target = x.optDouble("target", 0.0), stop = x.optDouble("stop", 0.0),
                        rewardRisk = x.optDouble("rewardRisk", 0.0),
                        expectedPct = x.optDouble("expectedPct", 0.0),
                        amount = x.optDouble("amount", 0.0),
                        allocationPct = x.optDouble("allocationPct", 0.0),
                        techBullish = x.optInt("techBullish", 0),
                        techTotal = x.optInt("techTotal", 0),
                        newsScore = x.optInt("newsScore", 0),
                        newsNote = x.optString("newsNote", ""),
                        extNote = x.optString("extNote", ""),
                        heldNote = x.optString("heldNote", ""),
                        reason = x.optString("reason", "")
                    )
                )
            }
            val actions = ArrayList<String>()
            val aArr = o.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until aArr.length()) actions.add(aArr.optString(i))
            NextWeekPlan(
                weekStart = o.getString("weekStart"),
                builtOn = o.optString("builtOn", ""),
                updatedAt = o.optLong("updatedAt"),
                headline = o.optString("headline", ""),
                marketNote = o.optString("marketNote", ""),
                sectors = sectors,
                stocks = stocks,
                investable = o.optDouble("investable", 0.0),
                cashLeft = o.optDouble("cashLeft", 0.0),
                portfolioNote = o.optString("portfolioNote", ""),
                actions = actions,
                caveat = o.optString("caveat", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [weekStart] is the ISO Monday being previewed; [investable] is next
     * week's reference buying power — the user's invested book value when
     * there is one; pass 0 to get a percentage-only split. [held] maps
     * open-position symbols to cost dollars; [sectorTrends], [pulse] and
     * [flow] let the caller share scans already paid for. When [flow] is
     * present, next week's sectors are chosen by MEASURED money flow, not
     * price momentum alone.
     */
    suspend fun build(
        weekStart: String,
        investable: Double,
        held: Map<String, Double> = emptyMap(),
        sectorTrends: List<SectorTrend>? = null,
        pulse: MarketRating? = null,
        flow: MoneyFlowReport? = null
    ): NextWeekPlan? {
        return try {
            if (investable < 0.0) return null

            // 1 — sectors to look at next week. When money flow is measured,
            // only CONFIRMED inflows are investable; balanced/outflow sectors
            // remain in the flow report, never a source of buy recommendations.
            val sectors: List<NextWeekSector>
            val topKeys: List<String>
            if (flow != null && flow.sectors.isNotEmpty()) {
                val ordered = flow.inflows.distinctBy { it.key }.take(3)
                sectors = ordered.map { s ->
                    NextWeekSector(
                        key = s.key, label = s.label, etf = s.etf,
                        r5Pct = s.r5Pct, newsTone = s.newsTone ?: 0,
                        note = "Flow ${s.flowScore}/100 — money is measurably flowing in. ${s.reason}."
                    )
                }
                topKeys = ordered.map { it.key }
                if (topKeys.isEmpty()) {
                    return cashOnlyPlan(
                        weekStart = weekStart,
                        investable = investable,
                        held = held,
                        pulse = pulse,
                        reason = "No sector has a confirmed inflow for next week.",
                        flowMeasured = true
                    )
                }
            } else {
                return cashOnlyPlan(
                    weekStart = weekStart,
                    investable = investable,
                    held = held,
                    pulse = pulse,
                    reason = "Money flow could not be verified for next week.",
                    flowMeasured = false
                )
            }

            // 2 — candidate pool: the whole market via the saved screens,
            // plus the leading themes' watch names so a trending theme is
            // represented even when no screen happens to surface it.
            val pool = LinkedHashMap<String, String>()
            topKeys.flatMap { SectorTrends.WATCH[it].orEmpty() }
                .forEach { (sym, name) -> pool.putIfAbsent(sym, name) }
            StockCatalog.SECTORS.filter { it.themeKey in topKeys }
                .flatMap { it.stocks }
                .forEach { (sym, name) -> pool.putIfAbsent(sym, name) }
            for (chunk in EntryPicker.MARKET_SCREENS.chunked(4)) {
                val results = coroutineScope {
                    chunk.map { scr ->
                        async {
                            try {
                                market.getScreener(scr)
                            } catch (_: Exception) {
                                emptyList<ScreenerQuote>()
                            }
                        }
                    }.awaitAll()
                }
                results.flatten().forEach { q ->
                    val ok = q.price in 3.0..2000.0 &&
                        q.avgVolume3M >= 1_000_000L &&
                        q.marketCap >= 500_000_000.0 &&
                        q.symbol.all { it.isLetterOrDigit() }
                    if (ok) pool.putIfAbsent(q.symbol, q.name)
                }
            }
            if (pool.isEmpty()) return null

            // Bound the cheap screen: the most liquid screen names already
            // passed a liquidity gate; cap the candle fan-out.
            val candidates = pool.entries.take(120)

            // 3 — cheap screen on 60-day candles.
            val screened = ArrayList<Screened>()
            for (chunk in candidates.chunked(SCREEN_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (symbol, name) ->
                        async {
                            try {
                                screen(symbol, name, market.getDailyCandles(symbol, 60), topKeys)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }
                results.filterNotNull().forEach { screened.add(it) }
            }
            if (screened.isEmpty()) return null

            // 4 — deep read of the finalists.
            val finalists = screened.sortedByDescending { it.raw }.take(FINALISTS)
            val deep = ArrayList<Deep>()
            for (chunk in finalists.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { s -> async { deepRead(s) } }.awaitAll()
                }
                results.filterNotNull().forEach { deep.add(it) }
            }
            val kept = deep.sortedByDescending { it.finalScore }.take(POSITIONS)
            if (kept.isEmpty()) return null

            // 5 — split next week's buying power across the keepers; held
            // names take smaller adds; the caps leave the rest in cash. With
            // no reference amount the split is expressed as percentages only.
            val scoreFloor = kept.minOf { it.finalScore } - 1.0
            val weightsRaw = kept.map { d ->
                val w = (d.finalScore - scoreFloor).coerceAtLeast(1.0)
                if (held.containsKey(d.s.symbol)) w * 0.6 else w
            }
            val weightSum = weightsRaw.sum()
            val shares = kept.indices.map { i ->
                min(0.9 * weightsRaw[i] / weightSum, 0.35)
            }
            val amounts = kept.indices.map { i -> round2(investable * shares[i]) }
            val cashLeft = round2(investable - amounts.sum())

            val stocks = kept.mapIndexed { i, d ->
                toStock(d, amounts[i], shares[i], investable, held[d.s.symbol])
            }

            // 6 — copy.
            val lead = sectors.firstOrNull()
            val headline = when {
                lead != null -> String.format(
                    Locale.US, "Next week starts with %s in the lead (%+.1f%% in 5 days).",
                    lead.label.lowercase(Locale.US), lead.r5Pct
                )
                else -> "Next week's preview — sector read unavailable."
            }
            val marketNote = pulse?.let {
                val scorePart = it.score?.let { s -> "$s/100" } ?: "no score (incomplete data)"
                "Market pulse $scorePart — ${it.call}. ${it.headline}"
            } ?: ""
            val overlap = stocks.filter { it.heldNote.isNotEmpty() }.map { it.symbol }
            val portfolioNote = when {
                overlap.isNotEmpty() ->
                    "You already hold ${overlap.joinToString(", ")} — those rows are adds to " +
                        "existing positions, sized down accordingly."
                held.isEmpty() -> ""
                else -> "None of next week's names are in your book yet — every row would be " +
                    "a fresh position."
            }

            NextWeekPlan(
                weekStart = weekStart,
                builtOn = Dates.todayIso(),
                updatedAt = System.currentTimeMillis(),
                headline = headline,
                marketNote = marketNote,
                sectors = sectors,
                stocks = stocks,
                investable = investable,
                cashLeft = cashLeft,
                portfolioNote = portfolioNote,
                actions = listOf(
                    "Thursday–Friday: set price alerts at each entry — no orders yet; Friday " +
                        "afternoons are for watching, not chasing.",
                    "Over the weekend: read each name's headline once more; a story that broke " +
                        "Saturday changes Monday.",
                    "Monday at the open: buy only the names trading NEAR their entry. A gap of " +
                        "3%+ above entry is a missed bus, not an invitation — the next scan " +
                        "finds another.",
                    "Place every stop with its buy. This preview is next Monday's plan " +
                        "taking shape — it re-ranks until the open, never a promise."
                ),
                caveat = "Built ${Dates.todayLabel()} from confirmed sector money flow, " +
                    "Yahoo's market screens (a broad liquid sample, not every US stock), news " +
                    "tone, the 35-technique board, and the latest pre/post-market prints. " +
                    "Numbers refresh until Monday's open; projections are dampened " +
                    "extrapolations, not promises."
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ pieces

    private class Screened(
        val symbol: String,
        val name: String,
        val raw: Double,
        val sectorKey: String?,
        val sectorLabel: String,
        val r5: Double,
        val r20: Double,
        val volumeRatio: Double
    )

    private fun screen(
        symbol: String,
        name: String,
        candles: List<Candle>,
        topKeys: List<String>
    ): Screened? {
        val closes = candles.map { it.close }
        val n = closes.size
        if (n < 21) return null
        val last = closes.last()
        if (last <= 0.0) return null
        val r5 = if (n >= 6 && closes[n - 6] > 0.0) (last / closes[n - 6] - 1.0) * 100.0 else 0.0
        val r20 = if (closes[n - 21] > 0.0) (last / closes[n - 21] - 1.0) * 100.0 else 0.0
        val rsi = Indicators.rsi(closes) ?: return null

        val volumes = candles.map { it.volume.toDouble() }
        val lastIsIncomplete = Dates.isCurrentEtDailyBarIncomplete(candles.last().ts)
        val volEnd =
            if (lastIsIncomplete && volumes.size >= 2) volumes.size - 1 else volumes.size
        val vol5 = volumes.subList((volEnd - 5).coerceAtLeast(0), volEnd).average()
        val prior = volumes.subList((volEnd - 25).coerceAtLeast(0), (volEnd - 5).coerceAtLeast(1))
        val volumeRatio = if (prior.isNotEmpty() && prior.average() > 0.0) vol5 / prior.average() else 1.0

        val high20 = Indicators.recentHigh(closes, 20) ?: last
        val distFromHigh = if (high20 > 0.0) (high20 - last) / high20 * 100.0 else 0.0

        val theme = SectorTrends.SYMBOL_THEME[symbol] ?: StockCatalog.SYMBOL_THEME[symbol]
        val sectorKey = theme?.first
        if (topKeys.isNotEmpty() && sectorKey !in topKeys) return null
        val sectorBoost = when (topKeys.indexOf(sectorKey)) {
            0 -> 8.0
            1 -> 5.0
            2 -> 3.0
            else -> 0.0
        }

        val raw = (r5 * 1.4).coerceIn(-8.0, 12.0) +
            (r20 * 0.8).coerceIn(-10.0, 14.0) +
            (10.0 - abs(rsi - 55.0) / 3.0).coerceIn(0.0, 10.0) +
            ((volumeRatio - 1.0) * 8.0).coerceIn(0.0, 8.0) +
            (8.0 - distFromHigh).coerceIn(0.0, 8.0) +
            sectorBoost

        return Screened(symbol, name, raw, sectorKey, theme?.second ?: "", r5, r20, volumeRatio)
    }

    private class Deep(
        val s: Screened,
        val finalScore: Double,
        val price: Double,
        val atr: Double,
        val supports: List<Double>,
        val expectedHigh: Double,
        val bullishCount: Int,
        val techTotal: Int,
        val confidence: Int,
        val newsScore: Int,
        val newsNote: String,
        val extNote: String
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
            // A bearish board never makes the next-week list — this is a
            // preview of what to BUY, and bearish tape is what to avoid.
            if (analysis.outlook.direction == TechniqueVerdict.BEARISH) return null

            val quote = try {
                market.getQuote(s.symbol)
            } catch (_: Exception) {
                null
            }
            val ext = try {
                market.getExtendedHours(s.symbol)
            } catch (_: Exception) {
                null
            }
            // Session-aware, not livePrice: the raw accessor would keep
            // serving the morning pre-market print all through the session.
            val price = when (com.aurum.invest.core.Dates.marketSessionNow()) {
                com.aurum.invest.core.Dates.MarketSession.REGULAR ->
                    ext?.regularPrice?.takeIf { it > 0.0 }
                com.aurum.invest.core.Dates.MarketSession.PRE ->
                    ext?.preMarketPrice?.takeIf { it > 0.0 }
                else -> ext?.postMarketPrice?.takeIf { it > 0.0 }
                    ?: ext?.regularPrice?.takeIf { it > 0.0 }
            } ?: quote?.price ?: candles.last().close
            if (price <= 0.0) return null
            if (analysis.outlook.expectedHigh <= price * 1.005) return null

            val extNote = when {
                ext?.preMarketPct != null && abs(ext.preMarketPct) >= 1.0 ->
                    String.format(Locale.US, "Pre-market %+.1f%% on the latest print.", ext.preMarketPct)
                ext?.postMarketPct != null && abs(ext.postMarketPct) >= 1.0 ->
                    String.format(Locale.US, "After-hours %+.1f%% on the latest print.", ext.postMarketPct)
                else -> ""
            }
            val extPct = ext?.preMarketPct ?: ext?.postMarketPct ?: 0.0

            val newsItems = if (s.symbol.length >= 2) {
                try {
                    news.getNews(s.symbol, candles)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val newsScore = newsItems.sumOf { it.sentiment }.coerceIn(-3, 3)
            val newsNote = newsItems.firstOrNull { it.sentiment != 0 }?.let {
                "${it.title} — ${it.source}"
            } ?: ""

            val confidence = analysis.outlook.confidence
            val techBonus =
                if (analysis.outlook.direction == TechniqueVerdict.BULLISH) confidence * 0.25 else 0.0

            Deep(
                s = s,
                finalScore = s.raw + techBonus + newsScore * 2.5 + extPct.coerceIn(-4.0, 4.0) * 1.5,
                price = price,
                // No measured ATR -> no candidate. A fabricated 2% stand-in
                // would flow into a displayed stop distance.
                atr = Indicators.atr(candles, 14) ?: return null,
                supports = analysis.srData.supports,
                expectedHigh = analysis.outlook.expectedHigh,
                bullishCount = analysis.outlook.bullishCount,
                techTotal = analysis.results.size,
                confidence = confidence,
                newsScore = newsScore,
                newsNote = newsNote,
                extNote = extNote
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun cashOnlyPlan(
        weekStart: String,
        investable: Double,
        held: Map<String, Double>,
        pulse: MarketRating?,
        reason: String,
        flowMeasured: Boolean
    ): NextWeekPlan {
        val marketNote = pulse?.let {
            val scorePart = it.score?.let { s -> "$s/100" } ?: "no score (incomplete data)"
            "Market pulse $scorePart — ${it.call}. ${it.headline}"
        }.orEmpty()
        return NextWeekPlan(
            weekStart = weekStart,
            builtOn = Dates.todayIso(),
            updatedAt = System.currentTimeMillis(),
            headline = "$reason Keep new buying power in cash.",
            marketNote = marketNote,
            sectors = emptyList(),
            stocks = emptyList(),
            investable = investable,
            cashLeft = investable,
            portfolioNote = if (held.isEmpty()) {
                ""
            } else {
                "Your current positions stay under the portfolio review; this preview adds no new exposure."
            },
            actions = listOf(
                "Do not force a Monday buy list when dollar volume, CMF, MFI, and OBV do not confirm an inflow.",
                "Re-run the preview after the next completed session; a sector must clear the money-flow gate before its stocks can qualify."
            ),
            caveat = if (flowMeasured) {
                "A cash result is a valid engine result, not missing data. The flow report " +
                    "was measured, but no sector cleared the 3-of-4 confirmation rule."
            } else {
                "No sector or stock recommendation is issued until the S&P baseline and sector " +
                    "money-flow inputs can all be measured."
            }
        )
    }

    private fun toStock(
        d: Deep,
        amount: Double,
        share: Double,
        investable: Double,
        heldDollars: Double?
    ): NextWeekStock {
        val entry = round2(d.price)
        // The deep-read gate already proved that the board's measured range
        // offers upside; never manufacture a minimum target.
        val target = round2(d.expectedHigh)
        val expectedPct = round1((target / entry - 1.0) * 100.0)
        val structural = d.supports.filter { it < entry }.maxOrNull()
        val stop = round2(
            max(
                structural?.let { min(it - 0.5 * d.atr, entry - 1.0 * d.atr) }
                    ?: (entry - 1.8 * d.atr),
                entry * 0.92
            )
        )
        val riskPerShare = (entry - stop).coerceAtLeast(0.0)
        val rewardRisk = if (riskPerShare > 1e-9)
            round1((target - entry) / riskPerShare).coerceIn(0.0, 9.9) else 0.0

        val reasonParts = mutableListOf(
            String.format(Locale.US, "%+.1f%% in 5 days, %+.1f%% in 20", d.s.r5, d.s.r20),
            "${d.bullishCount} of ${d.techTotal} techniques bullish (${d.confidence}%)"
        )
        if (d.s.volumeRatio >= 1.2) {
            reasonParts += String.format(Locale.US, "%.1fx volume", d.s.volumeRatio)
        }
        if (d.s.sectorLabel.isNotEmpty()) {
            reasonParts += "${d.s.sectorLabel} theme"
        }

        return NextWeekStock(
            symbol = d.s.symbol,
            name = d.s.name,
            sectorLabel = d.s.sectorLabel,
            score = ((d.finalScore / SCORE_SCALE) * 100.0).toInt().coerceIn(5, 98),
            price = round2(d.price),
            entry = entry,
            target = target,
            stop = stop,
            rewardRisk = rewardRisk,
            expectedPct = expectedPct,
            amount = amount,
            allocationPct = allocationShare(amount, share, investable),
            techBullish = d.bullishCount,
            techTotal = d.techTotal,
            newsScore = d.newsScore,
            newsNote = d.newsNote,
            extNote = d.extNote,
            heldNote = heldDollars?.let {
                String.format(Locale.US, "Already in your book (~%s at cost) — an add, not a fresh buy.", money(it))
            } ?: "",
            reason = reasonParts.joinToString(", ")
        )
    }

    private fun allocationShare(amount: Double, share: Double, investable: Double): Double =
        if (investable > 0.0) round1(amount / investable * 100.0) else round1(share * 100.0)

    // ------------------------------------------------------------ helpers

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun money(v: Double): String = com.aurum.invest.core.Fmt.money(v)
}
