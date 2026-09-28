package com.planlock.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 执行引擎：维护当前执行计划的黑/白名单，判断前台应用是否应被拦截。
 * 与业务 UI 完全解耦，可单独真机验证（一加 / ColorOS）。
 */
object ExecutionEngine {

    private data class State(
        val planId: Long,
        val blacklist: Set<String>,
        val whitelist: Set<String>,
        val strictMode: Boolean,
        val exemptions: Set<String>,
        val classifiedPackages: Set<String>,
        val exemptionSnapshotGeneration: Long?,
    )

    // 调度服务与无障碍回调读取同一份完整策略，避免切换时混用两份名单。
    @Volatile private var state: State? = null
    private val _stateVersion = MutableStateFlow(0L)

    /** UI 仅订阅版本变化，具体锁定结论仍统一由 [inspect] 给出。 */
    val stateVersion: StateFlow<Long> = _stateVersion.asStateFlow()

    fun start(
        planId: Long,
        blacklistPackages: Set<String>,
        whitelistPackages: Set<String>,
        strictMode: Boolean = false,
        exemptPackages: Set<String> = emptySet(),
        classifiedPackages: Set<String> = emptySet(),
        exemptionSnapshotGeneration: Long? = null,
    ) {
        val next = State(
            planId = planId,
            blacklist = blacklistPackages.toSet(),
            whitelist = whitelistPackages.toSet(),
            strictMode = strictMode,
            exemptions = exemptPackages.toSet(),
            classifiedPackages = classifiedPackages.toSet(),
            exemptionSnapshotGeneration = exemptionSnapshotGeneration,
        )
        if (state != next) {
            state = next
            _stateVersion.update { it + 1 }
        }
    }

    fun stop() {
        if (state != null) {
            state = null
            _stateVersion.update { it + 1 }
        }
    }

    fun isRunning(): Boolean = state != null

    fun activePlanId(): Long? = state?.planId

    /** 严格模式仅允许豁免与白名单；普通模式保留黑名单语义。 */
    fun shouldBlock(packageName: String?): Boolean {
        return inspect(packageName)?.shouldBlock ?: false
    }

    /** Returns the exact immutable state used for the current package decision. */
    fun inspect(packageName: String?): EnginePackageDecision? {
        val current = state ?: return null
        if (packageName.isNullOrBlank() || packageName == "com.planlock.app") {
            return EnginePackageDecision(
                planId = current.planId,
                strictMode = current.strictMode,
                snapshotGeneration = current.exemptionSnapshotGeneration,
                classifiedInSnapshot = packageName in current.classifiedPackages,
                exemptInSnapshot = packageName in current.exemptions,
                whitelisted = packageName in current.whitelist,
                shouldBlock = false,
            )
        }
        val whitelisted = packageName in current.whitelist
        val exempt = packageName in current.exemptions
        val shouldBlock = !whitelisted && if (current.strictMode) !exempt else packageName in current.blacklist
        return EnginePackageDecision(
            planId = current.planId,
            strictMode = current.strictMode,
            snapshotGeneration = current.exemptionSnapshotGeneration,
            classifiedInSnapshot = packageName in current.classifiedPackages,
            exemptInSnapshot = exempt,
            whitelisted = whitelisted,
            shouldBlock = shouldBlock,
        )
    }
}
