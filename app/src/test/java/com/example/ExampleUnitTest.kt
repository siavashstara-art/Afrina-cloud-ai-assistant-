package com.example

import com.example.afrina.core.ai.AdapterCallResult
import com.example.afrina.core.ai.AiProviderAdapter
import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.FreeTierFallbackProxyAdapter
import com.example.afrina.core.ai.FreeTierPrimaryProxyAdapter
import com.example.afrina.core.ai.LocalSimulatedAiGateway
import com.example.afrina.core.ai.PaidFrontierCloudAdapter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.ai.SelfHostedOpenWeightAdapter
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.AiCompletionRequest
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.i18n.stringsFor
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.AgentDefinition
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiModelDescriptor
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AiUsageLedgerEntry
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ApprovalRequest
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.CostTransparencySummary
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.FallbackEvent
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidApprovalGrant
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.ProviderRetentionPolicy
import com.example.afrina.core.model.ProviderTier
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.model.UsageCostCategory
import com.example.afrina.core.network.ControlPlaneHealthDto
import com.example.afrina.core.persona.CentralPersonaOrchestrator
import com.example.afrina.feature.agents.AgentsViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExampleUnitTest {

    private fun createRouter(
        policy: OwnerSpendingPolicy = OwnerSpendingPolicy(),
        adapters: List<AiProviderAdapter> = listOf(
            FreeTierPrimaryProxyAdapter(),
            FreeTierFallbackProxyAdapter(),
            SelfHostedOpenWeightAdapter(),
            PaidFrontierCloudAdapter(),
        ),
        approvalGrantResolver: suspend (String) -> PaidApprovalGrant? = { null },
    ): CentralAiProviderRouter = CentralAiProviderRouter(
        adapters = adapters,
        policy = policy,
        approvalGrantResolver = approvalGrantResolver,
    )

    @Test
    fun `persian and english language configs set expected layout direction`() {
        assertTrue(AppLanguage.PERSIAN.isRtl)
        assertFalse(AppLanguage.ENGLISH.isRtl)

        val fa = stringsFor(AppLanguage.PERSIAN)
        val en = stringsFor(AppLanguage.ENGLISH)
        assertTrue(fa.appTitle.isNotBlank())
        assertTrue(en.appTitle.isNotBlank())
        assertTrue(fa.phase0BannerTitle.isNotBlank())
        assertTrue(en.phase0BannerTitle.isNotBlank())
        assertTrue(fa.routerZeroBudgetBanner.contains("0.00"))
        assertTrue(en.routerZeroBudgetBanner.contains("0.00"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy simulated AI gateway direct execution is explicitly disabled for all roles`() = runTest {
        val gateway = LocalSimulatedAiGateway()
        assertTrue(gateway.supportedModels.isEmpty())
        assertEquals("disabled-legacy-gateway", gateway.providerId)

        for (role in AgentRole.entries) {
            var threwUnsupported = false
            try {
                gateway.executePrompt(
                    AiCompletionRequest(
                        agentRole = role,
                        modelId = "provider-agnostic/architect-v1",
                        prompt = "Verify architecture boundaries",
                        contextProjectSlug = "afrina-control-plane",
                    )
                )
            } catch (_: UnsupportedOperationException) {
                threwUnsupported = true
            }
            assertTrue("Expected direct execution to be blocked for $role", threwUnsupported)
        }
    }

    @Test
    fun `moshi adapter serializes and deserializes ControlPlaneHealthDto`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ControlPlaneHealthDto::class.java)
        val dto = ControlPlaneHealthDto(
            status = "healthy",
            apiVersion = "v1",
            vpsProfile = "ubuntu-24.04-docker",
            activeSandboxes = 2,
        )
        val json = adapter.toJson(dto)
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(dto, parsed)
    }

    @Test
    fun `free-first routing selects lightest free model for simple tasks and serves all 4 personas`() = runTest {
        val router = createRouter()
        val dispatcher = PersonaDispatcher(router)

        for (persona in AfrinaPersona.entries) {
            val outcome = dispatcher.runPersonaTask(
                persona = persona,
                taskTitle = "Simple triage for ${persona.name}",
                prompt = "Summarize sprint status",
                complexity = TaskComplexity.SIMPLE,
                requiresCoding = false,
                dataClassification = DataClassification.PROJECT_CODE,
            )
            assertTrue(outcome is AiRouteOutcome.Success)
            val success = outcome as AiRouteOutcome.Success
            assertEquals(ProviderTier.FREE_CLOUD, success.tier)
            assertEquals("free-lite/fast-router-v1", success.modelId)
            assertEquals(UsageCostCategory.FREE_TIER, success.usageCategory)
            assertEquals(0.0, success.estimatedApiCostUsd, 0.0)
            assertTrue(success.isSimulatedExecution)
        }
    }

    @Test
    fun `exhausting primary simulated quota triggers automatic fallback to secondary free provider`() = runTest {
        val router = createRouter()
        router.setSimulatedQuota("server-proxy-free-primary", 0)

        val outcome = router.routeAndExecute(
            TaskRoutingRequest(
                persona = AfrinaPersona.BUILDER_ENGINEER,
                taskTitle = "Generate Kotlin unit test",
                prompt = "Write test for repository",
                complexity = TaskComplexity.STANDARD,
                requiresCoding = true,
                dataClassification = DataClassification.PROJECT_CODE,
            )
        )

        assertTrue(outcome is AiRouteOutcome.Success)
        val success = outcome as AiRouteOutcome.Success
        assertEquals("server-proxy-free-fallback", success.providerId)
        assertTrue(success.fallbackTriggered)
        assertEquals("server-proxy-free-primary", success.fallbackFromProviderId)
        assertEquals(0.0, success.estimatedApiCostUsd, 0.0)
        assertTrue(success.isSimulatedExecution)
    }

    @Test
    fun `zero spending policy blocks paid calls without approval and fails closed by default`() = runTest {
        val defaultFailClosedRouter = createRouter()
        val policy = defaultFailClosedRouter.spendingPolicy()
        assertEquals(0.0, policy.defaultAutoBudgetUsd, 0.0)
        assertFalse(policy.allowAutomaticPaidCalls)
        assertFalse(policy.allowAutomaticCreditPurchase)
        assertFalse(policy.allowAutomaticBillingActivation)

        val videoRequest = TaskRoutingRequest(
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            taskTitle = "Analyze video trace",
            prompt = "Inspect screencast",
            complexity = TaskComplexity.COMPLEX_REASONING,
            requiresCoding = false,
            requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
            dataClassification = DataClassification.PROJECT_CODE,
            explicitPaidApprovalId = null,
        )

        // Default-constructed router with no grant resolver must reject any approval ID (fail-closed)
        val defaultDeniedOutcome = defaultFailClosedRouter.routeAndExecute(
            videoRequest.copy(explicitPaidApprovalId = "arbitrary-non-blank-id")
        )
        assertTrue(defaultDeniedOutcome is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

        val validGrant = PaidApprovalGrant(
            approvalId = "approved-id-1",
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            targetProviderId = "server-proxy-paid-frontier",
            targetModelId = "paid-frontier/reasoning-omni-v1",
            complexity = TaskComplexity.COMPLEX_REASONING,
            requiresCoding = false,
            requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
            dataClassification = DataClassification.PROJECT_CODE,
            explicitSensitiveDataAuthorized = false,
            maxApprovedCostUsd = 0.01,
        )
        val router = createRouter(
            approvalGrantResolver = { id -> if (id == validGrant.approvalId) validGrant else null }
        )

        // Video modality only exists on PaidFrontierCloudAdapter
        val noApprovalOutcome = router.routeAndExecute(videoRequest)
        assertTrue(noApprovalOutcome is AiRouteOutcome.PaidApprovalRequired)

        // Blank or invalid approval ID must block paid execution
        val blankOutcome = router.routeAndExecute(videoRequest.copy(explicitPaidApprovalId = "   "))
        assertTrue(blankOutcome is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

        val invalidIdOutcome = router.routeAndExecute(videoRequest.copy(explicitPaidApprovalId = "forged-unapproved-id"))
        assertTrue(invalidIdOutcome is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

        // Matching explicit approval grant allows paid adapter execution
        val approvedOutcome = router.routeAndExecute(videoRequest.copy(explicitPaidApprovalId = "approved-id-1"))
        assertTrue(approvedOutcome is AiRouteOutcome.Success)
        assertEquals(UsageCostCategory.PAID_API, (approvedOutcome as AiRouteOutcome.Success).usageCategory)
    }

    @Test
    fun `central router rejects tampered task parameters provider model and cost ceilings`() = runTest {
        val baseGrant = PaidApprovalGrant(
            approvalId = "approved-grant-1",
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            targetProviderId = "server-proxy-paid-frontier",
            targetModelId = "paid-frontier/reasoning-omni-v1",
            complexity = TaskComplexity.COMPLEX_REASONING,
            requiresCoding = false,
            requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
            dataClassification = DataClassification.PROJECT_CODE,
            explicitSensitiveDataAuthorized = false,
            maxApprovedCostUsd = 0.008,
        )
        val router = createRouter(
            approvalGrantResolver = { id -> if (id == baseGrant.approvalId) baseGrant else null }
        )
        val baseRequest = TaskRoutingRequest(
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            taskTitle = "Analyze video trace",
            prompt = "Inspect screencast",
            complexity = TaskComplexity.COMPLEX_REASONING,
            requiresCoding = false,
            requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
            dataClassification = DataClassification.PROJECT_CODE,
            explicitSensitiveDataAuthorized = false,
            explicitPaidApprovalId = baseGrant.approvalId,
        )

        // Tampered persona, complexity, coding flag, modality, classification, or sensitive flag
        val tamperedRequests = listOf(
            baseRequest.copy(persona = AfrinaPersona.BUILDER_ENGINEER),
            baseRequest.copy(complexity = TaskComplexity.STANDARD),
            baseRequest.copy(requiresCoding = true),
            baseRequest.copy(requiredModalities = setOf(AiModality.TEXT, AiModality.IMAGE, AiModality.VIDEO)),
            baseRequest.copy(dataClassification = DataClassification.PUBLIC_OR_SYNTHETIC),
            baseRequest.copy(explicitSensitiveDataAuthorized = true),
        )
        for (tampered in tamperedRequests) {
            val outcome = router.routeAndExecute(tampered)
            assertTrue("Expected tampered request to be blocked", outcome is AiRouteOutcome.PausedQuotaOrProviderUnavailable)
        }

        // Pre-call cost ceiling check: grant ceiling below adapter estimated cost must block
        val lowCeilingRouter = createRouter(
            approvalGrantResolver = { baseGrant.copy(maxApprovedCostUsd = 0.001) }
        )
        assertTrue(lowCeilingRouter.routeAndExecute(baseRequest) is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

        // Post-call cost ceiling check: adapter consumes more tokens than approved ceiling
        val highUsagePaidAdapter = object : AiProviderAdapter {
            override val providerId = "server-proxy-paid-frontier"
            override val displayName = "High Usage Paid"
            override val tier = ProviderTier.PAID_CLOUD
            override val retentionPolicy = ProviderRetentionPolicy("Paid", "پولی", false, false)
            override val models = listOf(
                AiModelDescriptor(
                    modelId = "paid-frontier/reasoning-omni-v1",
                    providerId = providerId,
                    displayName = "Paid Omni",
                    tier = tier,
                    maxComplexity = TaskComplexity.COMPLEX_REASONING,
                    supportsCoding = true,
                    supportsMultilingualFaEn = true,
                    supportedModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
                    avgLatencyMs = 500,
                    costPer1kInputUsd = 0.005,
                    costPer1kOutputUsd = 0.015,
                )
            )
            override fun quotaStatus() = ProviderQuotaStatus(providerId, displayName, tier, true, 0, 0, retentionPolicy, "", "")
            override fun setSimulatedQuotaRemaining(remaining: Int) = Unit
            override fun setSimulatedHealthy(healthy: Boolean) = Unit
            override suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest) =
                AdapterCallResult.Success("Out", "خروجی", inputTokens = 5000, outputTokens = 5000, isSimulatedExecution = true)
        }
        val postCallExceededRouter = createRouter(
            adapters = listOf(highUsagePaidAdapter),
            approvalGrantResolver = { baseGrant },
        )
        val postCallOutcome = postCallExceededRouter.routeAndExecute(baseRequest)
        assertTrue(postCallOutcome is AiRouteOutcome.PausedQuotaOrProviderUnavailable)
    }

    @Test
    fun `central router enforces OwnerSpendingPolicy automatic spend flags and cap`() = runTest {
        val videoRequest = TaskRoutingRequest(
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            taskTitle = "Analyze video trace",
            prompt = "Inspect",
            complexity = TaskComplexity.COMPLEX_REASONING,
            requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
            dataClassification = DataClassification.PROJECT_CODE,
        )

        // Even with non-zero cap, allowAutomaticPaidCalls = false must require approval
        val disabledAutoRouter = createRouter(
            policy = OwnerSpendingPolicy(
                defaultAutoBudgetUsd = 1.0,
                currentAutoSpendLimitUsd = 1.0,
                allowAutomaticPaidCalls = false,
            )
        )
        assertTrue(disabledAutoRouter.routeAndExecute(videoRequest) is AiRouteOutcome.PaidApprovalRequired)

        // With allowAutomaticPaidCalls = true, cost exceeding cap (0.0075 > 0.005) must still require approval
        val lowCapRouter = createRouter(
            policy = OwnerSpendingPolicy(
                defaultAutoBudgetUsd = 0.005,
                currentAutoSpendLimitUsd = 0.005,
                allowAutomaticPaidCalls = true,
            )
        )
        assertTrue(lowCapRouter.routeAndExecute(videoRequest) is AiRouteOutcome.PaidApprovalRequired)
    }

    @Test
    fun `sensitive owner and digital twin data is blocked from external cloud without authorization`() = runTest {
        val router = createRouter()

        // Without local open-weight allowed, external free and paid providers must BOTH be blocked
        val blockedOutcome = router.routeAndExecute(
            TaskRoutingRequest(
                persona = AfrinaPersona.COMPANION_EMPATHY,
                taskTitle = "Personal twin journal analysis",
                prompt = "My secret passport number is XYZ",
                complexity = TaskComplexity.STANDARD,
                dataClassification = DataClassification.DIGITAL_TWIN_SENSITIVE,
                explicitSensitiveDataAuthorized = false,
                allowLocalOpenWeight = false,
            )
        )
        assertTrue(blockedOutcome is AiRouteOutcome.PrivacyAuthorizationRequired)
        val privacyError = blockedOutcome as AiRouteOutcome.PrivacyAuthorizationRequired
        assertTrue(privacyError.blockedProviders.contains("server-proxy-free-primary"))
        assertTrue(privacyError.blockedProviders.contains("server-proxy-paid-frontier"))

        // With self-hosted VPS allowed, routes ONLY to self-hosted open-weight, never external cloud
        val localOutcome = router.routeAndExecute(
            TaskRoutingRequest(
                persona = AfrinaPersona.GUARDIAN_SENTINEL,
                taskTitle = "Private vault audit",
                prompt = "Check private policy",
                complexity = TaskComplexity.STANDARD,
                dataClassification = DataClassification.OWNER_PRIVATE,
                explicitSensitiveDataAuthorized = false,
                allowLocalOpenWeight = true,
            )
        )
        assertTrue(localOutcome is AiRouteOutcome.Success)
        val localSuccess = localOutcome as AiRouteOutcome.Success
        assertEquals(ProviderTier.LOCAL_OPEN_WEIGHT, localSuccess.tier)
        assertEquals(UsageCostCategory.SELF_HOSTED_INFRA, localSuccess.usageCategory)
        assertEquals(0.0, localSuccess.estimatedApiCostUsd, 0.0)
    }

    @Test
    fun `persona dispatcher distinguishes simulated vs real execution via interchangeable adapters`() = runTest {
        val realAdapter = object : AiProviderAdapter {
            override val providerId = "custom-real-proxy"
            override val displayName = "Custom Real Proxy"
            override val tier = ProviderTier.FREE_CLOUD
            override val retentionPolicy = ProviderRetentionPolicy(
                summaryEn = "Zero retention",
                summaryFa = "بدون نگهداری",
                trainsOnInput = false,
                allowsSensitiveDataByDefault = false,
            )
            override val models = listOf(
                AiModelDescriptor(
                    modelId = "real/model-v1",
                    providerId = providerId,
                    displayName = "Real Model",
                    tier = ProviderTier.FREE_CLOUD,
                    maxComplexity = TaskComplexity.STANDARD,
                    supportsCoding = true,
                    supportsMultilingualFaEn = true,
                    supportedModalities = setOf(AiModality.TEXT),
                    avgLatencyMs = 150,
                    costPer1kInputUsd = 0.0,
                    costPer1kOutputUsd = 0.0,
                )
            )
            override fun quotaStatus() = ProviderQuotaStatus(
                providerId = providerId,
                displayName = displayName,
                tier = tier,
                isAvailable = true,
                remainingFreeRequests = 5,
                dailyFreeLimit = 5,
                retentionPolicy = retentionPolicy,
                noteEn = "Real proxy",
                noteFa = "پروکسی واقعی",
                isSimulatedQuota = false,
            )
            override fun setSimulatedQuotaRemaining(remaining: Int) = Unit
            override fun setSimulatedHealthy(healthy: Boolean) = Unit
            override suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest) =
                AdapterCallResult.Success(
                    outputEn = "Real backend output",
                    outputFa = "خروجی واقعی سرور",
                    inputTokens = 50,
                    outputTokens = 80,
                    isSimulatedExecution = false,
                )
        }

        val dispatcher = PersonaDispatcher(CentralAiProviderRouter(adapters = listOf(realAdapter)))
        val outcome = dispatcher.runPersonaTask(
            persona = AfrinaPersona.BUILDER_ENGINEER,
            taskTitle = "Adapter swap test",
            prompt = "Hello",
            complexity = TaskComplexity.SIMPLE,
            requiresCoding = true,
            dataClassification = DataClassification.PROJECT_CODE,
        )
        assertTrue(outcome is AiRouteOutcome.Success)
        assertFalse((outcome as AiRouteOutcome.Success).isSimulatedExecution)
    }

    @Test
    fun `agents viewModel separates UI paid approval from execution and propagates usage ledger`() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val approvalsFlow = MutableStateFlow<List<PaidInferenceProposal>>(emptyList())
                val router = createRouter(
                    approvalGrantResolver = { id ->
                        approvalsFlow.value
                            .firstOrNull { it.id == id && it.status == ApprovalStatus.APPROVED && !it.isExecuted }
                            ?.let { proposal ->
                                PaidApprovalGrant(
                                    approvalId = proposal.id,
                                    persona = proposal.persona,
                                    targetProviderId = proposal.targetProviderId,
                                    targetModelId = proposal.targetModelId,
                                    complexity = proposal.complexity,
                                    requiresCoding = proposal.requiresCoding,
                                    requiredModalities = proposal.requiredModalities,
                                    dataClassification = proposal.dataClassification,
                                    explicitSensitiveDataAuthorized = proposal.explicitSensitiveDataAuthorized,
                                    maxApprovedCostUsd = proposal.estimatedCostUsd,
                                )
                            }
                    }
                )
                val dispatcher = PersonaDispatcher(router)
                val ledgerFlow = MutableStateFlow<List<AiUsageLedgerEntry>>(emptyList())
                val statusesFlow = MutableStateFlow(router.providerStatuses())
                val fallbacksFlow = MutableStateFlow<List<FallbackEvent>>(emptyList())
                val outcomeFlow = MutableStateFlow<AiRouteOutcome?>(null)

                val fakeRouterRepo = object : AiRouterRepository {
                    override fun observeProviderStatuses(): Flow<List<ProviderQuotaStatus>> = statusesFlow
                    override fun observeSpendingPolicy(): Flow<OwnerSpendingPolicy> = MutableStateFlow(router.spendingPolicy())
                    override fun observeUsageLedger(): Flow<List<AiUsageLedgerEntry>> = ledgerFlow
                    override fun observeCostSummary(): Flow<CostTransparencySummary> =
                        MutableStateFlow(
                            CostTransparencySummary(
                                totalFreeRequests = ledgerFlow.value.count { it.usageCategory == UsageCostCategory.FREE_TIER },
                                totalPaidRequests = ledgerFlow.value.count { it.usageCategory == UsageCostCategory.PAID_API },
                                ownerAutoBudgetUsd = 0.0,
                            )
                        )
                    override fun observePaidApprovals(): Flow<List<PaidInferenceProposal>> = approvalsFlow
                    override fun observeFallbackEvents(): Flow<List<FallbackEvent>> = fallbacksFlow
                    override fun observeLatestOutcome(): Flow<AiRouteOutcome?> = outcomeFlow

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
                        )
                        outcomeFlow.value = res
                        statusesFlow.value = router.providerStatuses()
                        when (res) {
                            is AiRouteOutcome.PaidApprovalRequired -> {
                                approvalsFlow.value = listOf(res.proposal) + approvalsFlow.value
                            }
                            is AiRouteOutcome.Success -> {
                                ledgerFlow.value = listOf(
                                    AiUsageLedgerEntry(
                                        id = "ldg-${ledgerFlow.value.size + 1}",
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
                                ) + ledgerFlow.value
                            }
                            else -> Unit
                        }
                        return res
                    }

                    override suspend fun approvePaidInference(proposalId: String) {
                        // Must ONLY record approval, NOT execute!
                        approvalsFlow.value = approvalsFlow.value.map {
                            if (it.id == proposalId) it.copy(status = ApprovalStatus.APPROVED) else it
                        }
                    }

                    override suspend fun rejectPaidInference(proposalId: String) {
                        approvalsFlow.value = approvalsFlow.value.map {
                            if (it.id == proposalId) it.copy(status = ApprovalStatus.REJECTED) else it
                        }
                    }

                    override suspend fun executeApprovedPaidTask(proposalId: String): AiRouteOutcome {
                        val item = approvalsFlow.value.firstOrNull { it.id == proposalId }
                        if (item == null || item.status != ApprovalStatus.APPROVED || item.isExecuted) {
                            return AiRouteOutcome.PausedQuotaOrProviderUnavailable("Blocked", "مسدود", emptyList())
                        }
                        val outcome = executePersonaTask(
                            TaskRoutingRequest(
                                persona = item.persona,
                                taskTitle = item.taskTitle,
                                prompt = item.taskTitle,
                                complexity = item.complexity,
                                requiresCoding = item.requiresCoding,
                                requiredModalities = item.requiredModalities,
                                dataClassification = item.dataClassification,
                                explicitSensitiveDataAuthorized = item.explicitSensitiveDataAuthorized,
                                explicitPaidApprovalId = proposalId,
                            )
                        )
                        if (outcome is AiRouteOutcome.Success) {
                            approvalsFlow.value = approvalsFlow.value.map {
                                if (it.id == proposalId) it.copy(isExecuted = true) else it
                            }
                        }
                        return outcome
                    }

                    override suspend fun simulateExhaustPrimaryQuota() = Unit
                    override suspend fun resetSimulatedQuotas() = Unit
                }

                val fakeAgentRepo = object : AgentRepository {
                    override fun observeAgentCatalog(): Flow<List<AgentDefinition>> = MutableStateFlow(emptyList())
                    override fun observeActivities(): Flow<List<AgentActivity>> = MutableStateFlow(emptyList())
                    override fun observeApprovals(): Flow<List<ApprovalRequest>> = MutableStateFlow(emptyList())
                    override suspend fun resolveApproval(approvalId: String, approved: Boolean) = Unit
                    override suspend fun triggerSimulatedAgentPlan(projectId: String, role: AgentRole, promptSummary: String) = Unit
                }

                val personaRepo = CentralPersonaOrchestrator(aiRouterRepository = fakeRouterRepo)
                val vm = AgentsViewModel(fakeAgentRepo, fakeRouterRepo, personaRepo)
                vm.toggleVideoModality(true)
                vm.runSimulatedRouterTask()
                advanceUntilIdle()

                // Pending approval created; 0 ledger entries yet
                val proposalId = approvalsFlow.value.first().id
                assertEquals(0, ledgerFlow.value.size)

                // UI approval records approval ONLY without executing
                vm.approvePaidInference(proposalId)
                advanceUntilIdle()
                assertEquals(ApprovalStatus.APPROVED, approvalsFlow.value.first().status)
                assertFalse(approvalsFlow.value.first().isExecuted)
                assertEquals(0, ledgerFlow.value.size)

                // Explicit separate execution runs the approved simulated call
                vm.executeApprovedPaidTask(proposalId)
                advanceUntilIdle()
                val state = vm.uiState.first { it.usageLedger.isNotEmpty() }
                assertTrue(state.paidApprovals.first().isExecuted)
                assertEquals(1, state.usageLedger.size)
                assertEquals(UsageCostCategory.PAID_API, state.usageLedger.first().usageCategory)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
