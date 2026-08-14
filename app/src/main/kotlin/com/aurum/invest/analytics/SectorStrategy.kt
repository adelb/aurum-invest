package com.aurum.invest.analytics

import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** How well the book already covers a trending theme. */
enum class GapStatus { MISSING, UNDER, COVERED, OVER }

/** One stock proposed to fill a sector gap. */
data class SectorPick(
    val symbol: String,
    val name: String,
    val price: Double,
    val r20Pct: Double,
    val rsi: Double,
    val techBullish: Int,
    val techTotal: Int,
    val entry: Double,
    val reason: String,
    /** Move over the last 3 trading days, percent. */
    val r3Pct: Double = 0.0,
    /** Latest completed session's volume vs its 20-day average. */
    val volumeRatio: Double = 0.0,
    /** Summed headline sentiment over the last 5 days, clamped -3..3. */
    val newsScore: Int = 0,
    /** The freshest report/insider headline ("" when none matched). */
    val newsNote: String = ""
)

/** A trending theme measured against what the portfolio already holds. */
data class SectorGap(
    val themeKey: String,
    val label: String,
    val etf: String,
    val r5Pct: Double,
    val r20Pct: Double,
    val heldPct: Double,        // share of the book already in this theme
    val targetPct: Double,      // suggested share
    val status: GapStatus,
    /** True when coverage came from exact holdings membership, not a sector map. */
    val coverageFromHoldings: Boolean,
    val picks: List<SectorPick>
)

/** One line of the weekly deployment plan. */
data class AllocationSlice(
    val themeKey: String,
    val label: String,
    val amount: Double,         // money to put in this week
    val sharePct: Double,       // share of this week's money
    val heldPct: Double,
    val lead: SectorPick?,      // the strongest stock, when one qualified
    /** The runner-up picks (2nd and 3rd strongest) that also passed the board. */
    val alternates: List<SectorPick> = emptyList()
)

/** The whole weekly answer: where the book is thin, and what to buy this week. */
data class WeeklyStrategy(
    val computedAt: Long,
    val investable: Double,
    val headline: String,
    val gaps: List<SectorGap>,
    val allocations: List<AllocationSlice>,
    val notes: List<String>
)

/**
 * Turns "what is trending" plus "what you already own" into one weekly answer:
 * which sectors the book is missing, which stock to use for each, and how to
 * split this week's money across them.
 *
 * Integrity rules:
 *  - Coverage is measured, never assumed. A theme with an unambiguous sector
 *    mapping uses the book's real sector weights; a cross-sector theme (AI,
 *    quantum, nuclear, solar) can only claim coverage from exact holdings
 *    membership, and says so via [SectorGap.coverageFromHoldings].
 *  - New money is steered toward trending themes the book is thin in — the
 *    weight is trend strength scaled by how much room is left.
 *  - A theme with no stock that passes the technique board contributes no
 *    allocation line; it is reported as a gap without a pick rather than
 *    filled with a name the engine does not actually like.
 */
class SectorStrategy(
    private val market: MarketRepository,
    private val news: NewsRepository? = null
) {

    companion object {
        private const val TOP_THEMES = 4
        private const val MIN_COVERAGE_PCT = 2.0
        private const val CANDLE_CHUNK = 8

        /** How many stocks each theme proposes — the 3 strongest that pass. */
        private const val PICKS_PER_THEME = 3

        /** Headlines that read as a report, analyst action, or insider move. */
        private val REPORT_KEYWORDS = listOf(
            "insider", "upgrade", "downgrade", "price target", "earnings",
            "beats", "misses", "guidance", "stake", "buyback", "acquire",
            "acquisition", "analyst", "rating", "initiates", "outlook",
            "quarterly", "results", "revenue", "10% owner", "ceo buys", "sec filing"
        )

        fun toJson(s: WeeklyStrategy): String = JSONObject().apply {
            put("computedAt", s.computedAt)
            put("investable", s.investable)
            put("headline", s.headline)
            put("notes", JSONArray(s.notes))
            put("gaps", JSONArray().apply {
                s.gaps.forEach { g ->
                    put(JSONObject().apply {
                        put("key", g.themeKey)
                        put("label", g.label)
                        put("etf", g.etf)
                        put("r5", g.r5Pct)
                        put("r20", g.r20Pct)
                        put("held", g.heldPct)
                        put("target", g.targetPct)
                        put("status", g.status.name)
                        put("fromHoldings", g.coverageFromHoldings)
                        put("picks", JSONArray().apply { g.picks.forEach { put(pickJson(it)) } })
                    })
                }
            })
            put("allocations", JSONArray().apply {
                s.allocations.forEach { a ->
                    put(JSONObject().apply {
                        put("key", a.themeKey)
                        put("label", a.label)
                        put("amount", a.amount)
                        put("share", a.sharePct)
                        put("held", a.heldPct)
                        a.lead?.let { put("lead", pickJson(it)) }
                        if (a.alternates.isNotEmpty()) {
                            put("alts", JSONArray().apply {
                                a.alternates.forEach { put(pickJson(it)) }
                            })
                        }
                    })
                }
            })
        }.toString()

        private fun pickJson(p: SectorPick): JSONObject = JSONObject().apply {
            put("symbol", p.symbol)
            put("name", p.name)
            put("price", p.price)
            put("r20", p.r20Pct)
            put("rsi", p.rsi)
            put("tb", p.techBullish)
            put("tt", p.techTotal)
            put("entry", p.entry)
            put("reason", p.reason)
            put("r3", p.r3Pct)
            put("vol", p.volumeRatio)
            put("news", p.newsScore)
            put("note", p.newsNote)
        }

        private fun pickFrom(o: JSONObject): SectorPick = SectorPick(
            symbol = o.getString("symbol"),
            name = o.optString("name", ""),
            price = o.optDouble("price", 0.0),
            r20Pct = o.optDouble("r20", 0.0),
            rsi = o.optDouble("rsi", 50.0),
            techBullish = o.optInt("tb", 0),
            techTotal = o.optInt("tt", 0),
            entry = o.optDouble("entry", 0.0),
            reason = o.optString("reason", ""),
            r3Pct = o.optDouble("r3", 0.0),
            volumeRatio = o.optDouble("vol", 0.0),
            newsScore = o.optInt("news", 0),
            newsNote = o.optString("note", "")
        )

        fun fromJson(s: String): WeeklyStrategy? = try {
            val o = JSONObject(s)
            val notes = ArrayList<String>()
            o.optJSONArray("notes")?.let { for (i in 0 until it.length()) notes.add(it.optString(i)) }

            val gaps = ArrayList<SectorGap>()
            o.optJSONArray("gaps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val picks = ArrayList<SectorPick>()
                    g.optJSONArray("picks")?.let { pa ->
                        for (j in 0 until pa.length()) {
                            pa.optJSONObject(j)?.let { picks.add(pickFrom(it)) }
                        }
                    }
                    gaps.add(
                        SectorGap(
                            themeKey = g.getString("key"),
                            label = g.optString("label", ""),
                            etf = g.optString("etf", ""),
                            r5Pct = g.optDouble("r5", 0.0),
                            r20Pct = g.optDouble("r20", 0.0),
                            heldPct = g.optDouble("held", 0.0),
                            targetPct = g.optDouble("target", 0.0),
                            status = runCatching { GapStatus.valueOf(g.optString("status")) }
                                .getOrDefault(GapStatus.MISSING),
                            coverageFromHoldings = g.optBoolean("fromHoldings", false),
                            picks = picks
                        )
                    )
                }
            }
            val allocations = ArrayList<AllocationSlice>()
            o.optJSONArray("allocations")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val alts = ArrayList<SectorPick>()
                    a.optJSONArray("alts")?.let { aa ->
                        for (j in 0 until aa.length()) {
                            aa.optJSONObject(j)?.let { alts.add(pickFrom(it)) }
                        }
                    }
                    allocations.add(
                        AllocationSlice(
                            themeKey = a.getString("key"),
                            label = a.optString("label", ""),
                            amount = a.optDouble("amount", 0.0),
                            sharePct = a.optDouble("share", 0.0),
                            heldPct = a.optDouble("held", 0.0),
                            lead = a.optJSONObject("lead")?.let { pickFrom(it) },
                            alternates = alts
                        )
                    )
                }
            }
            WeeklyStrategy(
                computedAt = o.optLong("computedAt", 0L),
                investable = o.optDouble("investable", 0.0),
                headline = o.optString("headline", ""),
                gaps = gaps,
                allocations = allocations,
                notes = notes
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [trends] ranked themes for the week, [book] the user's live portfolio,
     * [investable] the money to deploy this week.
     */
    suspend fun build(
        trends: List<SectorTrend>,
        book: BookContext,
        investable: Double
    ): WeeklyStrategy? {
        if (trends.isEmpty()) return null
        return try {
            val top = trends.take(TOP_THEMES)

            // Coverage per theme, measured two ways depending on what is honest.
            val coverage = top.associate { it.key to coverageOf(it.key, book) }

            // Targets: trend strength shared across the top themes, capped so no
            // single theme is ever proposed as more than a third of the book.
            val scoreSum = top.sumOf { it.score.coerceAtLeast(0.1) }
            val targets = top.associate { t ->
                val share = t.score.coerceAtLeast(0.1) / scoreSum
                t.key to (share * 100.0).coerceAtMost(33.0)
            }

            // Candidate stocks for every theme, in parallel across themes.
            val picksByTheme = coroutineScope {
                top.map { t -> async { t.key to bestPicks(t.key) } }.awaitAll()
            }.toMap()

            val gaps = top.map { t ->
                val (heldPct, fromHoldings) = coverage[t.key] ?: (0.0 to false)
                val target = targets[t.key] ?: 0.0
                SectorGap(
                    themeKey = t.key,
                    label = t.label,
                    etf = t.etf,
                    r5Pct = round1(t.r5Pct),
                    r20Pct = round1(t.r20Pct),
                    heldPct = round1(heldPct),
                    targetPct = round1(target),
                    status = when {
                        heldPct < MIN_COVERAGE_PCT -> GapStatus.MISSING
                        heldPct < target * 0.6 -> GapStatus.UNDER
                        heldPct > target * 1.6 -> GapStatus.OVER
                        else -> GapStatus.COVERED
                    },
                    coverageFromHoldings = fromHoldings,
                    picks = picksByTheme[t.key].orEmpty()
                )
            }.sortedWith(
                // Missing-and-trending first: that is the actionable end.
                compareBy({ it.status.ordinal }, { -it.r5Pct })
            )

            // This week's money: trend strength scaled by remaining room, so
            // themes the book already covers pull less new cash.
            val weights = gaps.mapNotNull { gap ->
                val lead = gap.picks.firstOrNull() ?: return@mapNotNull null
                val trend = top.first { it.key == gap.themeKey }
                val room = if (gap.targetPct <= 0.0) 0.0
                else ((gap.targetPct - gap.heldPct) / gap.targetPct).coerceIn(0.0, 1.0)
                val weight = trend.score.coerceAtLeast(0.1) * (0.2 + 0.8 * room)
                Triple(gap, lead, weight)
            }
            val weightSum = weights.sumOf { it.third }
            // Shares are computed even without a set budget, so the split is
            // still useful as percentages; amounts stay 0 until money is set.
            val allocations =
                if (weightSum <= 0.0) emptyList()
                else weights.map { (gap, lead, weight) ->
                    val share = weight / weightSum
                    AllocationSlice(
                        themeKey = gap.themeKey,
                        label = gap.label,
                        amount = round2(investable * share),
                        sharePct = round1(share * 100.0),
                        heldPct = gap.heldPct,
                        lead = lead,
                        alternates = gap.picks.drop(1)
                    )
                }.sortedByDescending { it.amount }

            WeeklyStrategy(
                computedAt = System.currentTimeMillis(),
                investable = investable,
                headline = headlineFor(gaps, book),
                gaps = gaps,
                allocations = allocations,
                notes = notesFor(gaps, book, allocations)
            )
        } catch (_: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- coverage

    /**
     * How much of the book sits in [themeKey], and whether that number came
     * from exact holdings membership (true) or the sector map (false).
     */
    private fun coverageOf(themeKey: String, book: BookContext): Pair<Double, Boolean> {
        if (book.isEmpty) return 0.0 to false
        val mapped = PortfolioLens.themeCoveragePct(themeKey, book)
        if (mapped != null) return mapped to false
        // No clean sector mapping — only exact membership can be claimed.
        val members = SectorTrends.WATCH[themeKey].orEmpty().map { it.first }.toSet()
        val pct = book.heldWeights.filterKeys { it in members }.values.sum()
        return pct to true
    }

    // ------------------------------------------------------------ stock picks

    /**
     * The theme's representative names, ranked; only technique-approved ones
     * survive, and the [PICKS_PER_THEME] strongest are proposed. "Strongest"
     * is measured, not guessed: the last 3 trading days' move, the latest
     * session's volume against its 20-day average, the 20-technique board,
     * RSI, and the last 5 days of headlines (reports, analyst actions,
     * insider flow) all feed the score.
     */
    private suspend fun bestPicks(themeKey: String): List<SectorPick> {
        val watch = SectorTrends.WATCH[themeKey].orEmpty()
        if (watch.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<SectorPick, Double>>()
        for (chunk in watch.chunked(CANDLE_CHUNK)) {
            val results = coroutineScope {
                chunk.map { (symbol, name) -> async { scorePick(symbol, name) } }.awaitAll()
            }
            results.filterNotNull().forEach { scored.add(it) }
        }
        return scored.sortedByDescending { it.second }.take(PICKS_PER_THEME).map { it.first }
    }

    private suspend fun scorePick(symbol: String, name: String): Pair<SectorPick, Double>? {
        return try {
            val candles = market.getDailyCandles(symbol, 180)
            if (candles.size < 30) return null
            val closes = candles.map { it.close }
            val price = closes.last()
            if (price <= 0.0) return null
            val rsi = Indicators.rsi(closes) ?: return null
            val atr = Indicators.atr(candles) ?: return null
            val n = closes.size
            val r20 = if (n > 21 && closes[n - 21] > 0.0) {
                (price / closes[n - 21] - 1.0) * 100.0
            } else 0.0
            // The last 3 trading days — how the stock is performing right now.
            val r3 = if (n > 4 && closes[n - 4] > 0.0) {
                (price / closes[n - 4] - 1.0) * 100.0
            } else 0.0

            // Latest completed session's volume vs its 20-day average. The
            // newest bar can be a partial live session with almost no volume
            // yet — fall back to the bar before it when that happens.
            val volumes = candles.map { it.volume }
            var volIdx = n - 1
            if (volIdx > 0 && volumes[volIdx] <= 0L) volIdx--
            val volBase = volumes.subList((volIdx - 20).coerceAtLeast(0), volIdx)
                .filter { it > 0L }
            val volumeRatio =
                if (volBase.isNotEmpty() && volumes[volIdx] > 0L) {
                    volumes[volIdx].toDouble() / volBase.average()
                } else 0.0

            val analysis = Techniques.analyze(symbol, candles)
            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            // A theme can trend while a given member is broken — drop those.
            if (direction == TechniqueVerdict.BEARISH) return null
            val bullish = analysis?.outlook?.bullishCount ?: 0
            val total = analysis?.results?.size ?: 0
            val confidence = analysis?.outlook?.confidence ?: 0

            // Reports, analyst actions, and insider flow from the last 5 days
            // of headlines. Sentiment is summed and clamped; the freshest
            // report-like headline is carried for the card.
            var newsScore = 0
            var newsNote = ""
            val items = news?.let { repo ->
                runCatching { repo.getNews(symbol, candles) }.getOrDefault(emptyList())
            }.orEmpty()
            if (items.isNotEmpty()) {
                newsScore = items.sumOf { it.sentiment }.coerceIn(-3, 3)
                newsNote = items.firstOrNull { item ->
                    val t = item.title.lowercase(Locale.US)
                    REPORT_KEYWORDS.any { t.contains(it) }
                }?.title.orEmpty()
            }

            // Overbought names get a pullback entry rather than a chase.
            val entry = if (rsi >= 68.0) price - atr * 0.5 else price
            val score = (r20 * 0.4).coerceIn(-12.0, 14.0) +
                (r3 * 1.2).coerceIn(-8.0, 10.0) +
                ((volumeRatio - 1.0) * 5.0).coerceIn(-3.0, 8.0) +
                (12.0 - abs(rsi - 58.0) / 2.0).coerceIn(0.0, 12.0) +
                (if (direction == TechniqueVerdict.BULLISH) confidence * 0.14 else 2.0) +
                newsScore * 2.0 +
                (if (newsNote.isNotBlank() && newsScore > 0) 3.0 else 0.0)

            val reason = buildString {
                append(String.format(Locale.US, "%+.1f%% in 3 days", r3))
                if (volumeRatio > 0.0) {
                    append(String.format(Locale.US, " on %.1fx volume", volumeRatio))
                }
                append(String.format(Locale.US, ", %+.1f%% over 20 days", r20))
                if (total > 0) append(", $bullish of $total techniques bullish")
                append(String.format(Locale.US, ", RSI %.0f", rsi))
                if (newsScore != 0) {
                    append(if (newsScore > 0) ", positive news" else ", negative news")
                }
                if (entry < price) {
                    append(String.format(Locale.US, " — extended, wait for $%.2f", entry))
                }
            }
            SectorPick(
                symbol = symbol,
                name = name,
                price = round2(price),
                r20Pct = round1(r20),
                rsi = round1(rsi),
                techBullish = bullish,
                techTotal = total,
                entry = round2(entry),
                reason = reason,
                r3Pct = round1(r3),
                volumeRatio = round1(volumeRatio),
                newsScore = newsScore,
                newsNote = newsNote
            ) to score
        } catch (_: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- copy

    private fun headlineFor(gaps: List<SectorGap>, book: BookContext): String {
        val missing = gaps.filter { it.status == GapStatus.MISSING && it.picks.isNotEmpty() }
        return when {
            book.isEmpty ->
                "Start with the strongest theme this week: ${gaps.firstOrNull()?.label ?: "—"}."
            missing.isEmpty() ->
                "Your book already covers this week's leading themes."
            missing.size == 1 ->
                "One trending theme is missing from your book: ${missing.first().label}."
            else ->
                "${missing.size} trending themes are missing from your book, led by ${missing.first().label}."
        }
    }

    private fun notesFor(
        gaps: List<SectorGap>,
        book: BookContext,
        allocations: List<AllocationSlice>
    ): List<String> {
        val out = mutableListOf<String>()
        gaps.firstOrNull { it.status == GapStatus.OVER }?.let { over ->
            out += String.format(
                Locale.US,
                "%s is already %.0f%% of the book against a %.0f%% target — this plan sends it less new money.",
                over.label, over.heldPct, over.targetPct
            )
        }
        val noPick = gaps.filter { it.picks.isEmpty() }
        if (noPick.isNotEmpty()) {
            out += "No allocation for ${noPick.joinToString(", ") { it.label }} — " +
                "the theme is trending but its representative names do not pass the technique board."
        }
        if (gaps.any { it.coverageFromHoldings }) {
            out += "Cross-sector themes (AI, quantum, nuclear, solar) have no clean sector " +
                "classification, so their coverage counts only stocks you hold from that theme's list."
        }
        if (allocations.isNotEmpty()) {
            out += "Amounts split this week's money by theme strength scaled by how much room " +
                "your book still has — they are a starting split, not a promise."
        }
        return out
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
