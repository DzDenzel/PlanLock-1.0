package com.planlock.app.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExecutionEngineTest {
    @Before fun reset() = ExecutionEngine.stop()
    @After fun cleanUp() = ExecutionEngine.stop()

    @Test
    fun legacyThreeArgumentStartOnlyBlocksBlacklistedAppsWithWhitelistTakingPriority() {
        ExecutionEngine.start(7, setOf("blocked", "both"), setOf("both"))
        assertTrue(ExecutionEngine.shouldBlock("blocked"))
        assertFalse(ExecutionEngine.shouldBlock("both"))
        assertFalse(ExecutionEngine.shouldBlock("unlisted"))
        assertEquals(7L, ExecutionEngine.activePlanId())
    }

    @Test
    fun strictModeBlocksUnlistedAppsAndIgnoresBlacklistForExemptAndAllowedApps() {
        ExecutionEngine.start(
            7,
            setOf("core", "allowed"),
            setOf("allowed"),
            true,
            setOf("core"),
            classifiedPackages = setOf("core", "allowed", "unlisted"),
            exemptionSnapshotGeneration = 12,
        )
        assertTrue(ExecutionEngine.shouldBlock("unlisted"))
        assertFalse(ExecutionEngine.shouldBlock("core"))
        assertFalse(ExecutionEngine.shouldBlock("allowed"))
        assertEquals(
            EnginePackageDecision(
                planId = 7,
                strictMode = true,
                snapshotGeneration = 12,
                classifiedInSnapshot = true,
                exemptInSnapshot = true,
                whitelisted = false,
                shouldBlock = false,
            ),
            ExecutionEngine.inspect("core"),
        )
    }

    @Test
    fun ownPackageAndMissingForegroundAreNeverBlockedInEitherMode() {
        for (strict in listOf(false, true)) {
            ExecutionEngine.start(7, setOf("com.planlock.app"), emptySet(), strict, emptySet())
            assertFalse(ExecutionEngine.shouldBlock("com.planlock.app"))
            assertFalse(ExecutionEngine.shouldBlock(null))
            assertFalse(ExecutionEngine.shouldBlock(" "))
        }
    }

    @Test
    fun switchingToLegacyModeDropsStrictModeAndIgnoresExemptions() {
        ExecutionEngine.start(7, emptySet(), emptySet(), true, setOf("core"))
        ExecutionEngine.start(8, setOf("core"), emptySet())
        assertTrue(ExecutionEngine.shouldBlock("core"))
        assertFalse(ExecutionEngine.shouldBlock("unlisted"))
    }

    @Test
    fun stoppingReleasesRestrictionsAndNextStrictPlanGetsFreshExemptions() {
        ExecutionEngine.start(7, emptySet(), emptySet(), true, setOf("previousCore"))
        ExecutionEngine.stop()
        assertFalse(ExecutionEngine.isRunning())
        assertNull(ExecutionEngine.activePlanId())
        assertFalse(ExecutionEngine.shouldBlock("unlisted"))
        ExecutionEngine.start(8, emptySet(), emptySet(), true, emptySet())
        assertTrue(ExecutionEngine.shouldBlock("previousCore"))
    }

    @Test
    fun stateVersionChangesWhenRestrictionsStartAndStop() {
        val beforeStart = ExecutionEngine.stateVersion.value

        ExecutionEngine.start(9, setOf("blocked"), emptySet())
        val afterStart = ExecutionEngine.stateVersion.value
        ExecutionEngine.stop()
        val afterStop = ExecutionEngine.stateVersion.value

        assertTrue(afterStart > beforeStart)
        assertTrue(afterStop > afterStart)
    }

    @Test
    fun identicalSchedulerTicksDoNotPublishRedundantUiVersions() {
        ExecutionEngine.start(9, setOf("blocked"), setOf("allowed"))
        val afterFirstTick = ExecutionEngine.stateVersion.value

        ExecutionEngine.start(9, setOf("blocked"), setOf("allowed"))

        assertEquals(afterFirstTick, ExecutionEngine.stateVersion.value)
    }
}
