package com.planlock.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViolationEventGateTest {

    @Test
    fun repeatedWindowEventsForOneLaunchAreRecordedOnce() {
        val gate = ViolationEventGate(windowMillis = 3_000)

        assertTrue(gate.shouldRecord(planId = 5, packageName = "com.google.android.youtube", elapsedMillis = 10_000))
        assertFalse(gate.shouldRecord(planId = 5, packageName = "com.google.android.youtube", elapsedMillis = 11_500))
        assertTrue(gate.shouldRecord(planId = 5, packageName = "com.google.android.youtube", elapsedMillis = 13_000))
    }

    @Test
    fun anotherPlanOrPackageIsANewViolation() {
        val gate = ViolationEventGate(windowMillis = 3_000)

        assertTrue(gate.shouldRecord(planId = 5, packageName = "one", elapsedMillis = 10_000))
        assertTrue(gate.shouldRecord(planId = 5, packageName = "two", elapsedMillis = 10_500))
        assertTrue(gate.shouldRecord(planId = 6, packageName = "two", elapsedMillis = 11_000))
    }
}
