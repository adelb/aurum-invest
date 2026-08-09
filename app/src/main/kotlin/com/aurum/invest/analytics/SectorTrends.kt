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
            Triple("utilities", "Utilities & power", "XLU"),
            Triple("nuclear", "Uranium & nuclear", "URA"),
            Triple("solar", "Solar & clean energy", "TAN")
        )

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
                            val volumes = candles.map { it.volume.toDouble() }
                            val volIdx = volumes.size - 1
                            val vol20 = volumes.takeLast(20).average()
                            val volRatio = if (vol20 > 0.0) volumes[volIdx] / vol20 else 1.0
                            Partial(key, label, etf, r5, r20, volRatio)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
            if (raw.isEmpty()) return emptyList()

            // News tone for the leading movers only.
            val byMomentum = raw.sortedByDescending { it.r5 * 2.0 + it.r20 }
            val toneBySector = HashMap<String, Int>()
            coroutineScope {
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
                        toneBySector[p.key] = items.sumOf { it.sentiment }.coerceIn(-5, 5)
                    }
                }.awaitAll()
            }

            raw.map { p ->
                val tone = toneBySector[p.key] ?: 0
                val score = p.r5 * 2.0 + p.r20 * 0.8 +
                    ((p.volRatio - 1.0) * 8.0).coerceIn(-4.0, 8.0) + tone * 2.0
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
