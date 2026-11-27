package com.example.afrina.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameFa: String,
    val slug: String,
    val description: String,
    val descriptionFa: String,
    val stackTagsCsv: String,
    val healthStatus: String,
    val repoFullName: String,
    val defaultBranch: String,
    val lastCommitSha: String,
    val lastBuildStatus: String,
    val activeEnvironment: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "dev_tasks")
data class DevTaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val titleFa: String,
    val priority: String,
    val status: String,
    val assignedAgent: String?,
    val requiresApproval: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "approval_requests")
data class ApprovalRequestEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val agentRole: String,
    val actionTitleEn: String,
    val actionTitleFa: String,
    val commandPreview: String,
    val riskLevel: String,
    val status: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "agent_activities")
data class AgentActivityEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val agentRole: String,
    val summaryEn: String,
    val summaryFa: String,
    val toolInvoked: String,
    val state: String,
    val timestampLabel: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val titleEn: String,
    val titleFa: String,
    val bodyEn: String,
    val bodyFa: String,
    val severity: String,
    val isRead: Boolean,
    val timestampLabel: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val actor: String,
    val action: String,
    val target: String,
    val outcome: String,
    val timestampEpochMs: Long,
)

@Entity(tableName = "ai_usage_ledger")
data class AiUsageLedgerEntity(
    @PrimaryKey val id: String,
    val persona: String,
    val providerId: String,
    val modelId: String,
    val taskTitle: String,
    val usageCategory: String,
    val dataClassification: String,
    val routingKind: String,
    val fallbackTriggered: Boolean,
    val fallbackReason: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedApiCostUsd: Double,
    val estimatedInfraCostUsd: Double,
    val isSimulated: Boolean,
    val timestampEpochMs: Long,
)

@Entity(tableName = "paid_inference_approvals")
data class PaidInferenceApprovalEntity(
    @PrimaryKey val id: String,
    val persona: String,
    val taskTitle: String,
    val complexity: String,
    val requiresCoding: Boolean,
    val requiredModalitiesCsv: String,
    val dataClassification: String,
    val explicitSensitiveAuthorized: Boolean,
    val targetProviderId: String,
    val targetModelId: String,
    val estimatedCostUsd: Double,
    val reasonEn: String,
    val reasonFa: String,
    val status: String,
    val isExecuted: Boolean,
    val isSimulated: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
