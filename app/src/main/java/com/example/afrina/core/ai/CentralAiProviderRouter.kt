package com.example.afrina.core.ai

import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModelDescriptor
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidApprovalGrant
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.ProviderTier
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.model.UsageCostCategory
import java.util.UUID

/**
 * Shared AI client interface consumed by all four AFRINA personas.
 * Personas depend solely on this interface—never on individual provider adapters.
 */
interface PersonaAiRouter {
    fun spendingPolicy(): OwnerSpendingPolicy
    fun providerStatuses(): List<ProviderQuotaStatus>
    suspend fun routeAndExecute(request: TaskRoutingRequest): AiRouteOutcome
}

/**
 * Centralized Free-First AI Provider Router enforcing:
 * 1. Free-tier priority & automatic fallback across eligible free/open-weight providers.
 * 2. Owner Mode $0.00 default API budget (no automatic paid calls, credits, or billing).
 * 3. Intelligent task-complexity, coding, multilingual, modality, latency, and privacy routing.
 * 4. Explicit privacy gate for Digital Twin & owner-private data before external transmission.
 * 5. Fail-closed PaidApprovalGrant verification matching persona, provider, model, complexity,
 *    coding flag, modalities, data classification, sensitive-data authorization, and cost ceiling.
 */
class CentralAiProviderRouter(
    private val adapters: List<AiProviderAdapter>,
    private val gpuWorkerAvailable: Boolean = false,
    private val policy: OwnerSpendingPolicy = OwnerSpendingPolicy(),
    private val approvalGrantResolver: suspend (String) -> PaidApprovalGrant? = { null },
) : PersonaAiRouter {

    override fun spendingPolicy(): OwnerSpendingPolicy = policy

    override fun providerStatuses(): List<ProviderQuotaStatus> =
        adapters.map { it.quotaStatus() }

    fun setSimulatedQuota(providerId: String, remaining: Int) {
        adapters.firstOrNull { it.providerId == providerId }?.setSimulatedQuotaRemaining(remaining)
    }

    fun setSimulatedHealthy(providerId: String, healthy: Boolean) {
        adapters.firstOrNull { it.providerId == providerId }?.setSimulatedHealthy(healthy)
    }

    override suspend fun routeAndExecute(request: TaskRoutingRequest): AiRouteOutcome {
        // 1. Privacy & Data Classification Check
        // OWNER_PRIVATE and DIGITAL_TWIN_SENSITIVE cannot be transmitted to external cloud providers
        // without explicit owner authorization. Self-hosted local/VPS open-weight routing stays isolated.
        val isSensitive = request.dataClassification.isSensitiveOwnerData
        val privacyEligibleAdapters = adapters.filter { adapter ->
            if (!isSensitive) {
                true
            } else if (!adapter.tier.isExternalCloud && adapter.retentionPolicy.allowsSensitiveDataByDefault) {
                true
            } else {
                request.explicitSensitiveDataAuthorized
            }
        }

        // 2. Collect candidate models matching technical capability requirements
        val capableFreeCandidates = collectCapableModels(
            adaptersToSearch = privacyEligibleAdapters.filter {
                it.tier == ProviderTier.FREE_CLOUD ||
                    (request.allowLocalOpenWeight && it.tier == ProviderTier.LOCAL_OPEN_WEIGHT)
            },
            request = request,
        )

        // Prioritize FREE_CLOUD (lightest capable model first for simple tasks) then LOCAL_OPEN_WEIGHT
        val orderedFreeCandidates = sortCandidatesIntelligently(capableFreeCandidates, request)

        val attemptedProviders = mutableListOf<String>()
        var fallbackReason: String? = null

        for ((adapter, model) in orderedFreeCandidates) {
            val status = adapter.quotaStatus()
            if (!status.isAvailable) {
                attemptedProviders += adapter.providerId
                fallbackReason = if (status.remainingFreeRequests <= 0) {
                    "${adapter.displayName} simulated quota exhausted"
                } else {
                    "${adapter.displayName} unavailable"
                }
                continue
            }

            when (val result = adapter.invoke(model, request)) {
                is AdapterCallResult.Success -> {
                    val category = if (adapter.tier == ProviderTier.LOCAL_OPEN_WEIGHT) {
                        UsageCostCategory.SELF_HOSTED_INFRA
                    } else {
                        UsageCostCategory.FREE_TIER
                    }
                    return AiRouteOutcome.Success(
                        persona = request.persona,
                        providerId = adapter.providerId,
                        modelId = model.modelId,
                        tier = adapter.tier,
                        usageCategory = category,
                        dataClassification = request.dataClassification,
                        outputEn = result.outputEn,
                        outputFa = result.outputFa,
                        inputTokens = result.inputTokens,
                        outputTokens = result.outputTokens,
                        estimatedApiCostUsd = 0.0,
                        estimatedInfraCostUsd = model.estimatedInfraCostPerCallUsd,
                        fallbackTriggered = attemptedProviders.isNotEmpty(),
                        fallbackFromProviderId = attemptedProviders.firstOrNull(),
                        fallbackReason = fallbackReason,
                        isSimulatedExecution = result.isSimulatedExecution,
                    )
                }

                is AdapterCallResult.QuotaExhausted -> {
                    attemptedProviders += adapter.providerId
                    fallbackReason = result.reason
                }

                is AdapterCallResult.Unavailable -> {
                    attemptedProviders += adapter.providerId
                    fallbackReason = result.reason
                }
            }
        }

        // If sensitive data blocked external cloud providers and self-hosted couldn't fulfill it, reject on privacy
        if (isSensitive && !request.explicitSensitiveDataAuthorized) {
            val blockedExternal = adapters
                .filter { it.tier.isExternalCloud }
                .map { it.providerId }
            if (blockedExternal.isNotEmpty()) {
                return AiRouteOutcome.PrivacyAuthorizationRequired(
                    dataClassification = request.dataClassification,
                    blockedProviders = blockedExternal,
                    messageEn = "Sensitive ${request.dataClassification.name} data blocked from external cloud providers. Authorize external transmission explicitly or route via capable self-hosted VPS worker.",
                    messageFa = "داده حساس (${request.dataClassification.name}) از ارسال به سرویس‌دهندگان ابری خارجی مسدود شد. بدون مجوز صریح مالک ارسال نمی‌شود.",
                )
            }
        }

        // 3. No eligible free/self-hosted model succeeded -> Enforce Spending Policy & Paid Approval Grant
        val paidCandidates = sortCandidatesIntelligently(
            collectCapableModels(
                adaptersToSearch = privacyEligibleAdapters.filter { it.tier == ProviderTier.PAID_CLOUD },
                request = request,
            ),
            request,
        )

        val bestPaid = paidCandidates.firstOrNull()
        if (bestPaid != null) {
            val (paidAdapter, paidModel) = bestPaid
            val estimatedUsd = estimateCostUsd(paidModel, inputTokens = 300, outputTokens = 400)

            val approvalId = request.explicitPaidApprovalId
            if (approvalId != null) {
                if (approvalId.isBlank()) {
                    return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                        messageEn = "Paid execution blocked: blank approval ID under $0.00 spending policy.",
                        messageFa = "اجرای پولی مسدود شد: شناسه تایید خالی است و بودجه خودکار ۰.۰۰ دلار می‌باشد.",
                        attemptedProviders = attemptedProviders + paidAdapter.providerId,
                    )
                }

                val grant = approvalGrantResolver(approvalId)
                if (grant == null) {
                    return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                        messageEn = "Paid execution blocked: approval ID '$approvalId' is missing, unapproved, or already consumed.",
                        messageFa = "اجرای پولی مسدود شد: شناسه تایید یافت نشد، تایید نشده یا قبلاً مصرف شده است.",
                        attemptedProviders = attemptedProviders + paidAdapter.providerId,
                    )
                }

                if (!matchesGrant(grant, approvalId, request, paidAdapter.providerId, paidModel.modelId, estimatedUsd)) {
                    return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                        messageEn = "Paid execution blocked: request parameters or estimated cost ($estimatedUsd USD) do not match approved proposal '$approvalId' (ceiling ${grant.maxApprovedCostUsd} USD).",
                        messageFa = "اجرای پولی مسدود شد: پارامترهای درخواست یا سقف هزینه با تایید ثبت‌شده مطابقت ندارد.",
                        attemptedProviders = attemptedProviders + paidAdapter.providerId,
                    )
                }

                when (val paidResult = paidAdapter.invoke(paidModel, request)) {
                    is AdapterCallResult.Success -> {
                        val realCost = estimateCostUsd(paidModel, paidResult.inputTokens, paidResult.outputTokens)
                        if (realCost > grant.maxApprovedCostUsd + COST_EPSILON) {
                            return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                                messageEn = "Paid execution blocked: actual cost ($realCost USD) exceeded approved ceiling (${grant.maxApprovedCostUsd} USD).",
                                messageFa = "اجرای پولی مسدود شد: هزینه واقعی از سقف تاییدشده فراتر رفت.",
                                attemptedProviders = attemptedProviders + paidAdapter.providerId,
                            )
                        }
                        return AiRouteOutcome.Success(
                            persona = request.persona,
                            providerId = paidAdapter.providerId,
                            modelId = paidModel.modelId,
                            tier = ProviderTier.PAID_CLOUD,
                            usageCategory = UsageCostCategory.PAID_API,
                            dataClassification = request.dataClassification,
                            outputEn = paidResult.outputEn,
                            outputFa = paidResult.outputFa,
                            inputTokens = paidResult.inputTokens,
                            outputTokens = paidResult.outputTokens,
                            estimatedApiCostUsd = realCost,
                            estimatedInfraCostUsd = 0.0,
                            fallbackTriggered = attemptedProviders.isNotEmpty(),
                            fallbackFromProviderId = attemptedProviders.firstOrNull(),
                            fallbackReason = fallbackReason,
                            isSimulatedExecution = paidResult.isSimulatedExecution,
                        )
                    }

                    is AdapterCallResult.QuotaExhausted, is AdapterCallResult.Unavailable -> {
                        return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                            messageEn = "Approved paid provider is currently unavailable; operation paused.",
                            messageFa = "ارائه‌دهنده پولی تاییدشده در دسترس نیست؛ عملیات متوقف شد.",
                            attemptedProviders = attemptedProviders + paidAdapter.providerId,
                        )
                    }
                }
            }

            // Enforce OwnerSpendingPolicy: automatic paid calls are blocked unless explicitly permitted
            // AND within a positive auto-spend ceiling; Owner Mode defaults to $0.00 and false.
            val autoAllowed = policy.allowAutomaticPaidCalls &&
                policy.defaultAutoBudgetUsd > 0.0 &&
                policy.currentAutoSpendLimitUsd > 0.0 &&
                estimatedUsd <= policy.currentAutoSpendLimitUsd + COST_EPSILON

            if (autoAllowed) {
                when (val autoResult = paidAdapter.invoke(paidModel, request)) {
                    is AdapterCallResult.Success -> {
                        val realCost = estimateCostUsd(paidModel, autoResult.inputTokens, autoResult.outputTokens)
                        if (realCost > policy.currentAutoSpendLimitUsd + COST_EPSILON) {
                            return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                                messageEn = "Auto-spend cap (${policy.currentAutoSpendLimitUsd} USD) exceeded; operation paused.",
                                messageFa = "سقف هزینه خودکار رد شد؛ عملیات متوقف گردید.",
                                attemptedProviders = attemptedProviders + paidAdapter.providerId,
                            )
                        }
                        return AiRouteOutcome.Success(
                            persona = request.persona,
                            providerId = paidAdapter.providerId,
                            modelId = paidModel.modelId,
                            tier = ProviderTier.PAID_CLOUD,
                            usageCategory = UsageCostCategory.PAID_API,
                            dataClassification = request.dataClassification,
                            outputEn = autoResult.outputEn,
                            outputFa = autoResult.outputFa,
                            inputTokens = autoResult.inputTokens,
                            outputTokens = autoResult.outputTokens,
                            estimatedApiCostUsd = realCost,
                            estimatedInfraCostUsd = 0.0,
                            fallbackTriggered = attemptedProviders.isNotEmpty(),
                            fallbackFromProviderId = attemptedProviders.firstOrNull(),
                            fallbackReason = fallbackReason,
                            isSimulatedExecution = autoResult.isSimulatedExecution,
                        )
                    }

                    is AdapterCallResult.QuotaExhausted, is AdapterCallResult.Unavailable -> {
                        return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                            messageEn = "Paid provider unavailable; operation paused.",
                            messageFa = "ارائه‌دهنده پولی در دسترس نیست؛ عملیات متوقف شد.",
                            attemptedProviders = attemptedProviders + paidAdapter.providerId,
                        )
                    }
                }
            }

            // Default Owner Mode ($0.00 auto budget): pause and request explicit owner approval
            return AiRouteOutcome.PaidApprovalRequired(
                proposal = PaidInferenceProposal(
                    id = "paid-${UUID.randomUUID().toString().take(8)}",
                    persona = request.persona,
                    taskTitle = request.taskTitle,
                    complexity = request.complexity,
                    requiresCoding = request.requiresCoding,
                    requiredModalities = request.requiredModalities,
                    dataClassification = request.dataClassification,
                    explicitSensitiveDataAuthorized = request.explicitSensitiveDataAuthorized,
                    targetProviderId = paidAdapter.providerId,
                    targetModelId = paidModel.modelId,
                    estimatedCostUsd = estimatedUsd,
                    reasonEn = "No eligible free or open-weight model available (${fallbackReason ?: "capability/quota requirement"}). Owner Mode auto-budget is $${policy.currentAutoSpendLimitUsd}; explicit approval required.",
                    reasonFa = "مدل رایگان یا متن‌باز واجد شرایط در دسترس نیست (${fallbackReason ?: "نیازمندی مدل/سهمیه"}). بودجه خودکار مالک ${policy.currentAutoSpendLimitUsd} دلار است؛ تایید صریح لازم است.",
                    isSimulated = true,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            )
        }

        return AiRouteOutcome.PausedQuotaOrProviderUnavailable(
            messageEn = "Simulated free quotas exhausted or providers unavailable; operation paused without spending ($0.00).",
            messageFa = "سهمیه‌های رایگان شبیه‌سازی‌شده تمام شده یا ارائه‌دهنده در دسترس نیست؛ عملیات بدون ایجاد هزینه (۰.۰۰ دلار) متوقف شد.",
            attemptedProviders = attemptedProviders,
        )
    }

    private fun matchesGrant(
        grant: PaidApprovalGrant,
        approvalId: String,
        request: TaskRoutingRequest,
        providerId: String,
        modelId: String,
        estimatedUsd: Double,
    ): Boolean =
        grant.approvalId == approvalId &&
            grant.persona == request.persona &&
            grant.targetProviderId == providerId &&
            grant.targetModelId == modelId &&
            grant.complexity == request.complexity &&
            grant.requiresCoding == request.requiresCoding &&
            grant.requiredModalities == request.requiredModalities &&
            grant.dataClassification == request.dataClassification &&
            grant.explicitSensitiveDataAuthorized == request.explicitSensitiveDataAuthorized &&
            grant.maxApprovedCostUsd >= 0.0 &&
            estimatedUsd <= grant.maxApprovedCostUsd + COST_EPSILON

    private companion object {
        const val COST_EPSILON = 1e-9
    }

    private fun collectCapableModels(
        adaptersToSearch: List<AiProviderAdapter>,
        request: TaskRoutingRequest,
    ): List<Pair<AiProviderAdapter, AiModelDescriptor>> =
        adaptersToSearch.flatMap { adapter ->
            adapter.models.filter { model ->
                model.maxComplexity.ordinal >= request.complexity.ordinal &&
                    (!request.requiresCoding || model.supportsCoding) &&
                    (!request.requiresMultilingualFaEn || model.supportsMultilingualFaEn) &&
                    request.requiredModalities.all { it in model.supportedModalities } &&
                    (!model.requiresGpuWorker || gpuWorkerAvailable)
            }.map { model -> adapter to model }
        }

    private fun sortCandidatesIntelligently(
        candidates: List<Pair<AiProviderAdapter, AiModelDescriptor>>,
        request: TaskRoutingRequest,
    ): List<Pair<AiProviderAdapter, AiModelDescriptor>> =
        candidates.sortedWith(
            compareBy<Pair<AiProviderAdapter, AiModelDescriptor>>(
                // 1. Free cloud first, then self-hosted open-weight, then paid cloud
                { (adapter, _) ->
                    when (adapter.tier) {
                        ProviderTier.FREE_CLOUD -> 0
                        ProviderTier.LOCAL_OPEN_WEIGHT -> 1
                        ProviderTier.PAID_CLOUD -> 2
                    }
                },
                // 2. For simple tasks, avoid burning scarce high-tier reasoning models when a lighter model suffices
                { (_, model) ->
                    if (request.complexity == TaskComplexity.SIMPLE) model.maxComplexity.ordinal else 0
                },
                // 3. Lower latency
                { (_, model) -> model.avgLatencyMs },
            )
        )

    private fun estimateCostUsd(model: AiModelDescriptor, inputTokens: Int, outputTokens: Int): Double =
        ((inputTokens / 1000.0) * model.costPer1kInputUsd) +
            ((outputTokens / 1000.0) * model.costPer1kOutputUsd)
}

/**
 * Persona-level service shared across all 4 AFRINA personas.
 * Demonstrates Acceptance Criterion #7: swapping providers does not touch persona logic.
 */
class PersonaDispatcher(private val router: PersonaAiRouter) {
    suspend fun runPersonaTask(
        persona: AfrinaPersona,
        taskTitle: String,
        prompt: String,
        complexity: TaskComplexity,
        requiresCoding: Boolean,
        requiredModalities: Set<com.example.afrina.core.model.AiModality> = setOf(com.example.afrina.core.model.AiModality.TEXT),
        dataClassification: com.example.afrina.core.model.DataClassification,
        explicitSensitiveDataAuthorized: Boolean = false,
        explicitPaidApprovalId: String? = null,
        allowLocalOpenWeight: Boolean = true,
    ): AiRouteOutcome =
        router.routeAndExecute(
            TaskRoutingRequest(
                persona = persona,
                taskTitle = taskTitle,
                prompt = prompt,
                complexity = complexity,
                requiresCoding = requiresCoding,
                requiresMultilingualFaEn = true,
                requiredModalities = requiredModalities,
                dataClassification = dataClassification,
                explicitSensitiveDataAuthorized = explicitSensitiveDataAuthorized,
                explicitPaidApprovalId = explicitPaidApprovalId,
                allowLocalOpenWeight = allowLocalOpenWeight,
            )
        )
}
