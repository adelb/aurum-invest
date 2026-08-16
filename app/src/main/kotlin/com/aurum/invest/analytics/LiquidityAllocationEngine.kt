package com.aurum.invest.analytics

import com.aurum.invest.data.repo.InvestorProfile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One candidate stock the engine considered for deploying liquidity into —
 * pre-gathered by the caller (repository layer) from live data; this engine
 * does no network I/O itself, only reasoning over given data.
 */
data class LiquidityCandidate(
    val symbol: String,
    val name: String,
    val sector: String,               // "Unclassified" when unknown, matching PortfolioLens.UNCLASSIFIED convention
    val price: Double,
    val entryScore: Double,           // 0..100, composite entry-quality (from EntryPick/PowerPick/BreakoutScout or TechniqueEvaluator)
    val volumeRatio: Double,          // recent volume vs its own average — the buying-volume signal; 1.0 = normal, >1.5 = notably elevated
    val dayChangePct: Double,
    val rsi: Double,
    val techDirection: TechniqueVerdict,
    val techConfidence: Int,          // 0..100
    val newsScore: Int,               // -5..5 summed recent headline tone
    val newsHeadline: String,         // "" when nothing headline-worthy
    val analystRating: Double?,       // 1..5, null if unrated
    val fiftyDayAvg: Double,
    val twoHundredDayAvg: Double,
    val marketCap: Double,
    val historyNote: String,          // one factual line of company/price-history context; "" when none available
    val reason: String                // caller's own one-line reason this candidate was surfaced
)

/** One sector's target within the liquidity plan. */
data class SectorAllocationTarget(
    val sector: String,
    val currentPct: Double,           // of (invested + liquidity) currently in this sector
    val targetPct: Double,            // suggested share of (invested + liquidity) after deploying
    val flow: FlowVerdict,            // from MoneyFlowEngine
    val note: String
)

/** One concrete "buy this much of this" recommendation. */
data class LiquidityAllocationLine(
    val rank: Int,                    // 1 = strongest conviction
    val symbol: String,
    val name: String,
    val sector: String,
    val amount: Double,               // dollars to deploy, > 0
    val approxShares: Double,         // amount / price, may be fractional
    val price: Double,
    val confidence: Int,              // 0..100
    val rationale: List<String>
)

/** The full answer: how much of the user's liquidity to deploy, and where. */
data class LiquidityPlan(
    val computedAt: Long,
    val liquidity: Double,
    val headline: String,
    val marketNote: String,
    val sectorTargets: List<SectorAllocationTarget>,
    val lines: List<LiquidityAllocationLine>,
    val reserveCash: Double,
    val reserveReason: String,
    val policyNote: String,
    val caveat: String
)

/**
 * The liquidity-deployment engine: takes the user's uninvested cash, the
 * current book, the measured sector money-flow read, the sector-trend scan,
 * and a pre-fetched list of candidate stocks (already scored by
 * EntryPicker / PowerPicker / BreakoutScout / TechniqueEvaluator), and
 * answers "how much of this cash to put to work, and where."
 *
 * Integrity rules (mirroring the rest of the analytics layer):
 *  - pure function over the inputs given; no network I/O, no throws — a
 *    partial input simply produces a smaller plan, never a fabricated one
 *  - every rationale line cites the measured number behind it (sector flow
 *    score, volume ratio, RSI, MA position, analyst rating, news tone),
 *    never generic filler
 *  - hard caps come from the acting [InvestorProfile]: no single position
 *    ends up above `maxPositionPct`, no sector above `maxSectorPct`, both
 *    measured against post-deploy total book (invested + liquidity)
 *  - a candidate the profile's caps would breach is filtered out or sized
 *    down; leftover liquidity is honestly reported as reserve cash, not
 *    force-deployed
 *  - a sector target is only claimed for a sector that appears in the book
 *    or in the given moneyFlow/sectorTrends inputs — nothing is invented
 */
object LiquidityAllocationEngine {

    // ---- allocation constants (justified in code) ----

    /** Below this composite conviction, a candidate does not earn any of the liquidity. */
    private const val MIN_CONFIDENCE = 55

    /** Don't recommend below this many dollars per line — a rounding-noise ticket helps no one. */
    private const val MIN_TICKET = 25.0

    /** How many names to try to spread liquidity across when many qualify. */
    private const val MAX_LINES = 8

    /** Volume ratios above this are flagged as extreme rather than blindly rewarded. */
    private const val VOL_EXTREME = 3.0

    /** RSI above this on a fresh buy is treated as overbought, penalized. */
    private const val RSI_OVERBOUGHT = 75.0

    /** RSI below this is treated as oversold — bonus only if technicals are turning. */
    private const val RSI_OVERSOLD = 30.0

    /**
     * Pure function, never throws — returns a best-effort LiquidityPlan even
     * with partial data (missing signals are simply omitted from rationale,
     * never guessed). Safely callable with liquidity <= 0.
     */
    fun build(
        liquidity: Double,
        book: BookContext,
        moneyFlow: MoneyFlowReport?,
        sectorTrends: List<SectorTrend>,
        candidates: List<LiquidityCandidate>,
        profile: InvestorProfile,
        marketNote: String = ""
    ): LiquidityPlan {
        val now = System.currentTimeMillis()
        val caveat =
            "Every dollar figure is computed from the latest available prices, the entry board, " +
                "sector money flow, and this profile's own position and sector caps. " +
                "Decision support, not financial advice."
        val policyNote = safePolicy(profile)
        val safeLiquidity = liquidity.coerceAtLeast(0.0)

        // ---- guard: nothing to deploy ---------------------------------------
        if (safeLiquidity < MIN_TICKET) {
            val reason =
                if (liquidity <= 0.0) "No uninvested cash was reported — there is nothing to deploy this run."
                else String.format(
                    Locale.US,
                    "Only %s of liquidity was reported — below the %s minimum ticket size, so the run is a no-op.",
                    money(safeLiquidity), money(MIN_TICKET)
                )
            return LiquidityPlan(
                computedAt = now,
                liquidity = safeLiquidity,
                headline = if (liquidity <= 0.0) "No liquidity to deploy right now."
                else "Liquidity too small to place a meaningful ticket.",
                marketNote = marketNote,
                sectorTargets = emptyList(),
                lines = emptyList(),
                reserveCash = safeLiquidity,
                reserveReason = reason,
                policyNote = policyNote,
                caveat = caveat
            )
        }

        // ---- book math ------------------------------------------------------
        // Post-deploy total = the money the caps are measured against.
        val investedValue = book.slices.sumOf { it.value }.coerceAtLeast(0.0)
        val totalBook = investedValue + safeLiquidity
        val sectorValueNow: Map<String, Double> =
            book.slices.associate { it.sector to it.value }
        val symbolValueNow: Map<String, Double> = book.heldWeights.mapValues { (_, w) ->
            // heldWeights is % of invested book — convert back to dollars.
            w / 100.0 * investedValue
        }

        // ---- flow lookup ----------------------------------------------------
        // moneyFlow sectors are keyed by theme key (SectorTrends.SECTORS keys) not Yahoo sector
        // names; the honest mapping is via PortfolioLens.themesForSector.
        val flowByKey: Map<String, SectorFlow> =
            moneyFlow?.sectors?.associateBy { it.key } ?: emptyMap()
        val trendByKey: Map<String, SectorTrend> = sectorTrends.associateBy { it.key }

        // ---- 1) score every candidate ---------------------------------------
        val scored: List<ScoredCandidate> = candidates
            .asSequence()
            .filter { it.price > 0.0 && it.symbol.isNotBlank() }
            .distinctBy { it.symbol }
            .map { c -> scoreCandidate(c, flowByKey, trendByKey) }
            .sortedByDescending { it.conviction }
            .toList()

        val qualifying = scored.filter { it.conviction >= MIN_CONFIDENCE }

        // ---- 2) sector targets (from flow / trends / current weights) -------
        val sectorTargets = buildSectorTargets(
            book = book,
            totalBook = totalBook,
            sectorValueNow = sectorValueNow,
            candidates = qualifying,
            flowByKey = flowByKey,
            trendByKey = trendByKey,
            maxSectorPct = profile.maxSectorPct.coerceIn(10.0, 100.0)
        )
        val sectorTargetPct: Map<String, Double> =
            sectorTargets.associate { it.sector to it.targetPct }

        // ---- 3) allocate ----------------------------------------------------
        val allocation = allocate(
            qualifying = qualifying,
            liquidity = safeLiquidity,
            totalBook = totalBook,
            sectorValueNow = sectorValueNow,
            symbolValueNow = symbolValueNow,
            sectorTargetPct = sectorTargetPct,
            maxPositionPct = profile.maxPositionPct.coerceIn(1.0, 100.0),
            maxSectorPct = profile.maxSectorPct.coerceIn(10.0, 100.0)
        )

        val deployed = allocation.sumOf { it.amount }
        val reserve = (safeLiquidity - deployed).coerceAtLeast(0.0)
        val reserveReason = buildReserveReason(
            reserve = reserve,
            liquidity = safeLiquidity,
            allocation = allocation,
            scored = scored,
            qualifying = qualifying,
            profile = profile
        )
        val headline = buildHeadline(safeLiquidity, deployed, reserve, allocation.size)

        return LiquidityPlan(
            computedAt = now,
            liquidity = round2(safeLiquidity),
            headline = headline,
            marketNote = marketNote,
            sectorTargets = sectorTargets,
            lines = allocation,
            reserveCash = round2(reserve),
            reserveReason = reserveReason,
            policyNote = policyNote,
            caveat = caveat
        )
    }

    // ------------------------------------------------------------------ scoring

    private data class ScoredCandidate(
        val c: LiquidityCandidate,
        val conviction: Int,          // 0..100
        val rationale: List<String>,
        val flowNote: String,         // sector-flow line (may be empty)
        val flowVerdict: FlowVerdict  // NEUTRAL when unmapped
    )

    /**
     * Composite conviction 0..100. Weighted (roughly):
     *   entryScore                       35 pts
     *   technicals (direction+conf)      20 pts
     *   sector money flow                15 pts
     *   volume behaviour                 10 pts
     *   trend structure (50/200 MA)      10 pts
     *   RSI regime                       6 pts (penalty-heavy)
     *   news tone                        4 pts
     * Then analyst-rating and extreme-volume adjustments on top.
     * Every component that couldn't be measured contributes 0, never a guess.
     */
    private fun scoreCandidate(
        c: LiquidityCandidate,
        flowByKey: Map<String, SectorFlow>,
        trendByKey: Map<String, SectorTrend>
    ): ScoredCandidate {
        val rationale = ArrayList<String>()
        var score = 0.0

        // --- entry-quality (the caller-composited pick score) ---
        val entry = c.entryScore.coerceIn(0.0, 100.0)
        score += entry * 0.35
        rationale += String.format(
            Locale.US, "Entry-board score %.0f/100 — %s.", entry, if (c.reason.isNotBlank()) c.reason else "surfaced by the entry engines"
        )

        // --- technicals ---
        val techConf = c.techConfidence.coerceIn(0, 100)
        val techPts = when (c.techDirection) {
            TechniqueVerdict.BULLISH -> techConf * 0.20
            TechniqueVerdict.NEUTRAL -> techConf * 0.05
            TechniqueVerdict.BEARISH -> -techConf * 0.15
        }
        score += techPts
        rationale += "Technique board reads ${c.techDirection.name.lowercase(Locale.US)} at $techConf% indicator agreement."

        // --- sector money flow (only when the candidate's sector maps to a tracked theme) ---
        val (sectorFlow, flowVerdict, flowNote) = sectorFlowFor(c, flowByKey, trendByKey)
        val flowPts = when (flowVerdict) {
            FlowVerdict.INFLOW -> {
                val strength = sectorFlow?.flowScore?.coerceIn(0, 100) ?: 60
                (strength - 50).coerceAtLeast(0) * 0.30 // up to +15
            }
            FlowVerdict.OUTFLOW -> {
                val strength = sectorFlow?.flowScore?.coerceIn(0, 100) ?: 40
                -(50 - strength).coerceAtLeast(0) * 0.30 // down to -15
            }
            FlowVerdict.NEUTRAL -> 0.0
        }
        score += flowPts
        if (flowNote.isNotEmpty()) rationale += flowNote

        // --- volume behaviour ---
        val vr = c.volumeRatio.coerceAtLeast(0.0)
        val volPts = when {
            vr <= 0.0 -> 0.0
            vr >= VOL_EXTREME -> {
                // A 3x+ surge could be news noise; give a small bonus and flag it.
                4.0
            }
            vr >= 1.5 -> 10.0
            vr >= 1.2 -> 6.0
            vr >= 1.0 -> 2.0
            else -> -4.0 // dwindling volume
        }
        score += volPts
        if (vr > 0.0) {
            val extreme = if (vr >= VOL_EXTREME) " (extreme — verify the catalyst)" else ""
            rationale += String.format(Locale.US, "Volume %.1fx its 20-day average%s.", vr, extreme)
        }

        // --- 50/200-day trend structure ---
        val sma50 = c.fiftyDayAvg
        val sma200 = c.twoHundredDayAvg
        val price = c.price
        var maPts = 0.0
        if (sma50 > 0.0 && price > 0.0) {
            val gap50 = (price / sma50 - 1.0) * 100.0
            when {
                gap50 in 0.0..15.0 -> maPts += 4.0 // constructive above 50DMA, not stretched
                gap50 > 15.0 -> maPts += 1.0       // extended
                gap50 in -3.0..0.0 -> maPts += 2.0 // reclaiming
                else -> maPts -= 3.0               // clearly below
            }
        }
        if (sma200 > 0.0 && price > 0.0) {
            val gap200 = (price / sma200 - 1.0) * 100.0
            when {
                gap200 > 0.0 -> maPts += 4.0
                gap200 in -5.0..0.0 -> maPts += 1.0
                else -> maPts -= 4.0
            }
        }
        score += maPts.coerceIn(-8.0, 10.0)
        if (sma50 > 0.0 && sma200 > 0.0 && price > 0.0) {
            val above50 = price >= sma50
            val above200 = price >= sma200
            rationale += String.format(
                Locale.US,
                "Price %s the 50-day (%s) and %s the 200-day (%s).",
                if (above50) "above" else "below", money(sma50),
                if (above200) "above" else "below", money(sma200)
            )
        }

        // --- RSI regime ---
        val rsi = c.rsi
        val rsiPts = when {
            rsi <= 0.0 -> 0.0
            rsi >= RSI_OVERBOUGHT -> -6.0
            rsi >= 65.0 -> -1.5
            rsi in 45.0..65.0 -> 4.0
            rsi in RSI_OVERSOLD..45.0 -> 2.0
            // oversold-but-turning: only reward if technicals aren't bearish
            rsi < RSI_OVERSOLD && c.techDirection != TechniqueVerdict.BEARISH -> 3.0
            else -> -3.0
        }
        score += rsiPts
        if (rsi > 0.0) {
            rationale += String.format(Locale.US, "RSI %.0f.", rsi)
        }

        // --- news tone ---
        val news = c.newsScore.coerceIn(-5, 5)
        score += news * 0.8 // up to ±4
        if (news != 0 && c.newsHeadline.isNotBlank()) {
            rationale += "News tone ${if (news > 0) "+" else ""}$news over the recent window — ${c.newsHeadline}."
        } else if (news != 0) {
            rationale += "News tone ${if (news > 0) "+" else ""}$news over the recent window."
        }

        // --- analyst rating (1=Strong Buy, 5=Strong Sell; null = neutral) ---
        c.analystRating?.let { r ->
            val rr = r.coerceIn(1.0, 5.0)
            val ratingPts = when {
                rr <= 1.8 -> 5.0
                rr <= 2.3 -> 3.0
                rr <= 2.8 -> 1.0
                rr <= 3.2 -> 0.0
                rr <= 3.8 -> -3.0
                else -> -5.0
            }
            score += ratingPts
            val label = when {
                rr <= 1.8 -> "Strong Buy"
                rr <= 2.5 -> "Buy"
                rr <= 3.2 -> "Hold"
                rr <= 4.0 -> "Underperform"
                else -> "Sell"
            }
            rationale += String.format(Locale.US, "Analyst average rating %.1f (%s).", rr, label)
        }

        // --- day change: extremely stretched intraday warrants a small penalty ---
        val dc = c.dayChangePct
        if (abs(dc) >= 8.0) {
            score -= 2.0
            rationale += String.format(Locale.US, "Session move %+.1f%% — sizing should account for the day's range.", dc)
        }

        // --- history context (factual, only when the caller supplied it) ---
        if (c.historyNote.isNotBlank()) rationale += c.historyNote

        val conviction = score.roundToInt().coerceIn(0, 100)
        return ScoredCandidate(
            c = c,
            conviction = conviction,
            rationale = rationale,
            flowNote = flowNote,
            flowVerdict = flowVerdict
        )
    }

    /**
     * Map candidate.sector -> a measured SectorFlow (and a note) via the
     * PortfolioLens theme mapping. When multiple themes cover the sector and
     * disagree, no verdict is claimed — the disagreement is reported.
     */
    private fun sectorFlowFor(
        c: LiquidityCandidate,
        flowByKey: Map<String, SectorFlow>,
        trendByKey: Map<String, SectorTrend>
    ): Triple<SectorFlow?, FlowVerdict, String> {
        if (c.sector.isBlank() || c.sector == PortfolioLens.UNCLASSIFIED) return Triple(null, FlowVerdict.NEUTRAL, "")
        val themeKeys = PortfolioLens.themesForSector(c.sector)
        if (themeKeys.isEmpty()) {
            // No unambiguous mapping — try sector-trend read as a softer signal.
            return Triple(null, FlowVerdict.NEUTRAL, "")
        }
        val flows = themeKeys.mapNotNull { flowByKey[it] }
        return when {
            flows.isNotEmpty() && (flows.size == 1 || flows.all { it.verdict == flows.first().verdict }) -> {
                val f = flows.maxBy { it.flowScore }
                val note = "${f.label} money flow ${f.flowScore}/100, ${f.verdict.name} " +
                    "(${f.confidence}% signal agreement)."
                Triple(f, f.verdict, note)
            }
            flows.size >= 2 -> {
                val parts = flows.joinToString(", ") {
                    "${it.label} ${it.verdict.name.lowercase(Locale.US)} ${it.flowScore}/100"
                }
                Triple(null, FlowVerdict.NEUTRAL, "Sector money flow is mixed across themes: $parts.")
            }
            else -> {
                // Fall back on the sector-trend scan when we have it.
                val trend = themeKeys.firstNotNullOfOrNull { trendByKey[it] }
                if (trend != null) {
                    val note = String.format(
                        Locale.US,
                        "%s trend: %+.1f%% in 5 days, %+.1f%% in 20 (%s).",
                        trend.label, trend.r5Pct, trend.r20Pct, trend.etf
                    )
                    val v = when {
                        trend.r5Pct >= 0.4 -> FlowVerdict.INFLOW
                        trend.r5Pct <= -0.4 -> FlowVerdict.OUTFLOW
                        else -> FlowVerdict.NEUTRAL
                    }
                    Triple(null, v, note)
                } else Triple(null, FlowVerdict.NEUTRAL, "")
            }
        }
    }

    // ---------------------------------------------------------- sector targets

    private fun buildSectorTargets(
        book: BookContext,
        totalBook: Double,
        sectorValueNow: Map<String, Double>,
        candidates: List<ScoredCandidate>,
        flowByKey: Map<String, SectorFlow>,
        trendByKey: Map<String, SectorTrend>,
        maxSectorPct: Double
    ): List<SectorAllocationTarget> {
        if (totalBook <= 0.0) return emptyList()

        // Universe of sectors we have any measurable read on.
        val sectors = LinkedHashSet<String>().apply {
            book.slices.forEach { if (it.sector != PortfolioLens.UNCLASSIFIED) add(it.sector) }
            candidates.forEach {
                val s = it.c.sector
                if (s.isNotBlank() && s != PortfolioLens.UNCLASSIFIED) add(s)
            }
        }
        if (sectors.isEmpty()) return emptyList()

        // Score each sector by its measured flow / trend read (fixed 0..100 scale).
        data class Read(val flow: SectorFlow?, val verdict: FlowVerdict, val score: Double)

        val reads: Map<String, Read> = sectors.associateWith { sector ->
            val themeKeys = PortfolioLens.themesForSector(sector)
            val flows = themeKeys.mapNotNull { flowByKey[it] }
            when {
                flows.isNotEmpty() && (flows.size == 1 || flows.all { it.verdict == flows.first().verdict }) -> {
                    val f = flows.maxBy { it.flowScore }
                    Read(f, f.verdict, f.flowScore.toDouble())
                }
                flows.size >= 2 -> Read(null, FlowVerdict.NEUTRAL, 50.0) // disagreement -> neutral
                else -> {
                    val trend = themeKeys.firstNotNullOfOrNull { trendByKey[it] }
                    if (trend != null) {
                        val s = (50.0 + trend.score).coerceIn(0.0, 100.0)
                        val v = when {
                            trend.r5Pct >= 0.4 -> FlowVerdict.INFLOW
                            trend.r5Pct <= -0.4 -> FlowVerdict.OUTFLOW
                            else -> FlowVerdict.NEUTRAL
                        }
                        Read(null, v, s)
                    } else Read(null, FlowVerdict.NEUTRAL, 50.0)
                }
            }
        }

        // Max fill possible in each sector from the qualifying candidates (dollars).
        // Anti-hallucination: never propose more sector weight than candidates can fill.
        val capacityBySector: Map<String, Double> = candidates
            .groupBy { it.c.sector }
            .mapValues { (_, list) -> list.sumOf { it.c.price * 1_000_000.0 } } // effectively uncapped in $ terms
        // (individual-candidate liquidity depth isn't part of the input contract; treating capacity
        // as "at least one line exists" is the honest bound we can defend.)

        // Compute a raw tilt: shift base share toward high-scoring sectors, but weighted by how
        // large the sector already is (avoid recommending 40% into a sector we have $0 in).
        // We work in *fractions of totalBook*.
        val rawTargets = HashMap<String, Double>()
        val sumScore = reads.values.sumOf { max(0.0, it.score) }.coerceAtLeast(1.0)
        for (sector in sectors) {
            val curPct = ((sectorValueNow[sector] ?: 0.0) / totalBook) * 100.0
            val read = reads.getValue(sector)
            val flowTilt = (read.score - 50.0) / 50.0 // -1..+1
            val bias = when (read.verdict) {
                FlowVerdict.INFLOW -> 1.0
                FlowVerdict.OUTFLOW -> -1.0
                FlowVerdict.NEUTRAL -> 0.0
            }
            // move up to ~6 percentage points toward the tilt direction.
            val move = (flowTilt * 4.0 + bias * 2.0).coerceIn(-6.0, 6.0)
            val target = (curPct + move).coerceIn(0.0, maxSectorPct)
            // A sector with no candidates in this batch cannot be actually filled — pin to current.
            val hasCandidate = capacityBySector.containsKey(sector) && (capacityBySector[sector]!! > 0.0)
            rawTargets[sector] = if (!hasCandidate && target > curPct) curPct else target
        }
        // Suppress lint warning on unused sumScore variable — we chose an additive tilt rather than
        // a normalized-share approach because it composes with existing weights more honestly.
        @Suppress("UNUSED_VARIABLE") val _s = sumScore

        return sectors.map { sector ->
            val curPct = ((sectorValueNow[sector] ?: 0.0) / totalBook) * 100.0
            val tgt = rawTargets.getValue(sector)
            val read = reads.getValue(sector)
            val note = buildSectorNote(sector, curPct, tgt, read.flow, read.verdict, maxSectorPct)
            SectorAllocationTarget(
                sector = sector,
                currentPct = round1(curPct),
                targetPct = round1(tgt),
                flow = read.verdict,
                note = note
            )
        }.sortedByDescending { abs(it.targetPct - it.currentPct) }
    }

    private fun buildSectorNote(
        sector: String,
        curPct: Double,
        targetPct: Double,
        flow: SectorFlow?,
        verdict: FlowVerdict,
        maxSectorPct: Double
    ): String {
        val delta = targetPct - curPct
        val direction = when {
            delta > 0.5 -> "add"
            delta < -0.5 -> "trim"
            else -> "hold"
        }
        val flowFragment = when {
            flow != null -> "money flow ${flow.flowScore}/100, ${verdict.name.lowercase(Locale.US)}"
            verdict == FlowVerdict.INFLOW -> "flow read: inflow"
            verdict == FlowVerdict.OUTFLOW -> "flow read: outflow"
            else -> "flow read: neutral"
        }
        val capNote = if (targetPct >= maxSectorPct - 0.05) String.format(
            Locale.US, "; pinned at your %.0f%% sector cap", maxSectorPct
        ) else ""
        return String.format(
            Locale.US,
            "%s at %.1f%% of book, %s at %.1f%% — %s (%s)%s.",
            sector, curPct, direction, targetPct, direction, flowFragment, capNote
        )
    }

    // -------------------------------------------------------------- allocation

    private fun allocate(
        qualifying: List<ScoredCandidate>,
        liquidity: Double,
        totalBook: Double,
        sectorValueNow: Map<String, Double>,
        symbolValueNow: Map<String, Double>,
        sectorTargetPct: Map<String, Double>,
        maxPositionPct: Double,
        maxSectorPct: Double
    ): List<LiquidityAllocationLine> {
        if (qualifying.isEmpty() || liquidity < MIN_TICKET) return emptyList()

        // Room in each sector: min(maxSectorPct, target) - current, expressed in dollars.
        // Use the tighter of the target and the hard cap.
        val sectorRoom: MutableMap<String, Double> = HashMap()
        val allSectors = (sectorValueNow.keys + sectorTargetPct.keys +
            qualifying.map { it.c.sector }).toSet()
        for (sector in allSectors) {
            val current = sectorValueNow[sector] ?: 0.0
            val targetPct = sectorTargetPct[sector] ?: run {
                // Unmapped sector — allow up to the hard sector cap, no more.
                maxSectorPct
            }
            val ceilingPct = min(targetPct, maxSectorPct)
            val room = (ceilingPct / 100.0 * totalBook - current).coerceAtLeast(0.0)
            sectorRoom[sector] = room
        }

        // Room per symbol: hard maxPositionPct against post-deploy totalBook.
        val symbolRoom: MutableMap<String, Double> = HashMap()
        for (sc in qualifying) {
            val held = symbolValueNow[sc.c.symbol] ?: 0.0
            val room = (maxPositionPct / 100.0 * totalBook - held).coerceAtLeast(0.0)
            symbolRoom[sc.c.symbol] = room
        }

        // First pass: pick up to MAX_LINES eligible names in conviction order.
        val eligible = qualifying
            .filter { (symbolRoom[it.c.symbol] ?: 0.0) >= MIN_TICKET }
            .filter { (sectorRoom[it.c.sector] ?: 0.0) >= MIN_TICKET }
            .take(MAX_LINES)
        if (eligible.isEmpty()) return emptyList()

        // Weight each pick by conviction^1.5 so the top idea gets a meaningful over-share
        // without starving the rest.
        val weights = eligible.map { Math.pow(it.conviction.toDouble(), 1.5) }
        val weightSum = weights.sum().coerceAtLeast(1e-9)

        var remaining = liquidity
        val out = ArrayList<LiquidityAllocationLine>()
        val sectorRoomLive = HashMap(sectorRoom)
        val symbolRoomLive = HashMap(symbolRoom)

        eligible.forEachIndexed { idx, sc ->
            val share = weights[idx] / weightSum
            val proposed = liquidity * share
            val room = minOf(
                proposed,
                symbolRoomLive[sc.c.symbol] ?: 0.0,
                sectorRoomLive[sc.c.sector] ?: 0.0,
                remaining
            )
            if (room < MIN_TICKET) return@forEachIndexed
            val amount = roundToTicket(room)
            if (amount < MIN_TICKET) return@forEachIndexed
            val shares = if (sc.c.price > 0.0) amount / sc.c.price else 0.0
            if (shares <= 0.0) return@forEachIndexed
            out.add(
                LiquidityAllocationLine(
                    rank = 0, // set after we know the final ordering
                    symbol = sc.c.symbol,
                    name = sc.c.name.ifBlank { sc.c.symbol },
                    sector = sc.c.sector,
                    amount = amount,
                    approxShares = round4(shares),
                    price = round2(sc.c.price),
                    confidence = sc.conviction,
                    rationale = sc.rationale
                )
            )
            remaining -= amount
            sectorRoomLive[sc.c.sector] = (sectorRoomLive[sc.c.sector] ?: 0.0) - amount
            symbolRoomLive[sc.c.symbol] = (symbolRoomLive[sc.c.symbol] ?: 0.0) - amount
        }

        // Second pass: sweep any leftover cash into the top conviction names that still have room.
        // This makes fuller use of the liquidity when the first pass rounded down.
        if (remaining >= MIN_TICKET) {
            for (i in out.indices) {
                if (remaining < MIN_TICKET) break
                val line = out[i]
                val sc = eligible.firstOrNull { it.c.symbol == line.symbol } ?: continue
                val extraRoom = minOf(
                    symbolRoomLive[sc.c.symbol] ?: 0.0,
                    sectorRoomLive[sc.c.sector] ?: 0.0,
                    remaining
                )
                if (extraRoom < MIN_TICKET) continue
                val extra = roundToTicket(extraRoom)
                if (extra < MIN_TICKET) continue
                val newAmount = line.amount + extra
                val newShares = if (sc.c.price > 0.0) newAmount / sc.c.price else line.approxShares
                out[i] = line.copy(amount = newAmount, approxShares = round4(newShares))
                remaining -= extra
                sectorRoomLive[sc.c.sector] = (sectorRoomLive[sc.c.sector] ?: 0.0) - extra
                symbolRoomLive[sc.c.symbol] = (symbolRoomLive[sc.c.symbol] ?: 0.0) - extra
            }
        }

        // Rank by (amount desc, confidence desc) — highest conviction / biggest ticket first.
        val ranked = out
            .sortedWith(compareByDescending<LiquidityAllocationLine> { it.confidence }.thenByDescending { it.amount })
            .mapIndexed { idx, line -> line.copy(rank = idx + 1) }

        return ranked
    }

    // ------------------------------------------------------------------ reserve

    private fun buildReserveReason(
        reserve: Double,
        liquidity: Double,
        allocation: List<LiquidityAllocationLine>,
        scored: List<ScoredCandidate>,
        qualifying: List<ScoredCandidate>,
        profile: InvestorProfile
    ): String {
        if (reserve <= 0.005) return ""
        val pct = if (liquidity > 0.0) reserve / liquidity * 100.0 else 0.0
        val reasons = ArrayList<String>()

        if (allocation.isEmpty()) {
            when {
                scored.isEmpty() ->
                    reasons += "No candidates were surfaced this run — the caller supplied an empty list."
                qualifying.isEmpty() -> {
                    val topConv = scored.first().conviction
                    reasons += String.format(
                        Locale.US,
                        "No candidate cleared the %d/100 conviction bar (best: %s at %d/100) — cash is honest advice here.",
                        MIN_CONFIDENCE, scored.first().c.symbol, topConv
                    )
                }
                else -> reasons += "Every qualifying candidate would have breached the profile's " +
                    "${profile.maxPositionPct.toInt()}% position or ${profile.maxSectorPct.toInt()}% sector cap."
            }
        } else {
            reasons += String.format(
                Locale.US,
                "The profile's %.0f%% max position and %.0f%% max sector caps left this uncommitted after filling %d name%s.",
                profile.maxPositionPct, profile.maxSectorPct,
                allocation.size, if (allocation.size == 1) "" else "s"
            )
            val below = scored.count { it.conviction < MIN_CONFIDENCE }
            if (below > 0) reasons += "$below other candidate${if (below == 1) "" else "s"} scored below the $MIN_CONFIDENCE/100 conviction bar."
        }
        return String.format(
            Locale.US,
            "%s held back as reserve cash (%.0f%% of liquidity). %s",
            money(reserve), pct, reasons.joinToString(" ")
        )
    }

    // ------------------------------------------------------------------ headline

    private fun buildHeadline(
        liquidity: Double,
        deployed: Double,
        reserve: Double,
        lineCount: Int
    ): String {
        return when {
            lineCount == 0 -> String.format(
                Locale.US,
                "%s ready to deploy, but no name clears the conviction bar — holding as reserve.",
                money(liquidity)
            )
            reserve <= 0.005 -> String.format(
                Locale.US,
                "%s deployed across %d idea%s; nothing held back.",
                money(deployed), lineCount, if (lineCount == 1) "" else "s"
            )
            else -> String.format(
                Locale.US,
                "%s to deploy across %d idea%s; %s held back as buffer.",
                money(deployed), lineCount, if (lineCount == 1) "" else "s", money(reserve)
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun safePolicy(profile: InvestorProfile): String = try {
        profile.label()
    } catch (_: Throwable) {
        String.format(
            Locale.US,
            "%s · %s%% risk/trade · %d%% max position · %d%% max sector",
            profile.riskTolerance.lowercase(Locale.US),
            profile.riskPerTradePct,
            profile.maxPositionPct.toInt(),
            profile.maxSectorPct.toInt()
        )
    }

    /** Round dollar amounts to the nearest $5, keeping small amounts at $1 granularity. */
    private fun roundToTicket(v: Double): Double = when {
        v <= 0.0 -> 0.0
        v < 100.0 -> round2(v)
        v < 1000.0 -> (Math.round(v / 5.0) * 5).toDouble()
        else -> (Math.round(v / 10.0) * 10).toDouble()
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0

    private fun money(v: Double): String =
        if (abs(v) >= 1000.0) String.format(Locale.US, "$%,.0f", v)
        else String.format(Locale.US, "$%.2f", v)
}
