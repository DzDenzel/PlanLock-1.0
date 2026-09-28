package com.planlock.app.sync

import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.dto.SyncPullResponse
import com.planlock.app.record.PendingUpload
import com.planlock.app.record.RecordStore
import com.planlock.app.record.toPendingUpload
import com.planlock.app.record.SchedulePendingUpsert
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同步客户端：进入 App 全量拉取、离线补传队列推送（client_id 幂等）。
 */
class SyncClient(
    private val api: PlanLockApi,
    private val recordStore: RecordStore
) {

    /** 进入 App 全量拉取并落本地快照（计划/策略）。 */
    suspend fun pull(): SyncPullResponse? {
        val resp = api.syncPull()
        if (resp.code == 0) {
            resp.data?.let {
                recordStore.saveSnapshots(it.plans ?: emptyList(), it.strategies ?: emptyList(), it.schedules ?: emptyList())
            }
        }
        return resp.data
    }

    /** 推送离线补传队列（执行/违规/解锁），成功即从队列删除。 */
    suspend fun pushPendingRecords() = pushMutex.withLock {
        val pending = recordStore.pending()
        val synced = mutableListOf<Long>()
        for (p in pending) {
            val ok = try {
                when (p.type) {
                    "schedule_upsert" -> {
                        val item = Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty())
                        val baseVersion = item.baseVersion
                        val response = if (baseVersion == null) api.createSchedule(item.request)
                        else api.updateSchedule(item.localId, baseVersion, item.request)
                        val remote = response.data
                        if (response.code == 0 && remote != null) {
                            recordStore.saveLocalSchedule(ScheduleSnapshotEntity(remote.id, remote.userId, remote.title, remote.startDate, remote.startTime, remote.endDate, remote.endTime, remote.allDay, remote.location, remote.note, remote.remindAdvanceMin, remote.repeatRule, remote.linkedPlanId, false, remote.tzId, remote.status, remote.version))
                            if (item.baseVersion == null && item.localId != remote.id) recordStore.deleteLocalSchedule(item.localId)
                            true
                        } else false
                    }
                    "schedule_delete" -> api.deleteSchedule(p.payload.orEmpty().toLong()).code == 0
                    else -> when (val upload = p.toPendingUpload()) {
                        is PendingUpload.Execution -> api.reportExecution(upload.request).code == 0
                        is PendingUpload.Violation -> api.reportViolation(upload.request).code == 0
                        is PendingUpload.Unlock -> api.reportUnlock(upload.request).code == 0
                        null -> false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                synced.add(p.id)
            }
        }
        if (synced.isNotEmpty()) {
            recordStore.removePending(synced)
        }
    }

    companion object {
        private val pushMutex = Mutex()
    }
}
