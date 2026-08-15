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

    /**
     * True when two epoch-millis timestamps fall on the same US-market (ET)
     * calendar day. Use this — not [sameDay] — for "is this bar today's
     * session?" checks: Yahoo stamps daily bars at the ET session open, and
     * a device east of ~UTC+7 crosses local midnight while the US session
     * is still running, making the device-local comparison lie.
     */
    fun sameEtDay(ts1: Long, ts2: Long): Boolean {
        val zone = ZoneId.of("America/New_York")
        val d1 = Instant.ofEpochMilli(ts1).atZone(zone).toLocalDate()
        val d2 = Instant.ofEpochMilli(ts2).atZone(zone).toLocalDate()
        return d1 == d2
    }

    /**
     * True when [barTs] is today's ET daily candle and the regular session has
     * not closed yet. Volume engines use this to exclude a genuinely partial
     * bar without throwing away today's completed volume after 4:00 PM ET.
     */
    fun isCurrentEtDailyBarIncomplete(
        barTs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val et = ZoneId.of("America/New_York")
        val now = Instant.ofEpochMilli(nowMs).atZone(et)
        val barDate = Instant.ofEpochMilli(barTs).atZone(et).toLocalDate()
        if (barDate != now.toLocalDate()) return false
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return false
        }
        return now.toLocalTime().isBefore(java.time.LocalTime.of(16, 0))
    }

    /** Epoch millis of today's local midnight. */
    fun todayStartMs(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** True once today's US regular session has opened (9:30 AM ET, weekdays). */
    fun usMarketOpenedToday(): Boolean {
        val nowEt = java.time.ZonedDateTime.now(ZoneId.of("America/New_York"))
        if (nowEt.dayOfWeek == DayOfWeek.SATURDAY || nowEt.dayOfWeek == DayOfWeek.SUNDAY) {
            return false
        }
        return !nowEt.toLocalTime().isBefore(java.time.LocalTime.of(9, 30))
    }

    /** Which US market session is running right now, in ET. */
    enum class MarketSession { WEEKEND, OVERNIGHT, PRE, REGULAR, POST }

    /**
     * The live US session for "now": pre-market runs 4:00-9:30 AM ET, the
     * regular session 9:30 AM-4:00 PM, after-hours to 8:00 PM. Screens use
     * this to say which prints they are showing instead of implying the
     * numbers are always live. Holidays are not modeled — those days simply
     * have no prints.
     */
    fun marketSessionNow(): MarketSession {
        val nowEt = java.time.ZonedDateTime.now(ZoneId.of("America/New_York"))
        if (nowEt.dayOfWeek == DayOfWeek.SATURDAY || nowEt.dayOfWeek == DayOfWeek.SUNDAY) {
            return MarketSession.WEEKEND
        }
        val t = nowEt.toLocalTime()
        return when {
            t.isBefore(java.time.LocalTime.of(4, 0)) -> MarketSession.OVERNIGHT
            t.isBefore(java.time.LocalTime.of(9, 30)) -> MarketSession.PRE
            t.isBefore(java.time.LocalTime.of(16, 0)) -> MarketSession.REGULAR
            t.isBefore(java.time.LocalTime.of(20, 0)) -> MarketSession.POST
            else -> MarketSession.OVERNIGHT
        }
    }

    /**
     * Today's ET wall-clock time expressed in Amman time, e.g. 9:30 ET ->
     * "4:30 PM". Derived from the live zone rules on both sides, so New
     * York's DST switches keep the conversion honest year-round (Amman has
     * stayed on UTC+3 since 2022).
     */
    fun etAsAmman(hour: Int, minute: Int = 0): String {
        val et = java.time.ZonedDateTime.now(ZoneId.of("America/New_York"))
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return et.withZoneSameInstant(ZoneId.of("Asia/Amman"))
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }

    /** Minutes until the US regular session opens; 0 once it has. */
    fun minutesToUsOpen(): Long {
        val et = ZoneId.of("America/New_York")
        val nowEt = java.time.ZonedDateTime.now(et)
        val open = nowEt.toLocalDate().atTime(9, 30).atZone(et)
        val diff = java.time.Duration.between(nowEt, open).toMinutes()
        return if (diff > 0) diff else 0
    }

    /**
     * The next-week preview window. The preview is built from Thursday on —
     * late enough in the week that the market has shown its hand — and stays
     * up through Monday, the day the plan gets executed. Tuesday and
     * Wednesday it rests; those days belong to the current week's plan.
     *
     * Returns whether the window is active now, plus the ISO Monday of the
     * week being previewed (Thu-Sun: the coming Monday; Monday itself: today).
     */
    fun nextWeekPreview(): Pair<Boolean, String> {
        val today = LocalDate.now()
        return when (today.dayOfWeek) {
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY ->
                true to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString()
            DayOfWeek.MONDAY -> true to today.toString()
            else -> false to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString()
        }
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
