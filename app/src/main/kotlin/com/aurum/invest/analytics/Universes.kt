package com.aurum.invest.analytics

import com.aurum.invest.data.model.AssetClass

/**
 * Curated non-equity symbol universes using Yahoo Finance conventions.
 *
 * All symbols work directly with the existing chart endpoints — Yahoo returns
 * candles for `GC=F` (Gold futures), `EURUSD=X` (FX pairs), `^NDX` (Nasdaq 100
 * index), etc. FX quotes typically carry zero volume; scoring already tolerates
 * that (volRatio defaults to 1.0 when the 20-day average is zero).
 *
 * Sector data from Yahoo's search endpoint is null for these instruments, so
 * MarketRepository.getSector short-circuits with a synthesized value based on
 * [assetClassOf] instead of round-tripping and caching null.
 */
object Universes {

    /** Precious and industrial metals — futures + spot pairs. */
    val METALS: List<Pair<String, String>> = listOf(
        "GC=F" to "Gold Futures",
        "SI=F" to "Silver Futures",
        "HG=F" to "Copper Futures",
        "PL=F" to "Platinum Futures",
        "PA=F" to "Palladium Futures",
        "XAUUSD=X" to "Gold Spot (XAU/USD)",
        "XAGUSD=X" to "Silver Spot (XAG/USD)"
    )

    /** Major forex pairs. */
    val FX: List<Pair<String, String>> = listOf(
        "EURUSD=X" to "EUR / USD",
        "GBPUSD=X" to "GBP / USD",
        "USDJPY=X" to "USD / JPY",
        "USDCHF=X" to "USD / CHF",
        "AUDUSD=X" to "AUD / USD",
        "USDCAD=X" to "USD / CAD",
        "NZDUSD=X" to "NZD / USD",
        "EURGBP=X" to "EUR / GBP",
        "EURJPY=X" to "EUR / JPY",
        "DX-Y.NYB" to "US Dollar Index (DXY)"
    )

    /**
     * US indices — index and front-month futures side by side.
     * `US100` = Nasdaq 100 (`^NDX` cash index / `NQ=F` futures).
     * `US30`  = Dow Jones Industrial Average (`^DJI` cash index / `YM=F` futures).
     * Futures reflect overnight moves; indices reflect the regular session.
     */
    val INDICES: List<Pair<String, String>> = listOf(
        "^NDX" to "US100 · Nasdaq 100",
        "NQ=F" to "US100 · Nasdaq Futures",
        "^DJI" to "US30 · Dow Jones",
        "YM=F" to "US30 · Dow Futures",
        "^GSPC" to "US500 · S&P 500",
        "ES=F" to "US500 · S&P Futures",
        "^RUT" to "US2000 · Russell 2000",
        "^VIX" to "VIX · Volatility Index"
    )

    val ALL_NON_EQUITY: List<Pair<String, String>> = METALS + FX + INDICES

    private val METAL_SET = METALS.map { it.first }.toHashSet()
    private val FX_SET = FX.map { it.first }.toHashSet()
    private val INDEX_SET = INDICES.map { it.first }.toHashSet()

    /**
     * Best-effort asset-class classification from the symbol shape.
     * Recognizes the curated lists first, then falls back to Yahoo suffix
     * conventions: `*=X` = FX, `^*` = index, `*=F` = futures (treated as index
     * for section purposes unless explicitly a metal).
     */
    fun assetClassOf(symbol: String): AssetClass = when {
        symbol in METAL_SET -> AssetClass.METAL
        symbol in FX_SET -> AssetClass.FX
        symbol in INDEX_SET -> AssetClass.INDEX
        symbol.endsWith("=X") -> AssetClass.FX
        symbol.startsWith("^") -> AssetClass.INDEX
        symbol.endsWith("=F") -> AssetClass.INDEX
        else -> AssetClass.EQUITY
    }

    /** Human-readable sector label for a non-equity symbol. */
    fun syntheticSectorFor(symbol: String): String? = when (assetClassOf(symbol)) {
        AssetClass.METAL -> "Metals"
        AssetClass.FX -> "FX"
        AssetClass.INDEX -> "Index"
        AssetClass.EQUITY -> null
    }

    /**
     * News-search query for a non-equity symbol. Google News RSS returns thin
     * or empty results for the raw ticker (e.g. `GC=F`), so we map to the
     * everyday name of the instrument. Returns null for symbols the caller
     * should search by the raw ticker (equities and unknowns).
     */
    fun newsQueryFor(symbol: String): String? {
        // Metals — search by the metal name + "price" for financial-tone results.
        when (symbol) {
            "GC=F", "XAUUSD=X" -> return "gold price"
            "SI=F", "XAGUSD=X" -> return "silver price"
            "HG=F" -> return "copper price"
            "PL=F" -> return "platinum price"
            "PA=F" -> return "palladium price"
        }
        // FX pairs — split the ticker.
        if (symbol.endsWith("=X") && symbol.length in 7..8) {
            val core = symbol.removeSuffix("=X")
            if (core.length == 6) {
                val base = core.substring(0, 3)
                val quote = core.substring(3)
                return "$base $quote forex"
            }
        }
        // Indices / futures
        return when (symbol) {
            "^NDX", "NQ=F" -> "Nasdaq 100"
            "^DJI", "YM=F" -> "Dow Jones industrial"
            "^GSPC", "ES=F" -> "S&P 500"
            "^RUT" -> "Russell 2000"
            "^VIX" -> "VIX volatility index"
            "DX-Y.NYB" -> "US dollar index DXY"
            else -> null
        }
    }
}
