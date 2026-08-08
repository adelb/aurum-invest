package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure technical-indicator math. No Android dependencies; safe to call from any thread.
 * Every function returns null (or an empty list) instead of throwing when the input
 * series is too short.
 */
object Indicators {

    /** Simple moving average of the LAST [period] values, or null if not enough data. */
    fun sma(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period) return null
        var sum = 0.0
        for (i in values.size - period until values.size) sum += values[i]
        return sum / period
    }

    /**
     * Relative Strength Index with Wilder smoothing.
     * Needs at least [period] + 1 closes. Returns a value in 0..100.
     */
    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || closes.size < period + 1) return null

        // Seed averages from the first [period] changes.
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0.0) avgGain += d else avgLoss += -d
        }
        avgGain /= period
        avgLoss /= period

        // Wilder smoothing over the remainder of the series.
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            val gain = if (d > 0.0) d else 0.0
            val loss = if (d < 0.0) -d else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /**
     * Average True Range with Wilder smoothing.
     * Needs at least [period] + 1 candles (true range uses the previous close).
     */
    fun atr(candles: List<Candle>, period: Int = 14): Double? {
        if (period <= 0 || candles.size < period + 1) return null

        val trueRanges = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            trueRanges.add(tr)
        }

        var atr = 0.0
        for (i in 0 until period) atr += trueRanges[i]
        atr /= period
        for (i in period until trueRanges.size) {
            atr = (atr * (period - 1) + trueRanges[i]) / period
        }
        return atr
    }

    /** Close-to-close fractional returns; empty when fewer than 2 closes. */
    fun dailyReturns(closes: List<Double>): List<Double> {
        if (closes.size < 2) return emptyList()
        val out = ArrayList<Double>(closes.size - 1)
        for (i in 1 until closes.size) {
            val prev = closes[i - 1]
            out.add(if (prev != 0.0) (closes[i] - prev) / prev else 0.0)
        }
        return out
    }

    /**
     * Pearson correlation coefficient of two equally sized series.
     * Null on size mismatch, fewer than 2 points, or zero variance in either series.
     */
    fun pearson(a: List<Double>, b: List<Double>): Double? {
        val n = a.size
        if (n != b.size || n < 2) return null
        var meanA = 0.0
        var meanB = 0.0
        for (i in 0 until n) {
            meanA += a[i]
            meanB += b[i]
        }
        meanA /= n
        meanB /= n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA == 0.0 || varB == 0.0) return null
        return (cov / sqrt(varA * varB)).coerceIn(-1.0, 1.0)
    }

    /** Highest close among the last [lookback] values, or null when the list is empty. */
    fun recentHigh(closes: List<Double>, lookback: Int): Double? {
        if (lookback <= 0 || closes.isEmpty()) return null
        return closes.takeLast(lookback).maxOrNull()
    }

    /** Lowest close among the last [lookback] values, or null when the list is empty. */
    fun recentLow(closes: List<Double>, lookback: Int): Double? {
        if (lookback <= 0 || closes.isEmpty()) return null
        return closes.takeLast(lookback).minOrNull()
    }
}
