package com.example.afrina.core.model

enum class AppLanguage(val code: String, val isRtl: Boolean) {
    PERSIAN("fa", true),
    ENGLISH("en", false),
}

enum class ThemePreference {
    SYSTEM,
    DARK,
    LIGHT,
}

enum class ImplementationOrigin {
    LOCAL_PERSISTED,
    SIMULATED_PHASE_0,
    PLANNED_REMOTE,
}

enum class ProjectHealthStatus {
    HEALTHY,
    WARNING,
    DEGRADED,
    IDLE,
}

enum class TaskPriority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
}

enum class TaskStatus {
    OPEN,
    IN_PROGRESS,
    REVIEW,
    DONE,
}

enum class AgentRole {
    ARCHITECT,
    PROGRAMMER,
    CODE_REVIEWER,
    DEBUGGER,
    TESTER,
    SECURITY_AUDITOR,
    DEPLOYMENT_ASSISTANT,
}

enum class AgentExecutionState {
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    IDLE,
}

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    EXECUTING,
    FAILED,
    REJECTED,
}

enum class PipelineStatus {
    PASSED,
    RUNNING,
    FAILED,
    QUEUED,
}

enum class DeploymentTarget {
    API_CONTAINER,
    WEB_APP,
    PWA,
    WORKER_NODE,
}

enum class NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    CRITICAL,
}

data class AfrinaProject(
    val id: String,
    val name: String,
    val nameFa: String,
    val slug: String,
    val description: String,
    val descriptionFa: String,
    val stackTags: List<String>,
    val healthStatus: ProjectHealthStatus,
    val repoFullName: String,
    val defaultBranch: String,
    val lastCommitSha: String,
    val lastBuildStatus: PipelineStatus,
    val activeEnvironment: String,
    val updatedAtEpochMs: Long,
    val origin: ImplementationOrigin = ImplementationOrigin.LOCAL_PERSISTED,
)

data class DevTask(
    val id: String,
    val projectId: String,
    val title: String,
    val titleFa: String,
    val priority: TaskPriority,
    val status: TaskStatus,
    val assignedAgent: AgentRole?,
    val requiresApproval: Boolean,
    val updatedAtEpochMs: Long,
)

data class AgentDefinition(
    val role: AgentRole,
    val titleEn: String,
    val titleFa: String,
    val purposeEn: String,
    val purposeFa: String,
    val allowedTools: List<String>,
    val requiresHumanGateFor: List<String>,
    val currentState: AgentExecutionState,
    val preferredModelId: String,
)

data class AgentActivity(
    val id: String,
    val projectId: String,
    val agentRole: AgentRole,
    val summaryEn: String,
    val summaryFa: String,
    val toolInvoked: String,
    val state: AgentExecutionState,
    val timestampLabel: String,
    val origin: ImplementationOrigin = ImplementationOrigin.SIMULATED_PHASE_0,
)

data class ApprovalRequest(
    val id: String,
    val projectId: String,
    val agentRole: AgentRole,
    val actionTitleEn: String,
    val actionTitleFa: String,
    val commandPreview: String,
    val riskLevel: TaskPriority,
    val status: ApprovalStatus,
    val createdAtEpochMs: Long,
)

data class GitHubRepoConnection(
    val id: String,
    val repoFullName: String,
    val branch: String,
    val commitSha: String,
    val syncStatusEn: String,
    val syncStatusFa: String,
    val isSimulated: Boolean = true,
)

data class BuildPipelineRun(
    val id: String,
    val projectName: String,
    val branch: String,
    val commitSha: String,
    val status: PipelineStatus,
    val durationSeconds: Int,
    val qualitySummaryEn: String,
    val qualitySummaryFa: String,
    val timestampLabel: String,
    val isSimulated: Boolean = true,
)

data class DeploymentRecord(
    val id: String,
    val projectName: String,
    val target: DeploymentTarget,
    val versionTag: String,
    val endpointLabel: String,
    val status: PipelineStatus,
    val rollbackAvailable: Boolean,
    val timestampLabel: String,
    val isSimulated: Boolean = true,
)

data class SystemHealthSnapshot(
    val controlPlaneStatusEn: String,
    val controlPlaneStatusFa: String,
    val vpsTargetProfile: String,
    val cpuUsagePercent: Int,
    val memoryUsagePercent: Int,
    val activeSandboxContainers: Int,
    val encryptedVaultReady: Boolean,
    val aiGatewayProvider: String,
    val isSimulatedTelemetry: Boolean = true,
)

data class CloudNotification(
    val id: String,
    val titleEn: String,
    val titleFa: String,
    val bodyEn: String,
    val bodyFa: String,
    val severity: NotificationSeverity,
    val isRead: Boolean,
    val timestampLabel: String,
)

data class AuditLogEntry(
    val id: String,
    val actor: String,
    val action: String,
    val target: String,
    val outcome: String,
    val timestampEpochMs: Long,
)

data class UserPreferences(
    val language: AppLanguage = AppLanguage.PERSIAN,
    val theme: ThemePreference = ThemePreference.LIGHT,
    val activeControlPlaneUrl: String = "https://control.afrina.local (Phase 0 Mock)",
    val selectedAiModelId: String = "provider-agnostic/architect-v1",
)
