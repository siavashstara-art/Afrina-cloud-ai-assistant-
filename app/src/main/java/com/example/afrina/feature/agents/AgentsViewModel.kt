package com.example.afrina.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.domain.PersonaArchitectureRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.AgentDefinition
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AiUsageLedgerEntry
import com.example.afrina.core.model.ApprovalRequest
import com.example.afrina.core.model.CostTransparencySummary
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.FallbackEvent
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.persona.PersonaFoundationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RouterSelectionState(
    val selectedPersona: AfrinaPersona = AfrinaPersona.ARCHITECT_STRATEGIST,
    val selectedComplexity: TaskComplexity = TaskComplexity.STANDARD,
    val selectedClassification: DataClassification = DataClassification.PROJECT_CODE,
    val requireVideoModality: Boolean = false,
    val allowLocalOpenWeight: Boolean = true,
)

data class AgentsUiState(
    val agents: List<AgentDefinition> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val activities: List<AgentActivity> = emptyList(),
    val gatewayProviderId: String = "",
    val supportedModels: List<String> = emptyList(),
    val selection: RouterSelectionState = RouterSelectionState(),
    val spendingPolicy: OwnerSpendingPolicy = OwnerSpendingPolicy(),
    val providerStatuses: List<ProviderQuotaStatus> = emptyList(),
    val latestOutcome: AiRouteOutcome? = null,
    val paidApprovals: List<PaidInferenceProposal> = emptyList(),
    val usageLedger: List<AiUsageLedgerEntry> = emptyList(),
    val costSummary: CostTransparencySummary = CostTransparencySummary(),
    val fallbackEvents: List<FallbackEvent> = emptyList(),
    val personaSnapshot: PersonaFoundationSnapshot = PersonaFoundationSnapshot(),
)

private data class RouterStatusBundle(
    val providerStatuses: List<ProviderQuotaStatus>,
    val spendingPolicy: OwnerSpendingPolicy,
    val latestOutcome: AiRouteOutcome?,
    val fallbackEvents: List<FallbackEvent>,
)

private data class LedgerBundle(
    val usageLedger: List<AiUsageLedgerEntry>,
    val costSummary: CostTransparencySummary,
    val paidApprovals: List<PaidInferenceProposal>,
)

class AgentsViewModel(
    private val agentRepository: AgentRepository,
    private val aiRouterRepository: AiRouterRepository,
    private val personaArchitectureRepository: PersonaArchitectureRepository,
) : ViewModel() {

    private val selectionState = MutableStateFlow(RouterSelectionState())

    private val baseAgentsFlow = combine(
        agentRepository.observeAgentCatalog(),
        agentRepository.observeApprovals(),
        agentRepository.observeActivities(),
    ) { catalog, approvals, activities ->
        Triple(catalog, approvals, activities)
    }

    private val routerStatusFlow = combine(
        aiRouterRepository.observeProviderStatuses(),
        aiRouterRepository.observeSpendingPolicy(),
        aiRouterRepository.observeLatestOutcome(),
        aiRouterRepository.observeFallbackEvents(),
    ) { providers, policy, outcome, fallbacks ->
        RouterStatusBundle(providers, policy, outcome, fallbacks)
    }

    private val ledgerFlow = combine(
        aiRouterRepository.observeUsageLedger(),
        aiRouterRepository.observeCostSummary(),
        aiRouterRepository.observePaidApprovals(),
    ) { ledger, summary, paidApprovals ->
        LedgerBundle(ledger, summary, paidApprovals)
    }

    val uiState: StateFlow<AgentsUiState> = combine(
        baseAgentsFlow,
        routerStatusFlow,
        ledgerFlow,
        selectionState,
        personaArchitectureRepository.observeFoundationSnapshot(),
    ) { base, routerStatus, ledger, selection, personaSnapshot ->
        AgentsUiState(
            agents = base.first,
            approvals = base.second,
            activities = base.third,
            gatewayProviderId = "central-ai-provider-router",
            supportedModels = routerStatus.providerStatuses.map { it.displayName },
            selection = selection,
            spendingPolicy = routerStatus.spendingPolicy,
            providerStatuses = routerStatus.providerStatuses,
            latestOutcome = routerStatus.latestOutcome,
            paidApprovals = ledger.paidApprovals,
            usageLedger = ledger.usageLedger,
            costSummary = ledger.costSummary,
            fallbackEvents = routerStatus.fallbackEvents,
            personaSnapshot = personaSnapshot,
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

    fun selectPersona(persona: AfrinaPersona) {
        selectionState.update { it.copy(selectedPersona = persona) }
    }

    fun selectComplexity(complexity: TaskComplexity) {
        selectionState.update { it.copy(selectedComplexity = complexity) }
    }

    fun selectClassification(classification: DataClassification) {
        selectionState.update { it.copy(selectedClassification = classification) }
    }

    fun toggleVideoModality(enabled: Boolean) {
        selectionState.update { it.copy(requireVideoModality = enabled) }
    }

    fun toggleLocalOpenWeight(enabled: Boolean) {
        selectionState.update { it.copy(allowLocalOpenWeight = enabled) }
    }

    fun runSimulatedRouterTask(explicitSensitiveAuthorized: Boolean = false) {
        val current = selectionState.value
        val modalities = if (current.requireVideoModality) {
            setOf(AiModality.TEXT, AiModality.VIDEO)
        } else {
            setOf(AiModality.TEXT)
        }
        val taskTitle = "${current.selectedPersona.name} • ${current.selectedComplexity.name}"
        val prompt = "Phase 2 persona-routed verification for ${current.selectedPersona.titleEn}"

        viewModelScope.launch {
            personaArchitectureRepository.interactWithPersona(
                persona = current.selectedPersona,
                taskTitle = taskTitle,
                prompt = prompt,
                complexity = current.selectedComplexity,
                requiresCoding = current.selectedPersona == AfrinaPersona.BUILDER_ENGINEER,
                requiredModalities = modalities,
                dataClassification = current.selectedClassification,
                explicitSensitiveDataAuthorized = explicitSensitiveAuthorized,
                allowLocalOpenWeight = current.allowLocalOpenWeight,
                useGuardianDigitalTwinIdentity = false,
            )
        }
    }

    fun toggleAffectiveFreeze() {
        val currentPolicy = uiState.value.personaSnapshot.affectivePolicy
        viewModelScope.launch {
            personaArchitectureRepository.updateAffectivePolicy(
                currentPolicy.copy(ownerLockFrozen = !currentPolicy.ownerLockFrozen)
            )
        }
    }

    fun resetAffectiveState() {
        viewModelScope.launch {
            personaArchitectureRepository.resetAffectiveState()
        }
    }

    fun approvePaidInference(proposalId: String) {
        viewModelScope.launch {
            aiRouterRepository.approvePaidInference(proposalId)
        }
    }

    fun rejectPaidInference(proposalId: String) {
        viewModelScope.launch {
            aiRouterRepository.rejectPaidInference(proposalId)
        }
    }

    fun executeApprovedPaidTask(proposalId: String) {
        viewModelScope.launch {
            aiRouterRepository.executeApprovedPaidTask(proposalId)
        }
    }

    fun simulateExhaustPrimaryQuota() {
        viewModelScope.launch {
            aiRouterRepository.simulateExhaustPrimaryQuota()
        }
    }

    fun resetSimulatedQuotas() {
        viewModelScope.launch {
            aiRouterRepository.resetSimulatedQuotas()
        }
    }
}
