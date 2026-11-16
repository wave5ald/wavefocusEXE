package com.example.util

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Routine day helpers.
 *
 * Tasks reset at a user-chosen time (default midnight), so the "routine day"
 * is the real calendar day shifted back by that many minutes. Everything the
 * user thinks of as "today" (tasks, streaks, focus sessions) uses this value,
 * while the Hijri calendar keeps showing the real calendar date.
 */
object RoutineDay {

    fun today(resetMinutes: Int): LocalDate =
        from(LocalDateTime.now(), resetMinutes)

    fun from(clock: LocalDateTime, resetMinutes: Int): LocalDate =
        clock.minusMinutes(resetMinutes.coerceIn(0, 1439).toLong()).toLocalDate()

    /** Parse "HH:mm" into minutes after midnight (falls back to 0). */
    fun parseMinutes(value: String?): Int {
        val raw = value?.trim().orEmpty()
        val parts = raw.split(":")
        if (parts.size != 2) return 0
        val hours = parts[0].toIntOrNull() ?: return 0
        val minutes = parts[1].toIntOrNull() ?: return 0
        return (hours.coerceIn(0, 23) * 60 + minutes.coerceIn(0, 59))
    }

    /** Format minutes after midnight as "HH:mm". */
    fun format(minutes: Int): String {
        val value = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(value / 60, value % 60)
    }

    /** 12-hour label, e.g. "12:00 PM". */
    fun format12(minutes: Int): String {
        val value = ((minutes % 1440) + 1440) % 1440
        val hour = value / 60
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(hour12, value % 60, if (hour < 12) "AM" else "PM")
    }
}
