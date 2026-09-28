package com.planlock.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExemptionAssemblySnapshotTest {
    private val ordinary = PackageFacts(uid = 10000, systemFlag = false, hasLauncherEntry = true)

    @Test
    fun assemblyMatchesClassifierForTheSameFactsAndAddsRoleAndOwnPackage() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 7,
            facts = mapOf(
                "system.uid" to ordinary.copy(uid = 9999),
                "system.hidden" to ordinary.copy(systemFlag = true, hasLauncherEntry = false),
                "ordinary" to ordinary,
                "basic" to ordinary,
                "role" to ordinary,
            ),
            rolePackages = setOf("role"),
            basicToolsEnabled = true,
            basicToolPackages = setOf("basic"),
        )

        assertEquals(7L, snapshot.generation)
        assertEquals(
            setOf("system.uid", "system.hidden", "basic", "role", "com.planlock.app"),
            snapshot.exemptPackages,
        )
        assertEquals(
            setOf("system.uid", "system.hidden", "ordinary", "basic", "role"),
            snapshot.classifiedPackages,
        )
        assertTrue(snapshot.audit().isEmpty())
    }

    @Test
    fun auditDetectsSystemPackageMissingFromTheAssembledFacts() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 9,
            facts = mapOf("ordinary" to ordinary),
            rolePackages = emptySet(),
            basicToolsEnabled = false,
            basicToolPackages = emptySet(),
        )
        val engineDecision = EnginePackageDecision(
            planId = 6,
            strictMode = true,
            snapshotGeneration = 9,
            classifiedInSnapshot = false,
            exemptInSnapshot = false,
            whitelisted = false,
            shouldBlock = true,
        )

        val gap = ExemptionGapAnalyzer.analyze(
            snapshot = snapshot,
            engineDecision = engineDecision,
            packageName = "missing.system",
            runtimeFacts = ordinary.copy(uid = 1000, systemFlag = true, hasLauncherEntry = false),
        )

        assertEquals(ExemptionGapKind.FACTS_MISSING_STATIC_EXEMPT, gap.kind)
        assertEquals(ExemptionClassification.SYSTEM_CORE, gap.runtimeClassification)
        assertTrue(gap.shouldBlockBeforeFix)

        val recovery = RuntimeExemptionGuard.resolve(engineDecision, gap)

        assertTrue(recovery.recovered)
        assertFalse(recovery.shouldBlock)
    }

    @Test
    fun absentOrdinaryPackageRemainsRestrictedAndIsDistinguishedFromMissingExemption() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 10,
            facts = emptyMap(),
            rolePackages = emptySet(),
            basicToolsEnabled = false,
            basicToolPackages = emptySet(),
        )
        val engineDecision = EnginePackageDecision(
            planId = 6,
            strictMode = true,
            snapshotGeneration = 10,
            classifiedInSnapshot = false,
            exemptInSnapshot = false,
            whitelisted = false,
            shouldBlock = true,
        )

        val gap = ExemptionGapAnalyzer.analyze(
            snapshot = snapshot,
            engineDecision = engineDecision,
            packageName = "missing.ordinary",
            runtimeFacts = ordinary,
        )

        assertEquals(ExemptionGapKind.UNKNOWN_RESTRICTED, gap.kind)
        assertEquals(ExemptionClassification.RESTRICTED, gap.runtimeClassification)
        assertTrue(gap.shouldBlockBeforeFix)

        val recovery = RuntimeExemptionGuard.resolve(engineDecision, gap)

        assertFalse(recovery.recovered)
        assertTrue(recovery.shouldBlock)
    }

    @Test
    fun unavailableRuntimeFactsFailClosedInsteadOfOpeningUnknownPackage() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 12,
            facts = emptyMap(),
            rolePackages = emptySet(),
            basicToolsEnabled = false,
            basicToolPackages = emptySet(),
        )
        val engineDecision = EnginePackageDecision(
            planId = 6,
            strictMode = true,
            snapshotGeneration = 12,
            classifiedInSnapshot = false,
            exemptInSnapshot = false,
            whitelisted = false,
            shouldBlock = true,
        )
        val gap = ExemptionGapAnalyzer.analyze(snapshot, engineDecision, "missing", null)

        val recovery = RuntimeExemptionGuard.resolve(engineDecision, gap)

        assertEquals(ExemptionGapKind.RUNTIME_FACTS_UNAVAILABLE, recovery.gapKind)
        assertFalse(recovery.recovered)
        assertTrue(recovery.shouldBlock)
    }

    @Test
    fun engineSnapshotMismatchIsReportedBeforePackageClassification() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 11,
            facts = mapOf("system" to ordinary.copy(uid = 1000)),
            rolePackages = emptySet(),
            basicToolsEnabled = false,
            basicToolPackages = emptySet(),
        )
        val engineDecision = EnginePackageDecision(
            planId = 6,
            strictMode = true,
            snapshotGeneration = 10,
            classifiedInSnapshot = false,
            exemptInSnapshot = false,
            whitelisted = false,
            shouldBlock = true,
        )

        val gap = ExemptionGapAnalyzer.analyze(snapshot, engineDecision, "system", ordinary.copy(uid = 1000))

        assertEquals(ExemptionGapKind.DOWNSTREAM_STALE_SNAPSHOT, gap.kind)
        assertFalse(gap.snapshotAndEngineAgree)
    }
}
