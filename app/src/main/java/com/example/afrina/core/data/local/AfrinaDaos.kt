package com.example.afrina.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ProjectEntity)
}

@Dao
interface DevTaskDao {
    @Query("SELECT * FROM dev_tasks ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DevTaskEntity>>

    @Query("UPDATE dev_tasks SET status = :status, updatedAtEpochMs = :updatedAt WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, status: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DevTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DevTaskEntity)
}

@Dao
interface ApprovalDao {
    @Query("SELECT * FROM approval_requests ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ApprovalRequestEntity>>

    @Query("UPDATE approval_requests SET status = :status WHERE id = :approvalId")
    suspend fun updateStatus(approvalId: String, status: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ApprovalRequestEntity>)
}

@Dao
interface AgentActivityDao {
    @Query("SELECT * FROM agent_activities ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<AgentActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AgentActivityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AgentActivityEntity)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllRead()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<NotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: NotificationEntity)
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestampEpochMs DESC LIMIT 50")
    fun observeRecent(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<AuditLogEntity>)
}
