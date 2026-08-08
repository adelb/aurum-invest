package com.aurum.invest.analytics

/**
 * Keyword-lexicon sentiment scoring for news headlines.
 * Case-insensitive substring matching; strong words count double.
 * Result is clamped to -2..+2 (the scale the rest of the app renders).
 */
object NewsSentiment {

    /** Very bullish phrases — each match adds +2. */
    private val strongPositive = listOf(
        "soar",
        "skyrocket",
        "all-time high",
        "all time high",
        "record high",
        "blowout",
        "blockbuster",
        "crushes estimates",
        "doubles",
        "stellar",
        "breakout"
    )

    /** Bullish phrases — each match adds +1. */
    private val positive = listOf(
        "beat",
        "tops estimates",
        "upgrade",
        "outperform",
        "overweight",
        "buy rating",
        "acquisition",
        "acquire",
        "merger",
        "buyback",
        "dividend",
        "raises guidance",
        "raised guidance",
        "raises forecast",
        "record",
        "rally",
        "rallies",
        "surge",
        "jump",
        "climb",
        "rises",
        "gains",
        "bullish",
        "strong demand",
        "breakthrough",
        "approval",
        "approved",
        "partnership",
        "expansion",
        "wins",
        "secures",
        "exceeds",
        "growth",
        "boost",
        "milestone",
        "profit"
    )

    /** Very bearish phrases — each match subtracts 2. */
    private val strongNegative = listOf(
        "crash",
        "fraud",
        "plunge",
        "plummet",
        "collapse",
        "bankruptcy",
        "scandal",
        "halted",
        "default",
        "wipes out"
    )

    /** Bearish phrases — each match subtracts 1. */
    private val negative = listOf(
        "misses",
        "missed",
        "downgrade",
        "underperform",
        "sell rating",
        "lawsuit",
        "sues",
        "sued",
        "recall",
        "probe",
        "investigation",
        "fined",
        "fines",
        "penalty",
        "layoffs",
        "job cuts",
        "cuts guidance",
        "cut guidance",
        "lowers guidance",
        "lowers forecast",
        "warning",
        "warns",
        "decline",
        "drops",
        "falls",
        "slump",
        "slides",
        "tumbles",
        "weak",
        "shortfall",
        "delay",
        "resigns",
        "bearish",
        "downturn",
        "short seller",
        "antitrust",
        "breach",
        "outage"
    )

    /** Score a headline: sum of matched term weights, clamped to -2..+2. */
    fun score(title: String): Int {
        if (title.isBlank()) return 0
        val t = title.lowercase()
        var s = 0
        for (term in strongPositive) if (t.contains(term)) s += 2
        for (term in positive) if (t.contains(term)) s += 1
        for (term in strongNegative) if (t.contains(term)) s -= 2
        for (term in negative) if (t.contains(term)) s -= 1
        return s.coerceIn(-2, 2)
    }
}
