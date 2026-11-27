package com.example.afrina.core.domain

import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.AgentDefinition
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AfrinaProject
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AiUsageLedgerEntry
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ApprovalRequest
import com.example.afrina.core.model.AuditLogEntry
import com.example.afrina.core.model.BuildPipelineRun
import com.example.afrina.core.model.CloudNotification
import com.example.afrina.core.model.CostTransparencySummary
import com.example.afrina.core.model.DeploymentRecord
import com.example.afrina.core.model.DeploymentTarget
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.FallbackEvent
import com.example.afrina.core.model.GitHubRepoConnection
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.SystemHealthSnapshot
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskPriority
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.model.TaskStatus
import com.example.afrina.core.model.ThemePreference
import com.example.afrina.core.model.UserPreferences
import com.example.afrina.core.persona.AffectiveControlPolicy
import com.example.afrina.core.persona.AffectiveSignalEvent
import com.example.afrina.core.persona.AffectiveState
import com.example.afrina.core.persona.AvatarCustomizationConfig
import com.example.afrina.core.persona.CustomerOnboardingProfile
import com.example.afrina.core.persona.CustomerWorkspaceCapability
import com.example.afrina.core.persona.DevelopmentCapability
import com.example.afrina.core.persona.DevelopmentPermissionDecision
import com.example.afrina.core.persona.DigitalTwinCustomizationProfile
import com.example.afrina.core.persona.DigitalTwinMediaSample
import com.example.afrina.core.persona.GuardianDigitalTwinState
import com.example.afrina.core.persona.IdentityMediaAsset
import com.example.afrina.core.persona.InteractionPreferencesConfig
import com.example.afrina.core.persona.MediaAssetKind
import com.example.afrina.core.persona.MemoryLayer
import com.example.afrina.core.persona.PersonaDelegationRequest
import com.example.afrina.core.persona.PersonaDelegationResult
import com.example.afrina.core.persona.PersonaFoundationSnapshot
import com.example.afrina.core.persona.PersonaIdentityRevision
import com.example.afrina.core.persona.PersonalityTraitsConfig
import com.example.afrina.core.persona.SharedMemoryEntry
import com.example.afrina.core.persona.SubjectAgePolicyStatus
import com.example.afrina.core.persona.TenantPersonaWorkspaceSnapshot
import com.example.afrina.core.persona.TenantScope
import com.example.afrina.core.persona.TwinMediaKind
import com.example.afrina.core.persona.VoiceCustomizationConfig
import com.example.afrina.core.voice.AudioCollaborationReadinessResult
import com.example.afrina.core.voice.AudioCollaborationSessionSpec
import com.example.afrina.core.voice.AudioFramePacket
import com.example.afrina.core.voice.EndOfTurnPolicy
import com.example.afrina.core.voice.MicrophonePermissionState
import com.example.afrina.core.voice.PersonaVoiceProfile
import com.example.afrina.core.voice.UnifiedConversationTurn
import com.example.afrina.core.voice.VadEvaluation
import com.example.afrina.core.voice.VoiceConversationSnapshot
import com.example.afrina.core.voice.VoicePresentationStyle
import com.example.afrina.core.voice.VoiceRecordingPrivacyRecord
import com.example.afrina.core.voice.VoiceStyleTuning
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(): Flow<List<AfrinaProject>>
    fun observeTasks(): Flow<List<DevTask>>
    suspend fun createProject(
        name: String,
        repoFullName: String,
        stackTags: List<String>,
        description: String,
    )
    suspend fun updateTaskStatus(taskId: String, newStatus: TaskStatus)
    suspend fun createTask(
        projectId: String,
        title: String,
        priority: TaskPriority,
        assignedAgent: AgentRole?,
    )
}

interface AgentRepository {
    fun observeAgentCatalog(): Flow<List<AgentDefinition>>
    fun observeActivities(): Flow<List<AgentActivity>>
    fun observeApprovals(): Flow<List<ApprovalRequest>>
    suspend fun resolveApproval(approvalId: String, approved: Boolean)
    suspend fun triggerSimulatedAgentPlan(projectId: String, role: AgentRole, promptSummary: String)
}

interface AiRouterRepository {
    fun observeProviderStatuses(): Flow<List<ProviderQuotaStatus>>
    fun observeSpendingPolicy(): Flow<OwnerSpendingPolicy>
    fun observeUsageLedger(): Flow<List<AiUsageLedgerEntry>>
    fun observeCostSummary(): Flow<CostTransparencySummary>
    fun observePaidApprovals(): Flow<List<PaidInferenceProposal>>
    fun observeFallbackEvents(): Flow<List<FallbackEvent>>
    fun observeLatestOutcome(): Flow<AiRouteOutcome?>
    suspend fun executePersonaTask(request: TaskRoutingRequest): AiRouteOutcome
    suspend fun approvePaidInference(proposalId: String)
    suspend fun rejectPaidInference(proposalId: String)
    suspend fun executeApprovedPaidTask(proposalId: String): AiRouteOutcome
    suspend fun simulateExhaustPrimaryQuota()
    suspend fun resetSimulatedQuotas()
}

interface PersonaArchitectureRepository {
    fun observeFoundationSnapshot(): Flow<PersonaFoundationSnapshot>

    suspend fun interactWithPersona(
        persona: AfrinaPersona,
        taskTitle: String,
        prompt: String,
        projectId: String? = null,
        complexity: TaskComplexity? = null,
        requiresCoding: Boolean? = null,
        requiredModalities: Set<AiModality> = setOf(AiModality.TEXT),
        dataClassification: DataClassification? = null,
        explicitSensitiveDataAuthorized: Boolean = false,
        allowLocalOpenWeight: Boolean = true,
        useGuardianDigitalTwinIdentity: Boolean = false,
    ): AiRouteOutcome

    suspend fun delegateTask(request: PersonaDelegationRequest): PersonaDelegationResult

    suspend fun recordExplicitMemory(
        layer: MemoryLayer,
        contentSummary: String,
        sourcePersona: AfrinaPersona,
        dataClassification: DataClassification = DataClassification.PROJECT_CODE,
        projectId: String? = null,
        preferenceKey: String? = null,
        ownerDirect: Boolean = true,
    ): SharedMemoryEntry

    suspend fun verifyMemory(memoryId: String): SharedMemoryEntry?
    suspend fun correctMemory(memoryId: String, correctedSummary: String, ownerNote: String?): SharedMemoryEntry?
    suspend fun disputeMemory(memoryId: String, reasonNote: String?): SharedMemoryEntry?
    suspend fun deleteMemory(memoryId: String): Boolean
    suspend fun learnFromVerifiedExperience(memoryId: String, preferenceKey: String): SharedMemoryEntry?

    suspend fun updateAffectivePolicy(policy: AffectiveControlPolicy): AffectiveControlPolicy
    suspend fun applyAffectiveSignal(event: AffectiveSignalEvent, reason: String): AffectiveState
    suspend fun regulateAffectiveHomeostasis(): AffectiveState
    suspend fun resetAffectiveState(): AffectiveState

    suspend fun setGuardianIdentityAuthorization(
        authorized: Boolean,
        approvedIdentitySummary: String?,
        approvedCommunicationStyle: String?,
    ): Result<GuardianDigitalTwinState>

    suspend fun registerGuardianMediaSample(
        kind: TwinMediaKind,
        privateVaultAlias: String,
        mimeType: String,
        durationMs: Long? = null,
        ownerAuthorized: Boolean,
    ): Result<DigitalTwinMediaSample>

    suspend fun prepareGuardianVoiceClone(ownerAuthorized: Boolean): Result<GuardianDigitalTwinState>
    suspend fun prepareGuardianVisualAvatar(ownerAuthorized: Boolean): Result<GuardianDigitalTwinState>

    suspend fun evaluateDevelopmentCapability(
        capability: DevelopmentCapability,
        inIsolatedDevEnvironment: Boolean,
        ownerApprovedWorkflow: Boolean,
    ): DevelopmentPermissionDecision
}

interface PersonaIdentityCustomizationRepository {
    fun observeTenantWorkspace(tenantScope: TenantScope): Flow<TenantPersonaWorkspaceSnapshot>

    suspend fun onboardCustomer(
        customerId: String,
        displayName: String,
        subjectAgeStatus: SubjectAgePolicyStatus,
        contentPolicyAccepted: Boolean,
        likenessUseAuthorized: Boolean,
        voiceUseAuthorized: Boolean,
    ): Result<CustomerOnboardingProfile>

    suspend fun registerAuthorizedMediaAsset(
        tenantScope: TenantScope,
        kind: MediaAssetKind,
        privateVaultAlias: String,
        mimeType: String,
        distinctFaceIdentityId: String? = null,
        durationMs: Long? = null,
        explicitLikenessOrVoiceAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
    ): Result<IdentityMediaAsset>

    suspend fun updatePersonaIdentity(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        customName: String? = null,
        identitySubtitleEn: String? = null,
        identitySubtitleFa: String? = null,
        roleSummaryEn: String? = null,
        roleSummaryFa: String? = null,
        responsibilities: List<String>? = null,
        personality: PersonalityTraitsConfig? = null,
        knowledgeScopeTags: List<String>? = null,
        behavioralInstructions: String? = null,
        interactionPreferences: InteractionPreferencesConfig? = null,
        changeSummary: String = "Identity configuration update",
    ): Result<PersonaIdentityRevision>

    suspend fun updateVoiceConfig(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        voiceConfig: VoiceCustomizationConfig,
        explicitVoiceAuthorized: Boolean,
        changeSummary: String = "Voice customization update",
    ): Result<PersonaIdentityRevision>

    suspend fun updateAvatarConfig(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        avatarConfig: AvatarCustomizationConfig,
        explicitLikenessAuthorized: Boolean,
        changeSummary: String = "Avatar customization update",
    ): Result<PersonaIdentityRevision>

    suspend fun rollbackPersonaConfiguration(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        targetVersion: Int,
    ): Result<PersonaIdentityRevision>

    suspend fun updateDigitalTwinProfile(
        tenantScope: TenantScope,
        likenessAuthorized: Boolean,
        voiceAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
        authorizedPhotoAssetIds: List<String> = emptyList(),
        authorizedVideoAssetIds: List<String> = emptyList(),
        authorizedVoiceAssetIds: List<String> = emptyList(),
        communicationStyleSummary: String?,
        approvedVocabulary: List<String> = emptyList(),
        approvedKnowledgeMemoryIds: List<String> = emptyList(),
        editablePersonality: PersonalityTraitsConfig? = null,
        behaviorNotes: String? = null,
    ): Result<DigitalTwinCustomizationProfile>

    suspend fun recordTenantMemory(
        tenantScope: TenantScope,
        layer: MemoryLayer,
        contentSummary: String,
        sourcePersona: AfrinaPersona,
        dataClassification: DataClassification = DataClassification.OWNER_PRIVATE,
        projectId: String? = null,
        ownerDirect: Boolean = true,
    ): Result<SharedMemoryEntry>

    suspend fun queryTenantMemories(
        tenantScope: TenantScope,
        includeSensitiveOwnerData: Boolean = false,
    ): List<SharedMemoryEntry>

    suspend fun evaluateCustomerWorkspaceAction(
        tenantScope: TenantScope,
        capability: CustomerWorkspaceCapability,
    ): DevelopmentPermissionDecision
}

interface VoiceConversationRepository {
    fun observeVoiceSnapshot(tenantScope: TenantScope = TenantScope.MasterOwner): Flow<VoiceConversationSnapshot>

    suspend fun setMicrophonePermission(
        tenantScope: TenantScope,
        permissionState: MicrophonePermissionState,
    )

    suspend fun startVoiceListening(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        localeTag: String = "fa-IR",
    ): Result<Unit>

    suspend fun processMicrophoneFrame(
        tenantScope: TenantScope,
        frame: AudioFramePacket,
        policy: EndOfTurnPolicy = EndOfTurnPolicy(),
    ): Result<VadEvaluation>

    suspend fun completeVoiceUtterance(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        localeTag: String = "fa-IR",
        transcriptOverride: String? = null,
        projectId: String? = null,
        dataClassification: DataClassification = DataClassification.OWNER_PRIVATE,
        explicitSensitiveDataAuthorized: Boolean = false,
        useGuardianDigitalTwinIdentity: Boolean = false,
    ): Result<UnifiedConversationTurn>

    suspend fun submitTextTurn(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        text: String,
        localeTag: String = "fa-IR",
        projectId: String? = null,
        dataClassification: DataClassification = DataClassification.OWNER_PRIVATE,
        explicitSensitiveDataAuthorized: Boolean = false,
        speakResponseAloud: Boolean = false,
    ): Result<UnifiedConversationTurn>

    suspend fun interruptSpeakingPersona(
        tenantScope: TenantScope,
        resumeListening: Boolean = true,
        reason: String = "Owner interrupted active response",
    ): Boolean

    suspend fun configurePersonaVoiceProfile(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        voiceName: String? = null,
        activeLocaleTag: String? = null,
        activeStyle: VoicePresentationStyle? = null,
        updatedTuning: VoiceStyleTuning? = null,
    ): Result<PersonaVoiceProfile>

    suspend fun linkAuthorizedVoiceCloneSample(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        voiceAssetId: String,
        explicitOwnerAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
    ): Result<PersonaVoiceProfile>

    suspend fun deleteVoiceProfile(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
    ): Boolean

    suspend fun registerVoiceRecording(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        privateVaultAlias: String,
        durationMs: Long,
        explicitOwnerAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
        allowSttTranscription: Boolean = true,
        allowVoiceCloningUsage: Boolean = false,
    ): Result<VoiceRecordingPrivacyRecord>

    suspend fun updateRecordingAccessControl(
        tenantScope: TenantScope,
        recordingId: String,
        allowSttTranscription: Boolean,
        allowVoiceCloningUsage: Boolean,
    ): Result<VoiceRecordingPrivacyRecord>

    suspend fun deleteVoiceRecording(
        tenantScope: TenantScope,
        recordingId: String,
    ): Boolean

    suspend fun prepareAudioCollaboration(
        tenantScope: TenantScope,
        spec: AudioCollaborationSessionSpec,
    ): Result<AudioCollaborationReadinessResult>
}

interface PipelineRepository {
    fun observeGitHubConnections(): Flow<List<GitHubRepoConnection>>
    fun observeBuilds(): Flow<List<BuildPipelineRun>>
    fun observeDeployments(): Flow<List<DeploymentRecord>>
    fun observeSystemHealth(): Flow<SystemHealthSnapshot>
    suspend fun triggerSimulatedBuild(projectName: String, branch: String)
    suspend fun triggerSimulatedDeploy(projectName: String, target: DeploymentTarget)
}

interface NotificationRepository {
    fun observeNotifications(): Flow<List<CloudNotification>>
    fun observeAuditLogs(): Flow<List<AuditLogEntry>>
    suspend fun markAllRead()
    suspend fun recordAudit(actor: String, action: String, target: String, outcome: String)
}

interface PreferencesRepository {
    fun observePreferences(): Flow<UserPreferences>
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setSelectedAiModel(modelId: String)
}

interface VaultCryptoManager {
    fun isHardwareVaultAvailable(): Boolean
    fun hasStoredCredential(alias: String): Boolean
    fun storeEncryptedCredential(alias: String, plaintext: String): Result<Unit>
    fun readDecryptedCredentialMasked(alias: String): String?
    fun deleteCredential(alias: String)
}

data class AiCompletionRequest(
    val agentRole: AgentRole,
    val modelId: String,
    val prompt: String,
    val contextProjectSlug: String,
)

data class AiCompletionResponse(
    val providerName: String,
    val modelId: String,
    val outputTextEn: String,
    val outputTextFa: String,
    val estimatedTokens: Int,
    val isSimulated: Boolean,
)

interface AiProviderGateway {
    val providerId: String
    val supportedModels: List<String>
    suspend fun executePrompt(request: AiCompletionRequest): AiCompletionResponse
}
