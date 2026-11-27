package com.example.afrina.core.data.repository

import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.local.AgentActivityEntity
import com.example.afrina.core.data.local.ApprovalRequestEntity
import com.example.afrina.core.data.local.AuditLogEntity
import com.example.afrina.core.data.local.DevTaskEntity
import com.example.afrina.core.data.local.NotificationEntity
import com.example.afrina.core.data.local.ProjectEntity
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.domain.NotificationRepository
import com.example.afrina.core.domain.PipelineRepository
import com.example.afrina.core.domain.ProjectRepository
import com.example.afrina.core.domain.VaultCryptoManager
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AfrinaProject
import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.AgentDefinition
import com.example.afrina.core.model.AgentExecutionState
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.ApprovalRequest
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.AuditLogEntry
import com.example.afrina.core.model.BuildPipelineRun
import com.example.afrina.core.model.CloudNotification
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.DeploymentRecord
import com.example.afrina.core.model.DeploymentTarget
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.GitHubRepoConnection
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.NotificationSeverity
import com.example.afrina.core.model.PipelineStatus
import com.example.afrina.core.model.ProjectHealthStatus
import com.example.afrina.core.model.SystemHealthSnapshot
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskPriority
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID

class LocalProjectRepository(
    private val db: AfrinaDatabase,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<AfrinaProject>> =
        db.projectDao().observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeTasks(): Flow<List<DevTask>> =
        db.devTaskDao().observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun createProject(
        name: String,
        repoFullName: String,
        stackTags: List<String>,
        description: String,
    ) {
        val now = System.currentTimeMillis()
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "project" }
        val id = "proj-${UUID.randomUUID().toString().take(8)}"
        val entity = ProjectEntity(
            id = id,
            name = name.trim(),
            nameFa = name.trim(),
            slug = slug,
            description = description.ifBlank { "Local Phase 0 software project workspace." },
            descriptionFa = description.ifBlank { "فضای کاری پروژه نرم‌افزاری محلی در فاز ۰." },
            stackTagsCsv = stackTags.ifEmpty { listOf("Kotlin", "API") }.joinToString(","),
            healthStatus = ProjectHealthStatus.HEALTHY.name,
            repoFullName = repoFullName.ifBlank { "afrina-cloud/$slug" },
            defaultBranch = "main",
            lastCommitSha = UUID.randomUUID().toString().take(7),
            lastBuildStatus = PipelineStatus.QUEUED.name,
            activeEnvironment = "phase0-local",
            updatedAtEpochMs = now,
        )
        db.projectDao().insert(entity)
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder",
                action = "CREATE_PROJECT",
                target = entity.slug,
                outcome = "PERSISTED_ROOM",
                timestampEpochMs = now,
            )
        )
    }

    override suspend fun updateTaskStatus(taskId: String, newStatus: TaskStatus) {
        val now = System.currentTimeMillis()
        db.devTaskDao().updateStatus(taskId, newStatus.name, now)
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder",
                action = "UPDATE_TASK_STATUS",
                target = "$taskId -> ${newStatus.name}",
                outcome = "PERSISTED_ROOM",
                timestampEpochMs = now,
            )
        )
    }

    override suspend fun createTask(
        projectId: String,
        title: String,
        priority: TaskPriority,
        assignedAgent: AgentRole?,
    ) {
        val now = System.currentTimeMillis()
        val entity = DevTaskEntity(
            id = "tsk-${UUID.randomUUID().toString().take(8)}",
            projectId = projectId,
            title = title.trim(),
            titleFa = title.trim(),
            priority = priority.name,
            status = TaskStatus.OPEN.name,
            assignedAgent = assignedAgent?.name,
            requiresApproval = assignedAgent == AgentRole.DEPLOYMENT_ASSISTANT || assignedAgent == AgentRole.SECURITY_AUDITOR,
            updatedAtEpochMs = now,
        )
        db.devTaskDao().insert(entity)
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder",
                action = "CREATE_TASK",
                target = entity.title,
                outcome = "PERSISTED_ROOM",
                timestampEpochMs = now,
            )
        )
    }
}

class SimulatedAgentRepository(
    private val db: AfrinaDatabase,
    private val aiRouterRepository: AiRouterRepository,
) : AgentRepository {

    private val agentCatalog = MutableStateFlow(buildInitialAgentCatalog())

    override fun observeAgentCatalog(): Flow<List<AgentDefinition>> = agentCatalog.asStateFlow()

    override fun observeActivities(): Flow<List<AgentActivity>> =
        db.agentActivityDao().observeAll().map { items -> items.map { it.toDomain() } }

    override fun observeApprovals(): Flow<List<ApprovalRequest>> =
        db.approvalDao().observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun resolveApproval(approvalId: String, approved: Boolean) {
        val now = System.currentTimeMillis()
        val newStatus = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
        db.approvalDao().updateStatus(approvalId, newStatus.name)
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder (Human Gate)",
                action = if (approved) "APPROVE_AGENT_ACTION" else "REJECT_AGENT_ACTION",
                target = approvalId,
                outcome = newStatus.name,
                timestampEpochMs = now,
            )
        )
        db.agentActivityDao().insert(
            AgentActivityEntity(
                id = "act-${UUID.randomUUID().toString().take(8)}",
                projectId = "proj-core-api",
                agentRole = AgentRole.DEPLOYMENT_ASSISTANT.name,
                summaryEn = if (approved) {
                    "Human approval granted for $approvalId (dry-run queued; no remote VPS attached)."
                } else {
                    "Human approval denied for $approvalId; execution halted safely."
                },
                summaryFa = if (approved) {
                    "تایید انسانی برای $approvalId صادر شد (اجرای آزمایشی در صف؛ VPS متصل نیست)."
                } else {
                    "درخواست $approvalId توسط مالک سیستم رد شد و اجرا متوقف گردید."
                },
                toolInvoked = "human_approval_gate",
                state = AgentExecutionState.COMPLETED.name,
                timestampLabel = "Just now",
                createdAtEpochMs = now,
            )
        )
    }

    override suspend fun triggerSimulatedAgentPlan(
        projectId: String,
        role: AgentRole,
        promptSummary: String,
    ) {
        val now = System.currentTimeMillis()
        val (persona, complexity, requiresCoding) = role.toRoutingProfile()
        val outcome = aiRouterRepository.executePersonaTask(
            TaskRoutingRequest(
                persona = persona,
                taskTitle = "${role.name} • $projectId",
                prompt = promptSummary,
                complexity = complexity,
                requiresCoding = requiresCoding,
                requiresMultilingualFaEn = true,
                requiredModalities = setOf(AiModality.TEXT),
                dataClassification = DataClassification.PROJECT_CODE,
                explicitSensitiveDataAuthorized = false,
                explicitPaidApprovalId = null,
                allowLocalOpenWeight = true,
            )
        )

        val (summaryEn, summaryFa, toolInvoked, executionState, auditOutcome) = when (outcome) {
            is AiRouteOutcome.Success -> AgentRoutingRecord(
                summaryEn = outcome.outputEn,
                summaryFa = outcome.outputFa,
                toolInvoked = "central_ai_router:${outcome.providerId}/${outcome.modelId}",
                executionState = AgentExecutionState.COMPLETED,
                auditOutcome = "COMPLETED_CENTRAL_ROUTER",
            )
            is AiRouteOutcome.PaidApprovalRequired -> AgentRoutingRecord(
                summaryEn = outcome.proposal.reasonEn,
                summaryFa = outcome.proposal.reasonFa,
                toolInvoked = "central_ai_router:paid_approval_gate",
                executionState = AgentExecutionState.AWAITING_APPROVAL,
                auditOutcome = "AWAITING_PAID_APPROVAL",
            )
            is AiRouteOutcome.PrivacyAuthorizationRequired -> AgentRoutingRecord(
                summaryEn = outcome.messageEn,
                summaryFa = outcome.messageFa,
                toolInvoked = "central_ai_router:privacy_gate",
                executionState = AgentExecutionState.AWAITING_APPROVAL,
                auditOutcome = "BLOCKED_PRIVACY_GATE",
            )
            is AiRouteOutcome.PausedQuotaOrProviderUnavailable -> AgentRoutingRecord(
                summaryEn = outcome.messageEn,
                summaryFa = outcome.messageFa,
                toolInvoked = "central_ai_router:paused",
                executionState = AgentExecutionState.IDLE,
                auditOutcome = "PAUSED_ZERO_SPEND",
            )
        }

        db.agentActivityDao().insert(
            AgentActivityEntity(
                id = "act-${UUID.randomUUID().toString().take(8)}",
                projectId = projectId,
                agentRole = role.name,
                summaryEn = summaryEn,
                summaryFa = summaryFa,
                toolInvoked = toolInvoked,
                state = executionState.name,
                timestampLabel = "Just now",
                createdAtEpochMs = now,
            )
        )
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Agent:${role.name}",
                action = "SIMULATED_AGENT_ROUTED",
                target = projectId,
                outcome = auditOutcome,
                timestampEpochMs = now,
            )
        )
    }

    private data class AgentRoutingRecord(
        val summaryEn: String,
        val summaryFa: String,
        val toolInvoked: String,
        val executionState: AgentExecutionState,
        val auditOutcome: String,
    )

    private fun AgentRole.toRoutingProfile(): Triple<AfrinaPersona, TaskComplexity, Boolean> =
        when (this) {
            AgentRole.ARCHITECT -> Triple(AfrinaPersona.ARCHITECT_STRATEGIST, TaskComplexity.STANDARD, false)
            AgentRole.PROGRAMMER -> Triple(AfrinaPersona.BUILDER_ENGINEER, TaskComplexity.STANDARD, true)
            AgentRole.CODE_REVIEWER -> Triple(AfrinaPersona.BUILDER_ENGINEER, TaskComplexity.STANDARD, true)
            AgentRole.DEBUGGER -> Triple(AfrinaPersona.BUILDER_ENGINEER, TaskComplexity.STANDARD, true)
            AgentRole.TESTER -> Triple(AfrinaPersona.BUILDER_ENGINEER, TaskComplexity.STANDARD, true)
            AgentRole.SECURITY_AUDITOR -> Triple(AfrinaPersona.GUARDIAN_SENTINEL, TaskComplexity.COMPLEX_REASONING, false)
            AgentRole.DEPLOYMENT_ASSISTANT -> Triple(AfrinaPersona.ARCHITECT_STRATEGIST, TaskComplexity.STANDARD, false)
        }

    private fun buildInitialAgentCatalog(): List<AgentDefinition> = listOf(
        AgentDefinition(
            role = AgentRole.ARCHITECT,
            titleEn = "AI Software Architect",
            titleFa = "معمار نرم‌افزار",
            purposeEn = "Designs module boundaries, API contracts, and ADRs.",
            purposeFa = "طراحی مرز ماژول‌ها، قراردادهای API و اسناد معماری.",
            allowedTools = listOf("repo.read_tree", "docs.write_adr", "schema.diff"),
            requiresHumanGateFor = listOf("architecture.breaking_migration"),
            currentState = AgentExecutionState.COMPLETED,
            preferredModelId = "provider-agnostic/architect-v1",
        ),
        AgentDefinition(
            role = AgentRole.PROGRAMMER,
            titleEn = "AI Programmer",
            titleFa = "برنامه‌نویس هوشمند",
            purposeEn = "Generates clean feature diffs in isolated branches.",
            purposeFa = "تولید تغییرات کد در شاخه‌های ایزوله با رعایت استانداردها.",
            allowedTools = listOf("repo.read", "sandbox.write_patch", "git.create_branch"),
            requiresHumanGateFor = listOf("git.push_protected", "deps.add_major"),
            currentState = AgentExecutionState.RUNNING,
            preferredModelId = "provider-agnostic/coder-v1",
        ),
        AgentDefinition(
            role = AgentRole.CODE_REVIEWER,
            titleEn = "AI Code Reviewer",
            titleFa = "بازبین کد",
            purposeEn = "Audits PRs for maintainability, concurrency, and style.",
            purposeFa = "بررسی کیفیت کد، همزمانی، خوانایی و انطباق با الگوها.",
            allowedTools = listOf("git.diff", "lint.inspect", "pr.comment"),
            requiresHumanGateFor = listOf("pr.merge_main"),
            currentState = AgentExecutionState.IDLE,
            preferredModelId = "provider-agnostic/architect-v1",
        ),
        AgentDefinition(
            role = AgentRole.DEBUGGER,
            titleEn = "AI Debugging Agent",
            titleFa = "عامل عیب‌یابی",
            purposeEn = "Correlates stack traces, logs, and runtime failures.",
            purposeFa = "تحلیل خطاها، لاگ‌ها و یافتن ریشه مشکلات زمان اجرا.",
            allowedTools = listOf("logs.read", "sandbox.reproduce"),
            requiresHumanGateFor = listOf("prod.restart_service"),
            currentState = AgentExecutionState.IDLE,
            preferredModelId = "provider-agnostic/coder-v1",
        ),
        AgentDefinition(
            role = AgentRole.TESTER,
            titleEn = "AI Testing Agent",
            titleFa = "عامل تست خودکار",
            purposeEn = "Synthesizes unit, integration, and Robolectric UI tests.",
            purposeFa = "تولید و اجرای تست‌های واحد، یکپارچه‌سازی و رابط کاربری.",
            allowedTools = listOf("gradle.test_unit", "coverage.report"),
            requiresHumanGateFor = listOf("ci.override_failing_gate"),
            currentState = AgentExecutionState.COMPLETED,
            preferredModelId = "provider-agnostic/coder-v1",
        ),
        AgentDefinition(
            role = AgentRole.SECURITY_AUDITOR,
            titleEn = "AI Security Auditor",
            titleFa = "ممیز امنیت",
            purposeEn = "Scans secrets, dependencies, permissions, and RBAC rules.",
            purposeFa = "پایش کلیدهای محرمانه، وابستگی‌ها، مجوزها و ریسک‌های امنیتی.",
            allowedTools = listOf("sast.scan", "secrets.entropy_check", "sbom.audit"),
            requiresHumanGateFor = listOf("vault.rotate_keys", "policy.waive_cve"),
            currentState = AgentExecutionState.AWAITING_APPROVAL,
            preferredModelId = "provider-agnostic/security-auditor-v1",
        ),
        AgentDefinition(
            role = AgentRole.DEPLOYMENT_ASSISTANT,
            titleEn = "AI Deployment Assistant",
            titleFa = "دستیار استقرار",
            purposeEn = "Prepares container builds, dry-runs, and rollback plans.",
            purposeFa = "آماده‌سازی کانتینرها، پیش‌نمایش استقرار و برنامه بازگشت نسخه.",
            allowedTools = listOf("docker.build_dryrun", "release.verify_checksum"),
            requiresHumanGateFor = listOf("deploy.production", "deploy.rollback", "vps.exec_shell"),
            currentState = AgentExecutionState.AWAITING_APPROVAL,
            preferredModelId = "provider-agnostic/architect-v1",
        ),
    )
}

class SimulatedPipelineRepository(
    private val db: AfrinaDatabase,
    private val vaultCryptoManager: VaultCryptoManager,
) : PipelineRepository {

    private val githubConnections = MutableStateFlow(
        listOf(
            GitHubRepoConnection(
                id = "gh-1",
                repoFullName = "afrina-cloud/afrina-control-plane",
                branch = "main",
                commitSha = "9f4c21a",
                syncStatusEn = "Simulated sync • Webhook contract ready",
                syncStatusFa = "همگام‌سازی شبیه‌سازی‌شده • وب‌هوک آماده",
                isSimulated = true,
            ),
            GitHubRepoConnection(
                id = "gh-2",
                repoFullName = "afrina-cloud/afrina-android-client",
                branch = "phase-0/scaffold",
                commitSha = "3b8e04d",
                syncStatusEn = "Simulated sync • Clean working tree",
                syncStatusFa = "همگام‌سازی شبیه‌سازی‌شده • شاخه پایدار",
                isSimulated = true,
            ),
            GitHubRepoConnection(
                id = "gh-3",
                repoFullName = "afrina-cloud/afrina-agent-sandbox",
                branch = "main",
                commitSha = "7c12a9e",
                syncStatusEn = "Awaiting VPS runner registration",
                syncStatusFa = "در انتظار ثبت رانر روی VPS آینده",
                isSimulated = true,
            ),
        )
    )

    private val builds = MutableStateFlow(
        listOf(
            BuildPipelineRun(
                id = "bld-104",
                projectName = "afrina-android-client",
                branch = "phase-0/scaffold",
                commitSha = "3b8e04d",
                status = PipelineStatus.PASSED,
                durationSeconds = 42,
                qualitySummaryEn = "Kotlin compile + Room schema + Lint baseline",
                qualitySummaryFa = "کامپایل کاتلین + اسکیمای Room + بررسی کیفیت",
                timestampLabel = "14m ago",
            ),
            BuildPipelineRun(
                id = "bld-103",
                projectName = "afrina-control-plane",
                branch = "main",
                commitSha = "9f4c21a",
                status = PipelineStatus.PASSED,
                durationSeconds = 29,
                qualitySummaryEn = "OpenAPI schema contract + unit spec",
                qualitySummaryFa = "قرارداد OpenAPI + تست واحد شبیه‌سازی‌شده",
                timestampLabel = "2h ago",
            ),
            BuildPipelineRun(
                id = "bld-102",
                projectName = "afrina-agent-sandbox",
                branch = "feat/seccomp-profile",
                commitSha = "7c12a9e",
                status = PipelineStatus.QUEUED,
                durationSeconds = 0,
                qualitySummaryEn = "Waiting for Linux container host in Phase 3",
                qualitySummaryFa = "در انتظار میزبان کانتینر لینوکس در فاز ۳",
                timestampLabel = "5h ago",
            ),
        )
    )

    private val deployments = MutableStateFlow(
        listOf(
            DeploymentRecord(
                id = "dep-21",
                projectName = "afrina-docs-pwa",
                target = DeploymentTarget.PWA,
                versionTag = "v0.1.0-sim",
                endpointLabel = "pwa.afrina.local (Simulated)",
                status = PipelineStatus.PASSED,
                rollbackAvailable = true,
                timestampLabel = "1d ago",
            ),
            DeploymentRecord(
                id = "dep-20",
                projectName = "afrina-control-plane",
                target = DeploymentTarget.API_CONTAINER,
                versionTag = "v0.1.0-rc1",
                endpointLabel = "api.afrina.local (VPS Target)",
                status = PipelineStatus.QUEUED,
                rollbackAvailable = true,
                timestampLabel = "Pending Phase 1 VPS",
            ),
        )
    )

    override fun observeGitHubConnections(): Flow<List<GitHubRepoConnection>> =
        githubConnections.asStateFlow()

    override fun observeBuilds(): Flow<List<BuildPipelineRun>> = builds.asStateFlow()

    override fun observeDeployments(): Flow<List<DeploymentRecord>> = deployments.asStateFlow()

    override fun observeSystemHealth(): Flow<SystemHealthSnapshot> =
        MutableStateFlow(
            SystemHealthSnapshot(
                controlPlaneStatusEn = "Phase 0 Local Simulation Active",
                controlPlaneStatusFa = "شبیه‌سازی محلی فاز ۰ فعال است",
                vpsTargetProfile = "Linux VPS (Docker + Caddy/Nginx) • Unbound",
                cpuUsagePercent = 18,
                memoryUsagePercent = 34,
                activeSandboxContainers = 0,
                encryptedVaultReady = vaultCryptoManager.isHardwareVaultAvailable(),
                aiGatewayProvider = "Provider-Agnostic Mock Gateway",
                isSimulatedTelemetry = true,
            )
        ).asStateFlow()

    override suspend fun triggerSimulatedBuild(projectName: String, branch: String) {
        val now = System.currentTimeMillis()
        val id = "bld-${(105..999).random()}"
        val sha = UUID.randomUUID().toString().take(7)
        val newRun = BuildPipelineRun(
            id = id,
            projectName = projectName,
            branch = branch,
            commitSha = sha,
            status = PipelineStatus.PASSED,
            durationSeconds = 24,
            qualitySummaryEn = "Simulated CI verification passed (local dry-run)",
            qualitySummaryFa = "بررسی CI شبیه‌سازی‌شده با موفقیت انجام شد (اجرای محلی)",
            timestampLabel = "Just now",
            isSimulated = true,
        )
        builds.update { listOf(newRun) + it }
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder",
                action = "SIMULATE_CI_BUILD",
                target = "$projectName@$branch",
                outcome = "PASSED_SIMULATION",
                timestampEpochMs = now,
            )
        )
    }

    override suspend fun triggerSimulatedDeploy(projectName: String, target: DeploymentTarget) {
        val now = System.currentTimeMillis()
        val id = "dep-${(22..999).random()}"
        val newDeploy = DeploymentRecord(
            id = id,
            projectName = projectName,
            target = target,
            versionTag = "v0.1.${(1..9).random()}-dryrun",
            endpointLabel = "${target.name.lowercase()}.afrina.local (Dry-Run)",
            status = PipelineStatus.PASSED,
            rollbackAvailable = true,
            timestampLabel = "Just now",
            isSimulated = true,
        )
        deployments.update { listOf(newDeploy) + it }
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder",
                action = "SIMULATE_DRY_RUN_DEPLOY",
                target = "$projectName -> ${target.name}",
                outcome = "DRY_RUN_READY",
                timestampEpochMs = now,
            )
        )
    }
}

class LocalNotificationRepository(
    private val db: AfrinaDatabase,
) : NotificationRepository {

    override fun observeNotifications(): Flow<List<CloudNotification>> =
        db.notificationDao().observeAll().map { items -> items.map { it.toDomain() } }

    override fun observeAuditLogs(): Flow<List<AuditLogEntry>> =
        db.auditLogDao().observeRecent().map { items -> items.map { it.toDomain() } }

    override suspend fun markAllRead() {
        db.notificationDao().markAllRead()
    }

    override suspend fun recordAudit(actor: String, action: String, target: String, outcome: String) {
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = actor,
                action = action,
                target = target,
                outcome = outcome,
                timestampEpochMs = System.currentTimeMillis(),
            )
        )
    }
}

object SeedDataInitializer {
    suspend fun ensureSeeded(db: AfrinaDatabase) {
        if (db.projectDao().count() > 0) return
        val now = System.currentTimeMillis()

        db.projectDao().insertAll(
            listOf(
                ProjectEntity(
                    id = "proj-core-api",
                    name = "AFRINA Control Plane API",
                    nameFa = "هسته کنترل‌پلن API آفرینا",
                    slug = "afrina-control-plane",
                    description = "Provider-independent backend orchestration service for future Linux VPS.",
                    descriptionFa = "سرویس ارکستراسیون مستقل از سرور برای اجرای آینده روی Linux VPS.",
                    stackTagsCsv = "Kotlin/Ktor,PostgreSQL,Docker,OpenAPI",
                    healthStatus = ProjectHealthStatus.HEALTHY.name,
                    repoFullName = "afrina-cloud/afrina-control-plane",
                    defaultBranch = "main",
                    lastCommitSha = "9f4c21a",
                    lastBuildStatus = PipelineStatus.PASSED.name,
                    activeEnvironment = "phase0-contract",
                    updatedAtEpochMs = now,
                ),
                ProjectEntity(
                    id = "proj-android-app",
                    name = "AFRINA Android Client",
                    nameFa = "اپلیکیشن موبایل آفرینا",
                    slug = "afrina-android-client",
                    description = "Persian-first RTL Jetpack Compose control plane for mobile engineering.",
                    descriptionFa = "رابط کاربری موبایل جت‌پک کامپوز فارسی‌محور و راست‌به‌چپ برای مدیریت پروژه.",
                    stackTagsCsv = "Kotlin,Compose,Room,DataStore",
                    healthStatus = ProjectHealthStatus.HEALTHY.name,
                    repoFullName = "afrina-cloud/afrina-android-client",
                    defaultBranch = "phase-0/scaffold",
                    lastCommitSha = "3b8e04d",
                    lastBuildStatus = PipelineStatus.PASSED.name,
                    activeEnvironment = "local-device",
                    updatedAtEpochMs = now - 60_000,
                ),
                ProjectEntity(
                    id = "proj-sandbox-runner",
                    name = "Agent Sandbox Runner",
                    nameFa = "موتور اجرای ایزوله عامل‌ها",
                    slug = "afrina-agent-sandbox",
                    description = "Ephemeral container runner with strict tool allowlists and approval gates.",
                    descriptionFa = "رانر کانتینری ایزوله با لیست سفید ابزارها و دروازه تایید انسانی.",
                    stackTagsCsv = "Containerd,gVisor,Python,gRPC",
                    healthStatus = ProjectHealthStatus.WARNING.name,
                    repoFullName = "afrina-cloud/afrina-agent-sandbox",
                    defaultBranch = "main",
                    lastCommitSha = "7c12a9e",
                    lastBuildStatus = PipelineStatus.QUEUED.name,
                    activeEnvironment = "awaiting-vps",
                    updatedAtEpochMs = now - 120_000,
                ),
            )
        )

        db.devTaskDao().insertAll(
            listOf(
                DevTaskEntity(
                    id = "tsk-1",
                    projectId = "proj-android-app",
                    title = "Verify RTL Persian & LTR English layout switching",
                    titleFa = "تایید تغییر پویا بین رابط فارسی RTL و انگلیسی LTR",
                    priority = TaskPriority.HIGH.name,
                    status = TaskStatus.IN_PROGRESS.name,
                    assignedAgent = AgentRole.TESTER.name,
                    requiresApproval = false,
                    updatedAtEpochMs = now,
                ),
                DevTaskEntity(
                    id = "tsk-2",
                    projectId = "proj-core-api",
                    title = "Review OpenAPI v1 control plane contract & auth headers",
                    titleFa = "بازبینی قرارداد OpenAPI v1 و هدرهای احراز هویت",
                    priority = TaskPriority.CRITICAL.name,
                    status = TaskStatus.REVIEW.name,
                    assignedAgent = AgentRole.ARCHITECT.name,
                    requiresApproval = false,
                    updatedAtEpochMs = now - 30_000,
                ),
                DevTaskEntity(
                    id = "tsk-3",
                    projectId = "proj-sandbox-runner",
                    title = "Approve seccomp sandbox policy before VPS runner phase",
                    titleFa = "تایید سیاست ایزوله‌سازی seccomp پیش از راه‌اندازی VPS",
                    priority = TaskPriority.CRITICAL.name,
                    status = TaskStatus.OPEN.name,
                    assignedAgent = AgentRole.SECURITY_AUDITOR.name,
                    requiresApproval = true,
                    updatedAtEpochMs = now - 90_000,
                ),
            )
        )

        db.approvalDao().insertAll(
            listOf(
                ApprovalRequestEntity(
                    id = "apr-901",
                    projectId = "proj-sandbox-runner",
                    agentRole = AgentRole.SECURITY_AUDITOR.name,
                    actionTitleEn = "Apply strict read-only rootfs policy template",
                    actionTitleFa = "اعمال الگوی سیستم‌فایل فقط‌خواندنی در سندباکس",
                    commandPreview = "sandboxctl policy apply --readonly-rootfs --drop-caps=ALL",
                    riskLevel = TaskPriority.HIGH.name,
                    status = ApprovalStatus.PENDING.name,
                    createdAtEpochMs = now - 40_000,
                ),
                ApprovalRequestEntity(
                    id = "apr-902",
                    projectId = "proj-core-api",
                    agentRole = AgentRole.DEPLOYMENT_ASSISTANT.name,
                    actionTitleEn = "Stage dry-run container manifest for Control Plane v0.1",
                    actionTitleFa = "آماده‌سازی مانیفست آزمایشی استقرار هسته کنترل‌پلن v0.1",
                    commandPreview = "compose-dryrun up -d afrina-control-plane --no-vps-exec",
                    riskLevel = TaskPriority.CRITICAL.name,
                    status = ApprovalStatus.PENDING.name,
                    createdAtEpochMs = now - 80_000,
                ),
            )
        )

        db.agentActivityDao().insertAll(
            listOf(
                AgentActivityEntity(
                    id = "act-1",
                    projectId = "proj-android-app",
                    agentRole = AgentRole.ARCHITECT.name,
                    summaryEn = "Established Clean Architecture package boundaries & Phase 0 ADRs.",
                    summaryFa = "ساختار لایه‌های Clean Architecture و اسناد تصمیم معماری فاز ۰ تدوین شد.",
                    toolInvoked = "docs.write_adr",
                    state = AgentExecutionState.COMPLETED.name,
                    timestampLabel = "5m ago",
                    createdAtEpochMs = now - 20_000,
                ),
                AgentActivityEntity(
                    id = "act-2",
                    projectId = "proj-sandbox-runner",
                    agentRole = AgentRole.SECURITY_AUDITOR.name,
                    summaryEn = "Proposed sandbox rootfs lockdown; awaiting founder sign-off.",
                    summaryFa = "پیشنهاد قفل‌گذاری فایل‌سیستم سندباکس؛ در انتظار تایید مالک سیستم.",
                    toolInvoked = "sast.scan",
                    state = AgentExecutionState.AWAITING_APPROVAL.name,
                    timestampLabel = "18m ago",
                    createdAtEpochMs = now - 50_000,
                ),
            )
        )

        db.notificationDao().insertAll(
            listOf(
                NotificationEntity(
                    id = "not-1",
                    titleEn = "Phase 0 Scaffold Initialized",
                    titleFa = "اسکلت فاز ۰ با موفقیت راه‌اندازی شد",
                    bodyEn = "Room DB, DataStore preferences, and Android Keystore vault are active locally.",
                    bodyFa = "پایگاه داده Room، تنظیمات DataStore و گاوصندوق Keystore به‌صورت محلی فعال شدند.",
                    severity = NotificationSeverity.SUCCESS.name,
                    isRead = false,
                    timestampLabel = "Now",
                    createdAtEpochMs = now,
                ),
                NotificationEntity(
                    id = "not-2",
                    titleEn = "2 Sensitive Agent Actions Pending",
                    titleFa = "۲ عملیات حساس منتظر تایید انسانی",
                    bodyEn = "Review Security Auditor and Deployment Assistant proposals before execution.",
                    bodyFa = "پیشنهادهای ممیز امنیت و دستیار استقرار را در بخش عامل‌ها بررسی کنید.",
                    severity = NotificationSeverity.WARNING.name,
                    isRead = false,
                    timestampLabel = "10m ago",
                    createdAtEpochMs = now - 60_000,
                ),
            )
        )

        db.auditLogDao().insertAll(
            listOf(
                AuditLogEntity(
                    id = "aud-seed-1",
                    actor = "SystemBootstrap",
                    action = "INIT_PHASE_0_WORKSPACE",
                    target = "AFRINA_LOCAL_DB",
                    outcome = "SUCCESS",
                    timestampEpochMs = now,
                )
            )
        )
    }
}

private fun ProjectEntity.toDomain() = AfrinaProject(
    id = id,
    name = name,
    nameFa = nameFa,
    slug = slug,
    description = description,
    descriptionFa = descriptionFa,
    stackTags = stackTagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    healthStatus = runCatching { ProjectHealthStatus.valueOf(healthStatus) }.getOrDefault(ProjectHealthStatus.IDLE),
    repoFullName = repoFullName,
    defaultBranch = defaultBranch,
    lastCommitSha = lastCommitSha,
    lastBuildStatus = runCatching { PipelineStatus.valueOf(lastBuildStatus) }.getOrDefault(PipelineStatus.QUEUED),
    activeEnvironment = activeEnvironment,
    updatedAtEpochMs = updatedAtEpochMs,
    origin = ImplementationOrigin.LOCAL_PERSISTED,
)

private fun DevTaskEntity.toDomain() = DevTask(
    id = id,
    projectId = projectId,
    title = title,
    titleFa = titleFa,
    priority = runCatching { TaskPriority.valueOf(priority) }.getOrDefault(TaskPriority.MEDIUM),
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.OPEN),
    assignedAgent = assignedAgent?.let { runCatching { AgentRole.valueOf(it) }.getOrNull() },
    requiresApproval = requiresApproval,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun ApprovalRequestEntity.toDomain() = ApprovalRequest(
    id = id,
    projectId = projectId,
    agentRole = runCatching { AgentRole.valueOf(agentRole) }.getOrDefault(AgentRole.DEPLOYMENT_ASSISTANT),
    actionTitleEn = actionTitleEn,
    actionTitleFa = actionTitleFa,
    commandPreview = commandPreview,
    riskLevel = runCatching { TaskPriority.valueOf(riskLevel) }.getOrDefault(TaskPriority.HIGH),
    status = runCatching { ApprovalStatus.valueOf(status) }.getOrDefault(ApprovalStatus.PENDING),
    createdAtEpochMs = createdAtEpochMs,
)

private fun AgentActivityEntity.toDomain() = AgentActivity(
    id = id,
    projectId = projectId,
    agentRole = runCatching { AgentRole.valueOf(agentRole) }.getOrDefault(AgentRole.ARCHITECT),
    summaryEn = summaryEn,
    summaryFa = summaryFa,
    toolInvoked = toolInvoked,
    state = runCatching { AgentExecutionState.valueOf(state) }.getOrDefault(AgentExecutionState.IDLE),
    timestampLabel = timestampLabel,
    origin = ImplementationOrigin.SIMULATED_PHASE_0,
)

private fun NotificationEntity.toDomain() = CloudNotification(
    id = id,
    titleEn = titleEn,
    titleFa = titleFa,
    bodyEn = bodyEn,
    bodyFa = bodyFa,
    severity = runCatching { NotificationSeverity.valueOf(severity) }.getOrDefault(NotificationSeverity.INFO),
    isRead = isRead,
    timestampLabel = timestampLabel,
)

private fun AuditLogEntity.toDomain() = AuditLogEntry(
    id = id,
    actor = actor,
    action = action,
    target = target,
    outcome = outcome,
    timestampEpochMs = timestampEpochMs,
)
