package com.planlock.app.engine

/**
 * Collapses the repeated window-state callbacks emitted by one blocked app launch.
 */
internal class ViolationEventGate(
    private val windowMillis: Long = 3_000,
) {
    private var lastPlanId: Long? = null
    private var lastPackageName: String? = null
    private var lastRecordedAt: Long = Long.MIN_VALUE

    fun shouldRecord(planId: Long, packageName: String, elapsedMillis: Long): Boolean {
        val sameAttempt = lastPlanId == planId &&
            lastPackageName == packageName &&
            elapsedMillis - lastRecordedAt < windowMillis
        if (sameAttempt) return false

        lastPlanId = planId
        lastPackageName = packageName
        lastRecordedAt = elapsedMillis
        return true
    }
}
