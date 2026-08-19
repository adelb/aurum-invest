package com.aurum.invest.analytics

/**
 * Sector rotation read for the browse shelves: takes the shared
 * [SectorTrends] scan and answers, per catalog shelf, "is money flowing in
 * or out right now, and where does this shelf rank this week?"
 *
 * Pure math over already-fetched trend numbers — no network, never throws.
 * Honest-numbers rules: ranks come from the measured trend score; the "hot"
 * flame only ever marks shelves whose score is actually positive, so a week
 * where everything falls has no hot shelves rather than a least-bad winner.
 */

enum class RotationState { INFLOW, STEADY, OUTFLOW }

/** One catalog shelf's live trend, ranked against the other shelves. */
data class SectorPulse(
    val sectorName: String,      // StockCatalog shelf name
    val themeKey: String,
    val etf: String,             // the proxy ETF behind the numbers
    val rank: Int,               // 1 = strongest shelf this week
    val ofTotal: Int,            // how many shelves were ranked
    val r5Pct: Double,
    val r20Pct: Double,
    val volumeRatio: Double,
    val newsTone: Int,
    val score: Double,
    val state: RotationState,
    /** Top-3 by score AND actually rising — the flame badge. */
    val hot: Boolean
)

object SectorRotation {

    /** How many of the strongest shelves can wear the flame. */
    private const val HOT_COUNT = 3

    /**
     * Map catalog shelf name -> pulse, for every shelf whose theme appears in
     * [trends]. Shelves whose ETF failed to fetch are simply absent.
     */
    fun pulses(trends: List<SectorTrend>): Map<String, SectorPulse> {
        if (trends.isEmpty()) return emptyMap()
        val byKey = trends.associateBy { it.key }
        val matched = StockCatalog.SECTORS.mapNotNull { shelf ->
            byKey[shelf.themeKey]?.let { shelf.name to it }
        }
        if (matched.isEmpty()) return emptyMap()
        val ranked = matched.sortedByDescending { (_, t) -> t.score }
        val total = ranked.size
        return ranked.mapIndexed { idx, (name, t) ->
            name to SectorPulse(
                sectorName = name,
                themeKey = t.key,
                etf = t.etf,
                rank = idx + 1,
                ofTotal = total,
                r5Pct = t.r5Pct,
                r20Pct = t.r20Pct,
                volumeRatio = t.volumeRatio,
                newsTone = t.newsTone,
                score = t.score,
                state = stateOf(t.r5Pct, t.r20Pct, t.volumeRatio),
                hot = idx < HOT_COUNT && t.score > 0.0
            )
        }.toMap()
    }

    /**
     * Rotation state from measured momentum: the 5-day move against the
     * month's per-week pace (r20/4), with a volume surge confirming the
     * direction money is actually pushing.
     */
    fun stateOf(r5Pct: Double, r20Pct: Double, volumeRatio: Double): RotationState {
        val pace = r5Pct - r20Pct / 4.0
        return when {
            r5Pct >= 0.4 && pace >= 0.3 -> RotationState.INFLOW
            r5Pct > 0.0 && volumeRatio >= 1.3 -> RotationState.INFLOW
            r5Pct <= -0.4 && pace <= -0.3 -> RotationState.OUTFLOW
            r5Pct < 0.0 && volumeRatio >= 1.3 -> RotationState.OUTFLOW
            else -> RotationState.STEADY
        }
    }
}
