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
    val slices: List<SectorSlice>           // by value, descending
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
     * SectorTrends themes -> Yahoo sector names, ONLY where the mapping is
     * unambiguous. Cross-sector themes (AI, quantum, nuclear, solar) are
     * deliberately unmapped — claiming alignment there would be a guess.
     */
    private val THEME_SECTORS: Map<String, Set<String>> = mapOf(
        "semis" to setOf("Technology"),
        "software" to setOf("Technology"),
        "oil" to setOf("Energy"),
        "materials" to setOf("Basic Materials"),
        "goldminers" to setOf("Basic Materials"),
        "banks" to setOf("Financial Services"),
        "health" to setOf("Healthcare"),
        "biotech" to setOf("Healthcare"),
        "defense" to setOf("Industrials"),
        "industrials" to setOf("Industrials"),
        "consumer" to setOf("Consumer Cyclical", "Consumer Defensive"),
        "utilities" to setOf("Utilities"),
        "media" to setOf("Communication Services"),
        "autos" to setOf("Consumer Cyclical"),
        "cyber" to setOf("Technology"),
        "reits" to setOf("Real Estate"),
        "transport" to setOf("Industrials"),
        "staples" to setOf("Consumer Defensive")
        // drones / crypto / space stay unmapped on purpose: cross-sector
        // themes can only claim coverage from exact holdings membership.
    )

    /**
     * Share of the book sitting in [themeKey]'s sectors, or null when the
     * theme has no unambiguous sector mapping (AI, quantum, nuclear, solar) —
     * callers must then measure coverage some other honest way rather than
     * treating null as zero.
     */
    fun themeCoveragePct(themeKey: String, book: BookContext): Double? {
        val sectors = THEME_SECTORS[themeKey] ?: return null
        if (book.isEmpty) return 0.0
        return book.slices.filter { it.sector in sectors }.sumOf { it.weightPct }
    }

    /** Slice the open positions by sector. Positions without a live value are skipped. */
    fun build(views: List<PositionView>, sectors: Map<String, String>): BookContext {
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
        return BookContext(total, weights, slices)
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

        val trendSectors = trendingKey?.let { THEME_SECTORS[it] }
        if (trendSectors != null && !trendingLabel.isNullOrBlank()) {
            val pct = book.slices.filter { it.sector in trendSectors }.sumOf { it.weightPct }
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
