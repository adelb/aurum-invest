package com.aurum.invest.core

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Money / percent / quantity formatting used across every screen. */
object Fmt {

    private val money2 = DecimalFormat("#,##0.00")
    private val money0 = DecimalFormat("#,##0")
    private val pct2 = DecimalFormat("0.00")
    private val qtyFmt = DecimalFormat("#,##0.####")

    fun money(v: Double, symbol: String = "$"): String {
        val f = if (abs(v) >= 10_000) money0 else money2
        return if (v < 0) "-$symbol${f.format(abs(v))}" else "$symbol${f.format(v)}"
    }

    fun signedMoney(v: Double, symbol: String = "$"): String {
        val sign = if (v >= 0) "+" else "-"
        val f = if (abs(v) >= 10_000) money0 else money2
        return "$sign$symbol${f.format(abs(v))}"
    }

    fun pct(v: Double): String = "${pct2.format(v)}%"

    fun signedPct(v: Double): String {
        val sign = if (v >= 0) "+" else "-"
        return "$sign${pct2.format(abs(v))}%"
    }

    fun compact(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1_000_000_000 -> "${pct2.format(v / 1_000_000_000)}B"
            a >= 1_000_000 -> "${pct2.format(v / 1_000_000)}M"
            a >= 1_000 -> "${money0.format(v / 1_000)}K"
            else -> money0.format(v)
        }
    }

    fun qty(v: Double): String = qtyFmt.format(v)

    fun dateShort(ts: Long): String =
        SimpleDateFormat("MMM d", Locale.US).format(Date(ts))

    fun dateTime(ts: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ts))

    fun timeShort(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

    fun timeAgo(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        val min = diff / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "${min}m ago"
            min < 60 * 24 -> "${min / 60}h ago"
            else -> "${min / (60 * 24)}d ago"
        }
    }
}
