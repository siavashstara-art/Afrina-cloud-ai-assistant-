package com.example.afrina.core.persona

import com.example.afrina.core.data.local.AuditLogDao
import com.example.afrina.core.data.local.AuditLogEntity
import com.example.afrina.core.domain.PersonaIdentityCustomizationRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.DataClassification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Configurable Persona Identity, Avatar, Voice, Digital Twin & Multi-Tenant Customer Customization Engine.
 *
 * Key invariants enforced:
 * 1. Independent per-persona configuration for all 4 personas across Master & isolated Customer editions.
 * 2. Strict separation between identity/avatar appearance changes and memories, permissions, or security roles.
 * 3. Every personality/voice/avatar update creates an immutable versioned [PersonaIdentityRevision] with rollback.
 * 4. Strict tenant isolation: Customer assets, memories, and twin profiles cannot leak across customers or Master.
 * 5. Explicit likeness/voice consent + 18+ age & content safety enforcement; metadata-only audit logs.
 */
class PersonaIdentityEngine(
    private val masterMemoryManager: SharedMemoryManager = SharedMemoryManager(),
    private val auditLogDao: AuditLogDao? = null,
    private val defaultProfiles: Map<AfrinaPersona, PersonaProfile> = PersonaCatalog.defaultProfiles(),
) : PersonaIdentityCustomizationRepository {

    private class MutableTenantState(
        val tenantScope: TenantScope,
        val memoryManager: SharedMemoryManager,
        var onboarding: CustomerOnboardingProfile? = null,
        val configs: MutableMap<AfrinaPersona, PersonaIdentityConfiguration> = mutableMapOf(),
        val revisions: MutableMap<AfrinaPersona, MutableList<PersonaIdentityRevision>> = mutableMapOf(),
        val assets: MutableList<IdentityMediaAsset> = mutableListOf(),
        var twinProfile: DigitalTwinCustomizationProfile = DigitalTwinCustomizationProfile(tenantKey = tenantScope.key),
    )

    private val mutex = Mutex()
    private val tenants = linkedMapOf<String, MutableTenantState>()
    private val snapshotsFlow = MutableStateFlow<Map<String, TenantPersonaWorkspaceSnapshot>>(emptyMap())

    init {
        val master = createInitialTenantState(TenantScope.MasterOwner, masterMemoryManager, onboarding = null)
        tenants[TenantScope.MasterOwner.key] = master
        snapshotsFlow.value = mapOf(TenantScope.MasterOwner.key to master.toSnapshot())
    }

    override fun observeTenantWorkspace(tenantScope: TenantScope): Flow<TenantPersonaWorkspaceSnapshot> =
        snapshotsFlow.map { all ->
            all[tenantScope.key] ?: TenantPersonaWorkspaceSnapshot(
                tenantScope = tenantScope,
                edition = tenantScope.edition,
                digitalTwinProfile = DigitalTwinCustomizationProfile(tenantKey = tenantScope.key),
            )
        }

    override suspend fun onboardCustomer(
        customerId: String,
        displayName: String,
        subjectAgeStatus: SubjectAgePolicyStatus,
        contentPolicyAccepted: Boolean,
        likenessUseAuthorized: Boolean,
        voiceUseAuthorized: Boolean,
    ): Result<CustomerOnboardingProfile> {
        val scope = runCatching { TenantScope.Customer(customerId) }
            .getOrElse { return Result.failure(it) }
        if (displayName.isBlank()) {
            return Result.failure(IllegalArgumentException("Customer display name must be non-blank."))
        }
        ContentAndLikenessPolicy.validateTextContent(displayName, "customerDisplayName")
            .onFailure { return Result.failure(it) }
        if (!contentPolicyAccepted) {
            return Result.failure(SecurityException("Customer onboarding requires accepting AFRINA age and content policies."))
        }
        if (subjectAgeStatus == SubjectAgePolicyStatus.MINOR_RESTRICTED) {
            return Result.failure(SecurityException("Digital twin and likeness customization is restricted for minors."))
        }

        val profile = mutex.withLock {
            val now = System.currentTimeMillis()
            val onboarding = CustomerOnboardingProfile(
                customerId = scope.customerId,
                displayName = displayName.trim(),
                status = CustomerOnboardingStatus.ACTIVE_CUSTOMIZABLE,
                subjectAgeStatus = subjectAgeStatus,
                contentPolicyAccepted = true,
                likenessUseAuthorized = likenessUseAuthorized,
                voiceUseAuthorized = voiceUseAuthorized,
                createdAtEpochMs = now,
            )
            val existing = tenants[scope.key]
            if (existing != null) {
                existing.onboarding = onboarding
                publishLocked()
            } else {
                // Dedicated isolated SharedMemoryManager per customer tenant
                val fresh = createInitialTenantState(scope, SharedMemoryManager(), onboarding)
                tenants[scope.key] = fresh
                publishLocked()
            }
            onboarding
        }

        recordAudit(
            actor = scope.key,
            action = "CUSTOMER_ONBOARDED",
            target = "CUSTOMER_STANDARD",
            outcome = profile.status.name,
        )
        return Result.success(profile)
    }

    override suspend fun registerAuthorizedMediaAsset(
        tenantScope: TenantScope,
        kind: MediaAssetKind,
        privateVaultAlias: String,
        mimeType: String,
        distinctFaceIdentityId: String?,
        durationMs: Long?,
        explicitLikenessOrVoiceAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
    ): Result<IdentityMediaAsset> {
        ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
            explicitAuthorized = explicitLikenessOrVoiceAuthorized,
            subjectAgeStatus = subjectAgeStatus,
            assetKindLabel = kind.name,
        ).onFailure { return Result.failure(it) }

        if (privateVaultAlias.isBlank() || mimeType.isBlank()) {
            return Result.failure(IllegalArgumentException("Private vault alias and MIME type must be non-blank."))
        }

        val asset = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            val created = IdentityMediaAsset(
                id = "ast-${UUID.randomUUID().toString().take(8)}",
                ownerTenantKey = tenantScope.key,
                kind = kind,
                privateVaultAlias = privateVaultAlias.trim(),
                mimeType = mimeType.trim(),
                distinctFaceIdentityId = distinctFaceIdentityId?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = durationMs,
                explicitLikenessOrVoiceAuthorized = true,
                subjectAgeStatus = subjectAgeStatus,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            tenant.assets += created
            publishLocked()
            created
        }

        recordAudit(
            actor = tenantScope.key,
            action = "IDENTITY_MEDIA_REGISTERED",
            target = kind.name,
            outcome = "AUTHORIZED_ISOLATED",
        )
        return Result.success(asset)
    }

    override suspend fun updatePersonaIdentity(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        customName: String?,
        identitySubtitleEn: String?,
        identitySubtitleFa: String?,
        roleSummaryEn: String?,
        roleSummaryFa: String?,
        responsibilities: List<String>?,
        personality: PersonalityTraitsConfig?,
        knowledgeScopeTags: List<String>?,
        behavioralInstructions: String?,
        interactionPreferences: InteractionPreferencesConfig?,
        changeSummary: String,
    ): Result<PersonaIdentityRevision> {
        val textFields = listOfNotNull(
            customName?.let { "customName" to it },
            identitySubtitleEn?.let { "identitySubtitleEn" to it },
            identitySubtitleFa?.let { "identitySubtitleFa" to it },
            roleSummaryEn?.let { "roleSummaryEn" to it },
            roleSummaryFa?.let { "roleSummaryFa" to it },
            behavioralInstructions?.let { "behavioralInstructions" to it },
            personality?.communicationStyleEn?.let { "communicationStyleEn" to it },
            personality?.communicationStyleFa?.let { "communicationStyleFa" to it },
        )
        for ((field, value) in textFields) {
            ContentAndLikenessPolicy.validateTextContent(value, field).onFailure { return Result.failure(it) }
        }

        val revision = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            val current = tenant.configs.getValue(persona)
            val now = System.currentTimeMillis()
            val sanitizedName = customName?.trim()?.takeIf { it.isNotEmpty() } ?: current.customName
            val sanitizedPersonality = personality?.copy(
                warmth = personality.warmth.coerceIn(0.0, 1.0),
                formality = personality.formality.coerceIn(0.0, 1.0),
                directness = personality.directness.coerceIn(0.0, 1.0),
                empathy = personality.empathy.coerceIn(0.0, 1.0),
                technicalDepth = personality.technicalDepth.coerceIn(0.0, 1.0),
            ) ?: current.personality

            // Immutable invariants preserved: securityRoleAnchor and minimumDataClassification
            val updated = current.copy(
                customName = sanitizedName,
                identitySubtitleEn = identitySubtitleEn?.trim()?.takeIf { it.isNotEmpty() } ?: current.identitySubtitleEn,
                identitySubtitleFa = identitySubtitleFa?.trim()?.takeIf { it.isNotEmpty() } ?: current.identitySubtitleFa,
                roleSummaryEn = roleSummaryEn?.trim()?.takeIf { it.isNotEmpty() } ?: current.roleSummaryEn,
                roleSummaryFa = roleSummaryFa?.trim()?.takeIf { it.isNotEmpty() } ?: current.roleSummaryFa,
                responsibilities = responsibilities?.map { it.trim() }?.filter { it.isNotEmpty() } ?: current.responsibilities,
                personality = sanitizedPersonality,
                knowledgeScopeTags = knowledgeScopeTags?.map { it.trim() }?.filter { it.isNotEmpty() } ?: current.knowledgeScopeTags,
                behavioralInstructions = behavioralInstructions?.trim()?.takeIf { it.isNotEmpty() } ?: current.behavioralInstructions,
                interactionPreferences = interactionPreferences?.copy(
                    proactivityLevel = interactionPreferences.proactivityLevel.coerceIn(0.0, 1.0),
                ) ?: current.interactionPreferences,
                securityRoleAnchor = current.securityRoleAnchor,
                minimumDataClassification = current.minimumDataClassification,
                updatedAtEpochMs = now,
            )
            appendRevisionLocked(tenant, persona, updated, changeSummary.ifBlank { "Identity update" }, now)
        }

        recordAudit(
            actor = tenantScope.key,
            action = "PERSONA_IDENTITY_UPDATED",
            target = "${persona.name}@v${revision.version}",
            outcome = "REVISION_CREATED",
        )
        return Result.success(revision)
    }

    override suspend fun updateVoiceConfig(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        voiceConfig: VoiceCustomizationConfig,
        explicitVoiceAuthorized: Boolean,
        changeSummary: String,
    ): Result<PersonaIdentityRevision> {
        ContentAndLikenessPolicy.validateTextContent(voiceConfig.voiceProfileName, "voiceProfileName")
            .onFailure { return Result.failure(it) }

        val revision = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            val sampleId = voiceConfig.authorizedVoiceAssetId
            if (sampleId != null) {
                if (!explicitVoiceAuthorized) {
                    return Result.failure(SecurityException("Explicit voice authorization required to link a voice sample."))
                }
                val ownedSample = tenant.assets.firstOrNull {
                    it.id == sampleId &&
                        it.ownerTenantKey == tenantScope.key &&
                        it.kind == MediaAssetKind.VOICE_SAMPLE &&
                        it.explicitLikenessOrVoiceAuthorized
                } ?: return Result.failure(
                    SecurityException("Voice sample '$sampleId' not found or unauthorized within tenant scope.")
                )
                ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                    explicitAuthorized = true,
                    subjectAgeStatus = ownedSample.subjectAgeStatus,
                    assetKindLabel = "VOICE_SAMPLE",
                ).onFailure { return Result.failure(it) }
            }

            val current = tenant.configs.getValue(persona)
            val now = System.currentTimeMillis()
            val sanitizedVoice = voiceConfig.copy(
                voiceProfileName = voiceConfig.voiceProfileName.trim().ifBlank { current.voiceConfig.voiceProfileName },
                pitchOffset = voiceConfig.pitchOffset.coerceIn(-0.5, 0.5),
                speakingRate = voiceConfig.speakingRate.coerceIn(0.5, 1.8),
                requiresExplicitVoiceAuthorization = true,
            )
            val updated = current.copy(
                voiceConfig = sanitizedVoice,
                updatedAtEpochMs = now,
            )
            appendRevisionLocked(tenant, persona, updated, changeSummary.ifBlank { "Voice customization" }, now)
        }

        recordAudit(
            actor = tenantScope.key,
            action = "PERSONA_VOICE_UPDATED",
            target = "${persona.name}@v${revision.version}",
            outcome = "REVISION_CREATED",
        )
        return Result.success(revision)
    }

    override suspend fun updateAvatarConfig(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        avatarConfig: AvatarCustomizationConfig,
        explicitLikenessAuthorized: Boolean,
        changeSummary: String,
    ): Result<PersonaIdentityRevision> {
        ContentAndLikenessPolicy.validateTextContent(
            "${avatarConfig.clothingStyle} ${avatarConfig.appearanceNotes}",
            "avatarAppearance",
        ).onFailure { return Result.failure(it) }

        val revision = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            val referencedMediaIds = avatarConfig.authorizedPhotoAssetIds + avatarConfig.authorizedVideoAssetIds
            if (referencedMediaIds.isNotEmpty()) {
                if (!explicitLikenessAuthorized) {
                    return Result.failure(SecurityException("Explicit likeness authorization required to attach photo/video avatar assets."))
                }
                for (assetId in avatarConfig.authorizedPhotoAssetIds) {
                    val asset = tenant.assets.firstOrNull {
                        it.id == assetId &&
                            it.ownerTenantKey == tenantScope.key &&
                            it.kind == MediaAssetKind.PHOTOGRAPH &&
                            it.explicitLikenessOrVoiceAuthorized
                    } ?: return Result.failure(
                        SecurityException("Photo asset '$assetId' not found or unauthorized inside tenant scope.")
                    )
                    ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                        explicitAuthorized = true,
                        subjectAgeStatus = asset.subjectAgeStatus,
                        assetKindLabel = "PHOTOGRAPH",
                    ).onFailure { return Result.failure(it) }
                }
                for (assetId in avatarConfig.authorizedVideoAssetIds) {
                    val asset = tenant.assets.firstOrNull {
                        it.id == assetId &&
                            it.ownerTenantKey == tenantScope.key &&
                            it.kind == MediaAssetKind.VIDEO_REFERENCE &&
                            it.explicitLikenessOrVoiceAuthorized
                    } ?: return Result.failure(
                        SecurityException("Video reference '$assetId' not found or unauthorized inside tenant scope.")
                    )
                    ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                        explicitAuthorized = true,
                        subjectAgeStatus = asset.subjectAgeStatus,
                        assetKindLabel = "VIDEO_REFERENCE",
                    ).onFailure { return Result.failure(it) }
                }
            }

            // Appearance change ONLY creates a new identity revision; memories, permissions & security role remain untouched.
            val current = tenant.configs.getValue(persona)
            val now = System.currentTimeMillis()
            val updated = current.copy(
                avatarConfig = avatarConfig.copy(
                    animationIntensity = avatarConfig.animationIntensity.coerceIn(0.0, 1.0),
                ),
                securityRoleAnchor = current.securityRoleAnchor,
                minimumDataClassification = current.minimumDataClassification,
                updatedAtEpochMs = now,
            )
            appendRevisionLocked(tenant, persona, updated, changeSummary.ifBlank { "Avatar customization" }, now)
        }

        recordAudit(
            actor = tenantScope.key,
            action = "PERSONA_AVATAR_UPDATED",
            target = "${persona.name}@v${revision.version}",
            outcome = "MEMORY_UNTOUCHED",
        )
        return Result.success(revision)
    }

    override suspend fun rollbackPersonaConfiguration(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        targetVersion: Int,
    ): Result<PersonaIdentityRevision> {
        val revision = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            val history = tenant.revisions[persona].orEmpty()
            val targetRevision = history.firstOrNull { it.version == targetVersion }
                ?: return Result.failure(IllegalArgumentException("Revision v$targetVersion not found for ${persona.name}."))
            val now = System.currentTimeMillis()
            val restoredConfig = targetRevision.configuration.copy(updatedAtEpochMs = now)
            appendRevisionLocked(
                tenant = tenant,
                persona = persona,
                config = restoredConfig,
                changeSummary = "Rollback to v$targetVersion",
                now = now,
            )
        }

        recordAudit(
            actor = tenantScope.key,
            action = "PERSONA_IDENTITY_ROLLBACK",
            target = "${persona.name}->v$targetVersion (new v${revision.version})",
            outcome = "ROLLED_BACK_SAFELY",
        )
        return Result.success(revision)
    }

    override suspend fun updateDigitalTwinProfile(
        tenantScope: TenantScope,
        likenessAuthorized: Boolean,
        voiceAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
        authorizedPhotoAssetIds: List<String>,
        authorizedVideoAssetIds: List<String>,
        authorizedVoiceAssetIds: List<String>,
        communicationStyleSummary: String?,
        approvedVocabulary: List<String>,
        approvedKnowledgeMemoryIds: List<String>,
        editablePersonality: PersonalityTraitsConfig?,
        behaviorNotes: String?,
    ): Result<DigitalTwinCustomizationProfile> {
        communicationStyleSummary?.let {
            ContentAndLikenessPolicy.validateTextContent(it, "twinCommunicationStyle")
                .onFailure { err -> return Result.failure(err) }
        }
        behaviorNotes?.let {
            ContentAndLikenessPolicy.validateTextContent(it, "twinBehaviorNotes")
                .onFailure { err -> return Result.failure(err) }
        }

        if (authorizedPhotoAssetIds.isNotEmpty() || authorizedVideoAssetIds.isNotEmpty()) {
            ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                explicitAuthorized = likenessAuthorized,
                subjectAgeStatus = subjectAgeStatus,
                assetKindLabel = "DIGITAL_TWIN_VISUAL_ASSETS",
            ).onFailure { return Result.failure(it) }
        }
        if (authorizedVoiceAssetIds.isNotEmpty()) {
            ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                explicitAuthorized = voiceAuthorized,
                subjectAgeStatus = subjectAgeStatus,
                assetKindLabel = "DIGITAL_TWIN_VOICE_ASSETS",
            ).onFailure { return Result.failure(it) }
        }

        val updatedProfile = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            // Verify every referenced asset belongs strictly to this tenant
            val validAssetIds = tenant.assets.associateBy { it.id }
            for (id in authorizedPhotoAssetIds) {
                val asset = validAssetIds[id]
                if (asset == null || asset.kind != MediaAssetKind.PHOTOGRAPH) {
                    return Result.failure(SecurityException("Photo asset '$id' is missing or outside tenant isolation boundary."))
                }
            }
            for (id in authorizedVideoAssetIds) {
                val asset = validAssetIds[id]
                if (asset == null || asset.kind != MediaAssetKind.VIDEO_REFERENCE) {
                    return Result.failure(SecurityException("Video asset '$id' is missing or outside tenant isolation boundary."))
                }
            }
            for (id in authorizedVoiceAssetIds) {
                val asset = validAssetIds[id]
                if (asset == null || asset.kind != MediaAssetKind.VOICE_SAMPLE) {
                    return Result.failure(SecurityException("Voice asset '$id' is missing or outside tenant isolation boundary."))
                }
            }
            // Verify approved knowledge memory IDs belong strictly to this tenant's memory partition
            val tenantMemoryIds = tenant.memoryManager.observeMemories().value
                .filter { it.reviewStatus != MemoryReviewStatus.OWNER_DISPUTED }
                .map { it.id }
                .toSet()
            for (memId in approvedKnowledgeMemoryIds) {
                if (memId !in tenantMemoryIds) {
                    return Result.failure(SecurityException("Memory '$memId' is missing, disputed, or outside tenant isolation boundary."))
                }
            }

            val profile = DigitalTwinCustomizationProfile(
                tenantKey = tenantScope.key,
                gender = GenderPresentation.MALE,
                likenessAuthorized = likenessAuthorized && subjectAgeStatus == SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
                voiceAuthorized = voiceAuthorized && subjectAgeStatus == SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
                subjectAgeStatus = subjectAgeStatus,
                authorizedPhotoAssetIds = authorizedPhotoAssetIds.distinct(),
                authorizedVideoAssetIds = authorizedVideoAssetIds.distinct(),
                authorizedVoiceAssetIds = authorizedVoiceAssetIds.distinct(),
                communicationStyleSummary = communicationStyleSummary?.trim()?.takeIf { it.isNotEmpty() },
                approvedVocabulary = approvedVocabulary.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                approvedKnowledgeMemoryIds = approvedKnowledgeMemoryIds.distinct(),
                editablePersonality = editablePersonality,
                behaviorNotes = behaviorNotes?.trim()?.takeIf { it.isNotEmpty() },
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            tenant.twinProfile = profile
            publishLocked()
            profile
        }

        recordAudit(
            actor = tenantScope.key,
            action = "DIGITAL_TWIN_PROFILE_UPDATED",
            target = "GUARDIAN_PRIVATE_TWIN",
            outcome = "ISOLATED_SAVED",
        )
        return Result.success(updatedProfile)
    }

    override suspend fun recordTenantMemory(
        tenantScope: TenantScope,
        layer: MemoryLayer,
        contentSummary: String,
        sourcePersona: AfrinaPersona,
        dataClassification: DataClassification,
        projectId: String?,
        ownerDirect: Boolean,
    ): Result<SharedMemoryEntry> {
        ContentAndLikenessPolicy.validateTextContent(contentSummary, "memoryContent")
            .onFailure { return Result.failure(it) }

        val entry = mutex.withLock {
            val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
            val created = tenant.memoryManager.recordMemory(
                layer = layer,
                contentSummary = contentSummary,
                sourcePersona = sourcePersona,
                sourceKind = if (ownerDirect) MemorySourceKind.OWNER_DIRECT else MemorySourceKind.INTERACTION,
                sourceReferenceId = "${tenantScope.key}:${UUID.randomUUID().toString().take(8)}",
                dataClassification = dataClassification,
                initialConfidence = if (ownerDirect) 0.90 else 0.65,
                projectId = projectId,
                ownerDirect = ownerDirect,
            )
            publishLocked()
            created
        }
        return Result.success(entry)
    }

    override suspend fun queryTenantMemories(
        tenantScope: TenantScope,
        includeSensitiveOwnerData: Boolean,
    ): List<SharedMemoryEntry> = mutex.withLock {
        val tenant = tenants[tenantScope.key] ?: return emptyList()
        tenant.memoryManager.queryMemories(includeSensitiveOwnerData = includeSensitiveOwnerData)
    }

    override suspend fun evaluateCustomerWorkspaceAction(
        tenantScope: TenantScope,
        capability: CustomerWorkspaceCapability,
    ): DevelopmentPermissionDecision {
        val decision = ContentAndLikenessPolicy.evaluateCustomerWorkspaceCapability(
            edition = tenantScope.edition,
            capability = capability,
        )
        recordAudit(
            actor = tenantScope.key,
            action = "TENANT_CAPABILITY_CHECK",
            target = capability.name,
            outcome = if (decision is DevelopmentPermissionDecision.Allowed) "ALLOWED" else "DENIED",
        )
        return decision
    }

    private fun requireActiveTenantLocked(tenantScope: TenantScope): Result<MutableTenantState> {
        val tenant = tenants[tenantScope.key]
            ?: return Result.failure(IllegalStateException("Tenant '${tenantScope.key}' is not initialized or onboarded."))
        if (tenantScope is TenantScope.Customer && tenant.onboarding?.status != CustomerOnboardingStatus.ACTIVE_CUSTOMIZABLE) {
            return Result.failure(SecurityException("Customer tenant '${tenantScope.customerId}' is not active."))
        }
        return Result.success(tenant)
    }

    private fun appendRevisionLocked(
        tenant: MutableTenantState,
        persona: AfrinaPersona,
        config: PersonaIdentityConfiguration,
        changeSummary: String,
        now: Long,
    ): PersonaIdentityRevision {
        val list = tenant.revisions.getOrPut(persona) { mutableListOf() }
        val nextVersion = (list.maxOfOrNull { it.version } ?: 0) + 1
        val revision = PersonaIdentityRevision(
            version = nextVersion,
            persona = persona,
            tenantKey = tenant.tenantScope.key,
            configuration = config,
            changeSummary = changeSummary,
            changedAtEpochMs = now,
        )
        tenant.configs[persona] = config
        list += revision
        publishLocked()
        return revision
    }

    private fun createInitialTenantState(
        scope: TenantScope,
        memoryManager: SharedMemoryManager,
        onboarding: CustomerOnboardingProfile?,
    ): MutableTenantState {
        val state = MutableTenantState(
            tenantScope = scope,
            memoryManager = memoryManager,
            onboarding = onboarding,
        )
        val now = System.currentTimeMillis()
        for (persona in AfrinaPersona.entries) {
            val base = defaultProfiles.getValue(persona)
            val initialConfig = defaultConfigForPersona(scope, base, now)
            state.configs[persona] = initialConfig
            state.revisions[persona] = mutableListOf(
                PersonaIdentityRevision(
                    version = 1,
                    persona = persona,
                    tenantKey = scope.key,
                    configuration = initialConfig,
                    changeSummary = "Initial Phase 2 baseline identity",
                    changedAtEpochMs = now,
                )
            )
        }
        return state
    }

    private fun defaultConfigForPersona(
        scope: TenantScope,
        base: PersonaProfile,
        now: Long,
    ): PersonaIdentityConfiguration {
        val visualPreset = when (base.persona) {
            AfrinaPersona.ARCHITECT_STRATEGIST -> VisualStylePreset.ARCHITECTURAL_MINIMAL
            AfrinaPersona.BUILDER_ENGINEER -> VisualStylePreset.EXECUTIVE_CHARCOAL
            AfrinaPersona.COMPANION_EMPATHY -> VisualStylePreset.WARM_EDITORIAL
            AfrinaPersona.GUARDIAN_SENTINEL -> VisualStylePreset.TERRACOTTA_ATELIER
        }
        val clothing = when (base.persona) {
            AfrinaPersona.ARCHITECT_STRATEGIST -> "Structured linen blazer & warm ivory collar"
            AfrinaPersona.BUILDER_ENGINEER -> "Minimalist charcoal studio knitwear"
            AfrinaPersona.COMPANION_EMPATHY -> "Soft earth-toned casual attire"
            AfrinaPersona.GUARDIAN_SENTINEL -> "Tailored warm terracotta/charcoal formalwear (Owner Twin)"
        }
        return PersonaIdentityConfiguration(
            persona = base.persona,
            tenantKey = scope.key,
            customName = base.canonicalName,
            identitySubtitleEn = base.titleEn,
            identitySubtitleFa = base.titleFa,
            roleSummaryEn = base.roleEn,
            roleSummaryFa = base.roleFa,
            responsibilities = listOf(base.persona.focusEn),
            personality = PersonalityTraitsConfig(
                warmth = base.behavioralConfig.emotionalAwarenessLevel,
                formality = if (base.persona == AfrinaPersona.GUARDIAN_SENTINEL) 0.75 else 0.55,
                directness = 0.70,
                empathy = base.behavioralConfig.emotionalAwarenessLevel,
                technicalDepth = base.behavioralConfig.technicalRigorLevel,
                communicationStyleEn = base.behavioralConfig.communicationToneEn,
                communicationStyleFa = base.behavioralConfig.communicationToneFa,
            ),
            knowledgeScopeTags = listOf(base.persona.name.lowercase(), "afrina_shared_core"),
            behavioralInstructions = base.systemInstructions,
            voiceConfig = VoiceCustomizationConfig(
                voiceProfileName = "${base.canonicalName} Default Voice",
                genderPresentation = base.genderPresentation,
                preferredLocaleTag = "fa-IR",
            ),
            avatarConfig = AvatarCustomizationConfig(
                avatarProfileId = "av-${base.persona.name.lowercase()}",
                distinctFaceIdentityId = "face-${scope.key}-${base.persona.name.lowercase()}",
                clothingStyle = clothing,
                appearanceNotes = "Prepared visual identity slot for ${base.canonicalName}",
                visualStylePreset = visualPreset,
                presentationMode = AvatarPresentationMode.STATIC_PORTRAIT,
            ),
            interactionPreferences = InteractionPreferencesConfig(
                preferredLanguage = AppLanguage.PERSIAN,
                proactivityLevel = base.behavioralConfig.proactivityLevel,
                confirmBeforeExternalActions = base.behavioralConfig.requiresOwnerApprovalForExternalActions,
            ),
            securityRoleAnchor = base.persona,
            minimumDataClassification = base.defaultDataClassification,
            updatedAtEpochMs = now,
        )
    }

    private fun MutableTenantState.toSnapshot(): TenantPersonaWorkspaceSnapshot =
        TenantPersonaWorkspaceSnapshot(
            tenantScope = tenantScope,
            edition = tenantScope.edition,
            currentConfigs = configs.toMap(),
            revisionsByPersona = revisions.mapValues { (_, list) -> list.toList() },
            mediaAssets = assets.toList(),
            digitalTwinProfile = twinProfile,
            customerOnboarding = onboarding,
            tenantMemoryCount = memoryManager.observeMemories().value.size,
        )

    private fun publishLocked() {
        snapshotsFlow.value = tenants.mapValues { (_, tenant) -> tenant.toSnapshot() }
    }

    private suspend fun recordAudit(
        actor: String,
        action: String,
        target: String,
        outcome: String,
    ) {
        auditLogDao?.insert(
            AuditLogEntity(
                id = "aud-${UUID.randomUUID().toString().take(8)}",
                actor = actor,
                action = action,
                target = target,
                outcome = outcome,
                timestampEpochMs = System.currentTimeMillis(),
            )
        )
    }
}
