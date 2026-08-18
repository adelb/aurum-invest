package com.aurum.invest

import com.aurum.invest.analytics.SectorTrends
import com.aurum.invest.analytics.StockCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse shelves and the trend scan are two halves of one thing: a shelf
 * shows its theme's live momentum by pointing at a [SectorTrends] key. A shelf
 * whose key does not exist silently loses its trend badge and its rotation
 * rank — it still renders, so nothing fails loudly. These tests are the loud
 * failure.
 */
class StockCatalogTest {

    private val themeKeys: Set<String> = SectorTrends.SECTORS.map { it.first }.toSet()

    @Test
    fun `every shelf points at a real trend theme`() {
        StockCatalog.SECTORS.forEach { shelf ->
            assertTrue(
                "shelf '${shelf.name}' has theme key '${shelf.themeKey}', which is not in SectorTrends.SECTORS",
                shelf.themeKey in themeKeys
            )
        }
    }

    @Test
    fun `every trend theme carries a watch list and a proxy ETF`() {
        SectorTrends.SECTORS.forEach { (key, label, etf) ->
            assertTrue("theme '$key' has no WATCH names", !SectorTrends.WATCH[key].isNullOrEmpty())
            assertTrue("theme '$key' has a blank label", label.isNotBlank())
            assertTrue("theme '$key' has a blank ETF", etf.isNotBlank())
        }
    }

    @Test
    fun `theme keys and shelf names are unique`() {
        val keys = SectorTrends.SECTORS.map { it.first }
        assertEquals("duplicate theme key", keys.size, keys.toSet().size)
        // The Stocks tab keys its sector chips by name; a duplicate would make
        // two chips collide in the LazyRow.
        val names = StockCatalog.SECTORS.map { it.name }
        assertEquals("duplicate shelf name", names.size, names.toSet().size)
    }

    @Test
    fun `no shelf lists the same symbol twice`() {
        StockCatalog.SECTORS.forEach { shelf ->
            val symbols = shelf.stocks.map { it.first }
            assertEquals(
                "shelf '${shelf.name}' repeats a symbol",
                symbols.size,
                symbols.toSet().size
            )
        }
    }

    @Test
    fun `symbols are uppercase and named`() {
        (StockCatalog.SECTORS.flatMap { it.stocks } + SectorTrends.WATCH.values.flatten())
            .forEach { (symbol, name) ->
                assertEquals("'$symbol' is not uppercase", symbol.uppercase(), symbol)
                assertTrue("'$symbol' has a blank company name", name.isNotBlank())
                assertTrue("'$symbol' is not a plausible ticker", symbol.isNotBlank() && symbol.length <= 5)
            }
    }

    @Test
    fun `the drones shelf carries the drone names, not only automation`() {
        val shelf = StockCatalog.SECTORS.first { it.themeKey == "drones" }
        val symbols = shelf.stocks.map { it.first }.toSet()
        // The shelf exists because of the drone build-out; a list that drifted
        // into pure factory automation would no longer answer what it is for.
        listOf("UMAC", "AVAV", "KTOS", "RCAT", "ONDS").forEach {
            assertTrue("drones shelf is missing $it", it in symbols)
        }
        assertTrue("drones shelf lost its robotics half", "SYM" in symbols || "ISRG" in symbols)
    }

    @Test
    fun `a watch name resolves to its own theme`() {
        SectorTrends.WATCH.forEach { (key, stocks) ->
            stocks.forEach { (symbol, _) ->
                val resolved = SectorTrends.SYMBOL_THEME[symbol]?.first
                assertTrue(
                    "$symbol resolves to '$resolved', but is watched by '$key' too",
                    resolved != null
                )
            }
        }
    }

    @Test
    fun `every shelf has enough names to browse`() {
        StockCatalog.SECTORS.forEach { shelf ->
            assertTrue(
                "shelf '${shelf.name}' has only ${shelf.stocks.size} names",
                shelf.stocks.size >= 6
            )
        }
    }
}
