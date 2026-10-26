package com.aurum.invest.analytics

import com.aurum.invest.data.model.PositionView
import java.util.Locale

/** One sector's share of the invested book. */
data class SectorSlice(
    val sector: String,
    val value: Double,
    val weightPct: Double,
    val symbols: List<String>   // largest holding first
)

enum class NoteKind { HELD, CONCENTRATION, DIVERSIFIES }

/** How much of the book sits in one trend theme, and which holdings carry it. */
data class ThemeCoverage(
    val pct: Double,
    /** Held symbols counted toward the theme, largest first. */
    val holders: List<String>,
    /** Only exact watch-list membership could be counted (no sector or industry read). */
    val membershipOnly: Boolean
)

/** A terse, number-backed portfolio note attached to a suggested stock. */
data class PickNote(val text: String, val kind: NoteKind)

/**
 * The user's invested book, sliced once and reused by the Dashboard
 * allocation card, the Picks tabs, and the Wealth section — so every screen
 * agrees on the same numbers.
 */
data class BookContext(
    val totalValue: Double,
    val heldWeights: Map<String, Double>,   // symbol -> % of book
    val slices: List<SectorSlice>,          // by value, descending
    val industries: Map<String, String> = emptyMap()   // symbol -> Yahoo industry, where known
) {
    val isEmpty: Boolean get() = totalValue <= 0.0 || heldWeights.isEmpty()

    companion object {
        val EMPTY = BookContext(0.0, emptyMap(), emptyList())
    }
}

/**
 * Portfolio-aware reads shared across the app. Integrity rules: every figure
 * is computed from live market values; unknown sectors stay "Unclassified"
 * rather than guessed; trend themes with no clean sector mapping produce no
 * alignment claim at all.
 */
object PortfolioLens {

    const val UNCLASSIFIED = "Unclassified"

    /**
     * Themes that are a whole Yahoo sector: every holding in the sector counts.
     */
    private val SECTOR_THEMES: Map<String, Set<String>> = mapOf(
        "oil" to setOf("Energy"),
        "materials" to setOf("Basic Materials"),
        "banks" to setOf("Financial Services"),
        "health" to setOf("Healthcare"),
        "industrials" to setOf("Industrials"),
        "consumer" to setOf("Consumer Cyclical", "Consumer Defensive"),
        "utilities" to setOf("Utilities"),
        "media" to setOf("Communication Services"),
        "reits" to setOf("Real Estate"),
        "staples" to setOf("Consumer Defensive")
    )

    /**
     * Themes narrower than a sector, matched on the holding's Yahoo industry
     * (lower-case fragments). A Technology holding is semis, software or
     * neither by its industry — never cybersecurity just for being in Tech.
     */
    private val THEME_INDUSTRIES: Map<String, List<String>> = mapOf(
        "semis" to listOf("semiconductor"),
        "software" to listOf("software"),
        "biotech" to listOf("biotechnology"),
        "autos" to listOf("auto manufacturers", "auto parts"),
        "defense" to listOf("aerospace & defense"),
        "transport" to listOf("railroads", "trucking", "airlines", "freight", "marine shipping"),
        "goldminers" to listOf("gold"),
        "nuclear" to listOf("uranium"),
        "solar" to listOf("solar"),
        // Power generation that Yahoo files under Industrials: fuel cells, grid gear.
        "utilities" to listOf("electrical equipment", "solar", "uranium")
    )

    /** Themes whose watch-list members also count toward a broader theme. */
    private val THEME_ALSO: Map<String, List<String>> = mapOf(
        "utilities" to listOf("nuclear", "solar")
    )

    /**
     * How much of the book already sits in [themeKey], and which holdings
     * carry it. A holding counts when it is on the theme's own watch list,
     * sits in a whole-sector theme's sector, or its industry names the
     * theme. Cross-sector themes (AI, quantum, cybersecurity, drones) have
     * neither a sector nor an industry, so only list membership counts there
     * — and [ThemeCoverage.membershipOnly] says so.
     */
    fun themeCoverage(themeKey: String, book: BookContext): ThemeCoverage {
        val sectors = SECTOR_THEMES[themeKey].orEmpty()
        val industries = THEME_INDUSTRIES[themeKey].orEmpty()
        val membershipOnly = sectors.isEmpty() && industries.isEmpty()
        if (book.isEmpty) return ThemeCoverage(0.0, emptyList(), membershipOnly)
        val members = (listOf(themeKey) + THEME_ALSO[themeKey].orEmpty())
            .flatMap { key -> SectorTrends.WATCH[key].orEmpty().map { it.first } }
            .toSet()
        val sectorOf = book.slices.flatMap { slice -> slice.symbols.map { it to slice.sector } }.toMap()
        val holders = book.heldWeights.entries
            .filter { (symbol, _) ->
                symbol in members ||
                    sectorOf[symbol] in sectors ||
                    book.industries[symbol]?.lowercase(Locale.US)?.let { industry ->
                        industries.any { it in industry }
                    } == true
            }
            .sortedByDescending { it.value }
        return ThemeCoverage(
            pct = holders.sumOf { it.value },
            holders = holders.map { it.key },
            membershipOnly = membershipOnly
        )
    }

    /**
     * Slice the open positions by sector. Positions without a live value are
     * skipped. [industries] (symbol -> Yahoo industry) lets theme coverage
     * tell semis from software inside one sector; leave it empty where no
     * theme is read.
     */
    fun build(
        views: List<PositionView>,
        sectors: Map<String, String>,
        industries: Map<String, String> = emptyMap()
    ): BookContext {
        val open = views.filter { it.marketValue > 0.0 }
        val total = open.sumOf { it.marketValue }
        if (total <= 0.0) return BookContext.EMPTY
        val weights = open.associate {
            it.position.symbol to it.marketValue / total * 100.0
        }
        val slices = open
            .groupBy { sectors[it.position.symbol] ?: UNCLASSIFIED }
            .map { (sector, group) ->
                val value = group.sumOf { it.marketValue }
                SectorSlice(
                    sector = sector,
                    value = value,
                    weightPct = value / total * 100.0,
                    symbols = group.sortedByDescending { it.marketValue }
                        .map { it.position.symbol }
                )
            }
            .sortedByDescending { it.value }
        return BookContext(total, weights, slices, industries.filterKeys { it in weights })
    }

    /**
     * Number-backed observations about the book, optionally against the
     * week's trending theme. Every sentence carries its measured figure.
     */
    fun exposureNotes(
        book: BookContext,
        trendingKey: String?,
        trendingLabel: String?
    ): List<String> {
        if (book.isEmpty) return emptyList()
        val out = mutableListOf<String>()

        val classified = book.slices.filter { it.sector != UNCLASSIFIED }
        val top = classified.firstOrNull()
        if (top != null) {
            when {
                top.weightPct >= 50.0 -> out += String.format(
                    Locale.US,
                    "Concentrated: %.0f%% of the book sits in %s (%s). A single-sector shock hits hard.",
                    top.weightPct, top.sector, top.symbols.take(3).joinToString(", ")
                )
                top.weightPct >= 35.0 -> out += String.format(
                    Locale.US,
                    "%s is your largest exposure at %.0f%% of the book.",
                    top.sector, top.weightPct
                )
            }
        }

        val trend = trendingKey?.let { themeCoverage(it, book) }?.takeIf { !it.membershipOnly }
        if (trend != null && !trendingLabel.isNullOrBlank()) {
            val pct = trend.pct
            out += if (pct >= 1.0) {
                String.format(
                    Locale.US,
                    "This week's leading theme (%s) already covers %.0f%% of your book.",
                    trendingLabel, pct
                )
            } else {
                "You hold nothing in this week's leading theme ($trendingLabel)."
            }
        }

        val unclassified = book.slices.firstOrNull { it.sector == UNCLASSIFIED }
        if (unclassified != null) {
            out += String.format(
                Locale.US,
                "%.0f%% of the book (%s) has no sector data yet and is left out of sector reads.",
                unclassified.weightPct, unclassified.symbols.joinToString(", ")
            )
        }
        return out
    }

    /**
     * A note for one suggested stock against the book. Null when there is
     * nothing factual worth saying.
     */
    fun pickNote(symbol: String, sector: String?, book: BookContext): PickNote? {
        if (book.isEmpty) return null
        book.heldWeights[symbol]?.let { w ->
            return PickNote(
                String.format(Locale.US, "In portfolio · %.0f%% of book", w),
                NoteKind.HELD
            )
        }
        if (sector == null || sector == UNCLASSIFIED) return null
        val slice = book.slices.firstOrNull { it.sector == sector }
        return when {
            slice != null && slice.weightPct >= 35.0 -> PickNote(
                String.format(
                    Locale.US,
                    "Adds to %s — already %.0f%% of book",
                    sector, slice.weightPct
                ),
                NoteKind.CONCENTRATION
            )
            slice == null && book.heldWeights.size >= 2 -> PickNote(
                "New sector for your book: $sector",
                NoteKind.DIVERSIFIES
            )
            else -> null
        }
    }
}
