package com.aurum.invest.analytics

import com.aurum.invest.data.model.NewsItem

/**
 * Keyword-lexicon sentiment scoring for news headlines.
 * Case-insensitive substring matching; strong words count double; a negation
 * within the four words before a term flips its sign ("fails to beat" is not
 * a beat). Result is clamped to -2..+2 (the scale the rest of the app
 * renders). This reads TITLES ONLY — it is headline tone, not analysis of
 * the article, and the UI labels it that way.
 */
object NewsSentiment {

    /**
     * Words that flip the meaning of the term that follows them. Sentiment
     * terms themselves (e.g. "misses") must NOT appear here — "misses
     * estimates, cuts guidance" would flip its own co-occurring bearish terms.
     */
    private val NEGATORS = listOf(
        "not", "no", "never", "denies", "denied", "fails to", "failed to",
        "without", "won't", "wont", "cannot", "can't", "cant",
        "unlikely to", "halts", "ends", "cancels", "cancelled", "scraps"
    )

    /** Sources whose headlines carry more editorial checking; tone counts fully. */
    private val TIER1_SOURCES = setOf(
        "reuters", "bloomberg", "the wall street journal", "wsj", "financial times",
        "cnbc", "associated press", "ap news", "barron's", "barrons", "marketwatch",
        "the new york times", "investor's business daily", "yahoo finance"
    )

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
        "profit",
        // insider / institutional flow
        "insider buying",
        "insider buys",
        "buys stake",
        "raises stake",
        "boosts stake",
        "builds stake",
        "new position",
        "adds position",
        "initiates coverage",
        "price target raised",
        "target raised",
        "berkshire buys",
        "hedge funds pile"
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
        "outage",
        // insider / institutional flow
        "insider selling",
        "insider sells",
        "cuts stake",
        "sells stake",
        "trims stake",
        "exits position",
        "liquidates",
        "price target cut",
        "target cut",
        "dumps shares"
    )

    /** Score a headline: sum of matched term weights (negation-aware), clamped to -2..+2. */
    fun score(title: String): Int {
        if (title.isBlank()) return 0
        val t = title.lowercase()
        var s = 0
        for (term in strongPositive) s += signed(t, term, +2)
        for (term in positive) s += signed(t, term, +1)
        for (term in strongNegative) s += signed(t, term, -2)
        for (term in negative) s += signed(t, term, -1)
        return s.coerceIn(-2, 2)
    }

    /**
     * The term's weight, sign-flipped when a negator sits within the ~30
     * characters before the match ("fails to beat", "no approval", "denies
     * merger talks").
     */
    private fun signed(text: String, term: String, weight: Int): Int {
        val idx = text.indexOf(term)
        if (idx < 0) return 0
        val windowStart = (idx - 30).coerceAtLeast(0)
        val before = text.substring(windowStart, idx)
        val negated = NEGATORS.any { neg ->
            val at = before.lastIndexOf(neg)
            // Must be a word match, not "nothing" containing "no".
            at >= 0 &&
                (at == 0 || !before[at - 1].isLetter()) &&
                (at + neg.length >= before.length || !before[at + neg.length].isLetter())
        }
        return if (negated) -weight else weight
    }

    /** True when the source is a major, editorially-checked outlet. */
    fun isTier1(source: String): Boolean = source.trim().lowercase() in TIER1_SOURCES

    /**
     * Drops near-duplicate headlines (syndicated copies of the same story),
     * keeping the first (newest) — and preferring a tier-1 source's copy when
     * one exists in the cluster. Similarity = shared-token overlap ≥ 60%.
     */
    fun dedupe(items: List<NewsItem>): List<NewsItem> {
        if (items.size <= 1) return items
        val kept = ArrayList<NewsItem>(items.size)
        val keptTokens = ArrayList<Set<String>>(items.size)
        for (item in items) {
            val tokens = tokenize(item.title)
            val dupAt = keptTokens.indexOfFirst { existing -> overlap(existing, tokens) >= 0.6 }
            if (dupAt < 0) {
                kept.add(item)
                keptTokens.add(tokens)
            } else if (isTier1(item.source) && !isTier1(kept[dupAt].source)) {
                // Same story, better source — keep the better-sourced copy.
                kept[dupAt] = item
                keptTokens[dupAt] = tokens
            }
        }
        return kept
    }

    private fun tokenize(title: String): Set<String> =
        title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / minOf(a.size, b.size)
    }
}
