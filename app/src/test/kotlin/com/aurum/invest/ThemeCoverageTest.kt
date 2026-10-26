package com.aurum.invest

import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.SectorSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the book's holdings count toward a trend theme. The sector gaps
 * read their "you hold" figure from here, so these tests lock the rule that
 * a holding counts only for what it is: a Tech chip maker is semis, not
 * cybersecurity; a fuel-cell maker filed under Industrials is still power.
 */
class ThemeCoverageTest {

    /** The book from 26 Oct 2026, with the sectors and industries Yahoo gives. */
    private val book = BookContext(
        totalValue = 11_228.17,
        heldWeights = mapOf("TSLA" to 26.5, "INTC" to 25.6, "GLW" to 18.7, "BE" to 17.2, "SMR" to 12.0),
        slices = listOf(
            SectorSlice("Technology", 4_972.41, 44.3, listOf("INTC", "GLW")),
            SectorSlice("Industrials", 3_278.0, 29.2, listOf("BE", "SMR")),
            SectorSlice("Consumer Cyclical", 2_977.76, 26.5, listOf("TSLA"))
        ),
        industries = mapOf(
            "TSLA" to "Auto Manufacturers",
            "INTC" to "Semiconductors",
            "GLW" to "Electronic Components",
            "BE" to "Electrical Equipment & Parts",
            "SMR" to "Specialty Industrial Machinery"
        )
    )

    @Test
    fun techHoldingsAreNotCybersecurity() {
        val cyber = PortfolioLens.themeCoverage("cyber", book)
        assertEquals(0.0, cyber.pct, 1e-9)
        assertTrue(cyber.holders.isEmpty())
        assertTrue("cybersecurity has no sector or industry of its own", cyber.membershipOnly)
    }

    @Test
    fun aChipMakerCountsAsSemisByItsIndustry() {
        val semis = PortfolioLens.themeCoverage("semis", book)
        assertEquals(listOf("INTC"), semis.holders)
        assertEquals(25.6, semis.pct, 1e-9)
        assertFalse(semis.membershipOnly)
    }

    @Test
    fun powerNamesFiledUnderIndustrialsCountAsUtilitiesAndPower() {
        // BE by its electrical-equipment industry, SMR by the nuclear watch list.
        val power = PortfolioLens.themeCoverage("utilities", book)
        assertEquals(listOf("BE", "SMR"), power.holders)
        assertEquals(29.2, power.pct, 1e-9)
    }

    @Test
    fun wholeSectorThemesStillCountTheSector() {
        assertEquals(listOf("BE", "SMR"), PortfolioLens.themeCoverage("industrials", book).holders)
        assertEquals(0.0, PortfolioLens.themeCoverage("oil", book).pct, 1e-9)
        assertEquals(listOf("TSLA"), PortfolioLens.themeCoverage("autos", book).holders)
    }

    @Test
    fun withoutIndustriesANarrowThemeClaimsNothingFromTheSector() {
        val noIndustries = book.copy(industries = emptyMap())
        assertTrue(PortfolioLens.themeCoverage("semis", noIndustries).holders.isEmpty())
        // SMR is still on the nuclear list, so the power theme keeps it.
        assertEquals(listOf("SMR"), PortfolioLens.themeCoverage("utilities", noIndustries).holders)
    }

    @Test
    fun anEmptyBookHoldsNothing() {
        val empty = PortfolioLens.themeCoverage("oil", BookContext.EMPTY)
        assertEquals(0.0, empty.pct, 1e-9)
        assertTrue(empty.holders.isEmpty())
    }
}
