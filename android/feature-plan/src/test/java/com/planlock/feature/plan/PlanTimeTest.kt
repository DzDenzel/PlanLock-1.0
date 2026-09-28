package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTimeTest {
    private fun plan(start: String, end: String) = PlanSnapshotEntity(
        id = 1, userId = 0, name = "test", type = "学习", startTime = start, endTime = end,
        repeatRule = null, color = null, icon = null, remindAdvanceMin = 15,
        remindMethods = null, status = 1, version = 0
    )

    @Test
    fun crossDayProgressUsesSameTimeRangeAsActiveState() {
        val value = plan("23:00:00", "01:00:00")
        assertTrue(PlanTime.isActive(value, 0, 1))
        assertEquals(.5f, PlanTime.progress(value, 0), .001f)
        assertEquals(60, PlanTime.remainingMinutes(value, 0))
    }
}
