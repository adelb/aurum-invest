package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Same-day movers scan: ranks the stocks most capable of a 3-10%+ up-move in
 * the current session. Two stages keep it fast over a ~100-name universe:
 *
 *  1. Cheap screen on cached daily candles — short-term momentum, latest-session
 *     volume surge, ATR capacity (can this name even move 3%+ in a day?),
 *     breakout proximity, and RSI headroom.
 *  2. Deep read on the ~12 finalists — the 20-technique analysis, live quote,
 *     pre/post-market prints, and the last 5 days of news with sentiment.
 *
 * Technique-bearish names are dropped; the survivors are ranked and the top
 * [count] returned with an honest expected-move range derived from ATR plus
 * the live catalysts. Never throws — failures skip the symbol.
 */
class DailyPicker(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        private const val CANDLE_RANGE_DAYS = 60
        private const val SCREEN_CHUNK = 10
        private const val FINALIST_CHUNK = 4
        private const val FINALISTS = 12
        /** Fixed score denominator — the realistic max of finalScore's parts. */
        private const val SCORE_SCALE = 110.0

        /** Serializes picks for the one-entry-per-day cache. */
        fun toJson(picks: List<DailyPick>): String {
            val arr = JSONArray()
            picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("date", p.date)
                    put("rank", p.rank)
                    put("symbol", p.symbol)
                    put("name", p.name)
                    put("score", p.score)
                    put("expectedLowPct", p.expectedLowPct)
                    put("expectedHighPct", p.expectedHighPct)
                    put("reason", p.reason)
                    put("price", p.price)
                    put("prevClose", p.prevClose)
                    put("dayChangePct", p.dayChangePct)
                    if (p.preMarketPct != null) put("preMarketPct", p.preMarketPct)
                    if (p.postMarketPct != null) put("postMarketPct", p.postMarketPct)
                    put("marketState", p.marketState)
                    put("techDirection", p.techDirection)
                    put("techBullish", p.techBullish)
                    put("techTotal", p.techTotal)
                    put("techConfidence", p.techConfidence)
                    put("volumeRatio", p.volumeRatio)
                    put("newsScore", p.newsScore)
                    put("headline", p.headline)
                    put("headlineSource", p.headlineSource)
                    put("headlineSentiment", p.headlineSentiment)
                })
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<DailyPick> = try {
            val arr = JSONArray(s)
            val out = ArrayList<DailyPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    DailyPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        score = o.getDouble("score"),
                        expectedLowPct = o.getDouble("expectedLowPct"),
                        expectedHighPct = o.getDouble("expectedHighPct"),
                        reason = o.optString("reason", ""),
                        price = o.getDouble("price"),
                        prevClose = o.optDouble("prevClose", 0.0),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        preMarketPct = if (o.has("preMarketPct")) o.getDouble("preMarketPct") else null,
                        postMarketPct = if (o.has("postMarketPct")) o.getDouble("postMarketPct") else null,
                        marketState = o.optString("marketState", ""),
                        techDirection = o.optString("techDirection", "NEUTRAL"),
                        techBullish = o.optInt("techBullish", 0),
                        techTotal = o.optInt("techTotal", 15),
                        techConfidence = o.optInt("techConfidence", 0),
                        volumeRatio = o.optDouble("volumeRatio", 1.0),
                        newsScore = o.optInt("newsScore", 0),
                        headline = o.optString("headline", ""),
                        headlineSource = o.optString("headlineSource", ""),
                        headlineSentiment = o.optInt("headlineSentiment", 0)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class Screened(
        val symbol: String,
        val name: String,
        val raw: Double,
        val atrPct: Double,
        val volumeRatio: Double,
        val r2: Double,
        val distFromHigh: Double
    )

    suspend fun computePicks(dateIso: String, count: Int = 5): List<DailyPick> {
        return try {
            // The fixed universe plus the market-wide screens — a same-day
            // mover scan that can only see mega-caps misses the day's movers.
            val screenNames = LinkedHashMap<String, String>()
            for (chunk in EntryPicker.MARKET_SCREENS.chunked(4)) {
                coroutineScope {
                    chunk.map { id ->
                        async {
                            try {
                                market.getScreener(id)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }.forEach { list ->
                    list.forEach { q ->
                        val ok = q.price in 2.0..2500.0 &&
                            q.avgVolume3M >= 1_000_000L &&
                            q.marketCap >= 300_000_000.0 &&
                            q.symbol.all { it.isLetterOrDigit() }
                        if (ok) screenNames.putIfAbsent(q.symbol, q.name)
                    }
                }
            }
            val candidates = (WeeklyPicker.UNIVERSE + WeeklyPicker.BUDGET_EXTRA +
                screenNames.entries.take(60).map { it.key to it.value })
                .distinctBy { it.first }

            // Stage 1 — cheap screen over cached daily candles.
            val candlesBySymbol = HashMap<String, List<Candle>>()
            for (chunk in candidates.chunked(SCREEN_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (symbol, _) ->
                        async {
                            val candles = try {
                                market.getDailyCandles(symbol, CANDLE_RANGE_DAYS)
                            } catch (_: Exception) {
                                emptyList()
                            }
                            symbol to candles
                        }
                    }.awaitAll()
                }
                for ((symbol, candles) in results) {
                    if (candles.size >= 21) candlesBySymbol[symbol] = candles
                }
            }

            val screened = candidates.mapNotNull { (symbol, name) ->
                val candles = candlesBySymbol[symbol] ?: return@mapNotNull null
                screen(symbol, name, candles)
            }
            if (screened.isEmpty()) return emptyList()

            val finalists = screened.sortedByDescending { it.raw }.take(FINALISTS)

            // Stage 2 — deep read per finalist.
            val deep = ArrayList<Deep>()
            for (chunk in finalists.chunked(FINALIST_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { s -> async { deepRead(s) } }.awaitAll()
                }
                results.filterNotNull().forEach { deep.add(it) }
            }
            if (deep.isEmpty()) return emptyList()

            // Keep names the 20 techniques do not read as bearish. When the
            // WHOLE list is bearish, return nothing — an empty screen is an
            // honest answer; a list of least-bad bearish names is not.
            val kept = deep.filter { it.techDirection != TechniqueVerdict.BEARISH.name }

            // Fixed scale, not min/max of survivors: the score means the same
            // thing on a hot day and a dead one.
            kept.sortedByDescending { it.finalScore }
                .take(count)
                .mapIndexed { index, d ->
                    val scaled = (d.finalScore / SCORE_SCALE * 100.0).coerceIn(5.0, 98.0)
                    d.toPick(dateIso, index + 1, round(scaled * 10.0) / 10.0)
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ stage 1

    private fun screen(symbol: String, name: String, candles: List<Candle>): Screened? {
        val closes = candles.map { it.close }
        val n = closes.size
        if (n < 21) return null
        val last = closes.last()
        if (last <= 0.0) return null
        val close2Ago = closes[n - 3]
        val close5Ago = closes[n - 6]
        if (close2Ago <= 0.0 || close5Ago <= 0.0) return null

        val r2 = (last / close2Ago - 1.0) * 100.0
        val r5 = (last / close5Ago - 1.0) * 100.0
        val rsi = Indicators.rsi(closes) ?: return null
        val atr = Indicators.atr(candles, 14) ?: return null
        val atrPct = atr / last * 100.0

        // The last bar may be today's in-progress session — its partial volume
        // would distort the surge read, so the last COMPLETED bar is used.
        // The 20-day base EXCLUDES the bar being measured, or a real surge
        // dilutes its own denominator.
        val volumes = candles.map { it.volume.toDouble() }
        val lastIsToday = com.aurum.invest.core.Dates.sameEtDay(candles.last().ts, System.currentTimeMillis())
        val volIdx = if (lastIsToday && volumes.size >= 2) volumes.size - 2 else volumes.size - 1
        val volLast = volumes[volIdx]
        val volBase = volumes.subList(max(0, volIdx - 20), volIdx)
        val vol20 = if (volBase.isNotEmpty()) volBase.average() else 0.0
        val volumeRatio = if (vol20 > 0.0) volLast / vol20 else 1.0

        val high20 = Indicators.recentHigh(closes, 20) ?: last
        val distFromHigh = if (high20 > 0.0) (high20 - last) / high20 * 100.0 else 0.0

        val momentum = (r2 * 2.0).coerceIn(-10.0, 14.0) + (r5 * 1.2).coerceIn(-8.0, 12.0)
        val volScore = ((volumeRatio - 1.0) * 10.0).coerceIn(0.0, 15.0)
        val atrScore = (atrPct * 3.0).coerceIn(0.0, 12.0)         // room to actually move 3%+
        val breakoutScore = (8.0 - distFromHigh * 1.2).coerceIn(0.0, 8.0)
        val rsiScore = (10.0 - abs(rsi - 57.5) / 3.0).coerceIn(0.0, 10.0)

        return Screened(
            symbol = symbol,
            name = name,
            raw = momentum + volScore + atrScore + breakoutScore + rsiScore,
            atrPct = atrPct,
            volumeRatio = volumeRatio,
            r2 = r2,
            distFromHigh = distFromHigh
        )
    }

    // ------------------------------------------------------------ stage 2

    private class Deep(
        val s: Screened,
        val finalScore: Double,
        val price: Double,
        val prevClose: Double,
        val dayChangePct: Double,
        val preMarketPct: Double?,
        val postMarketPct: Double?,
        val marketState: String,
        val techDirection: String,
        val techBullish: Int,
        val techTotal: Int,
        val techConfidence: Int,
        val newsScore: Int,
        val headline: String,
        val headlineSource: String,
        val headlineSentiment: Int,
        val expectedLowPct: Double,
        val expectedHighPct: Double,
        val reason: String
    ) {
        fun toPick(date: String, rank: Int, score: Double) = DailyPick(
            date = date,
            rank = rank,
            symbol = s.symbol,
            name = s.name,
            score = score,
            expectedLowPct = expectedLowPct,
            expectedHighPct = expectedHighPct,
            reason = reason,
            price = price,
            prevClose = prevClose,
            dayChangePct = dayChangePct,
            preMarketPct = preMarketPct,
            postMarketPct = postMarketPct,
            marketState = marketState,
            techDirection = techDirection,
            techBullish = techBullish,
            techTotal = techTotal,
            techConfidence = techConfidence,
            volumeRatio = round(s.volumeRatio * 10.0) / 10.0,
            newsScore = newsScore,
            headline = headline,
            headlineSource = headlineSource,
            headlineSentiment = headlineSentiment
        )
    }

    private suspend fun deepRead(s: Screened): Deep? {
        return try {
            // A full year so all 20 techniques (incl. the 200-day cross) can vote.
            val candles = try {
                market.getDailyCandles(s.symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < 30) return null

            val analysis = Techniques.analyze(s.symbol, candles)
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
            // One-letter tickers (F, T, M, B, V, C…) pull essentially random
            // headlines from the news feed — skip news for them entirely.
            val newsItems = if (s.symbol.length >= 2) {
                try {
                    news.getNews(s.symbol, candles)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val price = quote?.price ?: candles.last().close
            // When the cached last candle IS today's partial bar, its close is
            // today's price — falling back to it would make dayChange read ~0.
            val lastIsToday = com.aurum.invest.core.Dates.sameEtDay(
                candles.last().ts, System.currentTimeMillis()
            )
            val candleFallbackPrev =
                if (lastIsToday && candles.size >= 2) candles[candles.size - 2].close
                else candles.last().close
            val prevClose = quote?.prevClose ?: ext?.prevClose ?: candleFallbackPrev
            if (price <= 0.0) return null
            val dayChangePct =
                if (prevClose > 0.0) (price - prevClose) / prevClose * 100.0 else 0.0

            // ±3 and a 2x weight: on mega-caps the raw sum pins at the clamp
            // every day, which turns "news" into a permanent bonus for the
            // most-covered names rather than a signal.
            val newsScore = newsItems.sumOf { it.sentiment }.coerceIn(-3, 3)
            val top = newsItems.firstOrNull()

            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            val confidence = analysis?.outlook?.confidence ?: 0
            val bullishCount = analysis?.outlook?.bullishCount ?: 0
            val techTotal = analysis?.results?.size ?: 0

            val techBonus = when (direction) {
                TechniqueVerdict.BULLISH -> confidence * 0.25
                TechniqueVerdict.NEUTRAL -> 0.0
                TechniqueVerdict.BEARISH -> -confidence * 0.30
            }
            val pre = ext?.preMarketPct
            val post = ext?.postMarketPct
            val preBonus = pre?.let { (it * 3.0).coerceIn(-12.0, 12.0) } ?: 0.0
            val postBonus = post?.let { (it * 2.0).coerceIn(-6.0, 6.0) } ?: 0.0
            val newsBonus = newsScore * 2.0

            val finalScore = s.raw + techBonus + preBonus + postBonus + newsBonus

            // Expected-move range straight from measured volatility — no
            // manufactured floor. A stock whose ATR is 1.2% shows "1-3%",
            // not a fabricated "3-4%"; catalysts stretch the top only.
            val catalyst = max(0.0, preBonus) * 0.25 + max(0, newsScore) * 0.7 +
                max(0.0, (s.volumeRatio - 1.5)) * 1.2
            var hiPct = (s.atrPct * 2.2 + catalyst).coerceIn(1.0, 15.0)
            var loPct = (s.atrPct * 1.1).coerceIn(0.5, 10.0)
            if (hiPct - loPct < 1.0) hiPct = min(15.0, loPct + 1.0)
            loPct = round(loPct * 10.0) / 10.0
            hiPct = round(hiPct * 10.0) / 10.0

            val reason =
                buildReason(s, direction, bullishCount, techTotal, confidence, pre, post, newsScore)

            Deep(
                s = s,
                finalScore = finalScore,
                price = price,
                prevClose = prevClose,
                dayChangePct = dayChangePct,
                preMarketPct = pre,
                postMarketPct = post,
                marketState = quote?.marketState.orEmpty()
                    .ifEmpty { ext?.marketState.orEmpty() },
                techDirection = direction.name,
                techBullish = bullishCount,
                techTotal = techTotal,
                techConfidence = confidence,
                newsScore = newsScore,
                headline = top?.title ?: "",
                headlineSource = top?.source ?: "",
                headlineSentiment = top?.sentiment ?: 0,
                expectedLowPct = loPct,
                expectedHighPct = hiPct,
                reason = reason
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildReason(
        s: Screened,
        direction: TechniqueVerdict,
        bullishCount: Int,
        techTotal: Int,
        confidence: Int,
        pre: Double?,
        post: Double?,
        newsScore: Int
    ): String {
        val parts = mutableListOf<String>()
        if (pre != null && abs(pre) >= 0.3) {
            parts += String.format(Locale.US, "%+.1f%% pre-market", pre)
        } else if (post != null && abs(post) >= 0.3) {
            parts += String.format(Locale.US, "%+.1f%% post-market", post)
        }
        parts += String.format(Locale.US, "%+.1f%% in 2 days", s.r2)
        if (s.volumeRatio >= 1.2) {
            parts += String.format(Locale.US, "%.1fx volume", s.volumeRatio)
        }
        parts += when (direction) {
            TechniqueVerdict.BULLISH -> "$bullishCount of $techTotal techniques bullish ($confidence%)"
            TechniqueVerdict.NEUTRAL -> "techniques mixed"
            TechniqueVerdict.BEARISH -> "techniques lean bearish"
        }
        if (newsScore != 0) {
            parts += String.format(Locale.US, "news tone %+d", newsScore)
        }
        parts += if (s.distFromHigh <= 0.1) {
            "at its 20-day high"
        } else {
            String.format(Locale.US, "%.1f%% below the 20-day high", s.distFromHigh)
        }
        parts += String.format(Locale.US, "ATR %.1f%% of price", s.atrPct)
        return parts.joinToString(", ")
    }
}
