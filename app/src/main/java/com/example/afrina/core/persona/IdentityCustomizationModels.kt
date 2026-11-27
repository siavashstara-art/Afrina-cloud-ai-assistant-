package com.example.afrina.core.persona

import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.DataClassification

sealed interface TenantScope {
    data object MasterOwner : TenantScope
    data class Customer(val customerId: String) : TenantScope {
        init {
            require(customerId.isNotBlank()) { "Customer ID must be non-blank." }
            require(!customerId.equals("master", ignoreCase = true) && !customerId.equals("owner", ignoreCase = true)) {
                "Customer ID cannot impersonate master/owner namespace."
            }
        }
    }

    val key: String
        get() = when (this) {
            MasterOwner -> "tenant:master_owner"
            is Customer -> "tenant:customer:${customerId.trim()}"
        }

    val edition: AfrinaEdition
        get() = when (this) {
            MasterOwner -> AfrinaEdition.MASTER_OWNER
            is Customer -> AfrinaEdition.CUSTOMER_STANDARD
        }
}

enum class SubjectAgePolicyStatus {
    UNVERIFIED,
    VERIFIED_ADULT_18_PLUS,
    MINOR_RESTRICTED,
}

enum class CustomerOnboardingStatus {
    PENDING_VERIFICATION,
    ACTIVE_CUSTOMIZABLE,
    SUSPENDED,
}

enum class MediaAssetKind {
    PHOTOGRAPH,
    VIDEO_REFERENCE,
    VOICE_SAMPLE,
}

enum class AvatarPresentationMode {
    STATIC_PORTRAIT,
    SUBTLE_BREATHING_IDLE,
    CONVERSATIONAL_EXPRESSIVE,
}

enum class VisualStylePreset {
    TERRACOTTA_ATELIER,
    ARCHITECTURAL_MINIMAL,
    WARM_EDITORIAL,
    EXECUTIVE_CHARCOAL,
}

enum class ResponseLengthPreference {
    CONCISE,
    BALANCED,
    DETAILED,
}

data class IdentityMediaAsset(
    val id: String,
    val ownerTenantKey: String,
    val kind: MediaAssetKind,
    val privateVaultAlias: String,
    val mimeType: String,
    val distinctFaceIdentityId: String? = null,
    val durationMs: Long? = null,
    val explicitLikenessOrVoiceAuthorized: Boolean,
    val subjectAgeStatus: SubjectAgePolicyStatus,
    val createdAtEpochMs: Long,
)

data class PersonalityTraitsConfig(
    val warmth: Double = 0.65,
    val formality: Double = 0.55,
    val directness: Double = 0.70,
    val empathy: Double = 0.60,
    val technicalDepth: Double = 0.75,
    val communicationStyleEn: String,
    val communicationStyleFa: String,
    val vocabularyNotes: List<String> = emptyList(),
)

data class VoiceCustomizationConfig(
    val voiceProfileName: String,
    val genderPresentation: GenderPresentation,
    val pitchOffset: Double = 0.0,
    val speakingRate: Double = 1.0,
    val preferredLocaleTag: String = "fa-IR",
    val authorizedVoiceAssetId: String? = null,
    val requiresExplicitVoiceAuthorization: Boolean = true,
)

data class AvatarCustomizationConfig(
    val avatarProfileId: String,
    val distinctFaceIdentityId: String,
    val authorizedPhotoAssetIds: List<String> = emptyList(),
    val authorizedVideoAssetIds: List<String> = emptyList(),
    val clothingStyle: String,
    val appearanceNotes: String,
    val visualStylePreset: VisualStylePreset,
    val presentationMode: AvatarPresentationMode = AvatarPresentationMode.STATIC_PORTRAIT,
    val animationIntensity: Double = 0.35,
    val idleMotionEnabled: Boolean = true,
)

data class InteractionPreferencesConfig(
    val preferredLanguage: AppLanguage = AppLanguage.PERSIAN,
    val responseLength: ResponseLengthPreference = ResponseLengthPreference.BALANCED,
    val proactivityLevel: Double = 0.60,
    val confirmBeforeExternalActions: Boolean = true,
)

/**
 * Full independently configurable persona identity configuration.
 * Note: [securityRoleAnchor] and [minimumDataClassification] are immutable invariants
 * so avatar/personality edits can NEVER overwrite security identity or role boundaries.
 */
data class PersonaIdentityConfiguration(
    val persona: AfrinaPersona,
    val tenantKey: String,
    val customName: String,
    val identitySubtitleEn: String,
    val identitySubtitleFa: String,
    val roleSummaryEn: String,
    val roleSummaryFa: String,
    val responsibilities: List<String>,
    val personality: PersonalityTraitsConfig,
    val knowledgeScopeTags: List<String>,
    val behavioralInstructions: String,
    val voiceConfig: VoiceCustomizationConfig,
    val avatarConfig: AvatarCustomizationConfig,
    val interactionPreferences: InteractionPreferencesConfig,
    val securityRoleAnchor: AfrinaPersona = persona,
    val minimumDataClassification: DataClassification,
    val updatedAtEpochMs: Long,
)

data class PersonaIdentityRevision(
    val version: Int,
    val persona: AfrinaPersona,
    val tenantKey: String,
    val configuration: PersonaIdentityConfiguration,
    val changeSummary: String,
    val changedAtEpochMs: Long,
)

data class DigitalTwinCustomizationProfile(
    val tenantKey: String,
    val gender: GenderPresentation = GenderPresentation.MALE,
    val likenessAuthorized: Boolean = false,
    val voiceAuthorized: Boolean = false,
    val subjectAgeStatus: SubjectAgePolicyStatus = SubjectAgePolicyStatus.UNVERIFIED,
    val authorizedPhotoAssetIds: List<String> = emptyList(),
    val authorizedVideoAssetIds: List<String> = emptyList(),
    val authorizedVoiceAssetIds: List<String> = emptyList(),
    val communicationStyleSummary: String? = null,
    val approvedVocabulary: List<String> = emptyList(),
    val approvedKnowledgeMemoryIds: List<String> = emptyList(),
    val editablePersonality: PersonalityTraitsConfig? = null,
    val behaviorNotes: String? = null,
    val updatedAtEpochMs: Long = 0L,
)

data class CustomerOnboardingProfile(
    val customerId: String,
    val displayName: String,
    val status: CustomerOnboardingStatus,
    val subjectAgeStatus: SubjectAgePolicyStatus,
    val contentPolicyAccepted: Boolean,
    val likenessUseAuthorized: Boolean,
    val voiceUseAuthorized: Boolean,
    val createdAtEpochMs: Long,
)

enum class CustomerWorkspaceCapability {
    CUSTOMIZE_OWN_PERSONA_IDENTITY,
    CUSTOMIZE_OWN_AVATAR_AND_VOICE,
    CONFIGURE_OWN_DIGITAL_TWIN,
    DEVELOP_EXTERNAL_CUSTOMER_PROJECT,
    MODIFY_AFRINA_CORE,
    MODIFY_LICENSING,
    MODIFY_SECURITY_CONTROLS,
    MODIFY_INTERNAL_EXECUTION_POLICIES,
    ACCESS_MASTER_OR_OTHER_TENANT,
}

data class TenantPersonaWorkspaceSnapshot(
    val tenantScope: TenantScope,
    val edition: AfrinaEdition,
    val currentConfigs: Map<AfrinaPersona, PersonaIdentityConfiguration> = emptyMap(),
    val revisionsByPersona: Map<AfrinaPersona, List<PersonaIdentityRevision>> = emptyMap(),
    val mediaAssets: List<IdentityMediaAsset> = emptyList(),
    val digitalTwinProfile: DigitalTwinCustomizationProfile,
    val customerOnboarding: CustomerOnboardingProfile? = null,
    val tenantMemoryCount: Int = 0,
)
