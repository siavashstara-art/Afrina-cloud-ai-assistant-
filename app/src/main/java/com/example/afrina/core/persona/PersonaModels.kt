package com.example.afrina.core.persona

import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.TaskComplexity

enum class GenderPresentation {
    NEUTRAL,
    MALE,
}

data class PersonaBehavioralConfig(
    val communicationToneEn: String,
    val communicationToneFa: String,
    val decisionStyleEn: String,
    val decisionStyleFa: String,
    val proactivityLevel: Double,
    val emotionalAwarenessLevel: Double,
    val technicalRigorLevel: Double,
    val requiresOwnerApprovalForExternalActions: Boolean = true,
)

data class PersonaProfile(
    val persona: AfrinaPersona,
    val canonicalName: String,
    val titleEn: String,
    val titleFa: String,
    val genderPresentation: GenderPresentation,
    val roleEn: String,
    val roleFa: String,
    val systemInstructions: String,
    val defaultComplexity: TaskComplexity,
    val defaultRequiresCoding: Boolean,
    val defaultDataClassification: DataClassification,
    val behavioralConfig: PersonaBehavioralConfig,
    val isDigitalTwinIdentity: Boolean = false,
)

data class PersonaInteractionTurn(
    val id: String,
    val persona: AfrinaPersona,
    val taskTitle: String,
    val intentSummary: String,
    val responseEn: String,
    val responseFa: String,
    val providerId: String,
    val modelId: String,
    val isSimulated: Boolean,
    val delegatedFrom: AfrinaPersona? = null,
    val timestampEpochMs: Long,
)

data class PersonaContextState(
    val persona: AfrinaPersona,
    val activeProjectId: String? = null,
    val activeGoal: String? = null,
    val turns: List<PersonaInteractionTurn> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
)

enum class MemoryLayer {
    SHORT_TERM_WORKING,
    MEDIUM_TERM_EPISODIC_PROJECT,
    LONG_TERM_SEMANTIC_PREFERENCE,
}

enum class MemoryReviewStatus {
    UNREVIEWED,
    OWNER_VERIFIED,
    OWNER_CORRECTED,
    OWNER_DISPUTED,
}

enum class MemorySourceKind {
    INTERACTION,
    VERIFIED_EXPERIENCE,
    OWNER_DIRECT,
    PERSONA_DELEGATION,
    GRADUAL_CONSOLIDATION,
}

data class MemoryProvenance(
    val sourcePersona: AfrinaPersona,
    val sourceKind: MemorySourceKind,
    val sourceReferenceId: String,
    val lastReviewedByOwner: Boolean = false,
    val recordedAtEpochMs: Long,
)

data class SharedMemoryEntry(
    val id: String,
    val layer: MemoryLayer,
    val contentSummary: String,
    val dataClassification: DataClassification,
    val provenance: MemoryProvenance,
    val confidence: Double,
    val reviewStatus: MemoryReviewStatus = MemoryReviewStatus.UNREVIEWED,
    val ownerCorrectionNote: String? = null,
    val projectId: String? = null,
    val preferenceKey: String? = null,
    val verifiedExperienceCount: Int = 0,
    val updatedAtEpochMs: Long,
)

/**
 * Conceptual computational affective regulation variables.
 * These are software regulation state values, NOT biological measurements.
 */
data class AffectiveState(
    val dopamineLike: Double = 0.55,
    val serotoninLike: Double = 0.65,
    val endorphinLike: Double = 0.50,
    val cortisolLike: Double = 0.20,
    val isConceptualComputationalModel: Boolean = true,
    val isFrozenByOwner: Boolean = false,
    val lastRegulationReason: String = "Initialized to owner baseline",
    val updatedAtEpochMs: Long = 0L,
)

data class AffectiveControlPolicy(
    val allowAdaptiveLearning: Boolean = true,
    val allowSelfRegulation: Boolean = true,
    val maxStepDelta: Double = 0.12,
    val baselineDopamine: Double = 0.55,
    val baselineSerotonin: Double = 0.65,
    val baselineEndorphin: Double = 0.50,
    val baselineCortisol: Double = 0.20,
    val ownerLockFrozen: Boolean = false,
)

enum class AffectiveSignalEvent {
    TASK_SUCCESS,
    COMPLEX_PROGRESS,
    RISK_OR_PRIVACY_ALERT,
    CALM_STEADY_INTERACTION,
    OWNER_POSITIVE_FEEDBACK,
}

enum class TwinMediaKind {
    PHOTOGRAPH,
    VIDEO,
    VOICE_SAMPLE,
}

enum class TwinCapabilityStatus {
    NOT_CONFIGURED_REQUIRES_SUPPLIED_SAMPLES,
    AWAITING_OWNER_AUTHORIZATION,
    PREPARED_AWAITING_SYNTHESIS_ENGINE,
}

data class DigitalTwinMediaSample(
    val id: String,
    val kind: TwinMediaKind,
    val privateVaultAlias: String,
    val mimeType: String,
    val durationMs: Long? = null,
    val ownerAuthorized: Boolean,
    val addedAtEpochMs: Long,
)

data class GuardianDigitalTwinState(
    val persona: AfrinaPersona = AfrinaPersona.GUARDIAN_SENTINEL,
    val gender: GenderPresentation = GenderPresentation.MALE,
    val isOwnerAuthorized: Boolean = false,
    val identityAuthorized: Boolean = false,
    val voiceCloneAuthorized: Boolean = false,
    val avatarRenderAuthorized: Boolean = false,
    val approvedIdentitySummary: String? = null,
    val approvedCommunicationStyle: String? = null,
    val suppliedSamples: List<DigitalTwinMediaSample> = emptyList(),
    val voiceCloneStatus: TwinCapabilityStatus = TwinCapabilityStatus.NOT_CONFIGURED_REQUIRES_SUPPLIED_SAMPLES,
    val avatarRenderStatus: TwinCapabilityStatus = TwinCapabilityStatus.NOT_CONFIGURED_REQUIRES_SUPPLIED_SAMPLES,
    val privacyClassification: DataClassification = DataClassification.DIGITAL_TWIN_SENSITIVE,
)

enum class AfrinaEdition {
    MASTER_OWNER,
    CUSTOMER_STANDARD,
}

enum class DevelopmentCapability {
    PROPOSE_CODE,
    GENERATE_CODE_PATCH,
    RUN_ISOLATED_TESTS,
    IMPROVE_WORKFLOW_CODE,
    MODIFY_AFRINA_CORE,
    MODIFY_LICENSING,
    MODIFY_SECURITY_CONTROLS,
    MODIFY_INTERNAL_EXECUTION_POLICIES,
}

sealed interface DevelopmentPermissionDecision {
    data class Allowed(val reasonEn: String, val reasonFa: String) : DevelopmentPermissionDecision
    data class Denied(val reasonEn: String, val reasonFa: String) : DevelopmentPermissionDecision
}

data class PersonaDelegationRequest(
    val id: String,
    val fromPersona: AfrinaPersona,
    val toPersona: AfrinaPersona,
    val taskTitle: String,
    val taskPrompt: String,
    val targetProjectId: String? = null,
    val complexity: TaskComplexity = TaskComplexity.STANDARD,
    val requiresCoding: Boolean = false,
    val requiredModalities: Set<AiModality> = setOf(AiModality.TEXT),
    val dataClassification: DataClassification = DataClassification.PROJECT_CODE,
    val explicitSensitiveDataAuthorized: Boolean = false,
)

data class PersonaDelegationResult(
    val requestId: String,
    val fromPersona: AfrinaPersona,
    val toPersona: AfrinaPersona,
    val accepted: Boolean,
    val denialReason: String? = null,
    val routeOutcome: AiRouteOutcome? = null,
    val recordedTurnId: String? = null,
)

data class PersonaFoundationSnapshot(
    val edition: AfrinaEdition = AfrinaEdition.MASTER_OWNER,
    val profiles: List<PersonaProfile> = emptyList(),
    val contextsByPersona: Map<AfrinaPersona, PersonaContextState> = emptyMap(),
    val memories: List<SharedMemoryEntry> = emptyList(),
    val affectiveState: AffectiveState = AffectiveState(),
    val affectivePolicy: AffectiveControlPolicy = AffectiveControlPolicy(),
    val guardianTwin: GuardianDigitalTwinState = GuardianDigitalTwinState(),
    val delegationHistory: List<PersonaDelegationResult> = emptyList(),
)
