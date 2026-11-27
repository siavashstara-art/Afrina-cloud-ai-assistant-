package com.example.afrina.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameFa: String,
    val slug: String,
    val description: String,
    val descriptionFa: String,
    val stackTagsCsv: String,
    val healthStatus: String,
    val repoFullName: String,
    val defaultBranch: String,
    val lastCommitSha: String,
    val lastBuildStatus: String,
    val activeEnvironment: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "dev_tasks")
data class DevTaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val titleFa: String,
    val priority: String,
    val status: String,
    val assignedAgent: String?,
    val requiresApproval: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "approval_requests")
data class ApprovalRequestEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val agentRole: String,
    val actionTitleEn: String,
    val actionTitleFa: String,
    val commandPreview: String,
    val riskLevel: String,
    val status: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "agent_activities")
data class AgentActivityEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val agentRole: String,
    val summaryEn: String,
    val summaryFa: String,
    val toolInvoked: String,
    val state: String,
    val timestampLabel: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val titleEn: String,
    val titleFa: String,
    val bodyEn: String,
    val bodyFa: String,
    val severity: String,
    val isRead: Boolean,
    val timestampLabel: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val actor: String,
    val action: String,
    val target: String,
    val outcome: String,
    val timestampEpochMs: Long,
)

@Entity(tableName = "ai_usage_ledger")
data class AiUsageLedgerEntity(
    @PrimaryKey val id: String,
    val persona: String,
    val providerId: String,
    val modelId: String,
    val taskTitle: String,
    val usageCategory: String,
    val dataClassification: String,
    val routingKind: String,
    val fallbackTriggered: Boolean,
    val fallbackReason: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedApiCostUsd: Double,
    val estimatedInfraCostUsd: Double,
    val isSimulated: Boolean,
    val timestampEpochMs: Long,
)

@Entity(tableName = "paid_inference_approvals")
data class PaidInferenceApprovalEntity(
    @PrimaryKey val id: String,
    val persona: String,
    val taskTitle: String,
    val complexity: String,
    val requiresCoding: Boolean,
    val requiredModalitiesCsv: String,
    val dataClassification: String,
    val explicitSensitiveAuthorized: Boolean,
    val targetProviderId: String,
    val targetModelId: String,
    val estimatedCostUsd: Double,
    val reasonEn: String,
    val reasonFa: String,
    val status: String,
    val isExecuted: Boolean,
    val isSimulated: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)


@Entity(tableName = "persona_identities", primaryKeys = ["tenantKey", "persona"])
data class PersonaIdentityConfigEntity(
    val tenantKey: String,
    val persona: String,
    val customName: String,
    val identitySubtitleEn: String,
    val identitySubtitleFa: String,
    val roleSummaryEn: String,
    val roleSummaryFa: String,
    val responsibilitiesCsv: String,
    val warmth: Double,
    val formality: Double,
    val directness: Double,
    val empathy: Double,
    val technicalDepth: Double,
    val communicationStyleEn: String,
    val communicationStyleFa: String,
    val vocabularyNotesCsv: String,
    val knowledgeScopeTagsCsv: String,
    val behavioralInstructions: String,
    val voiceProfileName: String,
    val voiceGender: String,
    val pitchOffset: Double,
    val speakingRate: Double,
    val preferredLocaleTag: String,
    val authorizedVoiceAssetId: String?,
    val avatarProfileId: String,
    val distinctFaceIdentityId: String?,
    val clothingStyle: String,
    val appearanceNotes: String,
    val visualStylePreset: String,
    val presentationMode: String,
    val animationIntensity: Double,
    val idleMotionEnabled: Boolean,
    val authorizedPhotoAssetIdsCsv: String,
    val authorizedVideoAssetIdsCsv: String,
    val preferredLanguage: String,
    val responseLength: String,
    val proactivityLevel: Double,
    val confirmBeforeExternalActions: Boolean,
    val securityRoleAnchor: String,
    val minimumDataClassification: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "persona_identity_revisions")
data class PersonaIdentityRevisionEntity(
    @PrimaryKey val id: String,
    val tenantKey: String,
    val persona: String,
    val version: Int,
    val configurationJson: String,
    val changeSummary: String,
    val changedAtEpochMs: Long,
)

@Entity(tableName = "identity_media_assets")
data class IdentityMediaAssetEntity(
    @PrimaryKey val id: String,
    val ownerTenantKey: String,
    val kind: String,
    val privateVaultAlias: String,
    val mimeType: String,
    val distinctFaceIdentityId: String?,
    val durationMs: Long?,
    val explicitLikenessOrVoiceAuthorized: Boolean,
    val subjectAgeStatus: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "digital_twin_profiles")
data class DigitalTwinProfileEntity(
    @PrimaryKey val tenantKey: String,
    val gender: String,
    val likenessAuthorized: Boolean,
    val voiceAuthorized: Boolean,
    val subjectAgeStatus: String,
    val authorizedPhotoAssetIdsCsv: String,
    val authorizedVideoAssetIdsCsv: String,
    val authorizedVoiceAssetIdsCsv: String,
    val communicationStyleSummary: String?,
    val approvedVocabularyCsv: String,
    val approvedKnowledgeMemoryIdsCsv: String,
    val editablePersonalityJson: String?,
    val behaviorNotes: String?,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "customer_onboardings")
data class CustomerOnboardingEntity(
    @PrimaryKey val customerId: String,
    val displayName: String,
    val status: String,
    val subjectAgeStatus: String,
    val contentPolicyAccepted: Boolean,
    val likenessUseAuthorized: Boolean,
    val voiceUseAuthorized: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "shared_memories")
data class SharedMemoryEntity(
    @PrimaryKey val id: String,
    val tenantKey: String,
    val layer: String,
    val contentSummary: String,
    val dataClassification: String,
    val sourcePersona: String,
    val sourceKind: String,
    val sourceReferenceId: String,
    val lastReviewedByOwner: Boolean,
    val recordedAtEpochMs: Long,
    val confidence: Double,
    val reviewStatus: String,
    val ownerCorrectionNote: String?,
    val projectId: String?,
    val preferenceKey: String?,
    val verifiedExperienceCount: Int,
    val updatedAtEpochMs: Long,
)
