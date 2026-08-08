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

    /** True when two epoch-millis timestamps fall on the same local calendar day. */
    fun sameDay(ts1: Long, ts2: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val d1 = Instant.ofEpochMilli(ts1).atZone(zone).toLocalDate()
        val d2 = Instant.ofEpochMilli(ts2).atZone(zone).toLocalDate()
        return d1 == d2
    }
}
