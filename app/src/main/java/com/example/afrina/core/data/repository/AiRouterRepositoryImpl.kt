package com.example.afrina.core.data.repository

import androidx.room.withTransaction
import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.local.AiUsageLedgerEntity
import com.example.afrina.core.data.local.AuditLogEntity
import com.example.afrina.core.data.local.PaidInferenceApprovalEntity
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AiUsageLedgerEntry
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.CostTransparencySummary
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.FallbackEvent
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidApprovalGrant
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.model.UsageCostCategory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AiRouterRepositoryImpl(
    private val db: AfrinaDatabase,
    private val router: CentralAiProviderRouter,
    private val dispatcher: PersonaDispatcher,
) : AiRouterRepository {

    private val providerStatuses = MutableStateFlow(router.providerStatuses())
    private val spendingPolicy = MutableStateFlow(router.spendingPolicy())
    private val fallbackEvents = MutableStateFlow<List<FallbackEvent>>(emptyList())
    private val latestOutcome = MutableStateFlow<AiRouteOutcome?>(null)

    // Ephemeral in-memory cache so sensitive prompts are NEVER written to SQLite logs/approvals.
    private val transientPendingPrompts = ConcurrentHashMap<String, String>()

    override fun observeProviderStatuses(): Flow<List<ProviderQuotaStatus>> =
        providerStatuses.asStateFlow()

    override fun observeSpendingPolicy(): Flow<OwnerSpendingPolicy> =
        spendingPolicy.asStateFlow()

    override fun observeUsageLedger(): Flow<List<AiUsageLedgerEntry>> =
        db.aiUsageLedgerDao().observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeCostSummary(): Flow<CostTransparencySummary> =
        observeUsageLedger().map { entries ->
            val policy = router.spendingPolicy()
            CostTransparencySummary(
                totalFreeRequests = entries.count { it.usageCategory == UsageCostCategory.FREE_TIER },
                totalSelfHostedRequests = entries.count { it.usageCategory == UsageCostCategory.SELF_HOSTED_INFRA },
                totalPaidRequests = entries.count { it.usageCategory == UsageCostCategory.PAID_API },
                totalFreeTokens = entries
                    .filter { it.usageCategory == UsageCostCategory.FREE_TIER }
                    .sumOf { it.inputTokens + it.outputTokens },
                totalPaidApiSpendUsd = entries
                    .filter { it.usageCategory == UsageCostCategory.PAID_API }
                    .sumOf { it.estimatedApiCostUsd },
                totalSelfHostedInfraUsd = entries.sumOf { it.estimatedInfraCostUsd },
                ownerAutoBudgetUsd = policy.defaultAutoBudgetUsd,
            )
        }

    override fun observePaidApprovals(): Flow<List<PaidInferenceProposal>> =
        db.paidInferenceApprovalDao().observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeFallbackEvents(): Flow<List<FallbackEvent>> =
        fallbackEvents.asStateFlow()

    override fun observeLatestOutcome(): Flow<AiRouteOutcome?> =
        latestOutcome.asStateFlow()

    override suspend fun executePersonaTask(request: TaskRoutingRequest): AiRouteOutcome {
        val approvalId = request.explicitPaidApprovalId
        if (approvalId != null) {
            return executeExplicitPaidRequest(approvalId, request)
        }

        val outcome = dispatcher.runPersonaTask(
            persona = request.persona,
            taskTitle = request.taskTitle,
            prompt = request.prompt,
            complexity = request.complexity,
            requiresCoding = request.requiresCoding,
            requiredModalities = request.requiredModalities,
            dataClassification = request.dataClassification,
            explicitSensitiveDataAuthorized = request.explicitSensitiveDataAuthorized,
            explicitPaidApprovalId = null,
            allowLocalOpenWeight = request.allowLocalOpenWeight,
        )

        recordOutcome(request, outcome)
        return outcome
    }

    override suspend fun approvePaidInference(proposalId: String) {
        val now = System.currentTimeMillis()
        // UI approval ONLY records owner approval via conditional transition; it NEVER executes.
        db.withTransaction {
            val updated = db.paidInferenceApprovalDao().transitionStatus(
                proposalId = proposalId,
                expectedStatus = ApprovalStatus.PENDING.name,
                newStatus = ApprovalStatus.APPROVED.name,
                updatedAt = now,
            )
            if (updated == 1) {
                db.auditLogDao().insert(
                    AuditLogEntity(
                        id = "aud-${UUID.randomUUID().toString().take(8)}",
                        actor = "Founder (Spending Gate)",
                        action = "PAID_APPROVAL_RECORDED_NOT_EXECUTED",
                        target = proposalId,
                        outcome = "APPROVED_AWAITING_EXPLICIT_EXECUTION",
                        timestampEpochMs = now,
                    )
                )
            }
        }
    }

    override suspend fun rejectPaidInference(proposalId: String) {
        val now = System.currentTimeMillis()
        var rejected = false
        db.withTransaction {
            val updated = db.paidInferenceApprovalDao().transitionStatus(
                proposalId = proposalId,
                expectedStatus = ApprovalStatus.PENDING.name,
                newStatus = ApprovalStatus.REJECTED.name,
                updatedAt = now,
            )
            if (updated == 1) {
                rejected = true
                db.auditLogDao().insert(
                    AuditLogEntity(
                        id = "aud-${UUID.randomUUID().toString().take(8)}",
                        actor = "Founder (Spending Gate)",
                        action = "PAID_APPROVAL_REJECTED",
                        target = proposalId,
                        outcome = "REJECTED_ZERO_SPEND",
                        timestampEpochMs = now,
                    )
                )
            }
        }
        if (rejected) {
            transientPendingPrompts.remove(proposalId)
        }
    }

    override suspend fun executeApprovedPaidTask(proposalId: String): AiRouteOutcome {
        if (proposalId.isBlank()) {
            return blockPaidExecution(
                proposalId = proposalId,
                actor = "Founder (Spending Gate)",
                reasonCode = "BLOCKED_BLANK_APPROVAL",
                messageEn = "Cannot execute blank approval ID ($0.00 auto budget).",
                messageFa = "شناسه تایید خالی مجاز نیست.",
            )
        }

        val existing = db.paidInferenceApprovalDao().getById(proposalId)
            ?: return blockPaidExecution(
                proposalId = proposalId,
                actor = "Founder (Spending Gate)",
                reasonCode = "BLOCKED_MISSING_APPROVAL",
                messageEn = "Cannot execute proposal '$proposalId': explicit owner approval is missing ($0.00 auto budget).",
                messageFa = "اجرای درخواست '$proposalId' مجاز نیست: تایید صریح وجود ندارد.",
            )

        if (existing.status != ApprovalStatus.APPROVED.name || existing.isExecuted) {
            return blockPaidExecution(
                proposalId = proposalId,
                actor = "Founder (Spending Gate)",
                reasonCode = "BLOCKED_INVALID_OR_CONSUMED",
                messageEn = "Cannot execute proposal '$proposalId': approval is not active or was already consumed ($0.00 auto budget).",
                messageFa = "اجرای درخواست '$proposalId' مجاز نیست: تایید فعال نیست یا قبلاً مصرف شده است.",
            )
        }

        val persona = runCatching { AfrinaPersona.valueOf(existing.persona) }
            .getOrDefault(AfrinaPersona.BUILDER_ENGINEER)
        val complexity = runCatching { TaskComplexity.valueOf(existing.complexity) }
            .getOrDefault(TaskComplexity.COMPLEX_REASONING)
        val classification = runCatching { DataClassification.valueOf(existing.dataClassification) }
            .getOrDefault(DataClassification.PROJECT_CODE)
        val modalities = parseModalitiesCsv(existing.requiredModalitiesCsv)

        val request = TaskRoutingRequest(
            persona = persona,
            taskTitle = existing.taskTitle,
            prompt = existing.taskTitle,
            complexity = complexity,
            requiresCoding = existing.requiresCoding,
            requiredModalities = modalities,
            dataClassification = classification,
            explicitSensitiveDataAuthorized = existing.explicitSensitiveAuthorized,
            explicitPaidApprovalId = proposalId,
        )

        return claimAndExecuteApproved(proposalId, existing, request, useCachedPromptIfAvailable = true)
    }

    private suspend fun executeExplicitPaidRequest(
        proposalId: String,
        request: TaskRoutingRequest,
    ): AiRouteOutcome {
        if (proposalId.isBlank()) {
            return blockPaidExecution(
                proposalId = proposalId,
                actor = "Persona:${request.persona.name}",
                reasonCode = "BLOCKED_BLANK_APPROVAL",
                messageEn = "Paid execution blocked: blank approval ID ($0.00 spending limit enforced).",
                messageFa = "اجرای پولی مسدود شد: شناسه تایید خالی است.",
            )
        }

        val existing = db.paidInferenceApprovalDao().getById(proposalId)
        if (existing == null || existing.status != ApprovalStatus.APPROVED.name || existing.isExecuted) {
            return blockPaidExecution(
                proposalId = proposalId,
                actor = "Persona:${request.persona.name}",
                reasonCode = "BLOCKED_INVALID_APPROVAL",
                messageEn = "Paid execution blocked: approval '$proposalId' is missing, not approved, or already consumed ($0.00 spending limit enforced).",
                messageFa = "اجرای پولی مسدود شد: تایید معتبر نیست یا قبلاً مصرف شده است (سقف بودجه ۰.۰۰ دلار).",
            )
        }

        if (!matchesProposalParameters(existing, request)) {
            return blockPaidExecution(
                proposalId = proposalId,
                actor = "Persona:${request.persona.name}",
                reasonCode = "BLOCKED_PARAMETER_MISMATCH",
                messageEn = "Paid execution blocked: task parameters do not match approved proposal '$proposalId'.",
                messageFa = "اجرای پولی مسدود شد: پارامترهای درخواست با تایید ثبت‌شده مطابقت ندارد.",
            )
        }

        return claimAndExecuteApproved(proposalId, existing, request, useCachedPromptIfAvailable = false)
    }

    private suspend fun claimAndExecuteApproved(
        proposalId: String,
        existing: PaidInferenceApprovalEntity,
        baseRequest: TaskRoutingRequest,
        useCachedPromptIfAvailable: Boolean,
    ): AiRouteOutcome {
        val claimTime = System.currentTimeMillis()
        // Atomically claim the APPROVED, unexecuted row BEFORE async execution; no transaction held open.
        val claimed = db.paidInferenceApprovalDao().claimForExecution(
            proposalId = proposalId,
            expectedStatus = ApprovalStatus.APPROVED.name,
            executingStatus = ApprovalStatus.EXECUTING.name,
            updatedAt = claimTime,
        )
        if (claimed != 1) {
            return blockPaidExecution(
                proposalId = proposalId,
                actor = "Persona:${baseRequest.persona.name}",
                reasonCode = "BLOCKED_CONCURRENT_OR_CONSUMED",
                messageEn = "Paid execution blocked: approval '$proposalId' is already executing or consumed.",
                messageFa = "اجرای پولی مسدود شد: تایید در حال اجرا است یا قبلاً مصرف شده است.",
            )
        }

        val resolvedPrompt = if (useCachedPromptIfAvailable) {
            transientPendingPrompts.remove(proposalId) ?: baseRequest.prompt
        } else {
            transientPendingPrompts.remove(proposalId)
            baseRequest.prompt
        }
        val request = baseRequest.copy(prompt = resolvedPrompt)

        val outcome = try {
            dispatcher.runPersonaTask(
                persona = request.persona,
                taskTitle = request.taskTitle,
                prompt = request.prompt,
                complexity = request.complexity,
                requiresCoding = request.requiresCoding,
                requiredModalities = request.requiredModalities,
                dataClassification = request.dataClassification,
                explicitSensitiveDataAuthorized = request.explicitSensitiveDataAuthorized,
                explicitPaidApprovalId = proposalId,
                allowLocalOpenWeight = request.allowLocalOpenWeight,
            )
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                finalizeExecutionFailure(
                    proposalId = proposalId,
                    actor = "Persona:${request.persona.name}",
                    outcomeLabel = "FINALIZED_ON_EXCEPTION",
                )
            }
            throw t
        }

        providerStatuses.value = router.providerStatuses()
        latestOutcome.value = outcome

        when (outcome) {
            is AiRouteOutcome.Success -> {
                if (outcome.usageCategory == UsageCostCategory.PAID_API) {
                    finalizeExecutionSuccessAndRecord(proposalId, request, outcome)
                } else {
                    // Request resolved on $0 free/self-hosted tier without paid spend:
                    // restore APPROVED (unexecuted) and record the $0 ledger entry.
                    db.withTransaction {
                        db.paidInferenceApprovalDao().transitionStatus(
                            proposalId = proposalId,
                            expectedStatus = ApprovalStatus.EXECUTING.name,
                            newStatus = ApprovalStatus.APPROVED.name,
                            updatedAt = System.currentTimeMillis(),
                        )
                        insertSuccessLedgerAndAudit(request, outcome, System.currentTimeMillis())
                    }
                    recordFallbackEventIfAny(outcome, System.currentTimeMillis())
                }
            }

            else -> {
                withContext(NonCancellable) {
                    finalizeExecutionFailure(
                        proposalId = proposalId,
                        actor = "Persona:${request.persona.name}",
                        outcomeLabel = "FINALIZED_FAILED",
                    )
                }
            }
        }
        return outcome
    }

    private suspend fun finalizeExecutionSuccessAndRecord(
        proposalId: String,
        request: TaskRoutingRequest,
        outcome: AiRouteOutcome.Success,
    ) {
        val now = System.currentTimeMillis()
        recordFallbackEventIfAny(outcome, now)
        db.withTransaction {
            val marked = db.paidInferenceApprovalDao().markExecutedFromExecuting(
                proposalId = proposalId,
                executingStatus = ApprovalStatus.EXECUTING.name,
                finalStatus = ApprovalStatus.APPROVED.name,
                updatedAt = now,
            )
            if (marked != 1) {
                throw IllegalStateException("Failed to finalize claimed paid approval '$proposalId'")
            }
            insertSuccessLedgerAndAudit(request, outcome, now)
        }
    }

    private suspend fun finalizeExecutionFailure(
        proposalId: String,
        actor: String,
        outcomeLabel: String,
    ) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val transitioned = db.paidInferenceApprovalDao().transitionStatus(
                proposalId = proposalId,
                expectedStatus = ApprovalStatus.EXECUTING.name,
                newStatus = ApprovalStatus.FAILED.name,
                updatedAt = now,
            )
            if (transitioned == 1) {
                db.auditLogDao().insert(
                    AuditLogEntity(
                        id = "aud-${UUID.randomUUID().toString().take(8)}",
                        actor = actor,
                        action = "PAID_EXECUTION_FAILED",
                        target = proposalId,
                        outcome = outcomeLabel,
                        timestampEpochMs = now,
                    )
                )
            }
        }
        providerStatuses.value = router.providerStatuses()
    }

    private suspend fun blockPaidExecution(
        proposalId: String,
        actor: String,
        reasonCode: String,
        messageEn: String,
        messageFa: String,
    ): AiRouteOutcome.PausedQuotaOrProviderUnavailable {
        val blocked = AiRouteOutcome.PausedQuotaOrProviderUnavailable(
            messageEn = messageEn,
            messageFa = messageFa,
            attemptedProviders = listOf("paid-approval-gate"),
        )
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = actor,
                action = "PAID_EXECUTION_BLOCKED",
                target = proposalId.ifBlank { "<blank>" },
                outcome = reasonCode,
                timestampEpochMs = System.currentTimeMillis(),
            )
        )
        latestOutcome.value = blocked
        return blocked
    }

    private fun matchesProposalParameters(
        existing: PaidInferenceApprovalEntity,
        request: TaskRoutingRequest,
    ): Boolean =
        existing.persona == request.persona.name &&
            existing.complexity == request.complexity.name &&
            existing.requiresCoding == request.requiresCoding &&
            parseModalitiesCsv(existing.requiredModalitiesCsv) == request.requiredModalities &&
            existing.dataClassification == request.dataClassification.name &&
            existing.explicitSensitiveAuthorized == request.explicitSensitiveDataAuthorized

    override suspend fun simulateExhaustPrimaryQuota() {
        router.setSimulatedQuota("server-proxy-free-primary", 0)
        providerStatuses.value = router.providerStatuses()
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder (Simulation Control)",
                action = "SIMULATE_QUOTA_EXHAUSTION",
                target = "server-proxy-free-primary",
                outcome = "SIMULATED_QUOTA_ZERO",
                timestampEpochMs = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun resetSimulatedQuotas() {
        router.setSimulatedQuota("server-proxy-free-primary", 25)
        router.setSimulatedQuota("server-proxy-free-fallback", 20)
        router.setSimulatedQuota("vps-self-hosted-openweight", 100)
        router.setSimulatedHealthy("server-proxy-free-primary", true)
        router.setSimulatedHealthy("server-proxy-free-fallback", true)
        router.setSimulatedHealthy("vps-self-hosted-openweight", true)
        providerStatuses.value = router.providerStatuses()
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Founder (Simulation Control)",
                action = "RESET_SIMULATED_QUOTAS",
                target = "ALL_PHASE0_ADAPTERS",
                outcome = "SIMULATED_QUOTAS_RESTORED",
                timestampEpochMs = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun recordOutcome(request: TaskRoutingRequest, outcome: AiRouteOutcome) {
        val now = System.currentTimeMillis()
        providerStatuses.value = router.providerStatuses()
        latestOutcome.value = outcome

        when (outcome) {
            is AiRouteOutcome.Success -> {
                val routingKind = if (outcome.tier.isExternalCloud) {
                    "EXTERNAL_CLOUD"
                } else {
                    "SELF_HOSTED_VPS"
                }
                recordFallbackEventIfAny(outcome, now)

                db.withTransaction {
                    insertSuccessLedgerAndAudit(request, outcome, now, routingKind)
                }
            }

            is AiRouteOutcome.PaidApprovalRequired -> {
                val proposal = outcome.proposal
                // Keep sensitive prompt in transient memory only, never persisted to Room/audit logs.
                if (!request.dataClassification.isSensitiveOwnerData) {
                    transientPendingPrompts[proposal.id] = request.prompt
                }
                db.withTransaction {
                    db.paidInferenceApprovalDao().insert(
                        PaidInferenceApprovalEntity(
                            id = proposal.id,
                            persona = proposal.persona.name,
                            taskTitle = proposal.taskTitle,
                            complexity = proposal.complexity.name,
                            requiresCoding = proposal.requiresCoding,
                            requiredModalitiesCsv = proposal.requiredModalities.joinToString(",") { it.name },
                            dataClassification = proposal.dataClassification.name,
                            explicitSensitiveAuthorized = proposal.explicitSensitiveDataAuthorized,
                            targetProviderId = proposal.targetProviderId,
                            targetModelId = proposal.targetModelId,
                            estimatedCostUsd = proposal.estimatedCostUsd,
                            reasonEn = proposal.reasonEn,
                            reasonFa = proposal.reasonFa,
                            status = ApprovalStatus.PENDING.name,
                            isExecuted = false,
                            isSimulated = proposal.isSimulated,
                            createdAtEpochMs = proposal.createdAtEpochMs,
                            updatedAtEpochMs = proposal.updatedAtEpochMs,
                        )
                    )
                    db.auditLogDao().insert(
                        AuditLogEntity(
                            id = "aud-${UUID.randomUUID().toString().take(8)}",
                            actor = "Persona:${request.persona.name}",
                            action = "PAID_APPROVAL_REQUIRED",
                            target = "${proposal.targetProviderId}/${proposal.targetModelId}",
                            outcome = "PAUSED_ZERO_BUDGET",
                            timestampEpochMs = now,
                        )
                    )
                }
            }

            is AiRouteOutcome.PrivacyAuthorizationRequired -> {
                // Privacy rejection logged cleanly without exposing sensitive prompt text.
                db.auditLogDao().insert(
                    AuditLogEntity(
                        id = "aud-${UUID.randomUUID().toString().take(8)}",
                        actor = "Persona:${request.persona.name}",
                        action = "PRIVACY_EXTERNAL_BLOCKED",
                        target = "classification=${outcome.dataClassification.name};blocked=${outcome.blockedProviders.joinToString(",")}",
                        outcome = "REJECTED_UNAUTH_SENSITIVE_DATA",
                        timestampEpochMs = now,
                    )
                )
            }

            is AiRouteOutcome.PausedQuotaOrProviderUnavailable -> {
                db.auditLogDao().insert(
                    AuditLogEntity(
                        id = "aud-${UUID.randomUUID().toString().take(8)}",
                        actor = "Persona:${request.persona.name}",
                        action = "AI_ROUTE_PAUSED",
                        target = outcome.attemptedProviders.joinToString(",").ifBlank { "NONE" },
                        outcome = "PAUSED_ZERO_SPEND",
                        timestampEpochMs = now,
                    )
                )
            }
        }
    }

    private fun recordFallbackEventIfAny(outcome: AiRouteOutcome.Success, now: Long) {
        if (outcome.fallbackTriggered && outcome.fallbackFromProviderId != null) {
            val event = FallbackEvent(
                id = "fb-${UUID.randomUUID().toString().take(8)}",
                persona = outcome.persona,
                fromProviderId = outcome.fallbackFromProviderId,
                toProviderId = outcome.providerId,
                reason = outcome.fallbackReason ?: "Simulated quota or availability fallback",
                isSimulated = outcome.isSimulatedExecution,
                timestampEpochMs = now,
            )
            fallbackEvents.update { listOf(event) + it }
        }
    }

    private suspend fun insertSuccessLedgerAndAudit(
        request: TaskRoutingRequest,
        outcome: AiRouteOutcome.Success,
        now: Long,
        routingKind: String = if (outcome.tier.isExternalCloud) "EXTERNAL_CLOUD" else "SELF_HOSTED_VPS",
    ) {
        db.aiUsageLedgerDao().insert(
            AiUsageLedgerEntity(
                id = "ldg-${UUID.randomUUID().toString().take(8)}",
                persona = outcome.persona.name,
                providerId = outcome.providerId,
                modelId = outcome.modelId,
                taskTitle = request.taskTitle.take(80),
                usageCategory = outcome.usageCategory.name,
                dataClassification = outcome.dataClassification.name,
                routingKind = routingKind,
                fallbackTriggered = outcome.fallbackTriggered,
                fallbackReason = outcome.fallbackReason,
                inputTokens = outcome.inputTokens,
                outputTokens = outcome.outputTokens,
                estimatedApiCostUsd = outcome.estimatedApiCostUsd,
                estimatedInfraCostUsd = outcome.estimatedInfraCostUsd,
                isSimulated = outcome.isSimulatedExecution,
                timestampEpochMs = now,
            )
        )
        // Audit log records metadata ONLY — never raw prompts or credentials.
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = "Persona:${outcome.persona.name}",
                action = if (outcome.isSimulatedExecution) "AI_ROUTE_SIMULATED" else "AI_ROUTE_REAL",
                target = "${outcome.providerId}/${outcome.modelId} [$routingKind|${outcome.dataClassification.name}]",
                outcome = if (outcome.fallbackTriggered) "SUCCESS_WITH_FALLBACK" else "SUCCESS",
                timestampEpochMs = now,
            )
        )
    }
}

private fun parseModalitiesCsv(csv: String): Set<AiModality> =
    csv.split(",")
        .mapNotNull { token -> runCatching { AiModality.valueOf(token.trim()) }.getOrNull() }
        .toSet()
        .ifEmpty { setOf(AiModality.TEXT) }

fun PaidInferenceApprovalEntity.toPaidApprovalGrant(): PaidApprovalGrant = PaidApprovalGrant(
    approvalId = id,
    persona = runCatching { AfrinaPersona.valueOf(persona) }.getOrDefault(AfrinaPersona.BUILDER_ENGINEER),
    targetProviderId = targetProviderId,
    targetModelId = targetModelId,
    complexity = runCatching { TaskComplexity.valueOf(complexity) }.getOrDefault(TaskComplexity.COMPLEX_REASONING),
    requiresCoding = requiresCoding,
    requiredModalities = parseModalitiesCsv(requiredModalitiesCsv),
    dataClassification = runCatching { DataClassification.valueOf(dataClassification) }.getOrDefault(DataClassification.PROJECT_CODE),
    explicitSensitiveDataAuthorized = explicitSensitiveAuthorized,
    maxApprovedCostUsd = estimatedCostUsd,
)

private fun AiUsageLedgerEntity.toDomain() = AiUsageLedgerEntry(
    id = id,
    persona = runCatching { AfrinaPersona.valueOf(persona) }.getOrDefault(AfrinaPersona.BUILDER_ENGINEER),
    providerId = providerId,
    modelId = modelId,
    taskTitle = taskTitle,
    usageCategory = runCatching { UsageCostCategory.valueOf(usageCategory) }.getOrDefault(UsageCostCategory.FREE_TIER),
    dataClassification = runCatching { DataClassification.valueOf(dataClassification) }.getOrDefault(DataClassification.PROJECT_CODE),
    routingKind = routingKind,
    fallbackTriggered = fallbackTriggered,
    fallbackReason = fallbackReason,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    estimatedApiCostUsd = estimatedApiCostUsd,
    estimatedInfraCostUsd = estimatedInfraCostUsd,
    isSimulated = isSimulated,
    timestampEpochMs = timestampEpochMs,
)

private fun PaidInferenceApprovalEntity.toDomain() = PaidInferenceProposal(
    id = id,
    persona = runCatching { AfrinaPersona.valueOf(persona) }.getOrDefault(AfrinaPersona.BUILDER_ENGINEER),
    taskTitle = taskTitle,
    complexity = runCatching { TaskComplexity.valueOf(complexity) }.getOrDefault(TaskComplexity.STANDARD),
    requiresCoding = requiresCoding,
    requiredModalities = parseModalitiesCsv(requiredModalitiesCsv),
    dataClassification = runCatching { DataClassification.valueOf(dataClassification) }.getOrDefault(DataClassification.PROJECT_CODE),
    explicitSensitiveDataAuthorized = explicitSensitiveAuthorized,
    targetProviderId = targetProviderId,
    targetModelId = targetModelId,
    estimatedCostUsd = estimatedCostUsd,
    reasonEn = reasonEn,
    reasonFa = reasonFa,
    status = runCatching { ApprovalStatus.valueOf(status) }.getOrDefault(ApprovalStatus.PENDING),
    isExecuted = isExecuted,
    isSimulated = isSimulated,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)
