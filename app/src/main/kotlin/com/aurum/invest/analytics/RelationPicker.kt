package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.repo.MarketRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * First-party relations: for each of the market's giants, the stocks tied to
 * it by a REAL business link — its manufacturer, its suppliers, the companies
 * that build around its products. When a giant moves, these are the names
 * that move with it.
 *
 * The map itself is curated (a supplier relationship is a documented fact,
 * not a statistic), but every number shown is measured live: each pair
 * carries its actual 3-month daily-return correlation and both sides' real
 * moves this week. A curated link with a weak measured correlation is shown
 * honestly as weak.
 */

/** One stock that moves with a leader, with the measured evidence. */
data class RelatedMove(
    val symbol: String,
    val name: String,
    /** The concrete first-party link, e.g. "manufactures NVIDIA's chips". */
    val note: String,
    val price: Double,
    val dayChangePct: Double,
    /** Move over the last 5 trading days; null when history is short. */
    val weekChangePct: Double?,
    /** Pearson correlation of daily returns with the leader over ~3 months; null under 20 shared days. */
    val correlation: Double?
)

/** A big company and its first-party related stocks, all with live moves. */
data class RelationGroup(
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double,
    val weekChangePct: Double?,
    val related: List<RelatedMove>
)

private data class Rel(val symbol: String, val name: String, val note: String)

private data class Leader(val symbol: String, val name: String, val related: List<Rel>)

class RelationPicker(private val market: MarketRepository) {

    companion object {

        private const val CANDLE_RANGE_DAYS = 90
        private const val CHUNK_SIZE = 10
        private const val MIN_OVERLAP_DAYS = 20

        private val LEADERS: List<Leader> = listOf(
            Leader(
                "NVDA", "NVIDIA",
                listOf(
                    Rel("TSM", "Taiwan Semiconductor", "manufactures NVIDIA's chips"),
                    Rel("SMCI", "Super Micro Computer", "builds servers around NVIDIA GPUs"),
                    Rel("VRT", "Vertiv Holdings", "powers and cools GPU data centers"),
                    Rel("MU", "Micron Technology", "supplies HBM memory for its accelerators"),
                    Rel("ARM", "Arm Holdings", "licenses the CPU architecture in Grace chips"),
                    Rel("DELL", "Dell Technologies", "ships NVIDIA-based AI servers")
                )
            ),
            Leader(
                "AMD", "Advanced Micro Devices",
                listOf(
                    Rel("TSM", "Taiwan Semiconductor", "fabricates AMD's CPUs and GPUs"),
                    Rel("MU", "Micron Technology", "memory paired with AMD platforms"),
                    Rel("SMCI", "Super Micro Computer", "builds AMD Instinct AI servers"),
                    Rel("DELL", "Dell Technologies", "ships AMD EPYC servers")
                )
            ),
            Leader(
                "AAPL", "Apple",
                listOf(
                    Rel("TSM", "Taiwan Semiconductor", "sole maker of Apple silicon"),
                    Rel("AVGO", "Broadcom", "wireless chips in every iPhone"),
                    Rel("QCOM", "Qualcomm", "iPhone modem supplier"),
                    Rel("SWKS", "Skyworks Solutions", "iPhone radio-frequency chips"),
                    Rel("GLW", "Corning", "iPhone cover glass"),
                    Rel("JBL", "Jabil", "Apple contract manufacturing")
                )
            ),
            Leader(
                "MSFT", "Microsoft",
                listOf(
                    Rel("NVDA", "NVIDIA", "Azure's AI GPUs are its biggest hardware spend"),
                    Rel("AMD", "Advanced Micro Devices", "MI300 accelerators for Azure"),
                    Rel("VRT", "Vertiv Holdings", "data-center power for the Azure buildout"),
                    Rel("DELL", "Dell Technologies", "server hardware for Azure")
                )
            ),
            Leader(
                "GOOGL", "Alphabet",
                listOf(
                    Rel("AVGO", "Broadcom", "co-designs Google's TPU chips"),
                    Rel("NVDA", "NVIDIA", "GPUs for Google Cloud"),
                    Rel("ANET", "Arista Networks", "networking inside Google data centers")
                )
            ),
            Leader(
                "AMZN", "Amazon",
                listOf(
                    Rel("NVDA", "NVIDIA", "GPUs behind AWS AI capacity"),
                    Rel("AMD", "Advanced Micro Devices", "EPYC chips across AWS"),
                    Rel("VRT", "Vertiv Holdings", "AWS data-center infrastructure")
                )
            ),
            Leader(
                "META", "Meta Platforms",
                listOf(
                    Rel("NVDA", "NVIDIA", "Meta is one of its largest GPU buyers"),
                    Rel("ANET", "Arista Networks", "Meta's main data-center network supplier"),
                    Rel("VRT", "Vertiv Holdings", "power and cooling for Meta's AI clusters")
                )
            ),
            Leader(
                "TSLA", "Tesla",
                listOf(
                    Rel("ALB", "Albemarle", "lithium for Tesla batteries"),
                    Rel("ON", "ON Semiconductor", "silicon-carbide chips in Tesla drivetrains"),
                    Rel("STM", "STMicroelectronics", "power electronics for Tesla inverters")
                )
            ),
            Leader(
                "XOM", "Exxon Mobil",
                listOf(
                    Rel("SLB", "SLB (Schlumberger)", "oilfield services on Exxon projects"),
                    Rel("HAL", "Halliburton", "completion services for its shale programs"),
                    Rel("BKR", "Baker Hughes", "drilling equipment and services")
                )
            ),
            Leader(
                "BA", "Boeing",
                listOf(
                    Rel("SPR", "Spirit AeroSystems", "builds Boeing fuselages"),
                    Rel("GE", "GE Aerospace", "engines for the 737 MAX (CFM)"),
                    Rel("HWM", "Howmet Aerospace", "airframe fasteners and engine parts")
                )
            )
        )

        // ---- JSON (for the on-device cache) --------------------------------

        fun toJson(groups: List<RelationGroup>): String {
            val arr = JSONArray()
            for (g in groups) {
                val relArr = JSONArray()
                for (r in g.related) {
                    relArr.put(
                        JSONObject().apply {
                            put("symbol", r.symbol)
                            put("name", r.name)
                            put("note", r.note)
                            put("price", r.price)
                            put("dayChangePct", r.dayChangePct)
                            if (r.weekChangePct != null) put("weekChangePct", r.weekChangePct)
                            if (r.correlation != null) put("correlation", r.correlation)
                        }
                    )
                }
                arr.put(
                    JSONObject().apply {
                        put("symbol", g.symbol)
                        put("name", g.name)
                        put("price", g.price)
                        put("dayChangePct", g.dayChangePct)
                        if (g.weekChangePct != null) put("weekChangePct", g.weekChangePct)
                        put("related", relArr)
                    }
                )
            }
            return arr.toString()
        }

        fun fromJson(json: String): List<RelationGroup> = try {
            val arr = JSONArray(json)
            val out = ArrayList<RelationGroup>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val relArr = o.optJSONArray("related") ?: JSONArray()
                val related = ArrayList<RelatedMove>(relArr.length())
                for (j in 0 until relArr.length()) {
                    val r = relArr.optJSONObject(j) ?: continue
                    related.add(
                        RelatedMove(
                            symbol = r.getString("symbol"),
                            name = r.optString("name", ""),
                            note = r.optString("note", ""),
                            price = r.optDouble("price", 0.0),
                            dayChangePct = r.optDouble("dayChangePct", 0.0),
                            weekChangePct = if (r.has("weekChangePct")) r.getDouble("weekChangePct") else null,
                            correlation = if (r.has("correlation")) r.getDouble("correlation") else null
                        )
                    )
                }
                out.add(
                    RelationGroup(
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.optDouble("price", 0.0),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        weekChangePct = if (o.has("weekChangePct")) o.getDouble("weekChangePct") else null,
                        related = related
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Live read of every relation group, biggest weekly movers first, each
     * group's related names ordered by their measured correlation with the
     * leader. Never throws; symbols that fail to fetch are simply absent.
     */
    suspend fun computeGroups(): List<RelationGroup> {
        return try {
            val allSymbols = buildList {
                LEADERS.forEach { leader ->
                    add(leader.symbol)
                    leader.related.forEach { add(it.symbol) }
                }
            }.distinct()

            val candlesBySymbol = HashMap<String, List<Candle>>()
            for (chunk in allSymbols.chunked(CHUNK_SIZE)) {
                val results = coroutineScope {
                    chunk.map { symbol ->
                        async {
                            symbol to try {
                                market.getDailyCandles(symbol, CANDLE_RANGE_DAYS)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                for ((symbol, candles) in results) {
                    if (candles.isNotEmpty()) candlesBySymbol[symbol] = candles
                }
            }
            val quotes = try {
                market.getQuotes(allSymbols)
            } catch (_: Exception) {
                emptyMap()
            }

            val groups = LEADERS.mapNotNull { leader ->
                val leaderCandles = candlesBySymbol[leader.symbol] ?: return@mapNotNull null
                val leaderQuote = quotes[leader.symbol]
                val leaderPrice = leaderQuote?.price ?: leaderCandles.last().close
                if (leaderPrice <= 0.0) return@mapNotNull null

                val related = leader.related.mapNotNull { rel ->
                    val candles = candlesBySymbol[rel.symbol] ?: return@mapNotNull null
                    val quote = quotes[rel.symbol]
                    val price = quote?.price ?: candles.last().close
                    if (price <= 0.0) return@mapNotNull null
                    RelatedMove(
                        symbol = rel.symbol,
                        name = rel.name,
                        note = rel.note,
                        price = price,
                        dayChangePct = quote?.dayChangePct ?: lastDayPct(candles),
                        weekChangePct = weekChange(candles),
                        correlation = correlation(leaderCandles, candles)
                    )
                }.sortedByDescending { it.correlation ?: -2.0 }

                if (related.isEmpty()) return@mapNotNull null
                RelationGroup(
                    symbol = leader.symbol,
                    name = leader.name,
                    price = leaderPrice,
                    dayChangePct = leaderQuote?.dayChangePct ?: lastDayPct(leaderCandles),
                    weekChangePct = weekChange(leaderCandles),
                    related = related
                )
            }

            // The giants moving hardest this week lead the list — that is the
            // question this tab answers: WHO is moving, and who moves with them.
            groups.sortedByDescending { kotlin.math.abs(it.weekChangePct ?: 0.0) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Move over the last 5 trading days, percent; null when history is short. */
    private fun weekChange(candles: List<Candle>): Double? {
        val closes = candles.map { it.close }
        if (closes.size < 6) return null
        val base = closes[closes.size - 6]
        return if (base > 0.0) (closes.last() - base) / base * 100.0 else null
    }

    /** Last close-to-close move when no live quote is available. */
    private fun lastDayPct(candles: List<Candle>): Double {
        if (candles.size < 2) return 0.0
        val prev = candles[candles.size - 2].close
        return if (prev > 0.0) (candles.last().close - prev) / prev * 100.0 else 0.0
    }

    /**
     * Pearson correlation of daily returns, aligned by trading day. US daily
     * bars never cross UTC midnight, so the UTC day is a safe session key.
     */
    private fun correlation(a: List<Candle>, b: List<Candle>): Double? {
        val bByDay = b.associateBy { it.ts / 86_400_000L }
        val closesA = ArrayList<Double>(a.size)
        val closesB = ArrayList<Double>(a.size)
        for (candle in a) {
            val match = bByDay[candle.ts / 86_400_000L] ?: continue
            closesA.add(candle.close)
            closesB.add(match.close)
        }
        if (closesA.size < MIN_OVERLAP_DAYS + 1) return null
        val returnsA = Indicators.dailyReturns(closesA)
        val returnsB = Indicators.dailyReturns(closesB)
        return Indicators.pearson(returnsA, returnsB)
    }
}
