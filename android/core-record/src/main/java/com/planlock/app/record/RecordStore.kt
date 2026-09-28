package com.planlock.app.record

import android.content.Context
import com.planlock.app.api.dto.ExecutionRecordRequest
import com.planlock.app.api.dto.SyncPlanView
import com.planlock.app.api.dto.SyncStrategyView
import com.planlock.app.api.dto.SyncScheduleView
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.api.dto.UnlockRecordRequest
import com.planlock.app.api.dto.ViolationRecordRequest
import com.planlock.app.record.entity.PendingRecordEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 本地记录采集 + 计划/策略快照缓存 + 离线补传队列（Room）。
 */
class RecordStore(context: Context) {

    private val planDao = PlanLockDatabase.get(context).planSnapshotDao()
    private val strategyDao = PlanLockDatabase.get(context).strategySnapshotDao()
    private val pendingDao = PlanLockDatabase.get(context).pendingRecordDao()
    private val scheduleDao = PlanLockDatabase.get(context).scheduleSnapshotDao()

    suspend fun saveSnapshots(plans: List<SyncPlanView>, strategies: List<SyncStrategyView>, schedules: List<SyncScheduleView> = emptyList()) {
        planDao.upsertAll(plans.map {
            PlanSnapshotEntity(
                id = it.id,
                userId = it.userId,
                name = it.name,
                type = it.type,
                startTime = it.startTime,
                endTime = it.endTime,
                repeatRule = it.repeatRule,
                color = it.color,
                icon = it.icon,
                remindAdvanceMin = it.remindAdvanceMin,
                remindMethods = it.remindMethods,
                status = it.status,
                version = it.version
            )
        })
        strategyDao.upsertRemote(strategies.map {
            StrategySnapshotEntity(
                id = it.id,
                planId = it.planId,
                blacklistApps = it.blacklistApps,
                whitelistApps = it.whitelistApps,
                autoDnd = it.autoDnd,
                notifySilence = it.notifySilence,
                version = it.version
            )
        })
        scheduleDao.upsertAll(schedules.map { it.toEntity() })
    }

    suspend fun enqueueExecution(req: ExecutionRecordRequest) {
        insertPending(
            PendingRecordEntity(
                type = "execution",
                planId = req.planId,
                planDate = req.planDate,
                status = req.status,
                startedAt = req.startedAt,
                endedAt = req.endedAt,
                clientId = req.clientId
            )
        )
    }

    suspend fun enqueueViolation(req: ViolationRecordRequest) {
        insertPending(
            PendingRecordEntity(
                type = "violation",
                planId = req.planId,
                recordAt = req.recordAt,
                appName = req.appName,
                packageName = req.packageName,
                clientId = req.clientId
            )
        )
    }

    suspend fun enqueueUnlock(req: UnlockRecordRequest) {
        insertPending(
            PendingRecordEntity(
                type = "unlock",
                planId = req.planId,
                unlockAt = req.unlockAt,
                method = req.method,
                clientId = req.clientId
            )
        )
    }

    suspend fun pending(): List<PendingRecordEntity> = pendingDao.all()

    suspend fun removePending(ids: List<Long>) = pendingDao.delete(ids)

    private suspend fun insertPending(record: PendingRecordEntity) = pendingMutex.withLock {
        if (pendingDao.countByClientId(record.clientId) == 0) pendingDao.insert(record)
    }

    // ---- 本地为源闭环（BUG-005）：计划/策略先落 Room，网络可用时再同步 ----

    suspend fun saveLocalPlan(plan: PlanSnapshotEntity) = planDao.upsertAll(listOf(plan))

    suspend fun saveLocalStrategy(strategy: StrategySnapshotEntity) =
        strategyDao.upsertAll(listOf(strategy))

    suspend fun plans(): List<PlanSnapshotEntity> = planDao.all()

    suspend fun plan(id: Long): PlanSnapshotEntity? = planDao.byId(id)

    suspend fun strategy(planId: Long): StrategySnapshotEntity? = strategyDao.byPlanId(planId)

    suspend fun updateLocalPlan(plan: PlanSnapshotEntity) = planDao.upsertAll(listOf(plan))

    suspend fun updateLocalStrategy(strategy: StrategySnapshotEntity) =
        strategyDao.upsertAll(listOf(strategy))

    suspend fun deletePlan(id: Long) {
        scheduleDao.markPlanDeleted(id)
        planDao.delete(id)
        strategyDao.deleteByPlanId(id)
    }

    suspend fun replacePlanId(localId: Long, serverId: Long) = planDao.replaceId(localId, serverId)

    suspend fun replaceStrategyPlanId(localPlanId: Long, serverPlanId: Long) =
        strategyDao.replacePlanId(localPlanId, serverPlanId)

    suspend fun schedules(): List<ScheduleSnapshotEntity> = scheduleDao.all()
    suspend fun schedule(id: Long): ScheduleSnapshotEntity? = scheduleDao.byId(id)
    suspend fun saveLocalSchedule(item: ScheduleSnapshotEntity) = scheduleDao.upsertAll(listOf(item))
    suspend fun deleteLocalSchedule(id: Long) = scheduleDao.delete(id)

    suspend fun enqueueScheduleUpsert(localId: Long, baseVersion: Long?, request: ScheduleUpsertRequest) = insertPending(
        PendingRecordEntity(type = "schedule_upsert", planId = null, clientId = "schedule-$localId-${UUID.randomUUID()}", payload = Json.encodeToString(SchedulePendingUpsert(localId, baseVersion, request))).also {
            require(PlanOccurrence.isSupportedRule(request.repeatRule)) { "暂不支持该重复规则" }
        }
    )

    suspend fun enqueueScheduleDelete(id: Long) = insertPending(
        PendingRecordEntity(type = "schedule_delete", planId = null, clientId = "schedule-delete-$id", payload = id.toString())
    )

    companion object {
        private val pendingMutex = Mutex()
    }
}

@kotlinx.serialization.Serializable
data class SchedulePendingUpsert(val localId: Long, val baseVersion: Long?, val request: ScheduleUpsertRequest)

private fun SyncScheduleView.toEntity() = ScheduleSnapshotEntity(
    id, userId, title, startDate, startTime, endDate, endTime, allDay, location, note,
    remindAdvanceMin, repeatRule, linkedPlanId, false, tzId, status, version,
)
