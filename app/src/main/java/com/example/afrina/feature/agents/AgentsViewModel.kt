package com.example.afrina.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.AiProviderGateway
import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.AgentDefinition
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.ApprovalRequest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentsUiState(
    val agents: List<AgentDefinition> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val activities: List<AgentActivity> = emptyList(),
    val gatewayProviderId: String = "",
    val supportedModels: List<String> = emptyList(),
)

class AgentsViewModel(
    private val agentRepository: AgentRepository,
    private val aiProviderGateway: AiProviderGateway,
) : ViewModel() {

    val uiState: StateFlow<AgentsUiState> = combine(
        agentRepository.observeAgentCatalog(),
        agentRepository.observeApprovals(),
        agentRepository.observeActivities(),
    ) { catalog, approvals, activities ->
        AgentsUiState(
            agents = catalog,
            approvals = approvals,
            activities = activities,
            gatewayProviderId = aiProviderGateway.providerId,
            supportedModels = aiProviderGateway.supportedModels,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AgentsUiState(),
    )

    fun resolveApproval(approvalId: String, approved: Boolean) {
        viewModelScope.launch {
            agentRepository.resolveApproval(approvalId, approved)
        }
    }

    fun triggerSimulatedRun(role: AgentRole) {
        viewModelScope.launch {
            agentRepository.triggerSimulatedAgentPlan(
                projectId = "afrina-control-plane",
                role = role,
                promptSummary = "Phase 0 architecture & safety check",
            )
        }
    }
}
