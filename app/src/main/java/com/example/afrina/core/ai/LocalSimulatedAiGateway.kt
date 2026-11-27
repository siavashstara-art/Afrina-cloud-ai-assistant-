package com.example.afrina.core.ai

import com.example.afrina.core.domain.AiCompletionRequest
import com.example.afrina.core.domain.AiCompletionResponse
import com.example.afrina.core.domain.AiProviderGateway

/**
 * Legacy Phase 0 gateway placeholder.
 * Direct execution is explicitly disabled in Phase 1 so no execution path can bypass
 * [CentralAiProviderRouter], privacy gates, $0.00 spending protection, or the audit ledger.
 */
@Deprecated("Direct legacy gateway bypassed CentralAiProviderRouter; use AiRouterRepository instead.")
class LocalSimulatedAiGateway : AiProviderGateway {
    override val providerId: String = "disabled-legacy-gateway"

    override val supportedModels: List<String> = emptyList()

    override suspend fun executePrompt(request: AiCompletionRequest): AiCompletionResponse {
        throw UnsupportedOperationException(
            "Direct execution via LocalSimulatedAiGateway is disabled; route all tasks through AiRouterRepository / CentralAiProviderRouter."
        )
    }
}
