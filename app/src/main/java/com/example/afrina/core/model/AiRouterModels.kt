package com.example.afrina.core.model

enum class AfrinaPersona(
    val titleEn: String,
    val titleFa: String,
    val focusEn: String,
    val focusFa: String,
) {
    ARCHITECT_STRATEGIST(
        titleEn = "Architect Persona",
        titleFa = "شخصیت معمار",
        focusEn = "System architecture, API contracts, long-horizon planning",
        focusFa = "معماری سیستم، قراردادهای API و برنامه‌ریزی کلان",
    ),
    BUILDER_ENGINEER(
        titleEn = "Engineer Persona",
        titleFa = "شخصیت مهندس",
        focusEn = "Code generation, debugging, automated tests",
        focusFa = "تولید کد، عیب‌یابی و تست خودکار",
    ),
    COMPANION_EMPATHY(
        titleEn = "Companion Persona",
        titleFa = "شخصیت همراه",
        focusEn = "Owner daily assistant, Persian/English dialogue, twin context",
        focusFa = "دستیار روزانه مالک، گفت‌وگوی دوزبانه و زمینه شخصی",
    ),
    GUARDIAN_SENTINEL(
        titleEn = "Guardian Persona",
        titleFa = "شخصیت نگهبان",
        focusEn = "Security, privacy classification, $0 budget enforcement",
        focusFa = "امنیت، طبقه‌بندی حریم خصوصی و حفاظت از بودجه ۰ دلاری",
    ),
}

enum class ProviderTier {
    FREE_CLOUD,
    LOCAL_OPEN_WEIGHT,
    PAID_CLOUD;

    val isExternalCloud: Boolean
        get() = this == FREE_CLOUD || this == PAID_CLOUD
}

enum class AiModality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
}

enum class TaskComplexity {
    SIMPLE,
    STANDARD,
    COMPLEX_REASONING,
}

enum class DataClassification(val isSensitiveOwnerData: Boolean) {
    PUBLIC_OR_SYNTHETIC(false),
    PROJECT_CODE(false),
    OWNER_PRIVATE(true),
    DIGITAL_TWIN_SENSITIVE(true),
}

enum class UsageCostCategory {
    FREE_TIER,
    SELF_HOSTED_INFRA,
    PAID_API,
}

data class ProviderRetentionPolicy(
    val summaryEn: String,
    val summaryFa: String,
    val trainsOnInput: Boolean,
    val allowsSensitiveDataByDefault: Boolean,
)

data class AiModelDescriptor(
    val modelId: String,
    val providerId: String,
    val displayName: String,
    val tier: ProviderTier,
    val maxComplexity: TaskComplexity,
    val supportsCoding: Boolean,
    val supportsMultilingualFaEn: Boolean,
    val supportedModalities: Set<AiModality>,
    val avgLatencyMs: Int,
    val costPer1kInputUsd: Double,
    val costPer1kOutputUsd: Double,
    val estimatedInfraCostPerCallUsd: Double = 0.0,
    val requiresGpuWorker: Boolean = false,
)

data class ProviderQuotaStatus(
    val providerId: String,
    val displayName: String,
    val tier: ProviderTier,
    val isAvailable: Boolean,
    val remainingFreeRequests: Int,
    val dailyFreeLimit: Int,
    val retentionPolicy: ProviderRetentionPolicy,
    val noteEn: String,
    val noteFa: String,
    val isSimulatedQuota: Boolean = true,
)

data class OwnerSpendingPolicy(
    val defaultAutoBudgetUsd: Double = 0.0,
    val currentAutoSpendLimitUsd: Double = 0.0,
    val allowAutomaticPaidCalls: Boolean = false,
    val allowAutomaticCreditPurchase: Boolean = false,
    val allowAutomaticBillingActivation: Boolean = false,
)

data class TaskRoutingRequest(
    val persona: AfrinaPersona,
    val taskTitle: String,
    val prompt: String,
    val complexity: TaskComplexity,
    val requiresCoding: Boolean = false,
    val requiresMultilingualFaEn: Boolean = true,
    val requiredModalities: Set<AiModality> = setOf(AiModality.TEXT),
    val dataClassification: DataClassification = DataClassification.PROJECT_CODE,
    val explicitSensitiveDataAuthorized: Boolean = false,
    val explicitPaidApprovalId: String? = null,
    val allowLocalOpenWeight: Boolean = true,
)

data class PaidInferenceProposal(
    val id: String,
    val persona: AfrinaPersona,
    val taskTitle: String,
    val complexity: TaskComplexity = TaskComplexity.STANDARD,
    val requiresCoding: Boolean = false,
    val requiredModalities: Set<AiModality> = setOf(AiModality.TEXT),
    val dataClassification: DataClassification = DataClassification.PROJECT_CODE,
    val explicitSensitiveDataAuthorized: Boolean = false,
    val targetProviderId: String,
    val targetModelId: String,
    val estimatedCostUsd: Double,
    val reasonEn: String,
    val reasonFa: String,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val isExecuted: Boolean = false,
    val isSimulated: Boolean = true,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
)

data class PaidApprovalGrant(
    val approvalId: String,
    val persona: AfrinaPersona,
    val targetProviderId: String,
    val targetModelId: String,
    val complexity: TaskComplexity,
    val requiresCoding: Boolean,
    val requiredModalities: Set<AiModality>,
    val dataClassification: DataClassification,
    val explicitSensitiveDataAuthorized: Boolean,
    val maxApprovedCostUsd: Double,
)

data class FallbackEvent(
    val id: String,
    val persona: AfrinaPersona,
    val fromProviderId: String,
    val toProviderId: String,
    val reason: String,
    val isSimulated: Boolean = true,
    val timestampEpochMs: Long,
)

sealed interface AiRouteOutcome {
    data class Success(
        val persona: AfrinaPersona,
        val providerId: String,
        val modelId: String,
        val tier: ProviderTier,
        val usageCategory: UsageCostCategory,
        val dataClassification: DataClassification = DataClassification.PROJECT_CODE,
        val outputEn: String,
        val outputFa: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val estimatedApiCostUsd: Double,
        val estimatedInfraCostUsd: Double,
        val fallbackTriggered: Boolean,
        val fallbackFromProviderId: String? = null,
        val fallbackReason: String? = null,
        val isSimulatedExecution: Boolean = true,
    ) : AiRouteOutcome

    data class PaidApprovalRequired(
        val proposal: PaidInferenceProposal,
    ) : AiRouteOutcome

    data class PrivacyAuthorizationRequired(
        val dataClassification: DataClassification,
        val blockedProviders: List<String>,
        val messageEn: String,
        val messageFa: String,
    ) : AiRouteOutcome

    data class PausedQuotaOrProviderUnavailable(
        val messageEn: String,
        val messageFa: String,
        val attemptedProviders: List<String>,
    ) : AiRouteOutcome
}

data class AiUsageLedgerEntry(
    val id: String,
    val persona: AfrinaPersona,
    val providerId: String,
    val modelId: String,
    val taskTitle: String,
    val usageCategory: UsageCostCategory,
    val dataClassification: DataClassification = DataClassification.PROJECT_CODE,
    val routingKind: String = "EXTERNAL_CLOUD",
    val fallbackTriggered: Boolean = false,
    val fallbackReason: String? = null,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedApiCostUsd: Double,
    val estimatedInfraCostUsd: Double,
    val isSimulated: Boolean,
    val timestampEpochMs: Long,
)

data class CostTransparencySummary(
    val totalFreeRequests: Int = 0,
    val totalSelfHostedRequests: Int = 0,
    val totalPaidRequests: Int = 0,
    val totalFreeTokens: Int = 0,
    val totalPaidApiSpendUsd: Double = 0.0,
    val totalSelfHostedInfraUsd: Double = 0.0,
    val ownerAutoBudgetUsd: Double = 0.0,
)
