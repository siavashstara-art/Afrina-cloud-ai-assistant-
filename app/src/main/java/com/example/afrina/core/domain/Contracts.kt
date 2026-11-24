package com.example.afrina.core.domain

import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.AgentDefinition
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AfrinaProject
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ApprovalRequest
import com.example.afrina.core.model.AuditLogEntry
import com.example.afrina.core.model.BuildPipelineRun
import com.example.afrina.core.model.CloudNotification
import com.example.afrina.core.model.DeploymentRecord
import com.example.afrina.core.model.DeploymentTarget
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.GitHubRepoConnection
import com.example.afrina.core.model.SystemHealthSnapshot
import com.example.afrina.core.model.TaskPriority
import com.example.afrina.core.model.TaskStatus
import com.example.afrina.core.model.ThemePreference
import com.example.afrina.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(): Flow<List<AfrinaProject>>
    fun observeTasks(): Flow<List<DevTask>>
    suspend fun createProject(
        name: String,
        repoFullName: String,
        stackTags: List<String>,
        description: String,
    )
    suspend fun updateTaskStatus(taskId: String, newStatus: TaskStatus)
    suspend fun createTask(
        projectId: String,
        title: String,
        priority: TaskPriority,
        assignedAgent: AgentRole?,
    )
}

interface AgentRepository {
    fun observeAgentCatalog(): Flow<List<AgentDefinition>>
    fun observeActivities(): Flow<List<AgentActivity>>
    fun observeApprovals(): Flow<List<ApprovalRequest>>
    suspend fun resolveApproval(approvalId: String, approved: Boolean)
    suspend fun triggerSimulatedAgentPlan(projectId: String, role: AgentRole, promptSummary: String)
}

interface PipelineRepository {
    fun observeGitHubConnections(): Flow<List<GitHubRepoConnection>>
    fun observeBuilds(): Flow<List<BuildPipelineRun>>
    fun observeDeployments(): Flow<List<DeploymentRecord>>
    fun observeSystemHealth(): Flow<SystemHealthSnapshot>
    suspend fun triggerSimulatedBuild(projectName: String, branch: String)
    suspend fun triggerSimulatedDeploy(projectName: String, target: DeploymentTarget)
}

interface NotificationRepository {
    fun observeNotifications(): Flow<List<CloudNotification>>
    fun observeAuditLogs(): Flow<List<AuditLogEntry>>
    suspend fun markAllRead()
    suspend fun recordAudit(actor: String, action: String, target: String, outcome: String)
}

interface PreferencesRepository {
    fun observePreferences(): Flow<UserPreferences>
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setSelectedAiModel(modelId: String)
}

interface VaultCryptoManager {
    fun isHardwareVaultAvailable(): Boolean
    fun hasStoredCredential(alias: String): Boolean
    fun storeEncryptedCredential(alias: String, plaintext: String): Result<Unit>
    fun readDecryptedCredentialMasked(alias: String): String?
    fun deleteCredential(alias: String)
}

data class AiCompletionRequest(
    val agentRole: AgentRole,
    val modelId: String,
    val prompt: String,
    val contextProjectSlug: String,
)

data class AiCompletionResponse(
    val providerName: String,
    val modelId: String,
    val outputTextEn: String,
    val outputTextFa: String,
    val estimatedTokens: Int,
    val isSimulated: Boolean,
)

interface AiProviderGateway {
    val providerId: String
    val supportedModels: List<String>
    suspend fun executePrompt(request: AiCompletionRequest): AiCompletionResponse
}
