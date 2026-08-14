package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Power-hour scan: the 10 stocks to buy in the LAST 90 MINUTES of the regular
 * session (2:30-4:00 PM ET), positioned for next-day strength. The candidate
 * pool is the whole US market via Yahoo's predefined screens — the same net
 * the Entries scan casts — but the read is the opposite of an entry dip:
 *
 *  1. Pool — the market-wide screener lists, merged and deduped.
 *  2. Cheap pre-score for closing strength: up on the day but not a spike,
 *     finishing near the top of today's range, volume running hot against
 *     the 3-month average, in reach of the 52-week high, above the 50-day.
 *  3. Deep read on the ~28 best: the last 4 TRADING DAYS' behavior (total
 *     move, up-day consistency, acceleration into today), RSI in the
 *     momentum band, 4-day volume vs the 20-day average, breakout proximity,
 *     and the 20-technique board (bearish boards are dropped).
 *
 * Next-day potential is derived honestly from ATR plus the momentum read;
 * each pick carries a morning exit target and a hard stop under today's low.
 * Never throws — failures skip the symbol.
 */
class PowerPicker(
    private val market: MarketRepository,
    private val news: NewsRepository? = null
) {

    companion object {
        private const val SHORTLIST = 28
        private const val CANDLE_CHUNK = 7
        /** Fixed score denominator — the realistic max of finalScore's parts. */
        private const val SCORE_SCALE = 100.0

        fun toJson(picks: List<PowerPick>): String {
            val arr = JSONArray()
            picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("date", p.date)
                    put("rank", p.rank)
                    put("symbol", p.symbol)
                    put("name", p.name)
                    put("score", p.score)
                    put("price", p.price)
                    put("dayChangePct", p.dayChangePct)
                    put("r4Pct", p.r4Pct)
                    put("upDays", p.upDays)
                    put("closePosPct", p.closePosPct)
                    put("volumeRatio", p.volumeRatio)
                    put("expectedLowPct", p.expectedLowPct)
                    put("expectedHighPct", p.expectedHighPct)
                    put("target", p.target)
                    put("stop", p.stop)
                    put("rsi", p.rsi)
                    put("techDirection", p.techDirection)
                    put("techBullish", p.techBullish)
                    put("techTotal", p.techTotal)
                    put("techConfidence", p.techConfidence)
                    put("reason", p.reason)
                    put("newsScore", p.newsScore)
                    put("headline", p.headline)
                    put("headlineSource", p.headlineSource)
                })
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<PowerPick> = try {
            val arr = JSONArray(s)
            val out = ArrayList<PowerPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    PowerPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        score = o.getDouble("score"),
                        price = o.getDouble("price"),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        r4Pct = o.optDouble("r4Pct", 0.0),
                        upDays = o.optInt("upDays", 0),
                        closePosPct = o.optDouble("closePosPct", 0.0),
                        volumeRatio = o.optDouble("volumeRatio", 1.0),
                        expectedLowPct = o.optDouble("expectedLowPct", 1.0),
                        expectedHighPct = o.optDouble("expectedHighPct", 3.0),
                        target = o.getDouble("target"),
                        stop = o.getDouble("stop"),
                        rsi = o.optDouble("rsi", 50.0),
                        techDirection = o.optString("techDirection", "NEUTRAL"),
                        techBullish = o.optInt("techBullish", 0),
                        // 0, not 15: a failed board must not resurrect as 0/15.
                        techTotal = o.optInt("techTotal", 0),
                        techConfidence = o.optInt("techConfidence", 0),
                        reason = o.optString("reason", ""),
                        newsScore = o.optInt("newsScore", 0),
                        headline = o.optString("headline", ""),
                        headlineSource = o.optString("headlineSource", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun computePicks(dateIso: String, count: Int = 10): List<PowerPick> {
        return try {
            // Stage 1 — the same market-wide pool the Entries scan uses.
            val pool = HashMap<String, ScreenerQuote>()
            for (chunk in EntryPicker.MARKET_SCREENS.chunked(4)) {
                coroutineScope {
                    chunk.map { id -> async { market.getScreener(id) } }.awaitAll()
                }.forEach { list ->
                    list.forEach { q -> pool.putIfAbsent(q.symbol, q) }
                }
            }
            if (pool.isEmpty()) return emptyList()

            // Stage 2 — closing-strength pre-score.
            val shortlist = pool.values
                .filter(::tradeable)
                .mapNotNull { q -> preScore(q)?.let { q to it } }
                .sortedByDescending { it.second }
                .take(SHORTLIST)
            if (shortlist.isEmpty()) return emptyList()

            // Stage 3 — deep read per finalist.
            val deep = ArrayList<Deep>()
            for (chunk in shortlist.chunked(CANDLE_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (q, pre) -> async { deepRead(q, pre) } }.awaitAll()
                }
                results.filterNotNull().forEach { deep.add(it) }
            }
            if (deep.isEmpty()) return emptyList()

            // Fixed scale: the score means the same thing every day, instead
            // of rank 1 always reading 100 however weak the list.
            val ranked = deep.sortedByDescending { it.finalScore }.take(count)
            ranked.mapIndexed { index, d ->
                val scaled = (d.finalScore / SCORE_SCALE * 100.0).coerceIn(5.0, 98.0)
                d.toPick(dateIso, index + 1, round(scaled * 10.0) / 10.0)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ stage 2

    /** Liquid, sanely priced, showing green strength — not red, not a halted spike. */
    private fun tradeable(q: ScreenerQuote): Boolean =
        q.price in 2.0..2500.0 &&
            q.avgVolume3M >= 700_000L &&
            q.price * q.avgVolume3M >= 15_000_000.0 &&
            q.marketCap >= 300_000_000.0 &&
            q.dayChangePct > -1.0 && q.dayChangePct < 18.0 &&
            q.fiftyDayAvg > 0.0 &&
            q.symbol.all { it.isLetterOrDigit() }

    /**
     * Closing-strength score from screener fields alone. Null = no overnight
     * case: below the 50-day, or fading at the bottom of today's range.
     */
    private fun preScore(q: ScreenerQuote): Double? {
        val vs50 = (q.price / q.fiftyDayAvg - 1.0) * 100.0
        if (vs50 < -1.0) return null   // momentum plays live above the 50-day

        // Where is the price inside today's range? Near the high = conviction
        // close. Null = the screener sent no range — score it neutrally
        // instead of letting missing data impersonate a 50% close.
        val closePos = if (q.dayHigh > q.dayLow && q.dayLow > 0.0) {
            (q.price - q.dayLow) / (q.dayHigh - q.dayLow)
        } else null
        if (closePos != null && closePos < 0.45) return null   // fading into the close

        val strengthScore = when {
            q.dayChangePct in 0.5..8.0 -> 8.0 + (4.0 - kotlin.math.abs(q.dayChangePct - 4.0))
            q.dayChangePct > 8.0 -> 6.0      // strong but extended
            else -> 3.0                       // flat-green day
        }
        val closeScore = closePos?.let { ((it - 0.45) * 20.0).coerceIn(0.0, 11.0) } ?: 0.0
        // Missing volume is unknown, not zero — give it nothing either way,
        // but never let it pretend to be a measured dead tape.
        val volPace = if (q.avgVolume3M > 0L && q.dayVolume > 0L) {
            q.dayVolume.toDouble() / q.avgVolume3M
        } else 0.8
        val volScore = ((volPace - 0.8) * 6.0).coerceIn(0.0, 9.0)
        val off52 = if (q.fiftyTwoWeekHigh > 0.0) {
            (q.price / q.fiftyTwoWeekHigh - 1.0) * 100.0
        } else -50.0
        val breakoutScore = when {
            off52 > -3.0 -> 8.0    // knocking on the 52-week high
            off52 > -12.0 -> 5.0
            else -> 0.0
        }
        val trendScore = (vs50 * 0.3).coerceIn(0.0, 6.0)
        val ratingScore = q.analystRating?.let { (3.0 - it) * 2.0 }?.coerceIn(0.0, 4.0) ?: 0.0

        return strengthScore + closeScore + volScore + breakoutScore + trendScore + ratingScore
    }

    // ------------------------------------------------------------ stage 3

    private class Deep(
        val q: ScreenerQuote,
        val finalScore: Double,
        val r4Pct: Double,
        val upDays: Int,
        val closePosPct: Double,
        val volumeRatio: Double,
        val expectedLowPct: Double,
        val expectedHighPct: Double,
        val target: Double,
        val stop: Double,
        val rsi: Double,
        val techDirection: String,
        val techBullish: Int,
        val techTotal: Int,
        val techConfidence: Int,
        val reason: String,
        val newsScore: Int,
        val headline: String,
        val headlineSource: String,
        val livePrice: Double
    ) {
        fun toPick(date: String, rank: Int, score: Double) = PowerPick(
            date = date,
            rank = rank,
            symbol = q.symbol,
            name = q.name,
            score = score,
            price = round2(livePrice),
            dayChangePct = round1(q.dayChangePct),
            r4Pct = round1(r4Pct),
            upDays = upDays,
            closePosPct = round1(closePosPct),
            volumeRatio = round1(volumeRatio),
            expectedLowPct = round1(expectedLowPct),
            expectedHighPct = round1(expectedHighPct),
            target = round2(target),
            stop = round2(stop),
            rsi = round1(rsi),
            techDirection = techDirection,
            techBullish = techBullish,
            techTotal = techTotal,
            techConfidence = techConfidence,
            reason = reason,
            newsScore = newsScore,
            headline = headline,
            headlineSource = headlineSource
        )

        private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }

    private suspend fun deepRead(q: ScreenerQuote, pre: Double): Deep? {
        return try {
            val candles = try {
                market.getDailyCandles(q.symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < 30) return null
            // The screener price is up to 20 minutes old and still yesterday's
            // close after hours — for an overnight thesis, read the live print.
            val ext = try {
                market.getExtendedHours(q.symbol)
            } catch (_: Exception) {
                null
            }
            val price = ext?.livePrice ?: q.price
            if (price <= 0.0) return null

            // An overnight hold is the trade most exposed to an after-hours
            // print — a post-market slide is a warning the close can't show.
            val postPct = ext?.postMarketPct ?: 0.0

            // The last 4 COMPLETED trading days; today's in-progress bar (when
            // present) is excluded from the base and measured live via price.
            val lastIsToday = Dates.sameEtDay(candles.last().ts, System.currentTimeMillis())
            val completed = if (lastIsToday) candles.dropLast(1) else candles
            if (completed.size < 25) return null
            val cCloses = completed.map { it.close }
            val n = cCloses.size
            val base4 = cCloses[n - 4]
            if (base4 <= 0.0) return null
            val r4 = (price / base4 - 1.0) * 100.0

            // Up-day consistency over the SAME window as r4: the 3 completed
            // day-over-day moves plus today's live move.
            var upDays = 0
            for (i in n - 3 until n) {
                val prev = cCloses.getOrNull(i - 1) ?: continue
                if (prev > 0.0 && cCloses[i] > prev) upDays++
            }
            if (price > cCloses[n - 1]) upDays++
            val today = q.dayChangePct
            val avgPrior3 = ((cCloses[n - 1] / cCloses[n - 4] - 1.0) * 100.0) / 3.0
            val accelerating = today > avgPrior3 && today > 0.0

            val closes = candles.map { it.close }
            val rsi = Indicators.rsi(closes) ?: return null
            if (rsi > 88.0) return null   // blowoff — tomorrow's bag, not tomorrow's pop
            val atr = Indicators.atr(candles, 14) ?: return null
            if (atr <= 0.0) return null
            val atrPct = atr / price * 100.0

            // 4-day volume vs the PRIOR 20 days — excluding the 4 being
            // measured, or a real surge dilutes its own denominator.
            val vols = completed.map { it.volume.toDouble() }
            val vol4 = vols.takeLast(4).average()
            val vol20 = vols.dropLast(4).takeLast(20).average()
            val volumeRatio = if (vol20 > 0.0) vol4 / vol20 else 1.0

            val high20 = Indicators.recentHigh(cCloses, 20) ?: price
            val atBreakout = price >= high20 * 0.998

            val analysis = Techniques.analyze(q.symbol, candles)
            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            if (direction == TechniqueVerdict.BEARISH) return null
            val confidence = analysis?.outlook?.confidence ?: 0
            val bullishCount = analysis?.outlook?.bullishCount ?: 0
            val techTotal = analysis?.results?.size ?: 0

            // -1 = the screener sent no range today; the reason then says
            // nothing about the close position instead of inventing "50%".
            val closePos = if (q.dayHigh > q.dayLow && q.dayLow > 0.0) {
                (price - q.dayLow) / (q.dayHigh - q.dayLow) * 100.0
            } else -1.0

            val r4Score = (r4 * 1.5).coerceIn(0.0, 15.0)
            val consistencyScore = upDays * 2.5
            val accelScore = if (accelerating) 6.0 else 0.0
            val rsiScore = (10.0 - kotlin.math.abs(rsi - 66.0) / 2.5).coerceIn(0.0, 10.0)
            val volScore = ((volumeRatio - 1.0) * 8.0).coerceIn(0.0, 10.0)
            val breakoutScore = if (atBreakout) 7.0 else 0.0
            val boardScore = when (direction) {
                TechniqueVerdict.BULLISH -> confidence * 0.12
                else -> 2.0
            }
            // News: the overnight trade lives or dies on headlines the chart
            // cannot see. Bad tone vetoes; good tone earns points.
            val newsItems = if (news != null && q.symbol.length >= 2) {
                try {
                    news.getNews(q.symbol, candles)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val newsScore = newsItems.sumOf { it.sentiment }.coerceIn(-3, 3)
            if (newsScore <= -3) return null
            val headlineItem = newsItems.firstOrNull { it.sentiment != 0 } ?: newsItems.firstOrNull()

            val finalScore = pre * 0.7 + r4Score + consistencyScore + accelScore +
                rsiScore + volScore + breakoutScore + boardScore +
                newsScore * 2.5 + (postPct.coerceIn(-4.0, 4.0)) * 1.5

            // Next-day potential straight from measured volatility — no
            // manufactured floor: a quiet name shows a quiet range.
            val catalyst = (r4 * 0.15).coerceIn(0.0, 1.5) +
                (if (atBreakout) 1.0 else 0.0) +
                ((volumeRatio - 1.2).coerceAtLeast(0.0) * 0.8).coerceAtMost(1.5)
            var hiPct = (atrPct * 1.6 + catalyst).coerceIn(0.5, 12.0)
            var loPct = (atrPct * 0.7).coerceIn(0.2, 6.0)
            if (hiPct - loPct < 0.5) hiPct = min(12.0, loPct + 0.5)
            loPct = round(loPct * 10.0) / 10.0
            hiPct = round(hiPct * 10.0) / 10.0

            val target = price * (1.0 + hiPct / 100.0)
            val stop = max(
                if (q.dayLow > 0.0) q.dayLow - 0.5 * atr else price - 1.8 * atr,
                price * 0.90
            )
            if (stop >= price) return null

            var reason = buildReason(
                r4, upDays, closePos, volumeRatio, rsi, atBreakout,
                accelerating, direction, bullishCount, techTotal
            )
            if (newsScore != 0) {
                reason += String.format(Locale.US, ", news tone %+d", newsScore)
            }
            if (kotlin.math.abs(postPct) >= 1.0) {
                reason += String.format(Locale.US, ", after-hours %+.1f%%", postPct)
            }

            Deep(
                q = q,
                finalScore = finalScore,
                r4Pct = r4,
                upDays = upDays,
                closePosPct = closePos,
                volumeRatio = volumeRatio,
                expectedLowPct = loPct,
                expectedHighPct = hiPct,
                target = target,
                stop = stop,
                rsi = rsi,
                techDirection = direction.name,
                techBullish = bullishCount,
                techTotal = techTotal,
                techConfidence = confidence,
                reason = reason,
                newsScore = newsScore,
                headline = headlineItem?.title ?: "",
                headlineSource = headlineItem?.source ?: "",
                livePrice = price
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildReason(
        r4: Double,
        upDays: Int,
        closePos: Double,
        volumeRatio: Double,
        rsi: Double,
        atBreakout: Boolean,
        accelerating: Boolean,
        direction: TechniqueVerdict,
        bullishCount: Int,
        techTotal: Int
    ): String {
        val parts = mutableListOf<String>()
        parts += String.format(Locale.US, "%+.1f%% over the last 4 trading days (%d of 4 up)", r4, upDays)
        if (accelerating) parts += "accelerating into today"
        // Only claim a close position that was actually measured.
        if (closePos >= 0.0) {
            parts += String.format(Locale.US, "closing at %.0f%% of today's range", closePos)
        }
        if (volumeRatio >= 1.2) {
            parts += String.format(Locale.US, "%.1fx volume", volumeRatio)
        }
        if (atBreakout) parts += "at a 20-day breakout"
        parts += String.format(Locale.US, "RSI %.0f", rsi)
        if (direction == TechniqueVerdict.BULLISH && techTotal > 0) {
            parts += "$bullishCount of $techTotal techniques bullish"
        }
        return parts.joinToString(", ")
    }
}
