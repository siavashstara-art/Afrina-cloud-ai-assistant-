package com.example.afrina.core.persona

import com.example.afrina.core.data.local.AuditLogDao
import com.example.afrina.core.data.local.AuditLogEntity
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.domain.PersonaArchitectureRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Shared Phase 2 foundation orchestrating the four AFRINA personas
 * (Architect, Builder, Companion, Guardian) over one central AI router,
 * isolated per-persona contexts, 3-layer shared memory, owner-controlled
 * conceptual affective regulation, Guardian Digital Twin safeguards,
 * and Master vs Customer edition permission rules.
 */
class CentralPersonaOrchestrator(
    private val edition: AfrinaEdition = AfrinaEdition.MASTER_OWNER,
    private val aiRouterRepository: AiRouterRepository,
    private val auditLogDao: AuditLogDao? = null,
    private val profiles: Map<AfrinaPersona, PersonaProfile> = PersonaCatalog.defaultProfiles(),
    private val memoryManager: SharedMemoryManager = SharedMemoryManager(),
    private val affectiveEngine: AffectiveRegulationEngine = AffectiveRegulationEngine(),
    private val guardianTwinGuard: GuardianDigitalTwinGuard = GuardianDigitalTwinGuard(),
) : PersonaArchitectureRepository {

    private val contextMutex = Mutex()
    private val contextsFlow = MutableStateFlow(
        AfrinaPersona.entries.associateWith { persona ->
            PersonaContextState(persona = persona)
        }
    )
    private val delegationsFlow = MutableStateFlow<List<PersonaDelegationResult>>(emptyList())

    private val contextAndMemoryFlow = combine(
        contextsFlow,
        memoryManager.observeMemories(),
        delegationsFlow,
    ) { contexts, memories, delegations ->
        Triple(contexts, memories, delegations)
    }

    private val affectiveAndTwinFlow = combine(
        affectiveEngine.observeState(),
        affectiveEngine.observePolicy(),
        guardianTwinGuard.observeState(),
    ) { affectiveState, affectivePolicy, guardianTwin ->
        Triple(affectiveState, affectivePolicy, guardianTwin)
    }

    override fun observeFoundationSnapshot(): Flow<PersonaFoundationSnapshot> =
        combine(contextAndMemoryFlow, affectiveAndTwinFlow) { ctxMem, affTwin ->
            PersonaFoundationSnapshot(
                edition = edition,
                profiles = AfrinaPersona.entries.mapNotNull { profiles[it] },
                contextsByPersona = ctxMem.first,
                memories = ctxMem.second,
                affectiveState = affTwin.first,
                affectivePolicy = affTwin.second,
                guardianTwin = affTwin.third,
                delegationHistory = ctxMem.third,
            )
        }

    override suspend fun interactWithPersona(
        persona: AfrinaPersona,
        taskTitle: String,
        prompt: String,
        projectId: String?,
        complexity: TaskComplexity?,
        requiresCoding: Boolean?,
        requiredModalities: Set<AiModality>,
        dataClassification: DataClassification?,
        explicitSensitiveDataAuthorized: Boolean,
        allowLocalOpenWeight: Boolean,
        useGuardianDigitalTwinIdentity: Boolean,
    ): AiRouteOutcome {
        val profile = profiles.getValue(persona)
        val effectiveClassification = if (useGuardianDigitalTwinIdentity || persona == AfrinaPersona.GUARDIAN_SENTINEL) {
            DataClassification.DIGITAL_TWIN_SENSITIVE
        } else {
            dataClassification ?: profile.defaultDataClassification
        }

        // Fail closed if Guardian Digital Twin identity representation is requested without owner authorization.
        if (useGuardianDigitalTwinIdentity) {
            val twinState = guardianTwinGuard.currentState()
            if (!twinState.isOwnerAuthorized || !twinState.identityAuthorized) {
                affectiveEngine.applyEvent(
                    AffectiveSignalEvent.RISK_OR_PRIVACY_ALERT,
                    "Guardian Digital Twin blocked: owner authorization missing",
                )
                recordAuditMetadata(
                    actor = "Persona:${persona.name}",
                    action = "GUARDIAN_TWIN_BLOCKED",
                    target = "DIGITAL_TWIN_IDENTITY",
                    outcome = "DENIED_UNAUTH_TWIN",
                )
                return AiRouteOutcome.PrivacyAuthorizationRequired(
                    dataClassification = DataClassification.DIGITAL_TWIN_SENSITIVE,
                    blockedProviders = listOf("guardian-digital-twin"),
                    messageEn = "Guardian Digital Twin representation requires explicit owner authorization and supplied approved identity.",
                    messageFa = "بازنمایی دوقلوی دیجیتال نگهبان بدون مجوز صریح و هویت تاییدشده مالک مجاز نیست.",
                )
            }
        }

        val outcome = aiRouterRepository.executePersonaTask(
            TaskRoutingRequest(
                persona = persona,
                taskTitle = taskTitle,
                prompt = buildScopedPrompt(profile, prompt, useGuardianDigitalTwinIdentity),
                complexity = complexity ?: profile.defaultComplexity,
                requiresCoding = requiresCoding ?: profile.defaultRequiresCoding,
                requiresMultilingualFaEn = true,
                requiredModalities = requiredModalities,
                dataClassification = effectiveClassification,
                explicitSensitiveDataAuthorized = explicitSensitiveDataAuthorized,
                explicitPaidApprovalId = null,
                allowLocalOpenWeight = allowLocalOpenWeight,
            )
        )

        recordInteractionArtifacts(
            persona = persona,
            taskTitle = taskTitle,
            rawIntent = prompt,
            dataClassification = effectiveClassification,
            projectId = projectId,
            delegatedFrom = null,
            outcome = outcome,
        )
        return outcome
    }

    override suspend fun delegateTask(request: PersonaDelegationRequest): PersonaDelegationResult {
        if (request.fromPersona == request.toPersona) {
            return recordDelegationOutcome(
                PersonaDelegationResult(
                    requestId = request.id,
                    fromPersona = request.fromPersona,
                    toPersona = request.toPersona,
                    accepted = false,
                    denialReason = "Self-delegation is disallowed; delegate to a distinct AFRINA persona.",
                )
            )
        }
        if (request.taskTitle.isBlank() || request.taskPrompt.isBlank()) {
            return recordDelegationOutcome(
                PersonaDelegationResult(
                    requestId = request.id,
                    fromPersona = request.fromPersona,
                    toPersona = request.toPersona,
                    accepted = false,
                    denialReason = "Delegated task title and prompt must be non-blank.",
                )
            )
        }

        val targetProfile = profiles.getValue(request.toPersona)
        val outcome = aiRouterRepository.executePersonaTask(
            TaskRoutingRequest(
                persona = request.toPersona,
                taskTitle = request.taskTitle,
                prompt = "[Delegated from ${request.fromPersona.name}] ${targetProfile.systemInstructions}\n${request.taskPrompt}",
                complexity = request.complexity,
                requiresCoding = request.requiresCoding,
                requiresMultilingualFaEn = true,
                requiredModalities = request.requiredModalities,
                dataClassification = request.dataClassification,
                explicitSensitiveDataAuthorized = request.explicitSensitiveDataAuthorized,
                explicitPaidApprovalId = null,
                allowLocalOpenWeight = true,
            )
        )

        val turnId = recordInteractionArtifacts(
            persona = request.toPersona,
            taskTitle = request.taskTitle,
            rawIntent = request.taskPrompt,
            dataClassification = request.dataClassification,
            projectId = request.targetProjectId,
            delegatedFrom = request.fromPersona,
            outcome = outcome,
        )

        recordAuditMetadata(
            actor = "Persona:${request.fromPersona.name}",
            action = "PERSONA_DELEGATED",
            target = "${request.fromPersona.name}->${request.toPersona.name}",
            outcome = if (outcome is AiRouteOutcome.Success) "ACCEPTED_EXECUTED" else "ROUTED_GATED",
        )

        return recordDelegationOutcome(
            PersonaDelegationResult(
                requestId = request.id,
                fromPersona = request.fromPersona,
                toPersona = request.toPersona,
                accepted = outcome is AiRouteOutcome.Success,
                denialReason = when (outcome) {
                    is AiRouteOutcome.Success -> null
                    is AiRouteOutcome.PrivacyAuthorizationRequired -> outcome.messageEn
                    is AiRouteOutcome.PaidApprovalRequired -> outcome.proposal.reasonEn
                    is AiRouteOutcome.PausedQuotaOrProviderUnavailable -> outcome.messageEn
                },
                routeOutcome = outcome,
                recordedTurnId = turnId,
            )
        )
    }

    override suspend fun recordExplicitMemory(
        layer: MemoryLayer,
        contentSummary: String,
        sourcePersona: AfrinaPersona,
        dataClassification: DataClassification,
        projectId: String?,
        preferenceKey: String?,
        ownerDirect: Boolean,
    ): SharedMemoryEntry {
        val entry = memoryManager.recordMemory(
            layer = layer,
            contentSummary = contentSummary,
            sourcePersona = sourcePersona,
            sourceKind = if (ownerDirect) MemorySourceKind.OWNER_DIRECT else MemorySourceKind.INTERACTION,
            sourceReferenceId = "manual-${UUID.randomUUID().toString().take(8)}",
            dataClassification = dataClassification,
            initialConfidence = if (ownerDirect) 0.90 else 0.65,
            projectId = projectId,
            preferenceKey = preferenceKey,
            ownerDirect = ownerDirect,
        )
        recordAuditMetadata(
            actor = if (ownerDirect) "Founder" else "Persona:${sourcePersona.name}",
            action = "MEMORY_RECORDED",
            target = "${layer.name}/${dataClassification.name}",
            outcome = entry.id,
        )
        return entry
    }

    override suspend fun verifyMemory(memoryId: String): SharedMemoryEntry? {
        val updated = memoryManager.verifyMemoryByOwner(memoryId)
        if (updated != null) {
            recordAuditMetadata(
                actor = "Founder (Memory Review)",
                action = "MEMORY_VERIFIED",
                target = memoryId,
                outcome = "CONFIDENCE_${"%.2f".format(updated.confidence)}",
            )
        }
        return updated
    }

    override suspend fun correctMemory(
        memoryId: String,
        correctedSummary: String,
        ownerNote: String?,
    ): SharedMemoryEntry? {
        val updated = memoryManager.correctMemoryByOwner(memoryId, correctedSummary, ownerNote)
        if (updated != null) {
            recordAuditMetadata(
                actor = "Founder (Memory Review)",
                action = "MEMORY_CORRECTED",
                target = memoryId,
                outcome = "OWNER_CORRECTED",
            )
        }
        return updated
    }

    override suspend fun disputeMemory(memoryId: String, reasonNote: String?): SharedMemoryEntry? {
        val updated = memoryManager.disputeMemoryByOwner(memoryId, reasonNote)
        if (updated != null) {
            recordAuditMetadata(
                actor = "Founder (Memory Review)",
                action = "MEMORY_DISPUTED",
                target = memoryId,
                outcome = "OWNER_DISPUTED",
            )
        }
        return updated
    }

    override suspend fun deleteMemory(memoryId: String): Boolean {
        val deleted = memoryManager.deleteMemoryByOwner(memoryId)
        if (deleted) {
            recordAuditMetadata(
                actor = "Founder (Memory Review)",
                action = "MEMORY_DELETED",
                target = memoryId,
                outcome = "OWNER_DELETED",
            )
        }
        return deleted
    }

    override suspend fun learnFromVerifiedExperience(
        memoryId: String,
        preferenceKey: String,
    ): SharedMemoryEntry? {
        val result = memoryManager.recordVerifiedExperienceAndConsolidate(memoryId, preferenceKey)
        if (result != null) {
            recordAuditMetadata(
                actor = "SharedMemoryLearning",
                action = "GRADUAL_MEMORY_LEARNING",
                target = "${result.layer.name}:$preferenceKey",
                outcome = result.id,
            )
        }
        return result
    }

    override suspend fun updateAffectivePolicy(policy: AffectiveControlPolicy): AffectiveControlPolicy {
        val sanitized = affectiveEngine.updateOwnerPolicy(policy)
        recordAuditMetadata(
            actor = "Founder (Affective Control)",
            action = "AFFECTIVE_POLICY_UPDATED",
            target = "frozen=${sanitized.ownerLockFrozen};learn=${sanitized.allowAdaptiveLearning}",
            outcome = "UPDATED",
        )
        return sanitized
    }

    override suspend fun applyAffectiveSignal(
        event: AffectiveSignalEvent,
        reason: String,
    ): AffectiveState = affectiveEngine.applyEvent(event, reason)

    override suspend fun regulateAffectiveHomeostasis(): AffectiveState =
        affectiveEngine.regulateHomeostasis()

    override suspend fun resetAffectiveState(): AffectiveState {
        val reset = affectiveEngine.resetToOwnerBaselines()
        recordAuditMetadata(
            actor = "Founder (Affective Control)",
            action = "AFFECTIVE_STATE_RESET",
            target = "OWNER_BASELINE",
            outcome = "RESET",
        )
        return reset
    }

    override suspend fun setGuardianIdentityAuthorization(
        authorized: Boolean,
        approvedIdentitySummary: String?,
        approvedCommunicationStyle: String?,
    ): Result<GuardianDigitalTwinState> {
        val res = guardianTwinGuard.setIdentityAuthorization(
            authorized = authorized,
            approvedIdentitySummary = approvedIdentitySummary,
            approvedCommunicationStyle = approvedCommunicationStyle,
        )
        recordAuditMetadata(
            actor = "Founder (Guardian Twin Gate)",
            action = "GUARDIAN_IDENTITY_AUTH",
            target = "authorized=$authorized",
            outcome = if (res.isSuccess) "UPDATED" else "REJECTED",
        )
        return res
    }

    override suspend fun registerGuardianMediaSample(
        kind: TwinMediaKind,
        privateVaultAlias: String,
        mimeType: String,
        durationMs: Long?,
        ownerAuthorized: Boolean,
    ): Result<DigitalTwinMediaSample> {
        val res = guardianTwinGuard.registerSuppliedMediaSample(
            kind = kind,
            privateVaultAlias = privateVaultAlias,
            mimeType = mimeType,
            durationMs = durationMs,
            ownerAuthorized = ownerAuthorized,
        )
        recordAuditMetadata(
            actor = "Founder (Guardian Twin Gate)",
            action = "GUARDIAN_SAMPLE_REGISTERED",
            target = kind.name,
            outcome = if (res.isSuccess) "STORED_PRIVATE_REF" else "REJECTED",
        )
        return res
    }

    override suspend fun prepareGuardianVoiceClone(ownerAuthorized: Boolean): Result<GuardianDigitalTwinState> {
        val res = guardianTwinGuard.prepareVoiceCloneCapability(ownerAuthorized)
        recordAuditMetadata(
            actor = "Founder (Guardian Twin Gate)",
            action = "GUARDIAN_VOICE_PREPARE",
            target = "VOICE_CLONE_CAPABILITY",
            outcome = if (res.isSuccess) "PREPARED_AWAITING_ENGINE" else "REJECTED",
        )
        return res
    }

    override suspend fun prepareGuardianVisualAvatar(ownerAuthorized: Boolean): Result<GuardianDigitalTwinState> {
        val res = guardianTwinGuard.prepareVisualAvatarCapability(ownerAuthorized)
        recordAuditMetadata(
            actor = "Founder (Guardian Twin Gate)",
            action = "GUARDIAN_AVATAR_PREPARE",
            target = "AVATAR_RENDER_CAPABILITY",
            outcome = if (res.isSuccess) "PREPARED_AWAITING_ENGINE" else "REJECTED",
        )
        return res
    }

    override suspend fun evaluateDevelopmentCapability(
        capability: DevelopmentCapability,
        inIsolatedDevEnvironment: Boolean,
        ownerApprovedWorkflow: Boolean,
    ): DevelopmentPermissionDecision {
        val decision = EditionPermissionPolicy.evaluate(
            edition = edition,
            capability = capability,
            inIsolatedDevEnvironment = inIsolatedDevEnvironment,
            ownerApprovedWorkflow = ownerApprovedWorkflow,
        )
        recordAuditMetadata(
            actor = "Edition:${edition.name}",
            action = "DEV_PERMISSION_CHECK",
            target = capability.name,
            outcome = if (decision is DevelopmentPermissionDecision.Allowed) "ALLOWED" else "DENIED",
        )
        return decision
    }

    private fun buildScopedPrompt(
        profile: PersonaProfile,
        userPrompt: String,
        useGuardianDigitalTwinIdentity: Boolean,
    ): String {
        val twinPrefix = if (useGuardianDigitalTwinIdentity) {
            val twin = guardianTwinGuard.currentState()
            "Authorized Male Digital Twin Style: ${twin.approvedCommunicationStyle ?: "Owner-Approved Neutral"}. "
        } else {
            ""
        }
        return "[${profile.canonicalName}] $twinPrefix${profile.systemInstructions}\n$userPrompt"
    }

    private suspend fun recordInteractionArtifacts(
        persona: AfrinaPersona,
        taskTitle: String,
        rawIntent: String,
        dataClassification: DataClassification,
        projectId: String?,
        delegatedFrom: AfrinaPersona?,
        outcome: AiRouteOutcome,
    ): String? {
        val now = System.currentTimeMillis()
        val safeSummary = if (dataClassification.isSensitiveOwnerData) {
            "[Owner-Private Interaction: ${taskTitle.take(60)}]"
        } else {
            rawIntent.take(120)
        }

        val turnId: String? = if (outcome is AiRouteOutcome.Success) {
            val id = "turn-${UUID.randomUUID().toString().take(8)}"
            val turn = PersonaInteractionTurn(
                id = id,
                persona = persona,
                taskTitle = taskTitle,
                intentSummary = safeSummary,
                responseEn = outcome.outputEn,
                responseFa = outcome.outputFa,
                providerId = outcome.providerId,
                modelId = outcome.modelId,
                isSimulated = outcome.isSimulatedExecution,
                delegatedFrom = delegatedFrom,
                timestampEpochMs = now,
            )
            contextMutex.withLock {
                val map = contextsFlow.value.toMutableMap()
                val current = map[persona] ?: PersonaContextState(persona = persona)
                map[persona] = current.copy(
                    activeProjectId = projectId ?: current.activeProjectId,
                    activeGoal = taskTitle.take(80),
                    turns = listOf(turn) + current.turns.take(24),
                    updatedAtEpochMs = now,
                )
                contextsFlow.value = map
            }

            memoryManager.recordMemory(
                layer = if (projectId != null) MemoryLayer.MEDIUM_TERM_EPISODIC_PROJECT else MemoryLayer.SHORT_TERM_WORKING,
                contentSummary = "$taskTitle -> ${outcome.modelId}",
                sourcePersona = persona,
                sourceKind = if (delegatedFrom != null) MemorySourceKind.PERSONA_DELEGATION else MemorySourceKind.INTERACTION,
                sourceReferenceId = id,
                dataClassification = dataClassification,
                initialConfidence = 0.60,
                projectId = projectId,
            )

            val signal = when {
                persona == AfrinaPersona.COMPANION_EMPATHY -> AffectiveSignalEvent.CALM_STEADY_INTERACTION
                else -> AffectiveSignalEvent.TASK_SUCCESS
            }
            affectiveEngine.applyEvent(signal, "${persona.name} completed '$taskTitle'")
            affectiveEngine.regulateHomeostasis()
            id
        } else {
            affectiveEngine.applyEvent(
                AffectiveSignalEvent.RISK_OR_PRIVACY_ALERT,
                "${persona.name} gated on '${taskTitle}'",
            )
            null
        }
        return turnId
    }

    private fun recordDelegationOutcome(result: PersonaDelegationResult): PersonaDelegationResult {
        delegationsFlow.value = listOf(result) + delegationsFlow.value.take(24)
        return result
    }

    private suspend fun recordAuditMetadata(
        actor: String,
        action: String,
        target: String,
        outcome: String,
    ) {
        auditLogDao?.insert(
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
