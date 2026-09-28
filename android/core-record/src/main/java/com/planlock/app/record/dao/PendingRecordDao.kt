package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.planlock.app.record.entity.PendingRecordEntity

@Dao
interface PendingRecordDao {

    @Insert
    suspend fun insert(record: PendingRecordEntity)

    @Query("SELECT COUNT(*) FROM pending_record WHERE clientId = :clientId")
    suspend fun countByClientId(clientId: String): Int

    @Query("SELECT * FROM pending_record ORDER BY createdAt ASC")
    suspend fun all(): List<PendingRecordEntity>

    @Query("DELETE FROM pending_record WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
