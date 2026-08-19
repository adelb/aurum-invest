package com.aurum.invest.analytics

import java.util.Locale
import kotlin.math.abs

/**
 * The one place the app derives sector targets, ticket rounding, and money
 * formatting for a deployment plan.
 *
 * Two engines answer allocation questions — [LiquidityAllocationEngine] ("where
 * does my uninvested cash go?") and [AllocationPlanEngine] ("where should the
 * whole book sit?"). They must never disagree about what a sector's target is,
 * so the derivation lives here once and both call it. Same idiom as
 * `PortfolioAdvisor.positionCapPct(...)`: one formula, no drift.
 *
 * Integrity rules:
 *  - a sector is only given a target when the book or the candidate set
 *    actually contains it — no invented sectors
 *  - a sector with no candidate to fill it can never be told to grow; its
 *    target pins to its current weight
 *  - an unmeasurable flow read scores the fixed neutral midpoint (50) and says
 *    so through the note, rather than being renormalized away
 *  - ticket rounding never rounds UP past the room it was given
 */
internal object AllocationMath {

    /** One sector's measured flow read: the source, the direction, the 0..100 score. */
    data class FlowRead(
        val flow: SectorFlow?,
        val verdict: FlowVerdict,
        val score: Double,
        /** False when neither a sector flow nor a sector trend could be measured. */
        val measured: Boolean
    )

    /**
     * The measured flow read for one Yahoo sector: an agreeing money-flow
     * verdict first, then the sector-trend scan, then an explicit neutral.
     * Disagreeing themes produce a NEUTRAL read at the fixed 50 midpoint —
     * the disagreement is a fact, not a reason to pick a side.
     */
    fun flowRead(
        sector: String,
        flowByKey: Map<String, SectorFlow>,
        trendByKey: Map<String, SectorTrend>
    ): FlowRead {
        val themeKeys = PortfolioLens.themesForSector(sector)
        val flows = themeKeys.mapNotNull { flowByKey[it] }
        return when {
            flows.isNotEmpty() && (flows.size == 1 || flows.all { it.verdict == flows.first().verdict }) -> {
                val f = flows.maxBy { it.flowScore }
                FlowRead(f, f.verdict, f.flowScore.toDouble(), measured = true)
            }
            flows.size >= 2 -> FlowRead(null, FlowVerdict.NEUTRAL, 50.0, measured = true)
            else -> {
                val trend = themeKeys.firstNotNullOfOrNull { trendByKey[it] }
                if (trend != null) {
                    FlowRead(
                        flow = null,
                        verdict = when {
                            trend.r5Pct >= 0.4 -> FlowVerdict.INFLOW
                            trend.r5Pct <= -0.4 -> FlowVerdict.OUTFLOW
                            else -> FlowVerdict.NEUTRAL
                        },
                        score = (50.0 + trend.score).coerceIn(0.0, 100.0),
                        measured = true
                    )
                } else {
                    FlowRead(null, FlowVerdict.NEUTRAL, 50.0, measured = false)
                }
            }
        }
    }

    /**
     * Sector targets as a share of [totalBase] (the money the caps are measured
     * against — invested + deployable cash).
     *
     * [sectorValueNow] is the dollars currently held per sector;
     * [candidateSectors] the sectors that actually have a buyable candidate
     * this run. A sector outside [candidateSectors] can only be told to hold or
     * shrink — proposing growth the engine cannot fill would be a fabricated
     * instruction.
     */
    fun sectorTargets(
        sectorValueNow: Map<String, Double>,
        totalBase: Double,
        candidateSectors: Set<String>,
        flowByKey: Map<String, SectorFlow>,
        trendByKey: Map<String, SectorTrend>,
        maxSectorPct: Double
    ): List<SectorAllocationTarget> {
        if (totalBase <= 0.0) return emptyList()
        val sectors = LinkedHashSet<String>().apply {
            sectorValueNow.keys.forEach { if (it.isNotBlank() && it != PortfolioLens.UNCLASSIFIED) add(it) }
            candidateSectors.forEach { if (it.isNotBlank() && it != PortfolioLens.UNCLASSIFIED) add(it) }
        }
        if (sectors.isEmpty()) return emptyList()

        return sectors.map { sector ->
            val curPct = ((sectorValueNow[sector] ?: 0.0) / totalBase) * 100.0
            val read = flowRead(sector, flowByKey, trendByKey)
            // Additive tilt, not a normalized share: it composes with the
            // weights the book already carries instead of rewriting them.
            val flowTilt = (read.score - 50.0) / 50.0            // -1..+1
            val bias = when (read.verdict) {
                FlowVerdict.INFLOW -> 1.0
                FlowVerdict.OUTFLOW -> -1.0
                FlowVerdict.NEUTRAL -> 0.0
            }
            val move = (flowTilt * 4.0 + bias * 2.0).coerceIn(-6.0, 6.0)
            val raw = (curPct + move).coerceIn(0.0, maxSectorPct)
            val fillable = sector in candidateSectors
            val target = if (!fillable && raw > curPct) curPct else raw
            SectorAllocationTarget(
                sector = sector,
                currentPct = round1(curPct),
                targetPct = round1(target),
                flow = read.verdict,
                note = sectorNote(sector, curPct, target, read, maxSectorPct, fillable)
            )
        }.sortedByDescending { abs(it.targetPct - it.currentPct) }
    }

    private fun sectorNote(
        sector: String,
        curPct: Double,
        targetPct: Double,
        read: FlowRead,
        maxSectorPct: Double,
        fillable: Boolean
    ): String {
        val delta = targetPct - curPct
        val flowFragment = when {
            !read.measured -> "no measured flow or trend read for this sector"
            read.flow != null ->
                "money flow ${read.flow.flowScore}/100, ${read.verdict.name.lowercase(Locale.US)}"
            read.verdict == FlowVerdict.INFLOW -> "sector trend read: inflow"
            read.verdict == FlowVerdict.OUTFLOW -> "sector trend read: outflow"
            else -> "flow read: neutral"
        }
        val capNote =
            if (targetPct >= maxSectorPct - 0.05) String.format(
                Locale.US, "; pinned at your %.0f%% sector cap", maxSectorPct
            ) else ""
        val fillNote =
            if (!fillable && delta <= 0.5) "; no candidate to add here this run" else ""
        val direction = when {
            delta > 0.5 -> String.format(Locale.US, "add toward %.1f%%", targetPct)
            delta < -0.5 -> String.format(Locale.US, "trim toward %.1f%%", targetPct)
            else -> "hold here"
        }
        return String.format(
            Locale.US,
            "%s sits at %.1f%% of the base; %s (%s)%s%s.",
            sector, curPct, direction, flowFragment, capNote, fillNote
        )
    }

    /** Round dollar amounts to a sane ticket. Callers must still clamp to their room. */
    fun roundToTicket(v: Double): Double = when {
        v <= 0.0 -> 0.0
        v < 100.0 -> round2(v)
        v < 1000.0 -> (Math.round(v / 5.0) * 5).toDouble()
        else -> (Math.round(v / 10.0) * 10).toDouble()
    }

    fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0

    fun money(v: Double): String =
        if (abs(v) >= 1000.0) String.format(Locale.US, "$%,.0f", v)
        else String.format(Locale.US, "$%.2f", v)
}
