package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.planlock.app.record.entity.StrategySnapshotEntity

@Dao
interface StrategySnapshotDao {

    @Upsert
    suspend fun upsertAll(strategies: List<StrategySnapshotEntity>)

    /** 服务端没有 strictMode；拉取快照时在同一事务中保留本机选择。 */
    @Transaction
    suspend fun upsertRemote(strategies: List<StrategySnapshotEntity>) {
        upsertAll(strategies.map { remote ->
            val localStrictMode = byPlanId(remote.planId)?.strictMode
            // 首次同步的服务端策略 ID 可能与本地临时 ID 不同，避免同一计划留下两份策略。
            deleteByPlanId(remote.planId)
            remote.copy(strictMode = localStrictMode)
        })
    }

    @Query("SELECT * FROM strategy_snapshot")
    suspend fun all(): List<StrategySnapshotEntity>

    @Query("DELETE FROM strategy_snapshot")
    suspend fun clear()

    @Query("UPDATE strategy_snapshot SET planId = :newPlanId WHERE planId = :oldPlanId")
    suspend fun replacePlanId(oldPlanId: Long, newPlanId: Long)

    @Query("SELECT * FROM strategy_snapshot WHERE planId = :planId")
    suspend fun byPlanId(planId: Long): StrategySnapshotEntity?

    @Query("DELETE FROM strategy_snapshot WHERE planId = :planId")
    suspend fun deleteByPlanId(planId: Long)
}
