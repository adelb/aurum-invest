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

    /**
     * Always two decimals, however large the amount. [money] drops the cents
     * above $10,000 to keep dense lists narrow, which is wrong for the wallet
     * figures — a balance that reads "$25,477" hides money the user has.
     */
    fun moneyExact(v: Double, symbol: String = "$"): String =
        if (v < 0) "-$symbol${money2.format(abs(v))}" else "$symbol${money2.format(v)}"

    /** [moneyExact] with an explicit +/- sign — for P/L figures. */
    fun signedMoneyExact(v: Double, symbol: String = "$"): String {
        val sign = if (v >= 0) "+" else "-"
        return "$sign$symbol${money2.format(abs(v))}"
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

    /** Plain 2-decimal number, no currency symbol — for non-USD amounts. */
    fun num2(v: Double): String = money2.format(v)

    /** "2.06" not "2.0600" — for pre-filling editable number fields. */
    fun trimNumber(v: Double): String =
        java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    fun dateShort(ts: Long): String =
        SimpleDateFormat("MMM d", Locale.US).format(Date(ts))

    /** "MMM yyyy" — for chart date axes spanning a year or more, where "MMM d"
     * alone would repeat the same month across different years with no way
     * to tell which is which (e.g. a 1Y or 5Y price chart). */
    fun dateWithYear(ts: Long): String =
        SimpleDateFormat("MMM yyyy", Locale.US).format(Date(ts))

    /** "Aug 19, 2025" — the full point-in-time read for long-range crosshairs. */
    fun dateFull(ts: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ts))

    /** "Aug 19 '25" — a dated axis label that still carries its year. */
    fun dateShortYear(ts: Long): String =
        SimpleDateFormat("MMM d ''yy", Locale.US).format(Date(ts))

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
