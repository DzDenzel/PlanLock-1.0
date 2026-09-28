package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.PlanOccurrence

object PlanTime {
    fun minutesOfDay(value: String?): Int? {
        val parts = value?.split(":") ?: return null
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun isActive(plan: PlanSnapshotEntity, nowMinutes: Int, isoDayOfWeek: Int): Boolean =
        PlanOccurrence.isActive(plan, nowMinutes, isoDayOfWeek)

    fun isWithinClockWindow(plan: PlanSnapshotEntity, nowMinutes: Int): Boolean {
        val start = minutesOfDay(plan.startTime) ?: return false
        val end = minutesOfDay(plan.endTime) ?: return false
        if (start == end) return false
        return if (end > start) nowMinutes in start until end else nowMinutes >= start || nowMinutes < end
    }

    fun progress(plan: PlanSnapshotEntity, nowMinutes: Int): Float {
        val start = minutesOfDay(plan.startTime) ?: return 0f
        val end = minutesOfDay(plan.endTime) ?: return 0f
        if (end == start) return 0f
        val duration = if (end > start) end - start else 24 * 60 - start + end
        if (duration <= 0) return 0f
        val elapsed = if (end > start) nowMinutes - start else if (nowMinutes >= start) nowMinutes - start else 24 * 60 - start + nowMinutes
        return (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    }

    fun remainingMinutes(plan: PlanSnapshotEntity, nowMinutes: Int): Int {
        val end = minutesOfDay(plan.endTime) ?: return 0
        val start = minutesOfDay(plan.startTime) ?: return 0
        if (end == start) return 0
        return if (end >= nowMinutes) end - nowMinutes else 24 * 60 - nowMinutes + end
    }

    fun format(value: String?): String = value?.take(5) ?: "--:--"
}
