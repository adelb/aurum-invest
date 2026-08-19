package com.aurum.invest.analytics

import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Market-wide "best entry" scan: instead of the fixed ~100-name universe, the
 * candidate pool comes from Yahoo's predefined screeners (most actives,
 * gainers, losers, undervalued, small caps — several hundred unique names
 * across the whole US market). Three stages:
 *
 *  1. Pool — 8 screener lists merged and deduped (8 requests, cached).
 *  2. Cheap entry pre-score on screener fields alone: long trend intact
 *     (above the 200-day), pulled back to/below the 50-day but not broken,
 *     a real discount off the 52-week high, liquid, not chasing a green spike.
 *  3. Deep read on the ~28 best — a year of candles, RSI reset, dip off the
 *     20-day high, support proximity, ATR reward/risk to the nearest
 *     resistance, and the 35-technique board (bearish boards are dropped:
 *     that is a falling knife, not an entry).
 *
 * The top [count] are returned with an entry limit, target, stop, and the
 * numbers behind the read. Never throws — failures skip the symbol.
 */
class EntryPicker(private val market: MarketRepository) {

    companion object {
        /** Yahoo's market-wide saved screens; shared by every whole-market scan. */
        val MARKET_SCREENS = listOf(
            "most_actives",
            "day_gainers",
            "day_losers",
            "undervalued_large_caps",
            "undervalued_growth_stocks",
            "growth_technology_stocks",
            "aggressive_small_caps",
            "small_cap_gainers"
        )
        private const val SHORTLIST = 28
        private const val CANDLE_CHUNK = 7

        fun toJson(picks: List<EntryPick>): String {
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
                    put("entryLimit", p.entryLimit)
                    put("target", p.target)
                    put("stop", p.stop)
                    put("upsidePct", p.upsidePct)
                    put("riskPct", p.riskPct)
                    put("rewardRisk", p.rewardRisk)
                    put("rsi", p.rsi)
                    put("dipPct", p.dipPct)
                    put("vs50DayPct", p.vs50DayPct)
                    put("techDirection", p.techDirection)
                    put("techBullish", p.techBullish)
                    put("techTotal", p.techTotal)
                    put("techConfidence", p.techConfidence)
                    if (p.analystRating != null) put("analystRating", p.analystRating)
                    put("reason", p.reason)
                })
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<EntryPick> = try {
            val arr = JSONArray(s)
            val out = ArrayList<EntryPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    EntryPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        score = o.getDouble("score"),
                        price = o.getDouble("price"),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        entryLimit = o.getDouble("entryLimit"),
                        target = o.getDouble("target"),
                        stop = o.getDouble("stop"),
                        upsidePct = o.getDouble("upsidePct"),
                        riskPct = o.getDouble("riskPct"),
                        rewardRisk = o.optDouble("rewardRisk", 0.0),
                        rsi = o.optDouble("rsi", 50.0),
                        dipPct = o.optDouble("dipPct", 0.0),
                        vs50DayPct = o.optDouble("vs50DayPct", 0.0),
                        techDirection = o.optString("techDirection", "NEUTRAL"),
                        techBullish = o.optInt("techBullish", 0),
                        techTotal = o.optInt("techTotal", 15),
                        techConfidence = o.optInt("techConfidence", 0),
                        analystRating = if (o.has("analystRating")) o.getDouble("analystRating") else null,
                        reason = o.optString("reason", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun computePicks(dateIso: String, count: Int = 10): List<EntryPick> {
        return try {
            // Stage 1 — merge the market-wide screener lists into one pool.
            val pool = HashMap<String, ScreenerQuote>()
            for (chunk in MARKET_SCREENS.chunked(4)) {
                coroutineScope {
                    chunk.map { id -> async { market.getScreener(id) } }.awaitAll()
                }.forEach { list ->
                    list.forEach { q -> pool.putIfAbsent(q.symbol, q) }
                }
            }
            if (pool.isEmpty()) return emptyList()

            // Stage 2 — cheap pre-score; keep the strongest entry setups only.
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

            val ranked = deep.sortedByDescending { it.finalScore }.take(count)
            val minF = ranked.minOf { it.finalScore }
            val maxF = ranked.maxOf { it.finalScore }
            val span = maxF - minF
            ranked.mapIndexed { index, d ->
                val scaled = if (span > 0.0) 55.0 + (d.finalScore - minF) / span * 45.0 else 70.0
                d.toPick(dateIso, index + 1, round(scaled * 10.0) / 10.0)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ stage 2

    /** Liquid, sanely priced, plain common stock, not mid-crash and not spiking. */
    private fun tradeable(q: ScreenerQuote): Boolean =
        q.price in 2.0..2500.0 &&
            q.avgVolume3M >= 700_000L &&
            q.price * q.avgVolume3M >= 15_000_000.0 &&
            q.marketCap >= 300_000_000.0 &&
            q.dayChangePct > -9.0 && q.dayChangePct < 8.0 &&
            q.fiftyDayAvg > 0.0 && q.twoHundredDayAvg > 0.0 &&
            q.symbol.all { it.isLetterOrDigit() }

    /**
     * Entry quality from screener fields alone. Null = not an entry setup:
     * the long trend is broken, or the price is chasing above the 50-day.
     */
    private fun preScore(q: ScreenerQuote): Double? {
        val vs200 = (q.price / q.twoHundredDayAvg - 1.0) * 100.0
        val vs50 = (q.price / q.fiftyDayAvg - 1.0) * 100.0
        val off52 = if (q.fiftyTwoWeekHigh > 0.0) {
            (q.price / q.fiftyTwoWeekHigh - 1.0) * 100.0
        } else 0.0

        if (vs200 < -3.0) return null   // long trend broken — knife, not entry
        if (vs50 > 10.0) return null    // stretched — chasing, not entering

        val trendScore = (vs200 * 0.4).coerceIn(0.0, 10.0)
        // Sweet spot: a few percent below the 50-day average.
        val pullbackScore = when {
            vs50 < -8.0 -> 3.0
            vs50 <= 1.0 -> 10.0 - abs(vs50 + 3.5) * 1.3
            else -> 4.0 - (vs50 - 1.0) * 0.5
        }.coerceIn(0.0, 10.0)
        // A real discount off the 52-week high, but not a busted chart.
        val dipScore = when {
            off52 > -4.0 -> 2.0
            off52 >= -30.0 -> 8.0
            off52 >= -45.0 -> 4.0
            else -> 0.0
        }
        val ratingScore = q.analystRating?.let { (3.0 - it) * 3.0 }?.coerceIn(0.0, 6.0) ?: 0.0
        val calmScore = if (q.dayChangePct in -4.0..1.5) 3.0 else 0.0

        return trendScore + pullbackScore + dipScore + ratingScore + calmScore
    }

    // ------------------------------------------------------------ stage 3

    private class Deep(
        val q: ScreenerQuote,
        val finalScore: Double,
        val entryLimit: Double,
        val target: Double,
        val stop: Double,
        val upsidePct: Double,
        val riskPct: Double,
        val rewardRisk: Double,
        val rsi: Double,
        val dipPct: Double,
        val vs50: Double,
        val techDirection: String,
        val techBullish: Int,
        val techTotal: Int,
        val techConfidence: Int,
        val reason: String
    ) {
        fun toPick(date: String, rank: Int, score: Double) = EntryPick(
            date = date,
            rank = rank,
            symbol = q.symbol,
            name = q.name,
            score = score,
            price = round2(q.price),
            dayChangePct = round1(q.dayChangePct),
            entryLimit = round2(entryLimit),
            target = round2(target),
            stop = round2(stop),
            upsidePct = round1(upsidePct),
            riskPct = round1(riskPct),
            rewardRisk = round1(rewardRisk),
            rsi = round1(rsi),
            dipPct = round1(dipPct),
            vs50DayPct = round1(vs50),
            techDirection = techDirection,
            techBullish = techBullish,
            techTotal = techTotal,
            techConfidence = techConfidence,
            analystRating = q.analystRating,
            reason = reason
        )

        private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }

    private suspend fun deepRead(q: ScreenerQuote, pre: Double): Deep? {
        return try {
            // A full year so all 35 techniques (incl. the 200-day cross) can vote.
            val candles = try {
                market.getDailyCandles(q.symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < 60) return null
            val closes = candles.map { it.close }
            val price = q.price
            if (price <= 0.0) return null

            val rsi = Indicators.rsi(closes) ?: return null
            val atr = Indicators.atr(candles, 14) ?: return null
            if (atr <= 0.0) return null
            val high20 = Indicators.recentHigh(closes, 20) ?: price
            val dipPct = if (high20 > 0.0) (high20 - price) / high20 * 100.0 else 0.0
            val sma50 = Indicators.sma(closes, 50) ?: return null
            val sma50Prev = Indicators.sma(closes.dropLast(10), 50)
            val rising50 = sma50Prev != null && sma50 > sma50Prev
            val vs50 = (price / sma50 - 1.0) * 100.0

            val analysis = Techniques.analyze(q.symbol, candles)
            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            // A bearish board means the dip is still falling — skip it.
            if (direction == TechniqueVerdict.BEARISH) return null
            val confidence = analysis?.outlook?.confidence ?: 0
            val bullishCount = analysis?.outlook?.bullishCount ?: 0
            val techTotal = analysis?.results?.size ?: 0

            val supportBelow = analysis?.srData?.supports?.filter { it < price }?.maxOrNull()
            val resistanceAbove = analysis?.srData?.resistances?.filter { it > price }?.minOrNull()

            val stop = max(
                supportBelow?.let { it - 0.75 * atr } ?: (price - 2.2 * atr),
                price * 0.85
            )
            if (stop >= price) return null
            var target = resistanceAbove ?: max(high20, price + 2.0 * atr)
            if (target <= price * 1.005) target = max(high20, price + 2.0 * atr)
            val upsidePct = (target / price - 1.0) * 100.0
            val riskPct = (1.0 - stop / price) * 100.0
            val rewardRisk = (target - price) / (price - stop)

            // Already at support (within 1.2 ATR above it) = enter here; else a
            // patient limit toward support, at most 1.2 ATR below the price.
            val nearSupport = supportBelow != null && price - supportBelow <= 1.2 * atr
            val entryLimit = if (nearSupport) price else {
                max(supportBelow ?: (price - 0.8 * atr), price - 1.2 * atr)
            }

            val rsiScore = (12.0 - abs(rsi - 40.0) / 2.0).coerceIn(0.0, 12.0)
            val dip20Score = (10.0 - abs(dipPct - 6.5) * 1.4).coerceIn(0.0, 10.0)
            val supportScore = if (nearSupport) 8.0 else 0.0
            val trendScore = if (rising50) 5.0 else 0.0
            val rrScore = (rewardRisk * 3.0).coerceIn(0.0, 9.0)
            val boardScore = when (direction) {
                TechniqueVerdict.BULLISH -> confidence * 0.10
                else -> 2.0
            }
            val last = candles.last()
            val bounceScore = if (last.close > last.open) 2.0 else 0.0

            val finalScore = pre * 0.8 + rsiScore + dip20Score + supportScore +
                trendScore + rrScore + boardScore + bounceScore

            val reason = buildReason(
                rsi, dipPct, vs50, nearSupport, supportBelow, rising50,
                rewardRisk, direction, bullishCount, techTotal, q.analystRating
            )

            Deep(
                q = q,
                finalScore = finalScore,
                entryLimit = min(entryLimit, price),
                target = target,
                stop = stop,
                upsidePct = upsidePct,
                riskPct = riskPct,
                rewardRisk = rewardRisk,
                rsi = rsi,
                dipPct = dipPct,
                vs50 = vs50,
                techDirection = direction.name,
                techBullish = bullishCount,
                techTotal = techTotal,
                techConfidence = confidence,
                reason = reason
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildReason(
        rsi: Double,
        dipPct: Double,
        vs50: Double,
        nearSupport: Boolean,
        supportBelow: Double?,
        rising50: Boolean,
        rewardRisk: Double,
        direction: TechniqueVerdict,
        bullishCount: Int,
        techTotal: Int,
        analystRating: Double?
    ): String {
        val parts = mutableListOf<String>()
        parts += String.format(Locale.US, "RSI %.0f", rsi)
        if (dipPct >= 1.0) {
            parts += String.format(Locale.US, "%.1f%% off the 20-day high", dipPct)
        }
        parts += if (vs50 <= 0.0) {
            String.format(Locale.US, "%.1f%% below the 50-day average", -vs50)
        } else {
            String.format(Locale.US, "%.1f%% above the 50-day average", vs50)
        }
        if (nearSupport && supportBelow != null) {
            parts += String.format(Locale.US, "sitting on support at %.2f", supportBelow)
        }
        if (rising50) parts += "50-day average still rising"
        parts += String.format(Locale.US, "reward/risk %.1f", rewardRisk)
        if (direction == TechniqueVerdict.BULLISH && techTotal > 0) {
            parts += "$bullishCount of $techTotal techniques bullish"
        }
        if (analystRating != null && analystRating <= 2.5) {
            parts += String.format(Locale.US, "analysts at %.1f (Buy)", analystRating)
        }
        return parts.joinToString(", ")
    }
}
