package com.aurum.invest.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Week / trading-day helpers. The picks week starts every Monday. */
object Dates {

    /** ISO date (yyyy-MM-dd) of the Monday of the current week, local time. */
    fun currentWeekStartIso(): String =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    /** "Week of Aug 3" style label for an ISO week-start date. */
    fun weekStartLabel(iso: String): String {
        val d = LocalDate.parse(iso)
        return "Week of " + d.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }

    /** Millis from now until next Monday 07:00 local (for the weekly picks worker). */
    fun nextMondayMorningDelayMs(): Long {
        val now = LocalDateTime.now()
        var next = now.toLocalDate()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            .atTime(7, 0)
        if (!next.isAfter(now)) {
            next = next.plusWeeks(1)
        }
        val zone = ZoneId.systemDefault()
        return next.atZone(zone).toInstant().toEpochMilli() - System.currentTimeMillis()
    }

    /** ISO date (yyyy-MM-dd) of today, local time. */
    fun todayIso(): String = LocalDate.now().toString()

    /** "Sunday, Aug 9" style label for today. */
    fun todayLabel(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US))

    /** True on Saturdays — the one day the daily picks rest. */
    fun isSaturday(): Boolean = LocalDate.now().dayOfWeek == DayOfWeek.SATURDAY

    /** True when two epoch-millis timestamps fall on the same local calendar day. */
    fun sameDay(ts1: Long, ts2: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val d1 = Instant.ofEpochMilli(ts1).atZone(zone).toLocalDate()
        val d2 = Instant.ofEpochMilli(ts2).atZone(zone).toLocalDate()
        return d1 == d2
    }

    /** State of the power-hour buy window (last 90 min of the US regular session). */
    enum class PowerWindow { WEEKEND, BEFORE, OPEN, CLOSED }

    /**
     * Where "now" sits relative to today's 2:30-4:00 PM ET buy window, plus
     * the window bounds converted to device-local epoch millis. Regular US
     * holidays are not modeled — the window simply has no trades those days.
     */
    fun powerWindowNow(): Triple<PowerWindow, Long, Long> {
        val et = ZoneId.of("America/New_York")
        val nowEt = java.time.ZonedDateTime.now(et)
        val start = nowEt.toLocalDate().atTime(14, 30).atZone(et).toInstant().toEpochMilli()
        val end = nowEt.toLocalDate().atTime(16, 0).atZone(et).toInstant().toEpochMilli()
        val state = when {
            nowEt.dayOfWeek == DayOfWeek.SATURDAY || nowEt.dayOfWeek == DayOfWeek.SUNDAY ->
                PowerWindow.WEEKEND
            System.currentTimeMillis() < start -> PowerWindow.BEFORE
            System.currentTimeMillis() <= end -> PowerWindow.OPEN
            else -> PowerWindow.CLOSED
        }
        return Triple(state, start, end)
    }
}
