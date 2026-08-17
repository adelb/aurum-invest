package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.data.model.GoldRelation
import java.util.Locale

/**
 * Measures how a stock's daily returns correlate with gold (GLD proxy).
 * Candles are aligned by calendar day; Pearson runs on the aligned return series.
 * r >= 0.30 -> WITH_GOLD, r <= -0.30 -> INVERSE_GOLD, otherwise NEUTRAL.
 */
object GoldCorrelation {

    private const val MIN_OVERLAP_DAYS = 20
    private const val THRESHOLD = 0.30

    fun relation(stockCandles: List<Candle>, goldCandles: List<Candle>): GoldRelation {
        if (stockCandles.isEmpty() || goldCandles.isEmpty()) {
            return GoldRelation(
                correlation = null,
                link = GoldLink.NEUTRAL,
                description = "No overlapping trading days with gold yet — relationship unknown.",
                sampleDays = 0
            )
        }

        // Align stock and gold closes on shared market days (ET, not the
        // device zone — a bar's session identity never depends on the phone).
        val stockAligned = ArrayList<Double>()
        val goldAligned = ArrayList<Double>()
        for (s in stockCandles) {
            val g = goldCandles.firstOrNull { Dates.sameEtDay(it.ts, s.ts) } ?: continue
            stockAligned.add(s.close)
            goldAligned.add(g.close)
        }

        val days = stockAligned.size
        if (days < MIN_OVERLAP_DAYS) {
            return GoldRelation(
                correlation = null,
                link = GoldLink.NEUTRAL,
                description = "Only $days overlapping trading day${if (days == 1) "" else "s"} with gold — " +
                    "at least $MIN_OVERLAP_DAYS are needed to measure a relationship.",
                sampleDays = days
            )
        }

        val r = Indicators.pearson(
            Indicators.dailyReturns(stockAligned),
            Indicators.dailyReturns(goldAligned)
        ) ?: return GoldRelation(
            correlation = null,
            link = GoldLink.NEUTRAL,
            description = "Returns show no measurable variance against gold across $days shared trading days.",
            sampleDays = days
        )

        val link = when {
            r >= THRESHOLD -> GoldLink.WITH_GOLD
            r <= -THRESHOLD -> GoldLink.INVERSE_GOLD
            else -> GoldLink.NEUTRAL
        }

        val rTxt = String.format(Locale.US, "%.2f", r)
        val description = when (link) {
            GoldLink.WITH_GOLD ->
                "Tends to move with gold — correlation r = $rTxt across $days shared trading days."
            GoldLink.INVERSE_GOLD ->
                "Tends to move opposite to gold — correlation r = $rTxt across $days shared trading days."
            GoldLink.NEUTRAL ->
                "No meaningful gold relationship — correlation r = $rTxt across $days shared trading days."
        }

        return GoldRelation(
            correlation = r,
            link = link,
            description = description,
            sampleDays = days
        )
    }
}
