package com.planlock.app.record

import com.planlock.app.record.dao.StrategySnapshotDao
import com.planlock.app.record.entity.StrategySnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StrategySyncTest {
    @Test fun pullRetainsLocalStrictModeByPlanIdAndUpdatesRemoteFields() = runBlocking {
        val dao = MemoryStrategies()
        dao.upsertAll(listOf(snapshot(1, 10).copy(strictMode = true)))
        dao.upsertRemote(listOf(snapshot(1, 10).copy(autoDnd = false), snapshot(2, 20)))
        assertEquals(true, dao.byPlanId(10)?.strictMode)
        assertEquals(false, dao.byPlanId(10)?.autoDnd)
        assertNull(dao.byPlanId(20)?.strictMode)
        dao.replacePlanId(10, 30)
        dao.upsertRemote(listOf(snapshot(101, 30).copy(version = 2)))
        assertEquals(true, dao.byPlanId(30)?.strictMode)
        assertEquals(2L, dao.byPlanId(30)?.version)
        assertEquals(2, dao.all().size)
    }

    @Test fun explicitFalseAndLegacyNullArePreserved() = runBlocking {
        val dao = MemoryStrategies()
        dao.upsertAll(listOf(snapshot(1, 10).copy(strictMode = false), snapshot(2, 20)))
        dao.upsertRemote(listOf(snapshot(1, 10), snapshot(2, 20)))
        assertEquals(false, dao.byPlanId(10)?.strictMode)
        assertNull(dao.byPlanId(20)?.strictMode)
    }

    private fun snapshot(id: Long, plan: Long) = StrategySnapshotEntity(id, plan, null, null, true, true, 1)

    private class MemoryStrategies : StrategySnapshotDao {
        private val rows = mutableMapOf<Long, StrategySnapshotEntity>()
        override suspend fun upsertAll(strategies: List<StrategySnapshotEntity>) { strategies.forEach { rows[it.id] = it } }
        override suspend fun all() = rows.values.toList()
        override suspend fun clear() = rows.clear()
        override suspend fun replacePlanId(oldPlanId: Long, newPlanId: Long) {
            rows.replaceAll { _, row -> if (row.planId == oldPlanId) row.copy(planId = newPlanId) else row }
        }
        override suspend fun byPlanId(planId: Long) = rows.values.firstOrNull { it.planId == planId }
        override suspend fun deleteByPlanId(planId: Long) { rows.entries.removeAll { it.value.planId == planId } }
    }
}
