package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity

/** The only decision for whether a saved plan executes at a given local day and minute. */
object PlanOccurrence {
    private val typePattern = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
    private val weekdaysPattern = Regex("\"weekdays\"\\s*:\\s*\\[([^]]*)]")

    /** [isoDayOfWeek] uses Monday=1 ... Sunday=7. Time and day come from the caller. */
    fun isActive(plan: PlanSnapshotEntity, nowMinutes: Int, isoDayOfWeek: Int): Boolean =
        plan.status == 1 && isWithinOccurrence(plan, nowMinutes, isoDayOfWeek)

    /** Whether the clock window and repeat rule match, intentionally ignoring plan status. */
    fun isWithinOccurrence(plan: PlanSnapshotEntity, nowMinutes: Int, isoDayOfWeek: Int): Boolean {
        if (nowMinutes !in 0 until 1440 || isoDayOfWeek !in 1..7) return false
        val start = minutesOfDay(plan.startTime) ?: return false
        val end = minutesOfDay(plan.endTime) ?: return false
        if (end == start) return false
        val inWindow = if (end > start) nowMinutes in start until end
        else nowMinutes >= start || nowMinutes < end
        if (!inWindow) return false

        // The after-midnight tail belongs to the preceding start day.
        val startDay = if (end < start && nowMinutes < end) previousDay(isoDayOfWeek) else isoDayOfWeek
        return activeDays(plan.repeatRule)?.contains(startDay) ?: true
    }

    /** Whether the repeat rule schedules this plan on the supplied start day. Status is intentionally ignored. */
    fun occursOn(plan: PlanSnapshotEntity, isoDayOfWeek: Int): Boolean {
        if (isoDayOfWeek !in 1..7) return false
        return occursOn(plan.repeatRule, isoDayOfWeek)
    }

    /** Shared repeat vocabulary for plans and schedules. */
    fun occursOn(repeatRule: String?, isoDayOfWeek: Int): Boolean =
        isoDayOfWeek in 1..7 && (activeDays(repeatRule)?.contains(isoDayOfWeek) ?: true)

    fun isSupportedRule(repeatRule: String?): Boolean {
        val rule = repeatRule?.trim() ?: return true
        if (rule.isEmpty()) return true
        if (!rule.startsWith('{') || !rule.endsWith('}')) return false
        return when (typePattern.find(rule)?.groupValues?.get(1)) {
            "daily" -> true
            "weekly" -> weekdaysPattern.find(rule)?.groupValues?.get(1)?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }?.let { it.isNotEmpty() && it.all { day -> day in 1..7 } } == true
            else -> false
        }
    }

    fun isoDayOfWeek(calendarDay: Int): Int = ((calendarDay + 5) % 7) + 1

    private fun previousDay(day: Int): Int = if (day == 1) 7 else day - 1

    private fun minutesOfDay(value: String?): Int? {
        val parts = value?.split(':') ?: return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Null means the conservative daily fallback for legacy or unreadable rules. */
    private fun activeDays(raw: String?): Set<Int>? {
        val rule = raw?.trim() ?: return null
        if (!rule.startsWith('{') || !rule.endsWith('}')) return null
        return when (typePattern.find(rule)?.groupValues?.get(1)) {
            "daily" -> null
            "weekly" -> {
                val values = weekdaysPattern.find(rule)?.groupValues?.get(1)?.trim() ?: return null
                if (values.isEmpty()) return null
                val days = values.split(',').map { it.trim().toIntOrNull() ?: return null }
                if (days.any { it !in 1..7 }) null else days.toSet()
            }
            else -> null
        }
    }
}
