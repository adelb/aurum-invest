package com.aurum.invest.analytics

import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which corner of the market is hot this week? Each theme is proxied by a
 * liquid sector ETF; themes are ranked by 5-day and 20-day momentum, the
 * latest volume surge, and the tone of the theme's news. Never throws —
 * sectors that fail to fetch are skipped.
 */
data class SectorTrend(
    val key: String,
    val label: String,       // "AI & robotics", "Oil & gas", ...
    val etf: String,         // the proxy ETF
    val r5Pct: Double,       // 5-day return
    val r20Pct: Double,      // 20-day return
    val volumeRatio: Double, // latest completed session vs 20-day average
    val newsTone: Int,       // summed headline sentiment, clamped
    val score: Double,
    val reason: String
)

class SectorTrends(
    private val market: MarketRepository,
    private val news: NewsRepository
) {

    companion object {
        /** Theme -> proxy ETF. Ordered roughly by how often users ask about them. */
        val SECTORS: List<Triple<String, String, String>> = listOf(
            Triple("semis", "Semiconductors & AI hardware", "SMH"),
            Triple("ai", "AI & robotics", "BOTZ"),
            Triple("quantum", "Quantum computing", "QTUM"),
            Triple("software", "Software & big tech", "XLK"),
            Triple("oil", "Oil & gas", "XLE"),
            Triple("materials", "Materials & mining", "XLB"),
            Triple("goldminers", "Gold miners", "GDX"),
            Triple("banks", "Banks & finance", "XLF"),
            Triple("health", "Healthcare & pharma", "XLV"),
            Triple("biotech", "Biotech", "IBB"),
            Triple("defense", "Defense & aerospace", "ITA"),
            Triple("industrials", "Industrials", "XLI"),
            Triple("consumer", "Consumer & retail", "XLY"),
            Triple("media", "Media & telecom", "XLC"),
            Triple("autos", "Autos & EV", "DRIV"),
            Triple("utilities", "Utilities & power", "XLU"),
            Triple("nuclear", "Uranium & nuclear", "URA"),
            Triple("solar", "Solar & clean energy", "TAN")
        )

        /** Per theme: 4 representative stocks worth a look when the theme trends. */
        val WATCH: Map<String, List<Pair<String, String>>> = mapOf(
            "semis" to listOf(
                "NVDA" to "Nvidia", "AMD" to "AMD", "AVGO" to "Broadcom", "MU" to "Micron"
            ),
            "ai" to listOf(
                "PLTR" to "Palantir", "TSLA" to "Tesla",
                "ISRG" to "Intuitive Surgical", "SYM" to "Symbotic"
            ),
            "quantum" to listOf(
                "IONQ" to "IonQ", "RGTI" to "Rigetti",
                "QBTS" to "D-Wave Quantum", "QUBT" to "Quantum Computing"
            ),
            "software" to listOf(
                "MSFT" to "Microsoft", "GOOGL" to "Alphabet", "META" to "Meta", "CRM" to "Salesforce"
            ),
            "oil" to listOf(
                "XOM" to "Exxon Mobil", "CVX" to "Chevron", "COP" to "ConocoPhillips", "SLB" to "SLB"
            ),
            "materials" to listOf(
                "FCX" to "Freeport-McMoRan", "VALE" to "Vale", "NUE" to "Nucor", "ALB" to "Albemarle"
            ),
            "goldminers" to listOf(
                "NEM" to "Newmont", "B" to "Barrick Mining",
                "AEM" to "Agnico Eagle", "KGC" to "Kinross Gold"
            ),
            "banks" to listOf(
                "JPM" to "JPMorgan", "BAC" to "Bank of America",
                "GS" to "Goldman Sachs", "MS" to "Morgan Stanley"
            ),
            "health" to listOf(
                "LLY" to "Eli Lilly", "UNH" to "UnitedHealth",
                "JNJ" to "Johnson & Johnson", "ABBV" to "AbbVie"
            ),
            "biotech" to listOf(
                "VRTX" to "Vertex", "REGN" to "Regeneron", "GILD" to "Gilead", "MRNA" to "Moderna"
            ),
            "defense" to listOf(
                "LMT" to "Lockheed Martin", "RTX" to "RTX",
                "NOC" to "Northrop Grumman", "GE" to "GE Aerospace"
            ),
            "industrials" to listOf(
                "CAT" to "Caterpillar", "DE" to "Deere", "HON" to "Honeywell", "UPS" to "UPS"
            ),
            "consumer" to listOf(
                "WMT" to "Walmart", "COST" to "Costco", "HD" to "Home Depot", "MCD" to "McDonald's"
            ),
            "media" to listOf(
                "NFLX" to "Netflix", "DIS" to "Disney", "TMUS" to "T-Mobile", "SPOT" to "Spotify"
            ),
            "autos" to listOf(
                "TSLA" to "Tesla", "GM" to "General Motors", "F" to "Ford", "RIVN" to "Rivian"
            ),
            "utilities" to listOf(
                "NEE" to "NextEra", "VST" to "Vistra", "DUK" to "Duke Energy", "SO" to "Southern Co."
            ),
            "nuclear" to listOf(
                "CCJ" to "Cameco", "OKLO" to "Oklo", "SMR" to "NuScale", "LEU" to "Centrus"
            ),
            "solar" to listOf(
                "FSLR" to "First Solar", "ENPH" to "Enphase", "RUN" to "Sunrun", "SEDG" to "SolarEdge"
            )
        )

        /**
         * Reverse of [WATCH]: symbol -> (theme key, theme label), for engines
         * that need to know which tracked theme a candidate belongs to
         * without a network lookup. Covers all the watch names; a symbol on
         * two themes keeps the first (earlier themes rank higher in demand).
         */
        val SYMBOL_THEME: Map<String, Pair<String, String>> by lazy {
            buildMap {
                WATCH.forEach { (key, stocks) ->
                    val label = SECTORS.firstOrNull { it.first == key }?.second ?: key
                    stocks.forEach { (sym, _) -> putIfAbsent(sym, key to label) }
                }
            }
        }

        /** News tone is fetched only for the strongest movers to keep the scan fast. */
        private const val NEWS_TOP = 6

        fun toJson(trends: List<SectorTrend>): JSONArray {
            val arr = JSONArray()
            trends.forEach { t ->
                arr.put(JSONObject().apply {
                    put("key", t.key)
                    put("label", t.label)
                    put("etf", t.etf)
                    put("r5Pct", t.r5Pct)
                    put("r20Pct", t.r20Pct)
                    put("volumeRatio", t.volumeRatio)
                    put("newsTone", t.newsTone)
                    put("score", t.score)
                    put("reason", t.reason)
                })
            }
            return arr
        }

        fun fromJson(arr: JSONArray): List<SectorTrend> = try {
            val out = ArrayList<SectorTrend>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SectorTrend(
                        key = o.getString("key"),
                        label = o.getString("label"),
                        etf = o.getString("etf"),
                        r5Pct = o.getDouble("r5Pct"),
                        r20Pct = o.getDouble("r20Pct"),
                        volumeRatio = o.optDouble("volumeRatio", 1.0),
                        newsTone = o.optInt("newsTone", 0),
                        score = o.getDouble("score"),
                        reason = o.optString("reason", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** All sectors ranked best first. Empty on total failure. */
    suspend fun compute(): List<SectorTrend> {
        return try {
            val raw = coroutineScope {
                SECTORS.map { (key, label, etf) ->
                    async {
                        try {
                            val candles = market.getDailyCandles(etf, 60)
                            if (candles.size < 21) return@async null
                            val closes = candles.map { it.close }
                            val n = closes.size
                            val last = closes.last()
                            val c5 = closes[n - 6]
                            val c20 = closes[n - 21]
                            if (last <= 0.0 || c5 <= 0.0 || c20 <= 0.0) return@async null
                            val r5 = (last / c5 - 1.0) * 100.0
                            val r20 = (last / c20 - 1.0) * 100.0
                            // A live bar has partial volume. After the close,
                            // today's completed bar must remain in the read.
                            val volumes = candles.map { it.volume.toDouble() }
                            val lastIsIncomplete =
                                com.aurum.invest.core.Dates.isCurrentEtDailyBarIncomplete(
                                    candles.last().ts
                                )
                            val volIdx =
                                if (lastIsIncomplete && volumes.size >= 2) volumes.size - 2
                                else volumes.size - 1
                            val volBase = volumes
                                .subList((volIdx - 20).coerceAtLeast(0), volIdx)
                                .filter { it > 0.0 }
                            val volRatio =
                                if (volBase.isNotEmpty() && volumes[volIdx] > 0.0) {
                                    volumes[volIdx] / volBase.average()
                                } else 1.0
                            Partial(key, label, etf, r5, r20, volRatio)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
            if (raw.isEmpty()) return emptyList()

            // News tone for the leading movers only. Results come back
            // through awaitAll — no shared map mutated across IO threads.
            val byMomentum = raw.sortedByDescending { it.r5 * 2.0 + it.r20 }
            val toneBySector: Map<String, Int> = coroutineScope {
                byMomentum.take(NEWS_TOP).map { p ->
                    async {
                        val items = try {
                            news.getTopicNews(
                                query = "${p.label} stocks",
                                cacheKey = "sector-${p.key}",
                                tag = p.etf
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                        p.key to items.sumOf { it.sentiment }.coerceIn(-5, 5)
                    }
                }.awaitAll()
            }.toMap()

            raw.map { p ->
                val tone = toneBySector[p.key] ?: 0
                // Tone stays OUT of the ranking score: only the top movers
                // get a tone fetched, so a ±10 term would rank the measured
                // few against the unmeasured many on feed luck. It is still
                // displayed and folded into the reason text.
                val score = p.r5 * 2.0 + p.r20 * 0.8 +
                    ((p.volRatio - 1.0) * 8.0).coerceIn(-4.0, 8.0)
                SectorTrend(
                    key = p.key,
                    label = p.label,
                    etf = p.etf,
                    r5Pct = round1(p.r5),
                    r20Pct = round1(p.r20),
                    volumeRatio = round1(p.volRatio),
                    newsTone = tone,
                    score = round1(score),
                    reason = buildReason(p, tone)
                )
            }.sortedByDescending { it.score }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private class Partial(
        val key: String,
        val label: String,
        val etf: String,
        val r5: Double,
        val r20: Double,
        val volRatio: Double
    )

    private fun buildReason(p: Partial, tone: Int): String {
        val parts = mutableListOf(
            String.format(Locale.US, "%+.1f%% in 5 days, %+.1f%% in 20 (%s)", p.r5, p.r20, p.etf)
        )
        if (p.volRatio >= 1.2) {
            parts += String.format(Locale.US, "%.1fx volume", p.volRatio)
        }
        if (tone != 0) {
            parts += String.format(Locale.US, "news tone %+d", tone)
        }
        return parts.joinToString(", ")
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
