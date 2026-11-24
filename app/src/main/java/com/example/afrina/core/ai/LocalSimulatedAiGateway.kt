package com.example.afrina.core.ai

import com.example.afrina.core.domain.AiCompletionRequest
import com.example.afrina.core.domain.AiCompletionResponse
import com.example.afrina.core.domain.AiProviderGateway
import com.example.afrina.core.model.AgentRole
import kotlinx.coroutines.delay

/**
 * Phase 0 provider-independent AI gateway implementation.
 * Real HTTP adapters (OpenAI-compatible, Anthropic, Gemini, Self-Hosted vLLM/Ollama)
 * implement [AiProviderGateway] in Phase 2+ behind the Cloud Control Plane.
 */
class LocalSimulatedAiGateway : AiProviderGateway {
    override val providerId: String = "phase0-local-simulator"

    override val supportedModels: List<String> = listOf(
        "provider-agnostic/architect-v1",
        "provider-agnostic/coder-v1",
        "provider-agnostic/security-auditor-v1",
        "self-hosted/qwen-coder-32b (planned)",
    )

    override suspend fun executePrompt(request: AiCompletionRequest): AiCompletionResponse {
        delay(350)
        val (en, fa) = when (request.agentRole) {
            AgentRole.ARCHITECT ->
                "Simulated Architecture Review for ${request.contextProjectSlug}: Verified MVVM + Clean Architecture boundaries and zero cyclic module dependencies." to
                    "تحلیل معماری شبیه‌سازی‌شده برای ${request.contextProjectSlug}: مرزبندی MVVM و Clean Architecture بدون وابستگی چرخه‌ای تایید شد."
            AgentRole.PROGRAMMER ->
                "Simulated Code Plan for ${request.contextProjectSlug}: Prepared repository interface and ViewModel StateFlow diff; awaiting human review." to
                    "طرح کدنویسی شبیه‌سازی‌شده برای ${request.contextProjectSlug}: رابط Repository و StateFlow آماده شد و در انتظار بازبینی است."
            AgentRole.CODE_REVIEWER ->
                "Simulated Code Review for ${request.contextProjectSlug}: Checked coroutine cancellation safety, nullability, and Compose recomposition keys." to
                    "بازبینی کد شبیه‌سازی‌شده برای ${request.contextProjectSlug}: ایمنی Coroutines، مدیریت Null و کلیدهای Compose بررسی شد."
            AgentRole.DEBUGGER ->
                "Simulated Diagnostic Trace for ${request.contextProjectSlug}: Isolated transient timeout in simulated worker queue; no crash signatures found." to
                    "عیب‌یابی شبیه‌سازی‌شده برای ${request.contextProjectSlug}: خطای وقفه در صف کارگر شبیه‌سازی‌شده شناسایی و ایزوله شد."
            AgentRole.TESTER ->
                "Simulated Test Matrix for ${request.contextProjectSlug}: Generated 14 unit + Robolectric UI verification assertions." to
                    "ماتریس تست شبیه‌سازی‌شده برای ${request.contextProjectSlug}: ۱۴ مورد تست واحد و UI روبولکتریک پیشنهاد شد."
            AgentRole.SECURITY_AUDITOR ->
                "Simulated Security Audit for ${request.contextProjectSlug}: Confirmed zero hardcoded credentials and validated approval gate on shell tools." to
                    "ممیزی امنیت شبیه‌سازی‌شده برای ${request.contextProjectSlug}: عدم وجود کلید هاردکد و فعال بودن دروازه تایید انسانی تایید شد."
            AgentRole.DEPLOYMENT_ASSISTANT ->
                "Simulated Release Plan for ${request.contextProjectSlug}: Prepared non-destructive container dry-run manifest; requires founder approval to deploy." to
                    "طرح انتشار شبیه‌سازی‌شده برای ${request.contextProjectSlug}: مانیفست استقرار آزمایشی آماده شد؛ اجرای واقعی نیازمند تایید شماست."
        }
        return AiCompletionResponse(
            providerName = "Phase 0 Simulated Gateway",
            modelId = request.modelId,
            outputTextEn = en,
            outputTextFa = fa,
            estimatedTokens = 420,
            isSimulated = true,
        )
    }
}
