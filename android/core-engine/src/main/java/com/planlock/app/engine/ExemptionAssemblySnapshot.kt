package com.planlock.app.engine

/** Immutable record of one complete exemption-assembly pass. */
class ExemptionAssemblySnapshot private constructor(
    val generation: Long,
    val facts: Map<String, PackageFacts>,
    val rolePackages: Set<String>,
    val basicToolsEnabled: Boolean,
    val basicToolPackages: Set<String>,
    val exemptPackages: Set<String>,
) {
    val classifiedPackages: Set<String> = facts.keys

    fun classify(packageName: String, packageFacts: PackageFacts): ExemptionClassification =
        ExemptionClassifier.classify(
            packageName = packageName,
            facts = packageFacts,
            roles = PackageRoles(isBasicRole = packageName in rolePackages),
            basicToolsEnabled = basicToolsEnabled,
            basicToolPackages = basicToolPackages,
        )

    /** Recomputes the expected exemption set from exactly the facts captured by this snapshot. */
    fun audit(): Set<String> = expectedExemptions(
        facts = facts,
        rolePackages = rolePackages,
        basicToolsEnabled = basicToolsEnabled,
        basicToolPackages = basicToolPackages,
    ).symmetricDifference(exemptPackages)

    companion object {
        fun assemble(
            generation: Long,
            facts: Map<String, PackageFacts>,
            rolePackages: Set<String>,
            basicToolsEnabled: Boolean,
            basicToolPackages: Set<String>,
        ): ExemptionAssemblySnapshot {
            val stableFacts = facts.toMap()
            val stableRoles = rolePackages.toSet()
            val stableBasicTools = basicToolPackages.toSet()
            return ExemptionAssemblySnapshot(
                generation = generation,
                facts = stableFacts,
                rolePackages = stableRoles,
                basicToolsEnabled = basicToolsEnabled,
                basicToolPackages = stableBasicTools,
                exemptPackages = expectedExemptions(
                    facts = stableFacts,
                    rolePackages = stableRoles,
                    basicToolsEnabled = basicToolsEnabled,
                    basicToolPackages = stableBasicTools,
                ),
            )
        }

        private fun expectedExemptions(
            facts: Map<String, PackageFacts>,
            rolePackages: Set<String>,
            basicToolsEnabled: Boolean,
            basicToolPackages: Set<String>,
        ): Set<String> = facts.filter { (packageName, packageFacts) ->
            ExemptionClassifier.classify(
                packageName = packageName,
                facts = packageFacts,
                roles = PackageRoles(isBasicRole = packageName in rolePackages),
                basicToolsEnabled = basicToolsEnabled,
                basicToolPackages = basicToolPackages,
            ) != ExemptionClassification.RESTRICTED
        }.keys + rolePackages + "com.planlock.app"
    }
}

private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)

data class EnginePackageDecision(
    val planId: Long,
    val strictMode: Boolean,
    val snapshotGeneration: Long?,
    val classifiedInSnapshot: Boolean,
    val exemptInSnapshot: Boolean,
    val whitelisted: Boolean,
    val shouldBlock: Boolean,
)

enum class ExemptionGapKind {
    NONE,
    FACTS_MISSING_STATIC_EXEMPT,
    ASSEMBLY_MISMATCH,
    DOWNSTREAM_STALE_SNAPSHOT,
    DOWNSTREAM_STATE_MISMATCH,
    UNKNOWN_RESTRICTED,
    RUNTIME_FACTS_UNAVAILABLE,
}

data class ExemptionGap(
    val kind: ExemptionGapKind,
    val runtimeClassification: ExemptionClassification?,
    val snapshotAndEngineAgree: Boolean,
    val shouldBlockBeforeFix: Boolean,
)

data class RuntimeExemptionResolution(
    val gapKind: ExemptionGapKind,
    val runtimeClassification: ExemptionClassification?,
    val recovered: Boolean,
    val shouldBlock: Boolean,
)

/**
 * Fail-closed fallback for packages absent from an otherwise coherent assembly snapshot.
 * Runtime facts may only restore the exemption that the unchanged classifier already grants;
 * an unknown or restricted package remains blocked.
 */
object RuntimeExemptionGuard {
    fun resolve(
        engineDecision: EnginePackageDecision,
        gap: ExemptionGap,
    ): RuntimeExemptionResolution {
        val recoverableGap = gap.kind == ExemptionGapKind.FACTS_MISSING_STATIC_EXEMPT ||
            gap.kind == ExemptionGapKind.ASSEMBLY_MISMATCH
        val recovered = engineDecision.strictMode &&
            engineDecision.shouldBlock &&
            recoverableGap &&
            gap.runtimeClassification != null &&
            gap.runtimeClassification != ExemptionClassification.RESTRICTED
        return RuntimeExemptionResolution(
            gapKind = gap.kind,
            runtimeClassification = gap.runtimeClassification,
            recovered = recovered,
            shouldBlock = engineDecision.shouldBlock && !recovered,
        )
    }
}

/** Pure diagnostic: identifies which exemption-assembly boundary disagrees with runtime facts. */
object ExemptionGapAnalyzer {
    fun analyze(
        snapshot: ExemptionAssemblySnapshot,
        engineDecision: EnginePackageDecision,
        packageName: String,
        runtimeFacts: PackageFacts?,
    ): ExemptionGap {
        if (snapshot.generation != engineDecision.snapshotGeneration) {
            return gap(ExemptionGapKind.DOWNSTREAM_STALE_SNAPSHOT, null, false, engineDecision)
        }
        val snapshotClassified = packageName in snapshot.classifiedPackages
        val snapshotExempt = packageName in snapshot.exemptPackages
        if (
            snapshotClassified != engineDecision.classifiedInSnapshot ||
            snapshotExempt != engineDecision.exemptInSnapshot
        ) {
            return gap(ExemptionGapKind.DOWNSTREAM_STATE_MISMATCH, null, false, engineDecision)
        }
        if (runtimeFacts == null) {
            return gap(ExemptionGapKind.RUNTIME_FACTS_UNAVAILABLE, null, true, engineDecision)
        }
        val runtimeClassification = snapshot.classify(packageName, runtimeFacts)
        val kind = when {
            !snapshotClassified && runtimeClassification != ExemptionClassification.RESTRICTED ->
                ExemptionGapKind.FACTS_MISSING_STATIC_EXEMPT
            !snapshotClassified -> ExemptionGapKind.UNKNOWN_RESTRICTED
            !snapshotExempt && runtimeClassification != ExemptionClassification.RESTRICTED ->
                ExemptionGapKind.ASSEMBLY_MISMATCH
            else -> ExemptionGapKind.NONE
        }
        return gap(kind, runtimeClassification, true, engineDecision)
    }

    private fun gap(
        kind: ExemptionGapKind,
        classification: ExemptionClassification?,
        agrees: Boolean,
        decision: EnginePackageDecision,
    ) = ExemptionGap(
        kind = kind,
        runtimeClassification = classification,
        snapshotAndEngineAgree = agrees,
        shouldBlockBeforeFix = decision.shouldBlock,
    )
}

/** Process-local bridge between the scheduler's assembly pass and accessibility diagnostics. */
object ExemptionDiagnostics {
    @Volatile private var snapshot: ExemptionAssemblySnapshot? = null

    fun publish(value: ExemptionAssemblySnapshot) {
        snapshot = value
    }

    fun latest(): ExemptionAssemblySnapshot? = snapshot

    fun clear() {
        snapshot = null
    }
}
