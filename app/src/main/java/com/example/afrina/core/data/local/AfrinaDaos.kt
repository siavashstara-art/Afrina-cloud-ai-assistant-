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

@Dao
interface AiUsageLedgerDao {
    @Query("SELECT * FROM ai_usage_ledger ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<AiUsageLedgerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AiUsageLedgerEntity)
}

@Dao
interface PaidInferenceApprovalDao {
    @Query("SELECT * FROM paid_inference_approvals ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<PaidInferenceApprovalEntity>>

    @Query("SELECT * FROM paid_inference_approvals WHERE id = :proposalId LIMIT 1")
    suspend fun getById(proposalId: String): PaidInferenceApprovalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PaidInferenceApprovalEntity)

    @Query(
        "UPDATE paid_inference_approvals SET status = :newStatus, updatedAtEpochMs = :updatedAt " +
            "WHERE id = :proposalId AND status = :expectedStatus"
    )
    suspend fun transitionStatus(
        proposalId: String,
        expectedStatus: String,
        newStatus: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE paid_inference_approvals SET status = :executingStatus, updatedAtEpochMs = :updatedAt " +
            "WHERE id = :proposalId AND status = :expectedStatus AND isExecuted = 0"
    )
    suspend fun claimForExecution(
        proposalId: String,
        expectedStatus: String,
        executingStatus: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE paid_inference_approvals SET status = :finalStatus, isExecuted = 1, updatedAtEpochMs = :updatedAt " +
            "WHERE id = :proposalId AND status = :executingStatus AND isExecuted = 0"
    )
    suspend fun markExecutedFromExecuting(
        proposalId: String,
        executingStatus: String,
        finalStatus: String,
        updatedAt: Long,
    ): Int
}
