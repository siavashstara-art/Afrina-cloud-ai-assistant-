package com.example.afrina.core.ai

import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiModelDescriptor
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.ProviderRetentionPolicy
import com.example.afrina.core.model.ProviderTier
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import kotlinx.coroutines.delay

sealed interface AdapterCallResult {
    data class Success(
        val outputEn: String,
        val outputFa: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val isSimulatedExecution: Boolean,
    ) : AdapterCallResult

    data class QuotaExhausted(val reason: String) : AdapterCallResult
    data class Unavailable(val reason: String) : AdapterCallResult
}

/**
 * Interchangeable AI provider adapter contract.
 * Provider keys NEVER reside on Android; production adapters proxy through the owner's server.
 */
interface AiProviderAdapter {
    val providerId: String
    val displayName: String
    val tier: ProviderTier
    val retentionPolicy: ProviderRetentionPolicy
    val models: List<AiModelDescriptor>
    fun quotaStatus(): ProviderQuotaStatus
    fun setSimulatedQuotaRemaining(remaining: Int)
    fun setSimulatedHealthy(healthy: Boolean)
    suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest): AdapterCallResult
}

/**
 * Primary eligible free-tier cloud proxy adapter (server-proxied; Phase 0 simulated execution).
 * Respects finite free quotas (never claims unlimited free access).
 */
class FreeTierPrimaryProxyAdapter(
    initialQuota: Int = 25,
    private val dailyLimit: Int = 25,
) : AiProviderAdapter {
    override val providerId: String = "server-proxy-free-primary"
    override val displayName: String = "Server Proxy • Free Tier A"
    override val tier: ProviderTier = ProviderTier.FREE_CLOUD
    override val retentionPolicy = ProviderRetentionPolicy(
        summaryEn = "Free tier (limited RPM/daily quota; keys kept on server; may log prompts per vendor terms)",
        summaryFa = "لایه رایگان ابری (سهمیه محدود روزانه؛ کلید فقط روی سرور؛ مشمول شرایط نگهداری ارائه‌دهنده)",
        trainsOnInput = true,
        allowsSensitiveDataByDefault = false,
    )

    private var remainingQuota: Int = initialQuota
    private var healthy: Boolean = true

    override val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            modelId = "free-lite/fast-router-v1",
            providerId = providerId,
            displayName = "Free Lite Fast",
            tier = ProviderTier.FREE_CLOUD,
            maxComplexity = TaskComplexity.SIMPLE,
            supportsCoding = false,
            supportsMultilingualFaEn = true,
            supportedModalities = setOf(AiModality.TEXT),
            avgLatencyMs = 280,
            costPer1kInputUsd = 0.0,
            costPer1kOutputUsd = 0.0,
        ),
        AiModelDescriptor(
            modelId = "free-pro/multimodal-coder-v1",
            providerId = providerId,
            displayName = "Free Coder & Vision",
            tier = ProviderTier.FREE_CLOUD,
            maxComplexity = TaskComplexity.COMPLEX_REASONING,
            supportsCoding = true,
            supportsMultilingualFaEn = true,
            supportedModalities = setOf(AiModality.TEXT, AiModality.IMAGE, AiModality.AUDIO),
            avgLatencyMs = 620,
            costPer1kInputUsd = 0.0,
            costPer1kOutputUsd = 0.0,
        ),
    )

    override fun quotaStatus(): ProviderQuotaStatus = ProviderQuotaStatus(
        providerId = providerId,
        displayName = displayName,
        tier = tier,
        isAvailable = healthy && remainingQuota > 0,
        remainingFreeRequests = remainingQuota,
        dailyFreeLimit = dailyLimit,
        retentionPolicy = retentionPolicy,
        noteEn = "Server-proxied free quota ($remainingQuota/$dailyLimit remaining). Simulated in Phase 0.",
        noteFa = "سهمیه رایگان از طریق سرور ($remainingQuota از $dailyLimit باقی‌مانده). شبیه‌سازی در فاز ۰.",
    )

    override fun setSimulatedQuotaRemaining(remaining: Int) {
        remainingQuota = remaining.coerceAtLeast(0)
    }

    override fun setSimulatedHealthy(healthy: Boolean) {
        this.healthy = healthy
    }

    override suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest): AdapterCallResult {
        if (!healthy) return AdapterCallResult.Unavailable("Primary free proxy unavailable")
        if (remainingQuota <= 0) return AdapterCallResult.QuotaExhausted("Primary free daily quota exhausted")
        delay(120)
        remainingQuota -= 1
        return AdapterCallResult.Success(
            outputEn = "[Simulated Free-Tier A • ${model.modelId}] ${request.persona.titleEn} handled '${request.taskTitle}' at $0.00 API cost.",
            outputFa = "[شبیه‌سازی لایه رایگان A • ${model.modelId}] ${request.persona.titleFa} درخواست «${request.taskTitle}» را با هزینه ۰ دلار پردازش کرد.",
            inputTokens = 180,
            outputTokens = 260,
            isSimulatedExecution = true,
        )
    }
}

/**
 * Secondary eligible free-tier fallback adapter with independent quota pool.
 */
class FreeTierFallbackProxyAdapter(
    initialQuota: Int = 20,
    private val dailyLimit: Int = 20,
) : AiProviderAdapter {
    override val providerId: String = "server-proxy-free-fallback"
    override val displayName: String = "Server Proxy • Free Tier B (Fallback)"
    override val tier: ProviderTier = ProviderTier.FREE_CLOUD
    override val retentionPolicy = ProviderRetentionPolicy(
        summaryEn = "Secondary free tier (no-train API mode; finite quota; server-side credentials only)",
        summaryFa = "لایه رایگان پشتیبان (سهمیه محدود؛ کلید روی سرور؛ بدون آموزش روی داده)",
        trainsOnInput = false,
        allowsSensitiveDataByDefault = false,
    )

    private var remainingQuota: Int = initialQuota
    private var healthy: Boolean = true

    override val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            modelId = "free-fallback/coder-instruct-v2",
            providerId = providerId,
            displayName = "Fallback Free Coder",
            tier = ProviderTier.FREE_CLOUD,
            maxComplexity = TaskComplexity.COMPLEX_REASONING,
            supportsCoding = true,
            supportsMultilingualFaEn = true,
            supportedModalities = setOf(AiModality.TEXT, AiModality.IMAGE),
            avgLatencyMs = 740,
            costPer1kInputUsd = 0.0,
            costPer1kOutputUsd = 0.0,
        ),
    )

    override fun quotaStatus(): ProviderQuotaStatus = ProviderQuotaStatus(
        providerId = providerId,
        displayName = displayName,
        tier = tier,
        isAvailable = healthy && remainingQuota > 0,
        remainingFreeRequests = remainingQuota,
        dailyFreeLimit = dailyLimit,
        retentionPolicy = retentionPolicy,
        noteEn = "Fallback free quota ($remainingQuota/$dailyLimit remaining). Simulated in Phase 0.",
        noteFa = "سهمیه رایگان پشتیبان ($remainingQuota از $dailyLimit باقی‌مانده). شبیه‌سازی در فاز ۰.",
    )

    override fun setSimulatedQuotaRemaining(remaining: Int) {
        remainingQuota = remaining.coerceAtLeast(0)
    }

    override fun setSimulatedHealthy(healthy: Boolean) {
        this.healthy = healthy
    }

    override suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest): AdapterCallResult {
        if (!healthy) return AdapterCallResult.Unavailable("Fallback free proxy unavailable")
        if (remainingQuota <= 0) return AdapterCallResult.QuotaExhausted("Fallback free daily quota exhausted")
        delay(140)
        remainingQuota -= 1
        return AdapterCallResult.Success(
            outputEn = "[Simulated Free-Tier B Fallback • ${model.modelId}] ${request.persona.titleEn} completed '${request.taskTitle}' after automatic fallback ($0.00 API cost).",
            outputFa = "[شبیه‌سازی لایه رایگان پشتیبان B • ${model.modelId}] ${request.persona.titleFa} با جایگزینی خودکار «${request.taskTitle}» را با هزینه ۰ دلار انجام داد.",
            inputTokens = 190,
            outputTokens = 250,
            isSimulatedExecution = true,
        )
    }
}

/**
 * Self-hosted open-weight adapter (CPU-only compatible on VPS initially; future GPU worker optional).
 * Never executes on the owner's mobile phone; keeps private & Digital Twin data self-hosted.
 */
class SelfHostedOpenWeightAdapter : AiProviderAdapter {
    override val providerId: String = "vps-self-hosted-openweight"
    override val displayName: String = "Self-Hosted Open-Weight (VPS CPU/GPU)"
    override val tier: ProviderTier = ProviderTier.LOCAL_OPEN_WEIGHT
    override val retentionPolicy = ProviderRetentionPolicy(
        summaryEn = "Zero external retention (runs on owner's Linux VPS CPU/GPU worker; safe for Digital Twin & private data)",
        summaryFa = "حفظ کامل حریم خصوصی (اجرا روی سرور شخصی لینوکس؛ مناسب داده‌های دوقلوی دیجیتال و شخصی)",
        trainsOnInput = false,
        allowsSensitiveDataByDefault = true,
    )

    private var healthy: Boolean = true
    private var remainingOps: Int = 100

    override val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            modelId = "open-weight/qwen2.5-7b-q4-cpu",
            providerId = providerId,
            displayName = "Qwen 2.5 7B (VPS CPU)",
            tier = ProviderTier.LOCAL_OPEN_WEIGHT,
            maxComplexity = TaskComplexity.STANDARD,
            supportsCoding = true,
            supportsMultilingualFaEn = true,
            supportedModalities = setOf(AiModality.TEXT),
            avgLatencyMs = 1400,
            costPer1kInputUsd = 0.0,
            costPer1kOutputUsd = 0.0,
            estimatedInfraCostPerCallUsd = 0.0004,
            requiresGpuWorker = false,
        ),
        AiModelDescriptor(
            modelId = "open-weight/deepseek-r1-32b-gpu",
            providerId = providerId,
            displayName = "DeepSeek R1 32B (Future GPU Worker)",
            tier = ProviderTier.LOCAL_OPEN_WEIGHT,
            maxComplexity = TaskComplexity.COMPLEX_REASONING,
            supportsCoding = true,
            supportsMultilingualFaEn = true,
            supportedModalities = setOf(AiModality.TEXT, AiModality.IMAGE),
            avgLatencyMs = 900,
            costPer1kInputUsd = 0.0,
            costPer1kOutputUsd = 0.0,
            estimatedInfraCostPerCallUsd = 0.0025,
            requiresGpuWorker = true,
        ),
    )

    override fun quotaStatus(): ProviderQuotaStatus = ProviderQuotaStatus(
        providerId = providerId,
        displayName = displayName,
        tier = tier,
        isAvailable = healthy && remainingOps > 0,
        remainingFreeRequests = remainingOps,
        dailyFreeLimit = 100,
        retentionPolicy = retentionPolicy,
        noteEn = "CPU-first VPS worker (future GPU optional). Never runs on mobile device. Simulated in Phase 0.",
        noteFa = "موتور متن‌باز روی سرور شخصی (CPU ابتدا، GPU در آینده). بدون اجرا روی گوشی. شبیه‌سازی فاز ۰.",
    )

    override fun setSimulatedQuotaRemaining(remaining: Int) {
        remainingOps = remaining.coerceAtLeast(0)
    }

    override fun setSimulatedHealthy(healthy: Boolean) {
        this.healthy = healthy
    }

    override suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest): AdapterCallResult {
        if (!healthy || remainingOps <= 0) {
            return AdapterCallResult.Unavailable("Self-hosted VPS worker offline or capacity reached")
        }
        delay(160)
        remainingOps -= 1
        return AdapterCallResult.Success(
            outputEn = "[Simulated Self-Hosted • ${model.modelId}] ${request.persona.titleEn} processed '${request.taskTitle}' privately on VPS open-weight model ($0.00 API fee; ~$${model.estimatedInfraCostPerCallUsd} infra).",
            outputFa = "[شبیه‌سازی مدل متن‌باز سرور شخصی • ${model.modelId}] ${request.persona.titleFa} «${request.taskTitle}» را بدون ارسال به بیرون و با هزینه API صفر دلار پردازش کرد.",
            inputTokens = 210,
            outputTokens = 240,
            isSimulatedExecution = true,
        )
    }
}

/**
 * Paid cloud provider adapter.
 * Strictly blocked by OwnerSpendingPolicy ($0 automatic budget) unless owner explicitly approves.
 */
class PaidFrontierCloudAdapter : AiProviderAdapter {
    override val providerId: String = "server-proxy-paid-frontier"
    override val displayName: String = "Server Proxy • Paid Frontier Tier"
    override val tier: ProviderTier = ProviderTier.PAID_CLOUD
    override val retentionPolicy = ProviderRetentionPolicy(
        summaryEn = "Commercial API (external cloud; requires explicit owner spend & privacy authorization in Phase 1+)",
        summaryFa = "لایه تجاری ابری خارجی (نیازمند تایید صریح هزینه و حریم خصوصی توسط مالک سیستم)",
        trainsOnInput = false,
        allowsSensitiveDataByDefault = false,
    )

    private var healthy: Boolean = true

    override val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            modelId = "paid-frontier/reasoning-omni-v1",
            providerId = providerId,
            displayName = "Paid Frontier Omni (Reasoning/Video)",
            tier = ProviderTier.PAID_CLOUD,
            maxComplexity = TaskComplexity.COMPLEX_REASONING,
            supportsCoding = true,
            supportsMultilingualFaEn = true,
            supportedModalities = setOf(AiModality.TEXT, AiModality.IMAGE, AiModality.AUDIO, AiModality.VIDEO),
            avgLatencyMs = 850,
            costPer1kInputUsd = 0.005,
            costPer1kOutputUsd = 0.015,
        ),
    )

    override fun quotaStatus(): ProviderQuotaStatus = ProviderQuotaStatus(
        providerId = providerId,
        displayName = displayName,
        tier = tier,
        isAvailable = healthy,
        remainingFreeRequests = 0,
        dailyFreeLimit = 0,
        retentionPolicy = retentionPolicy,
        noteEn = "Simulated Paid API ($0.005/$0.015 per 1k tokens). Blocked under $0.00 auto budget unless explicitly approved.",
        noteFa = "مدل پولی شبیه‌سازی‌شده ($0.005/$0.015 به ازای هزار توکن). در بودجه خودکار ۰ دلار مسدود است مگر تایید شود.",
        isSimulatedQuota = true,
    )

    override fun setSimulatedQuotaRemaining(remaining: Int) = Unit

    override fun setSimulatedHealthy(healthy: Boolean) {
        this.healthy = healthy
    }

    override suspend fun invoke(model: AiModelDescriptor, request: TaskRoutingRequest): AdapterCallResult {
        if (!healthy) return AdapterCallResult.Unavailable("Paid frontier provider unavailable")
        delay(120)
        return AdapterCallResult.Success(
            outputEn = "[Simulated Paid Tier (Owner-Approved) • ${model.modelId}] ${request.persona.titleEn} completed '${request.taskTitle}'.",
            outputFa = "[شبیه‌سازی لایه پولی (با تایید مالک) • ${model.modelId}] ${request.persona.titleFa} درخواست «${request.taskTitle}» را انجام داد.",
            inputTokens = 300,
            outputTokens = 400,
            isSimulatedExecution = true,
        )
    }
}
