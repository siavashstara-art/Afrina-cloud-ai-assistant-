package com.example

import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.FreeTierFallbackProxyAdapter
import com.example.afrina.core.ai.FreeTierPrimaryProxyAdapter
import com.example.afrina.core.ai.PaidFrontierCloudAdapter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.ai.SelfHostedOpenWeightAdapter
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AiUsageLedgerEntry
import com.example.afrina.core.model.CostTransparencySummary
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.FallbackEvent
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.ProviderTier
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.model.UsageCostCategory
import com.example.afrina.core.persona.AffectiveControlPolicy
import com.example.afrina.core.persona.AffectiveRegulationEngine
import com.example.afrina.core.persona.AffectiveSignalEvent
import com.example.afrina.core.persona.AfrinaEdition
import com.example.afrina.core.persona.CentralPersonaOrchestrator
import com.example.afrina.core.persona.DevelopmentCapability
import com.example.afrina.core.persona.DevelopmentPermissionDecision
import com.example.afrina.core.persona.EditionPermissionPolicy
import com.example.afrina.core.persona.GenderPresentation
import com.example.afrina.core.persona.MemoryLayer
import com.example.afrina.core.persona.MemoryReviewStatus
import com.example.afrina.core.persona.MemorySourceKind
import com.example.afrina.core.persona.PersonaCatalog
import com.example.afrina.core.persona.PersonaDelegationRequest
import com.example.afrina.core.persona.SharedMemoryManager
import com.example.afrina.core.persona.TwinCapabilityStatus
import com.example.afrina.core.persona.TwinMediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaFoundationTest {

    private fun createInMemoryRouterRepo(): Pair<CentralAiProviderRouter, AiRouterRepository> {
        val router = CentralAiProviderRouter(
            adapters = listOf(
                FreeTierPrimaryProxyAdapter(),
                FreeTierFallbackProxyAdapter(),
                SelfHostedOpenWeightAdapter(),
                PaidFrontierCloudAdapter(),
            ),
            approvalGrantResolver = { null }, // Fail-closed unless explicitly granted
        )
        val dispatcher = PersonaDispatcher(router)
        val statuses = MutableStateFlow(router.providerStatuses())
        val policy = MutableStateFlow(router.spendingPolicy())
        val ledger = MutableStateFlow<List<AiUsageLedgerEntry>>(emptyList())
        val costSummary = MutableStateFlow(CostTransparencySummary())
        val paidApprovals = MutableStateFlow<List<PaidInferenceProposal>>(emptyList())
        val fallbacks = MutableStateFlow<List<FallbackEvent>>(emptyList())
        val latest = MutableStateFlow<AiRouteOutcome?>(null)

        val repo = object : AiRouterRepository {
            override fun observeProviderStatuses(): Flow<List<ProviderQuotaStatus>> = statuses
            override fun observeSpendingPolicy(): Flow<OwnerSpendingPolicy> = policy
            override fun observeUsageLedger(): Flow<List<AiUsageLedgerEntry>> = ledger
            override fun observeCostSummary(): Flow<CostTransparencySummary> = costSummary
            override fun observePaidApprovals(): Flow<List<PaidInferenceProposal>> = paidApprovals
            override fun observeFallbackEvents(): Flow<List<FallbackEvent>> = fallbacks
            override fun observeLatestOutcome(): Flow<AiRouteOutcome?> = latest

            override suspend fun executePersonaTask(request: TaskRoutingRequest): AiRouteOutcome {
                val res = dispatcher.runPersonaTask(
                    persona = request.persona,
                    taskTitle = request.taskTitle,
                    prompt = request.prompt,
                    complexity = request.complexity,
                    requiresCoding = request.requiresCoding,
                    requiredModalities = request.requiredModalities,
                    dataClassification = request.dataClassification,
                    explicitSensitiveDataAuthorized = request.explicitSensitiveDataAuthorized,
                    explicitPaidApprovalId = request.explicitPaidApprovalId,
                    allowLocalOpenWeight = request.allowLocalOpenWeight,
                )
                latest.value = res
                statuses.value = router.providerStatuses()
                if (res is AiRouteOutcome.Success) {
                    ledger.value = listOf(
                        AiUsageLedgerEntry(
                            id = "ldg-${ledger.value.size + 1}",
                            persona = res.persona,
                            providerId = res.providerId,
                            modelId = res.modelId,
                            taskTitle = request.taskTitle,
                            usageCategory = res.usageCategory,
                            dataClassification = res.dataClassification,
                            routingKind = if (res.tier.isExternalCloud) "EXTERNAL_CLOUD" else "SELF_HOSTED_VPS",
                            inputTokens = res.inputTokens,
                            outputTokens = res.outputTokens,
                            estimatedApiCostUsd = res.estimatedApiCostUsd,
                            estimatedInfraCostUsd = res.estimatedInfraCostUsd,
                            isSimulated = res.isSimulatedExecution,
                            timestampEpochMs = 1L,
                        )
                    ) + ledger.value
                }
                return res
            }

            override suspend fun approvePaidInference(proposalId: String) = Unit
            override suspend fun rejectPaidInference(proposalId: String) = Unit
            override suspend fun executeApprovedPaidTask(proposalId: String) =
                AiRouteOutcome.PausedQuotaOrProviderUnavailable("Disabled", "غیرفعال", emptyList())

            override suspend fun simulateExhaustPrimaryQuota() {
                router.setSimulatedQuota("server-proxy-free-primary", 0)
                statuses.value = router.providerStatuses()
            }

            override suspend fun resetSimulatedQuotas() {
                router.setSimulatedQuota("server-proxy-free-primary", 40)
                statuses.value = router.providerStatuses()
            }
        }
        return router to repo
    }

    @Test
    fun `four personas have distinct identities roles configs and share one central router`() = runTest {
        val profiles = PersonaCatalog.defaultProfiles()
        assertEquals(4, profiles.size)
        assertEquals(
            setOf("Architect", "Builder", "Companion", "Guardian"),
            profiles.values.map { it.canonicalName }.toSet(),
        )
        assertEquals(4, profiles.values.map { it.systemInstructions }.toSet().size)

        val guardian = profiles.getValue(AfrinaPersona.GUARDIAN_SENTINEL)
        assertEquals(GenderPresentation.MALE, guardian.genderPresentation)
        assertTrue(guardian.isDigitalTwinIdentity)
        assertEquals(DataClassification.DIGITAL_TWIN_SENSITIVE, guardian.defaultDataClassification)

        val (_, repo) = createInMemoryRouterRepo()
        val orchestrator = CentralPersonaOrchestrator(aiRouterRepository = repo)

        for (persona in AfrinaPersona.entries) {
            val outcome = orchestrator.interactWithPersona(
                persona = persona,
                taskTitle = "Shared-router check ${persona.name}",
                prompt = "Confirm shared routing for ${persona.name}",
            )
            assertTrue("Expected success for $persona but got $outcome", outcome is AiRouteOutcome.Success)
            val success = outcome as AiRouteOutcome.Success
            assertEquals(0.0, success.estimatedApiCostUsd, 0.0)
            if (persona == AfrinaPersona.COMPANION_EMPATHY || persona == AfrinaPersona.GUARDIAN_SENTINEL) {
                assertEquals(ProviderTier.LOCAL_OPEN_WEIGHT, success.tier)
            }
        }

        val recordedLedger = repo.observeUsageLedger().first()
        assertEquals(4, recordedLedger.size)
        assertEquals(AfrinaPersona.entries.toSet(), recordedLedger.map { it.persona }.toSet())
    }

    @Test
    fun `persona interaction contexts remain strictly isolated per persona`() = runTest {
        val (_, repo) = createInMemoryRouterRepo()
        val orchestrator = CentralPersonaOrchestrator(aiRouterRepository = repo)

        orchestrator.interactWithPersona(
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            taskTitle = "Design boundary ADR",
            prompt = "Outline module contracts",
            projectId = "proj-arch-1",
        )
        orchestrator.interactWithPersona(
            persona = AfrinaPersona.BUILDER_ENGINEER,
            taskTitle = "Write Kotlin parser",
            prompt = "Implement parser unit test",
            projectId = "proj-build-2",
        )

        val snapshot = orchestrator.observeFoundationSnapshot().first()
        val archCtx = snapshot.contextsByPersona.getValue(AfrinaPersona.ARCHITECT_STRATEGIST)
        val buildCtx = snapshot.contextsByPersona.getValue(AfrinaPersona.BUILDER_ENGINEER)
        val compCtx = snapshot.contextsByPersona.getValue(AfrinaPersona.COMPANION_EMPATHY)
        val guardCtx = snapshot.contextsByPersona.getValue(AfrinaPersona.GUARDIAN_SENTINEL)

        assertEquals(1, archCtx.turns.size)
        assertEquals("proj-arch-1", archCtx.activeProjectId)
        assertEquals("Design boundary ADR", archCtx.turns.first().taskTitle)

        assertEquals(1, buildCtx.turns.size)
        assertEquals("proj-build-2", buildCtx.activeProjectId)
        assertEquals("Write Kotlin parser", buildCtx.turns.first().taskTitle)

        assertTrue(compCtx.turns.isEmpty())
        assertTrue(guardCtx.turns.isEmpty())
    }

    @Test
    fun `shared three-layer memory preserves provenance owner review correction dispute and gradual learning`() = runTest {
        val manager = SharedMemoryManager()

        val working = manager.recordMemory(
            layer = MemoryLayer.SHORT_TERM_WORKING,
            contentSummary = "Owner prefers bilingual Persian RTL default",
            sourcePersona = AfrinaPersona.COMPANION_EMPATHY,
            sourceKind = MemorySourceKind.INTERACTION,
            sourceReferenceId = "turn-01",
            dataClassification = DataClassification.OWNER_PRIVATE,
            initialConfidence = 0.60,
            preferenceKey = "ui_language_pref",
        )
        assertEquals(MemoryReviewStatus.UNREVIEWED, working.reviewStatus)

        // Privacy gate: private memory hidden unless includeSensitiveOwnerData = true
        assertTrue(manager.queryMemories(includeSensitiveOwnerData = false).isEmpty())
        assertEquals(1, manager.queryMemories(includeSensitiveOwnerData = true).size)

        // Owner verification boosts confidence and marks reviewed
        val verified = manager.verifyMemoryByOwner(working.id, confidenceBoost = 0.15)!!
        assertEquals(MemoryReviewStatus.OWNER_VERIFIED, verified.reviewStatus)
        assertTrue(verified.provenance.lastReviewedByOwner)
        assertEquals(0.75, verified.confidence, 0.001)

        // Gradual learning after second verified experience promotes to LONG_TERM_SEMANTIC_PREFERENCE
        val promoted = manager.recordVerifiedExperienceAndConsolidate(working.id, "ui_language_pref")!!
        assertEquals(MemoryLayer.LONG_TERM_SEMANTIC_PREFERENCE, promoted.layer)
        assertEquals(MemorySourceKind.GRADUAL_CONSOLIDATION, promoted.provenance.sourceKind)
        assertEquals(working.id, promoted.provenance.sourceReferenceId)

        // Owner correction sets OWNER_CORRECTED and future consolidation must not overwrite it
        val corrected = manager.correctMemoryByOwner(
            memoryId = promoted.id,
            correctedSummary = "Owner uses Persian RTL on mobile and English LTR for specs",
            correctionNote = "Clarified per device context",
        )!!
        assertEquals(MemoryReviewStatus.OWNER_CORRECTED, corrected.reviewStatus)
        val afterSecondConsolidation = manager.recordVerifiedExperienceAndConsolidate(working.id, "ui_language_pref")!!
        assertEquals(
            "Owner uses Persian RTL on mobile and English LTR for specs",
            afterSecondConsolidation.contentSummary,
        )

        // Owner dispute hides memory from active queries
        val disputed = manager.disputeMemoryByOwner(corrected.id, "Outdated rule")!!
        assertEquals(MemoryReviewStatus.OWNER_DISPUTED, disputed.reviewStatus)
        assertTrue(
            manager.queryMemories(
                layer = MemoryLayer.LONG_TERM_SEMANTIC_PREFERENCE,
                includeSensitiveOwnerData = true,
            ).none { it.id == disputed.id }
        )

        // Owner hard-delete removes memory record permanently
        assertTrue(manager.deleteMemoryByOwner(disputed.id))
        assertFalse(manager.deleteMemoryByOwner("nonexistent-id"))
        assertTrue(manager.observeMemories().value.none { it.id == disputed.id })
    }

    @Test
    fun `conceptual affective engine models bounded regulation variables under owner freeze and reset control`() = runTest {
        val engine = AffectiveRegulationEngine()
        val initial = engine.currentState()
        assertTrue(initial.isConceptualComputationalModel)
        assertFalse(initial.isFrozenByOwner)

        val elevated = engine.applyEvent(AffectiveSignalEvent.TASK_SUCCESS, "Completed architecture check")
        assertTrue(elevated.dopamineLike > initial.dopamineLike)
        assertTrue(elevated.cortisolLike < initial.cortisolLike)
        assertTrue(elevated.dopamineLike in 0.0..1.0)

        val regulated = engine.regulateHomeostasis(factor = 0.5)
        assertTrue(regulated.dopamineLike < elevated.dopamineLike)
        assertTrue(regulated.dopamineLike >= initial.dopamineLike)

        // Owner freeze blocks autonomous updates
        engine.updateOwnerPolicy(AffectiveControlPolicy(ownerLockFrozen = true))
        val frozenSnapshot = engine.currentState()
        assertTrue(frozenSnapshot.isFrozenByOwner)
        val afterBlockedAlert = engine.applyEvent(AffectiveSignalEvent.RISK_OR_PRIVACY_ALERT, "Should be ignored while frozen")
        assertEquals(frozenSnapshot.cortisolLike, afterBlockedAlert.cortisolLike, 0.0001)
        assertEquals(frozenSnapshot.dopamineLike, afterBlockedAlert.dopamineLike, 0.0001)

        // Owner reset restores baseline
        val reset = engine.resetToOwnerBaselines()
        assertEquals(0.55, reset.dopamineLike, 0.0001)
        assertEquals(0.65, reset.serotoninLike, 0.0001)
        assertEquals(0.50, reset.endorphinLike, 0.0001)
        assertEquals(0.20, reset.cortisolLike, 0.0001)
    }

    @Test
    fun `controlled inter-persona delegation enforces boundaries and updates target persona context only`() = runTest {
        val (_, repo) = createInMemoryRouterRepo()
        val orchestrator = CentralPersonaOrchestrator(aiRouterRepository = repo)

        // Self-delegation must be rejected
        val selfResult = orchestrator.delegateTask(
            PersonaDelegationRequest(
                id = "del-self",
                fromPersona = AfrinaPersona.ARCHITECT_STRATEGIST,
                toPersona = AfrinaPersona.ARCHITECT_STRATEGIST,
                taskTitle = "Self loop",
                taskPrompt = "Should fail",
            )
        )
        assertFalse(selfResult.accepted)
        assertNotNull(selfResult.denialReason)

        // Valid Architect -> Builder delegation routes through central AI router
        val validResult = orchestrator.delegateTask(
            PersonaDelegationRequest(
                id = "del-1",
                fromPersona = AfrinaPersona.ARCHITECT_STRATEGIST,
                toPersona = AfrinaPersona.BUILDER_ENGINEER,
                taskTitle = "Implement ADR module",
                taskPrompt = "Create clean Kotlin interface",
                targetProjectId = "proj-core",
                complexity = TaskComplexity.STANDARD,
                requiresCoding = true,
                dataClassification = DataClassification.PROJECT_CODE,
            )
        )
        assertTrue(validResult.accepted)
        assertNotNull(validResult.recordedTurnId)

        val snapshot = orchestrator.observeFoundationSnapshot().first()
        assertEquals(0, snapshot.contextsByPersona.getValue(AfrinaPersona.ARCHITECT_STRATEGIST).turns.size)
        val builderTurns = snapshot.contextsByPersona.getValue(AfrinaPersona.BUILDER_ENGINEER).turns
        assertEquals(1, builderTurns.size)
        assertEquals(AfrinaPersona.ARCHITECT_STRATEGIST, builderTurns.first().delegatedFrom)

        // Paid-only modality delegation still blocked by $0 spending rule
        val blockedPaidDelegation = orchestrator.delegateTask(
            PersonaDelegationRequest(
                id = "del-paid-blocked",
                fromPersona = AfrinaPersona.ARCHITECT_STRATEGIST,
                toPersona = AfrinaPersona.BUILDER_ENGINEER,
                taskTitle = "Video review",
                taskPrompt = "Analyze screencast",
                complexity = TaskComplexity.COMPLEX_REASONING,
                requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
            )
        )
        assertFalse(blockedPaidDelegation.accepted)
        assertTrue(blockedPaidDelegation.routeOutcome is AiRouteOutcome.PaidApprovalRequired)
    }

    @Test
    fun `guardian digital twin fails closed without owner authorization and never fabricates samples`() = runTest {
        val (_, repo) = createInMemoryRouterRepo()
        val orchestrator = CentralPersonaOrchestrator(aiRouterRepository = repo)

        // 1. Fail closed when digital twin identity requested before owner authorization
        val blockedTwin = orchestrator.interactWithPersona(
            persona = AfrinaPersona.GUARDIAN_SENTINEL,
            taskTitle = "Speak as owner digital twin",
            prompt = "Draft reply in owner voice",
            useGuardianDigitalTwinIdentity = true,
        )
        assertTrue(blockedTwin is AiRouteOutcome.PrivacyAuthorizationRequired)

        // 2. Reject blank identity summary
        val blankIdentityResult = orchestrator.setGuardianIdentityAuthorization(
            authorized = true,
            approvedIdentitySummary = "   ",
            approvedCommunicationStyle = "Concise",
        )
        assertTrue(blankIdentityResult.isFailure)

        // 3. Reject unauthorized media sample & reject voice/avatar prep without supplied samples
        assertTrue(
            orchestrator.registerGuardianMediaSample(
                kind = TwinMediaKind.VOICE_SAMPLE,
                privateVaultAlias = "vault://twin/voice_01",
                mimeType = "audio/wav",
                durationMs = 15_000L,
                ownerAuthorized = false,
            ).isFailure
        )
        val validIdentity = orchestrator.setGuardianIdentityAuthorization(
            authorized = true,
            approvedIdentitySummary = "Owner male technical founder profile",
            approvedCommunicationStyle = "Measured, principled, bilingual Persian/English",
        )
        assertTrue(validIdentity.isSuccess)
        assertEquals(GenderPresentation.MALE, validIdentity.getOrThrow().gender)
        assertTrue(orchestrator.prepareGuardianVoiceClone(ownerAuthorized = true).isFailure)
        assertTrue(orchestrator.prepareGuardianVisualAvatar(ownerAuthorized = true).isFailure)

        // 4. Register owner-authorized samples -> prepare enters PREPARED_AWAITING_SYNTHESIS_ENGINE (no fake cloning)
        assertTrue(
            orchestrator.registerGuardianMediaSample(
                kind = TwinMediaKind.VOICE_SAMPLE,
                privateVaultAlias = "vault://twin/voice_01",
                mimeType = "audio/wav",
                durationMs = 18_000L,
                ownerAuthorized = true,
            ).isSuccess
        )
        assertTrue(
            orchestrator.registerGuardianMediaSample(
                kind = TwinMediaKind.PHOTOGRAPH,
                privateVaultAlias = "vault://twin/photo_01",
                mimeType = "image/jpeg",
                ownerAuthorized = true,
            ).isSuccess
        )
        val preparedVoice = orchestrator.prepareGuardianVoiceClone(ownerAuthorized = true).getOrThrow()
        assertEquals(TwinCapabilityStatus.PREPARED_AWAITING_SYNTHESIS_ENGINE, preparedVoice.voiceCloneStatus)
        val preparedAvatar = orchestrator.prepareGuardianVisualAvatar(ownerAuthorized = true).getOrThrow()
        assertEquals(TwinCapabilityStatus.PREPARED_AWAITING_SYNTHESIS_ENGINE, preparedAvatar.avatarRenderStatus)

        // 5. Authorized Guardian Digital Twin routes strictly via self-hosted VPS (never external cloud)
        val authorizedOutcome = orchestrator.interactWithPersona(
            persona = AfrinaPersona.GUARDIAN_SENTINEL,
            taskTitle = "Owner-authorized twin draft",
            prompt = "Draft technical alignment note",
            useGuardianDigitalTwinIdentity = true,
        )
        assertTrue(authorizedOutcome is AiRouteOutcome.Success)
        val success = authorizedOutcome as AiRouteOutcome.Success
        assertEquals(ProviderTier.LOCAL_OPEN_WEIGHT, success.tier)
        assertEquals(UsageCostCategory.SELF_HOSTED_INFRA, success.usageCategory)
    }

    @Test
    fun `master vs customer edition enforces isolated owner-approved development vs full customer lock`() {
        val masterAllowedCapabilities = listOf(
            DevelopmentCapability.PROPOSE_CODE,
            DevelopmentCapability.GENERATE_CODE_PATCH,
            DevelopmentCapability.RUN_ISOLATED_TESTS,
            DevelopmentCapability.IMPROVE_WORKFLOW_CODE,
        )
        val protectedGovernanceCapabilities = listOf(
            DevelopmentCapability.MODIFY_AFRINA_CORE,
            DevelopmentCapability.MODIFY_LICENSING,
            DevelopmentCapability.MODIFY_SECURITY_CONTROLS,
            DevelopmentCapability.MODIFY_INTERNAL_EXECUTION_POLICIES,
        )

        for (cap in masterAllowedCapabilities) {
            // Allowed ONLY when BOTH inIsolatedDevEnvironment AND ownerApprovedWorkflow are true
            assertTrue(
                EditionPermissionPolicy.evaluate(
                    edition = AfrinaEdition.MASTER_OWNER,
                    capability = cap,
                    inIsolatedDevEnvironment = true,
                    ownerApprovedWorkflow = true,
                ) is DevelopmentPermissionDecision.Allowed
            )
            // Denied if not isolated
            assertTrue(
                EditionPermissionPolicy.evaluate(
                    edition = AfrinaEdition.MASTER_OWNER,
                    capability = cap,
                    inIsolatedDevEnvironment = false,
                    ownerApprovedWorkflow = true,
                ) is DevelopmentPermissionDecision.Denied
            )
            // Denied if not owner-approved
            assertTrue(
                EditionPermissionPolicy.evaluate(
                    edition = AfrinaEdition.MASTER_OWNER,
                    capability = cap,
                    inIsolatedDevEnvironment = true,
                    ownerApprovedWorkflow = false,
                ) is DevelopmentPermissionDecision.Denied
            )
        }

        // Protected governance capabilities remain denied in automated workflow even for Master
        for (cap in protectedGovernanceCapabilities) {
            assertTrue(
                EditionPermissionPolicy.evaluate(
                    edition = AfrinaEdition.MASTER_OWNER,
                    capability = cap,
                    inIsolatedDevEnvironment = true,
                    ownerApprovedWorkflow = true,
                ) is DevelopmentPermissionDecision.Denied
            )
        }

        // Customer edition is ALWAYS denied EVERY capability regardless of flags
        for (cap in DevelopmentCapability.entries) {
            val decision = EditionPermissionPolicy.evaluate(
                edition = AfrinaEdition.CUSTOMER_STANDARD,
                capability = cap,
                inIsolatedDevEnvironment = true,
                ownerApprovedWorkflow = true,
            )
            assertTrue("Customer edition must be denied $cap", decision is DevelopmentPermissionDecision.Denied)
        }
    }
}
