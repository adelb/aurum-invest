package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Finds a shelf's "possible to blow next week" names: stocks pressing their
 * 20-day high on real volume with the week's momentum behind them. A name
 * only qualifies by MEASURED criteria — rising this week, volume above its
 * own average, within reach of the high — never "the best of a weak list";
 * a shelf with no setup returns an empty list.
 *
 * The survivors get a deeper look: the 35-technique board (a BEARISH board
 * disqualifies) and the last days of headlines, where analyst actions and
 * insider-flavored stories add conviction and are quoted on the card.
 * Never throws; symbols that fail to fetch are skipped.
 */
data class BreakoutCall(
    val symbol: String,
    val r5Pct: Double,           // 5-day move
    val volumeRatio: Double,     // last completed session vs 20-day average
    val distToHighPct: Double,   // % below the 20-day high; 0 = breaking out now
    val techBullish: Int,
    val techTotal: Int,
    val newsScore: Int,          // summed headline tone, clamped -3..3
    val newsNote: String,        // freshest report/insider headline, "" if none
    val reason: String
)

class BreakoutScout(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        /** Qualification bar — every leg is a real measurement. */
        private const val MIN_R5_PCT = 1.0
        private const val MIN_VOLUME_RATIO = 1.15
        private const val MAX_DIST_TO_HIGH_PCT = 3.0

        /** How many qualifiers get the deep look, and how many are shown. */
        private const val FINALISTS = 4
        private const val CALLS = 3

        private const val CHUNK = 8

        /** Headlines that read as a report, analyst action, or insider move. */
        private val REPORT_KEYWORDS = listOf(
            "insider", "upgrade", "downgrade", "price target", "earnings",
            "beats", "misses", "guidance", "stake", "buyback", "acquire",
            "acquisition", "analyst", "rating", "initiates", "outlook",
            "quarterly", "results", "revenue", "10% owner", "ceo buys", "sec filing"
        )
    }

    private class Setup(
        val symbol: String,
        val r5: Double,
        val volRatio: Double,
        val dist: Double,
        val score: Double
    )

    /** Scan [stocks] (symbol to name) and return at most [CALLS] setups, strongest first. */
    suspend fun scan(stocks: List<Pair<String, String>>): List<BreakoutCall> {
        if (stocks.isEmpty()) return emptyList()
        return try {
            // Decide who is even worth a full candle read from ONE batched
            // pull of the close lines. [measure] rejects anything under
            // MIN_R5_PCT anyway and that test needs closes alone, so the same
            // names survive — they just no longer cost a request each to
            // discard. A symbol the batch missed is kept, so a gap in the feed
            // narrows the scan rather than silently dropping a name from it.
            val series = market.getCloseSeries(stocks.map { it.first }, rangeDays = 30)
            val worthReading = stocks.filter { (symbol, _) ->
                val closes = series[symbol]?.map { it.close } ?: return@filter true
                if (closes.size < 21) return@filter true
                val last = closes.last()
                val c5 = closes[closes.size - 6]
                if (last <= 0.0 || c5 <= 0.0) return@filter true
                (last / c5 - 1.0) * 100.0 >= MIN_R5_PCT
            }
            if (worthReading.isEmpty()) return emptyList()

            val setups = ArrayList<Setup>()
            for (chunk in worthReading.chunked(CHUNK)) {
                val part = coroutineScope {
                    chunk.map { (symbol, _) -> async { measure(symbol) } }.awaitAll()
                }
                part.filterNotNull().forEach { setups.add(it) }
            }
            if (setups.isEmpty()) return emptyList()
            val finalists = setups.sortedByDescending { it.score }.take(FINALISTS)
            val deep = coroutineScope {
                finalists.map { s -> async { deepen(s) } }.awaitAll()
            }.filterNotNull()
            deep.sortedByDescending { it.second }.take(CALLS).map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The cheap pass over the browse's own cached 30-day candles. */
    private suspend fun measure(symbol: String): Setup? {
        return try {
            val candles = market.getDailyCandles(symbol, 30)
            if (candles.size < 21) return null
            val closes = candles.map { it.close }
            val n = closes.size
            val last = closes.last()
            val c5 = closes[n - 6]
            if (last <= 0.0 || c5 <= 0.0) return null
            val r5 = (last / c5 - 1.0) * 100.0

            val high20 = candles.subList(n - 20, n).maxOf { it.high }
            if (high20 <= 0.0) return null
            val dist = ((high20 - last) / high20 * 100.0).coerceAtLeast(0.0)

            // A live bar carries partial volume; after the close today's full
            // session belongs in the read.
            val volumes = candles.map { it.volume.toDouble() }
            val lastIsIncomplete = Dates.isCurrentEtDailyBarIncomplete(candles.last().ts)
            val volIdx =
                if (lastIsIncomplete && volumes.size >= 2) volumes.size - 2
                else volumes.size - 1
            val volBase = volumes
                .subList((volIdx - 20).coerceAtLeast(0), volIdx)
                .filter { it > 0.0 }
            val volRatio =
                if (volBase.isNotEmpty() && volumes[volIdx] > 0.0) {
                    volumes[volIdx] / volBase.average()
                } else 0.0

            if (r5 < MIN_R5_PCT) return null
            if (volRatio < MIN_VOLUME_RATIO) return null
            if (dist > MAX_DIST_TO_HIGH_PCT) return null

            val score = (r5 * 1.2).coerceAtMost(12.0) +
                ((volRatio - 1.0) * 6.0).coerceAtMost(9.0) +
                (MAX_DIST_TO_HIGH_PCT - dist) * 2.0
            Setup(symbol, r5, volRatio, dist, score)
        } catch (_: Exception) {
            null
        }
    }

    /** The deep pass: technique board plus headlines, only for finalists. */
    private suspend fun deepen(s: Setup): Pair<BreakoutCall, Double>? {
        return try {
            val candles = market.getDailyCandles(s.symbol, 180)
            var bullish = 0
            var total = 0
            var confidence = 0
            if (candles.size >= 30) {
                val analysis = Techniques.analyze(s.symbol, candles)
                val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
                // A setup against a bearish 35-technique board is a trap, not a call.
                if (direction == TechniqueVerdict.BEARISH) return null
                bullish = analysis?.outlook?.bullishCount ?: 0
                total = analysis?.results?.size ?: 0
                confidence =
                    if (direction == TechniqueVerdict.BULLISH) analysis?.outlook?.confidence ?: 0
                    else 0
            }

            var newsScore = 0
            var newsNote = ""
            val items = runCatching { news.getNews(s.symbol, candles) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) {
                newsScore = items.sumOf { it.sentiment }.coerceIn(-3, 3)
                newsNote = items.firstOrNull { item ->
                    val t = item.title.lowercase(Locale.US)
                    REPORT_KEYWORDS.any { t.contains(it) }
                }?.title.orEmpty()
            }

            val score = s.score + confidence * 0.1 + newsScore * 2.0 +
                (if (newsNote.isNotBlank() && newsScore > 0) 3.0 else 0.0)

            val reason = buildString {
                append(String.format(Locale.US, "%+.1f%% in 5 days on %.1fx volume", s.r5, s.volRatio))
                append(
                    if (s.dist <= 0.05) ", breaking its 20-day high"
                    else String.format(Locale.US, ", %.1f%% from its 20-day high", s.dist)
                )
                if (total > 0) append(", $bullish of $total techniques bullish")
                if (newsScore != 0) {
                    append(if (newsScore > 0) ", positive news" else ", negative news")
                }
            }
            BreakoutCall(
                symbol = s.symbol,
                r5Pct = round1(s.r5),
                volumeRatio = round1(s.volRatio),
                distToHighPct = round1(s.dist),
                techBullish = bullish,
                techTotal = total,
                newsScore = newsScore,
                newsNote = newsNote,
                reason = reason
            ) to score
        } catch (_: Exception) {
            null
        }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
