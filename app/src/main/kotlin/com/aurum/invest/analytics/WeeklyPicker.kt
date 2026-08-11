package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.WeeklyPick
import com.aurum.invest.data.repo.MarketRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Ranks the 10 best weekly set-ups from a fixed universe of liquid US names
 * PLUS live market-wide candidates pulled from Yahoo's saved screens (most
 * actives, gainers, growth), so each refresh sees what is actually moving.
 * Signals: 5-day and 20-day momentum, RSI sweet band (40..65 best), volume surge
 * (5-day vs 20-day average), and distance from the 20-day high.
 * Never throws — symbols that fail to fetch are skipped; total failure yields an empty list.
 */
class WeeklyPicker(private val market: MarketRepository) {

    companion object {
        /** Yahoo saved screens that widen the weekly universe with live movers. */
        private val LIVE_SCREENS = listOf(
            "most_actives",
            "day_gainers",
            "growth_technology_stocks",
            "undervalued_growth_stocks"
        )

        /** How many live screener names may join the fixed universe per scan. */
        private const val LIVE_CAP = 50
        /** ~80 liquid large/mid-cap US names across sectors, plus gold miners. */
        val UNIVERSE: List<Pair<String, String>> = listOf(
            // Tech
            "AAPL" to "Apple",
            "MSFT" to "Microsoft",
            "NVDA" to "NVIDIA",
            "GOOGL" to "Alphabet",
            "AMZN" to "Amazon",
            "META" to "Meta Platforms",
            "TSLA" to "Tesla",
            "AVGO" to "Broadcom",
            "AMD" to "Advanced Micro Devices",
            "QCOM" to "Qualcomm",
            "ORCL" to "Oracle",
            "CRM" to "Salesforce",
            "ADBE" to "Adobe",
            "INTC" to "Intel",
            "MU" to "Micron Technology",
            "PLTR" to "Palantir Technologies",
            "SMCI" to "Super Micro Computer",
            "IBM" to "IBM",
            "CSCO" to "Cisco Systems",
            "TXN" to "Texas Instruments",
            "NOW" to "ServiceNow",
            "SNOW" to "Snowflake",
            "SHOP" to "Shopify",
            "XYZ" to "Block",
            "PYPL" to "PayPal",
            "COIN" to "Coinbase",
            // Financials
            "JPM" to "JPMorgan Chase",
            "BAC" to "Bank of America",
            "GS" to "Goldman Sachs",
            "MS" to "Morgan Stanley",
            "V" to "Visa",
            "MA" to "Mastercard",
            "AXP" to "American Express",
            "C" to "Citigroup",
            "WFC" to "Wells Fargo",
            // Healthcare
            "UNH" to "UnitedHealth Group",
            "JNJ" to "Johnson & Johnson",
            "LLY" to "Eli Lilly",
            "PFE" to "Pfizer",
            "MRK" to "Merck",
            "ABBV" to "AbbVie",
            "TMO" to "Thermo Fisher Scientific",
            // Energy
            "XOM" to "Exxon Mobil",
            "CVX" to "Chevron",
            "COP" to "ConocoPhillips",
            "SLB" to "SLB (Schlumberger)",
            "OXY" to "Occidental Petroleum",
            // Consumer
            "WMT" to "Walmart",
            "COST" to "Costco Wholesale",
            "TGT" to "Target",
            "HD" to "Home Depot",
            "LOW" to "Lowe's",
            "MCD" to "McDonald's",
            "SBUX" to "Starbucks",
            "NKE" to "Nike",
            "DIS" to "Walt Disney",
            "NFLX" to "Netflix",
            "UBER" to "Uber Technologies",
            "ABNB" to "Airbnb",
            "KO" to "Coca-Cola",
            "PEP" to "PepsiCo",
            "PG" to "Procter & Gamble",
            "CL" to "Colgate-Palmolive",
            // Industrials
            "BA" to "Boeing",
            "CAT" to "Caterpillar",
            "DE" to "Deere",
            "GE" to "GE Aerospace",
            "HON" to "Honeywell",
            "LMT" to "Lockheed Martin",
            "RTX" to "RTX",
            "UPS" to "United Parcel Service",
            "FDX" to "FedEx",
            "MMM" to "3M",
            // Telecom
            "T" to "AT&T",
            "VZ" to "Verizon",
            "TMUS" to "T-Mobile US",
            // Gold miners & royalties (the app tracks gold relations)
            "NEM" to "Newmont",
            "B" to "Barrick Mining",
            "AEM" to "Agnico Eagle Mines",
            "FNV" to "Franco-Nevada",
            "RGLD" to "Royal Gold",
            "WPM" to "Wheaton Precious Metals",
            "KGC" to "Kinross Gold",
            "AU" to "AngloGold Ashanti",
            "HMY" to "Harmony Gold"
        )

        /**
         * ~20 liquid, habitually low-priced US-listed names that widen the under-$25
         * candidate pool. The price filter at compute time is what guarantees < $25.
         */
        val BUDGET_EXTRA: List<Pair<String, String>> = listOf(
            "F" to "Ford Motor",
            "T" to "AT&T",
            "NOK" to "Nokia",
            "PLUG" to "Plug Power",
            "SOFI" to "SoFi Technologies",
            "NIO" to "NIO",
            "RIVN" to "Rivian Automotive",
            "LCID" to "Lucid Group",
            "AAL" to "American Airlines Group",
            "CCL" to "Carnival",
            "NCLH" to "Norwegian Cruise Line Holdings",
            "SNAP" to "Snap",
            "WBD" to "Warner Bros. Discovery",
            "M" to "Macy's",
            "RIG" to "Transocean",
            "TLRY" to "Tilray Brands",
            "VALE" to "Vale",
            "BBD" to "Banco Bradesco",
            "HBAN" to "Huntington Bancshares",
            "KEY" to "KeyCorp"
        )

        private const val CANDLE_RANGE_DAYS = 60
        private const val CHUNK_SIZE = 10
        private const val TOP_N = 10

        /**
         * Pre-filter margin over maxPrice applied to the last close, so a symbol
         * that closed slightly above the cap but trades below it now still gets a
         * live-quote check without quoting the whole universe.
         */
        private const val PRICE_PREFILTER_MARGIN = 1.15
    }

    private data class Scored(
        val symbol: String,
        val name: String,
        val raw: Double,
        val reason: String,
        val lastClose: Double
    )

    /**
     * Live candidates from the market-wide screens. Liquidity-gated so thin
     * names can't ride a one-day spike into the weekly list. With [maxPrice]
     * set, the gate loosens to what the under-$25 scan needs.
     */
    private suspend fun liveCandidates(maxPrice: Double? = null): List<Pair<String, String>> {
        return try {
            val pools = coroutineScope {
                LIVE_SCREENS.map { id ->
                    async {
                        try {
                            market.getScreener(id)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            pools.flatten()
                .asSequence()
                .filter { it.avgVolume3M >= 1_000_000L }
                .filter {
                    if (maxPrice != null) it.price in 1.0..maxPrice
                    else it.price >= 5.0 && it.marketCap >= 2_000_000_000.0
                }
                .distinctBy { it.symbol }
                .sortedByDescending { it.avgVolume3M }
                .take(LIVE_CAP)
                .map { it.symbol to it.name.ifEmpty { it.symbol } }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun computePicks(weekStart: String): List<WeeklyPick> {
        return try {
            val candidates = (UNIVERSE + liveCandidates()).distinctBy { it.first }

            // Fetch candles in chunks of 10 concurrent requests; skip symbols that fail.
            val candlesBySymbol = HashMap<String, List<Candle>>()
            for (chunk in candidates.chunked(CHUNK_SIZE)) {
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

            val scored = candidates.mapNotNull { (symbol, name) ->
                val candles = candlesBySymbol[symbol] ?: return@mapNotNull null
                scoreSymbol(symbol, name, candles)
            }
            if (scored.isEmpty()) return emptyList()

            val minRaw = scored.minOf { it.raw }
            val maxRaw = scored.maxOf { it.raw }
            val span = maxRaw - minRaw

            val top = scored.sortedByDescending { it.raw }.take(TOP_N)

            // Live prices for the winners only; fall back to last close per symbol.
            val quotes = try {
                market.getQuotes(top.map { it.symbol })
            } catch (_: Exception) {
                emptyMap()
            }

            top.mapIndexed { index, s ->
                val scaled = if (span > 0.0) (s.raw - minRaw) / span * 100.0 else 50.0
                WeeklyPick(
                    weekStart = weekStart,
                    rank = index + 1,
                    symbol = s.symbol,
                    name = s.name,
                    score = round(scaled * 10.0) / 10.0,
                    reason = s.reason,
                    priceAtPick = quotes[s.symbol]?.price ?: s.lastClose
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Weekly under-[maxPrice] watch: same scoring as [computePicks], over
     * UNIVERSE + BUDGET_EXTRA, keeping only names whose latest price (live
     * quote, else last close) is below [maxPrice]. Top [count], rank 1..count.
     */
    suspend fun computeBudgetPicks(
        weekStart: String,
        maxPrice: Double = 25.0,
        count: Int = 5
    ): List<WeeklyPick> {
        return try {
            val candidates =
                (UNIVERSE + BUDGET_EXTRA + liveCandidates(maxPrice = maxPrice * PRICE_PREFILTER_MARGIN))
                    .distinctBy { it.first }

            val candlesBySymbol = HashMap<String, List<Candle>>()
            for (chunk in candidates.chunked(CHUNK_SIZE)) {
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

            val scored = candidates.mapNotNull { (symbol, name) ->
                val candles = candlesBySymbol[symbol] ?: return@mapNotNull null
                val s = scoreSymbol(symbol, name, candles) ?: return@mapNotNull null
                if (s.lastClose < maxPrice * PRICE_PREFILTER_MARGIN) s else null
            }
            if (scored.isEmpty()) return emptyList()

            val minRaw = scored.minOf { it.raw }
            val maxRaw = scored.maxOf { it.raw }
            val span = maxRaw - minRaw

            // Live-quote check on a pool of the best pre-filtered names only.
            val pool = scored.sortedByDescending { it.raw }.take(count * 3)
            val quotes = try {
                market.getQuotes(pool.map { it.symbol })
            } catch (_: Exception) {
                emptyMap()
            }

            pool.map { s -> s to (quotes[s.symbol]?.price ?: s.lastClose) }
                .filter { (_, price) -> price > 0.0 && price < maxPrice }
                .take(count)
                .mapIndexed { index, (s, price) ->
                    val scaled = if (span > 0.0) (s.raw - minRaw) / span * 100.0 else 50.0
                    WeeklyPick(
                        weekStart = weekStart,
                        rank = index + 1,
                        symbol = s.symbol,
                        name = s.name,
                        score = round(scaled * 10.0) / 10.0,
                        reason = String.format(Locale.US, "$%.2f — %s", price, s.reason),
                        priceAtPick = price
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun scoreSymbol(symbol: String, name: String, candles: List<Candle>): Scored? {
        val closes = candles.map { it.close }
        val n = closes.size
        if (n < 21) return null
        val last = closes.last()
        if (last <= 0.0) return null
        val close5Ago = closes[n - 6]
        val close20Ago = closes[n - 21]
        if (close5Ago <= 0.0 || close20Ago <= 0.0) return null

        val r5 = (last / close5Ago - 1.0) * 100.0
        val r20 = (last / close20Ago - 1.0) * 100.0
        val rsi = Indicators.rsi(closes) ?: return null

        // Today's in-progress bar has partial volume — exclude it from the surge read.
        val volumes = candles.map { it.volume.toDouble() }
        val lastIsToday =
            com.aurum.invest.core.Dates.sameDay(candles.last().ts, System.currentTimeMillis())
        val volEnd = if (lastIsToday && volumes.size >= 2) volumes.size - 1 else volumes.size
        val vol5 = volumes.subList((volEnd - 5).coerceAtLeast(0), volEnd).average()
        val vol20 = volumes.subList((volEnd - 20).coerceAtLeast(0), volEnd).average()
        val volRatio = if (vol20 > 0.0) vol5 / vol20 else 1.0

        val high20 = Indicators.recentHigh(closes, 20) ?: last
        val distFromHigh = if (high20 > 0.0) (high20 - last) / high20 * 100.0 else 0.0

        // Component scores.
        val momentumScore = (r5 * 1.5).coerceIn(-15.0, 20.0) + (r20 * 0.75).coerceIn(-10.0, 15.0)
        val rsiScore = (12.0 - abs(rsi - 52.5) / 2.5).coerceIn(0.0, 12.0) // peaks in the 40..65 band
        val volScore = ((volRatio - 1.0) * 12.0).coerceIn(0.0, 12.0)
        val distScore = (10.0 - distFromHigh * 1.25).coerceIn(0.0, 10.0) // closer to the 20d high is better
        val raw = momentumScore + rsiScore + volScore + distScore

        val parts = mutableListOf<String>()
        parts += String.format(
            Locale.US,
            "%+.1f%% in 5 days and %+.1f%% in 20 on %.1fx volume",
            r5, r20, volRatio
        )
        parts += String.format(Locale.US, "RSI %.0f", rsi)
        parts += if (distFromHigh <= 0.1) {
            "at its 20-day high"
        } else {
            String.format(Locale.US, "%.1f%% below the 20-day high", distFromHigh)
        }

        return Scored(
            symbol = symbol,
            name = name,
            raw = raw,
            reason = parts.joinToString(", "),
            lastClose = last
        )
    }
}
