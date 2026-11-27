package com.example.afrina.core.persona

import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.local.AuditLogDao
import com.example.afrina.core.data.local.AuditLogEntity
import com.example.afrina.core.data.local.CustomerOnboardingEntity
import com.example.afrina.core.data.local.DigitalTwinProfileEntity
import com.example.afrina.core.data.local.IdentityMediaAssetEntity
import com.example.afrina.core.data.local.PersonaIdentityConfigEntity
import com.example.afrina.core.data.local.PersonaIdentityRevisionEntity
import com.example.afrina.core.data.local.SharedMemoryEntity
import com.example.afrina.core.domain.PersonaIdentityCustomizationRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.DataClassification
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
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
    private val database: AfrinaDatabase? = null,
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

        if (database != null) {
            runBlocking(Dispatchers.IO) {
                val onboardings = database.customerOnboardingDao().getAll()
                for (onboardingEntity in onboardings) {
                    val scope = TenantScope.Customer(onboardingEntity.customerId)
                    val memManager = SharedMemoryManager()
                    val tenantState = loadTenantStateFromDb(scope, memManager, onboardingEntity.toDomain())
                    tenants[scope.key] = tenantState
                }
                loadExistingTenantStateIntoLocked(TenantScope.MasterOwner, master)
            }
        }

        snapshotsFlow.value = tenants.mapValues { (_, tenant) -> tenant.toSnapshot() }
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
            val fresh = if (existing == null) {
                createInitialTenantState(scope, SharedMemoryManager(), onboarding)
            } else null

            if (database != null) {
                database.withTransaction {
                    database.customerOnboardingDao().insert(onboarding.toEntity())
                    if (fresh != null) {
                        for (persona in AfrinaPersona.entries) {
                            val cfg = fresh.configs.getValue(persona)
                            database.personaIdentityDao().insert(cfg.toEntity())
                            val rev = fresh.revisions.getValue(persona).first()
                            database.personaIdentityDao().insertRevision(rev.toEntity())
                        }
                    }
                }
            }

            if (existing != null) {
                existing.onboarding = onboarding
            } else if (fresh != null) {
                tenants[scope.key] = fresh
            }
            publishLocked()
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
            if (database != null) {
                database.identityMediaAssetDao().insert(created.toEntity())
            }
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
            if (database != null) {
                database.digitalTwinDao().insert(profile.toEntity())
            }
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
            val now = System.currentTimeMillis()
            val created = SharedMemoryEntry(
                id = "mem-${UUID.randomUUID().toString().take(8)}",
                layer = layer,
                contentSummary = contentSummary.trim(),
                dataClassification = dataClassification,
                provenance = MemoryProvenance(
                    sourcePersona = sourcePersona,
                    sourceKind = if (ownerDirect) MemorySourceKind.OWNER_DIRECT else MemorySourceKind.INTERACTION,
                    sourceReferenceId = "${tenantScope.key}:${UUID.randomUUID().toString().take(8)}",
                    lastReviewedByOwner = ownerDirect,
                    recordedAtEpochMs = now,
                ),
                confidence = if (ownerDirect) 0.90 else 0.65,
                reviewStatus = if (ownerDirect) MemoryReviewStatus.OWNER_VERIFIED else MemoryReviewStatus.UNREVIEWED,
                projectId = projectId,
                preferenceKey = null,
                verifiedExperienceCount = if (ownerDirect) 1 else 0,
                updatedAtEpochMs = now,
            )
            if (database != null) {
                database.sharedMemoryDao().insert(created.toEntity(tenantScope.key))
            }
            tenant.memoryManager.restoreEntry(created)
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

    override suspend fun revokeMediaAuthorization(
        tenantScope: TenantScope,
        assetId: String,
    ): Result<Boolean> = mutex.withLock {
        val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
        val index = tenant.assets.indexOfFirst { it.id == assetId && it.ownerTenantKey == tenantScope.key }
        if (index == -1) return Result.failure(IllegalArgumentException("Asset $assetId not found in tenant scope"))
        val asset = tenant.assets[index]
        val revoked = asset.copy(explicitLikenessOrVoiceAuthorized = false)
        if (database != null) {
            database.identityMediaAssetDao().revokeAuthorization(assetId, tenantScope.key)
        }
        tenant.assets[index] = revoked
        publishLocked()
        recordAudit(tenantScope.key, "MEDIA_AUTHORIZATION_REVOKED", assetId, "REVOKED")
        Result.success(true)
    }

    override suspend fun deleteMediaAsset(
        tenantScope: TenantScope,
        assetId: String,
    ): Result<Boolean> = mutex.withLock {
        val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
        val exists = tenant.assets.any { it.id == assetId && it.ownerTenantKey == tenantScope.key }
        if (!exists) return Result.success(false)
        if (database != null) {
            database.identityMediaAssetDao().deleteById(assetId, tenantScope.key)
        }
        val removed = tenant.assets.removeIf { it.id == assetId && it.ownerTenantKey == tenantScope.key }
        if (removed) {
            publishLocked()
            recordAudit(tenantScope.key, "MEDIA_ASSET_DELETED", assetId, "DELETED")
        }
        Result.success(removed)
    }

    override suspend fun deleteTenantMemory(
        tenantScope: TenantScope,
        memoryId: String,
    ): Result<Boolean> = mutex.withLock {
        val tenant = requireActiveTenantLocked(tenantScope).getOrElse { return Result.failure(it) }
        if (database != null) {
            database.sharedMemoryDao().deleteById(memoryId)
        }
        val deleted = tenant.memoryManager.deleteMemoryByOwner(memoryId)
        if (deleted) {
            publishLocked()
            recordAudit(tenantScope.key, "TENANT_MEMORY_DELETED", memoryId, "DELETED")
        }
        Result.success(deleted)
    }

    private fun requireActiveTenantLocked(tenantScope: TenantScope): Result<MutableTenantState> {
        val tenant = tenants[tenantScope.key]
            ?: return Result.failure(IllegalStateException("Tenant '${tenantScope.key}' is not initialized or onboarded."))
        if (tenantScope is TenantScope.Customer && tenant.onboarding?.status != CustomerOnboardingStatus.ACTIVE_CUSTOMIZABLE) {
            return Result.failure(SecurityException("Customer tenant '${tenantScope.customerId}' is not active."))
        }
        return Result.success(tenant)
    }

    private suspend fun appendRevisionLocked(
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
        if (database != null) {
            database.withTransaction {
                database.personaIdentityDao().insert(config.toEntity())
                database.personaIdentityDao().insertRevision(revision.toEntity())
            }
        }
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

    private suspend fun loadTenantStateFromDb(
        scope: TenantScope,
        memoryManager: SharedMemoryManager,
        onboarding: CustomerOnboardingProfile?,
    ): MutableTenantState {
        val state = MutableTenantState(
            tenantScope = scope,
            memoryManager = memoryManager,
            onboarding = onboarding,
        )
        val db = database ?: return state
        val tenantKey = scope.key

        val dbAssets = db.identityMediaAssetDao().getForTenant(tenantKey)
        state.assets.addAll(dbAssets.map { it.toDomain() })

        val dbTwin = db.digitalTwinDao().getForTenant(tenantKey)
        if (dbTwin != null) {
            state.twinProfile = dbTwin.toDomain()
        }

        val dbMemories = db.sharedMemoryDao().getForTenant(tenantKey)
        for (memEntity in dbMemories.reversed()) {
            memoryManager.restoreEntry(memEntity.toDomain())
        }

        val dbConfigs = db.personaIdentityDao().getForTenant(tenantKey)
        if (dbConfigs.isNotEmpty()) {
            for (cfgEntity in dbConfigs) {
                val persona = AfrinaPersona.valueOf(cfgEntity.persona)
                val config = cfgEntity.toDomain()
                state.configs[persona] = config
                val revs = db.personaIdentityDao().getRevisions(tenantKey, persona.name)
                if (revs.isNotEmpty()) {
                    state.revisions[persona] = revs.map { revEntity ->
                        PersonaIdentityRevision(
                            version = revEntity.version,
                            persona = persona,
                            tenantKey = tenantKey,
                            configuration = revEntity.configurationJson.toConfigurationOrNull() ?: config,
                            changeSummary = revEntity.changeSummary,
                            changedAtEpochMs = revEntity.changedAtEpochMs,
                        )
                    }.toMutableList()
                } else {
                    state.revisions[persona] = mutableListOf(
                        PersonaIdentityRevision(
                            version = 1,
                            persona = persona,
                            tenantKey = tenantKey,
                            configuration = config,
                            changeSummary = "Restored from DB",
                            changedAtEpochMs = config.updatedAtEpochMs,
                        )
                    )
                }
            }
        } else {
            val now = System.currentTimeMillis()
            for (persona in AfrinaPersona.entries) {
                val base = defaultProfiles.getValue(persona)
                val initialConfig = defaultConfigForPersona(scope, base, now)
                state.configs[persona] = initialConfig
                state.revisions[persona] = mutableListOf(
                    PersonaIdentityRevision(
                        version = 1,
                        persona = persona,
                        tenantKey = tenantKey,
                        configuration = initialConfig,
                        changeSummary = "Initial Phase 2 baseline identity",
                        changedAtEpochMs = now,
                    )
                )
            }
        }
        return state
    }

    private suspend fun loadExistingTenantStateIntoLocked(scope: TenantScope, state: MutableTenantState) {
        val db = database ?: return
        val tenantKey = scope.key
        val dbAssets = db.identityMediaAssetDao().getForTenant(tenantKey)
        if (dbAssets.isNotEmpty()) {
            state.assets.clear()
            state.assets.addAll(dbAssets.map { it.toDomain() })
        }
        val dbTwin = db.digitalTwinDao().getForTenant(tenantKey)
        if (dbTwin != null) {
            state.twinProfile = dbTwin.toDomain()
        }
        val dbMemories = db.sharedMemoryDao().getForTenant(tenantKey)
        if (dbMemories.isNotEmpty()) {
            for (memEntity in dbMemories.reversed()) {
                state.memoryManager.restoreEntry(memEntity.toDomain())
            }
        }
        val dbConfigs = db.personaIdentityDao().getForTenant(tenantKey)
        if (dbConfigs.isNotEmpty()) {
            for (cfgEntity in dbConfigs) {
                val persona = AfrinaPersona.valueOf(cfgEntity.persona)
                val config = cfgEntity.toDomain()
                state.configs[persona] = config
                val revs = db.personaIdentityDao().getRevisions(tenantKey, persona.name)
                if (revs.isNotEmpty()) {
                    state.revisions[persona] = revs.map { revEntity ->
                        PersonaIdentityRevision(
                            version = revEntity.version,
                            persona = persona,
                            tenantKey = tenantKey,
                            configuration = revEntity.configurationJson.toConfigurationOrNull() ?: config,
                            changeSummary = revEntity.changeSummary,
                            changedAtEpochMs = revEntity.changedAtEpochMs,
                        )
                    }.toMutableList()
                } else {
                    state.revisions[persona] = mutableListOf(
                        PersonaIdentityRevision(
                            version = 1,
                            persona = persona,
                            tenantKey = tenantKey,
                            configuration = config,
                            changeSummary = "Restored from DB",
                            changedAtEpochMs = config.updatedAtEpochMs,
                        )
                    )
                }
            }
        } else {
            // First run on new DB: seed initial baseline into Room DB!
            db.withTransaction {
                for (persona in AfrinaPersona.entries) {
                    val cfg = state.configs.getValue(persona)
                    db.personaIdentityDao().insert(cfg.toEntity())
                    val rev = state.revisions.getValue(persona).first()
                    db.personaIdentityDao().insertRevision(rev.toEntity())
                }
            }
        }
    }
}

private fun CustomerOnboardingProfile.toEntity(): CustomerOnboardingEntity = CustomerOnboardingEntity(
    customerId = customerId,
    displayName = displayName,
    status = status.name,
    subjectAgeStatus = subjectAgeStatus.name,
    contentPolicyAccepted = contentPolicyAccepted,
    likenessUseAuthorized = likenessUseAuthorized,
    voiceUseAuthorized = voiceUseAuthorized,
    createdAtEpochMs = createdAtEpochMs,
)

private fun CustomerOnboardingEntity.toDomain(): CustomerOnboardingProfile = CustomerOnboardingProfile(
    customerId = customerId,
    displayName = displayName,
    status = CustomerOnboardingStatus.valueOf(status),
    subjectAgeStatus = SubjectAgePolicyStatus.valueOf(subjectAgeStatus),
    contentPolicyAccepted = contentPolicyAccepted,
    likenessUseAuthorized = likenessUseAuthorized,
    voiceUseAuthorized = voiceUseAuthorized,
    createdAtEpochMs = createdAtEpochMs,
)

private fun IdentityMediaAsset.toEntity(): IdentityMediaAssetEntity = IdentityMediaAssetEntity(
    id = id,
    ownerTenantKey = ownerTenantKey,
    kind = kind.name,
    privateVaultAlias = privateVaultAlias,
    mimeType = mimeType,
    distinctFaceIdentityId = distinctFaceIdentityId,
    durationMs = durationMs,
    explicitLikenessOrVoiceAuthorized = explicitLikenessOrVoiceAuthorized,
    subjectAgeStatus = subjectAgeStatus.name,
    createdAtEpochMs = createdAtEpochMs,
)

private fun IdentityMediaAssetEntity.toDomain(): IdentityMediaAsset = IdentityMediaAsset(
    id = id,
    ownerTenantKey = ownerTenantKey,
    kind = MediaAssetKind.valueOf(kind),
    privateVaultAlias = privateVaultAlias,
    mimeType = mimeType,
    distinctFaceIdentityId = distinctFaceIdentityId,
    durationMs = durationMs,
    explicitLikenessOrVoiceAuthorized = explicitLikenessOrVoiceAuthorized,
    subjectAgeStatus = SubjectAgePolicyStatus.valueOf(subjectAgeStatus),
    createdAtEpochMs = createdAtEpochMs,
)

private fun PersonaIdentityConfiguration.toEntity(): PersonaIdentityConfigEntity = PersonaIdentityConfigEntity(
    tenantKey = tenantKey,
    persona = persona.name,
    customName = customName,
    identitySubtitleEn = identitySubtitleEn,
    identitySubtitleFa = identitySubtitleFa,
    roleSummaryEn = roleSummaryEn,
    roleSummaryFa = roleSummaryFa,
    responsibilitiesCsv = responsibilities.joinToString("||"),
    warmth = personality.warmth,
    formality = personality.formality,
    directness = personality.directness,
    empathy = personality.empathy,
    technicalDepth = personality.technicalDepth,
    communicationStyleEn = personality.communicationStyleEn,
    communicationStyleFa = personality.communicationStyleFa,
    vocabularyNotesCsv = personality.vocabularyNotes.joinToString("||"),
    knowledgeScopeTagsCsv = knowledgeScopeTags.joinToString("||"),
    behavioralInstructions = behavioralInstructions,
    voiceProfileName = voiceConfig.voiceProfileName,
    voiceGender = voiceConfig.genderPresentation.name,
    pitchOffset = voiceConfig.pitchOffset,
    speakingRate = voiceConfig.speakingRate,
    preferredLocaleTag = voiceConfig.preferredLocaleTag,
    authorizedVoiceAssetId = voiceConfig.authorizedVoiceAssetId,
    avatarProfileId = avatarConfig.avatarProfileId,
    distinctFaceIdentityId = avatarConfig.distinctFaceIdentityId,
    clothingStyle = avatarConfig.clothingStyle,
    appearanceNotes = avatarConfig.appearanceNotes,
    visualStylePreset = avatarConfig.visualStylePreset.name,
    presentationMode = avatarConfig.presentationMode.name,
    animationIntensity = avatarConfig.animationIntensity,
    idleMotionEnabled = avatarConfig.idleMotionEnabled,
    authorizedPhotoAssetIdsCsv = avatarConfig.authorizedPhotoAssetIds.joinToString("||"),
    authorizedVideoAssetIdsCsv = avatarConfig.authorizedVideoAssetIds.joinToString("||"),
    preferredLanguage = interactionPreferences.preferredLanguage.name,
    responseLength = interactionPreferences.responseLength.name,
    proactivityLevel = interactionPreferences.proactivityLevel,
    confirmBeforeExternalActions = interactionPreferences.confirmBeforeExternalActions,
    securityRoleAnchor = securityRoleAnchor.name,
    minimumDataClassification = minimumDataClassification.name,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun PersonaIdentityConfigEntity.toDomain(): PersonaIdentityConfiguration = PersonaIdentityConfiguration(
    persona = AfrinaPersona.valueOf(persona),
    tenantKey = tenantKey,
    customName = customName,
    identitySubtitleEn = identitySubtitleEn,
    identitySubtitleFa = identitySubtitleFa,
    roleSummaryEn = roleSummaryEn,
    roleSummaryFa = roleSummaryFa,
    responsibilities = responsibilitiesCsv.split("||").filter { it.isNotBlank() },
    personality = PersonalityTraitsConfig(
        warmth = warmth,
        formality = formality,
        directness = directness,
        empathy = empathy,
        technicalDepth = technicalDepth,
        communicationStyleEn = communicationStyleEn,
        communicationStyleFa = communicationStyleFa,
        vocabularyNotes = vocabularyNotesCsv.split("||").filter { it.isNotBlank() },
    ),
    knowledgeScopeTags = knowledgeScopeTagsCsv.split("||").filter { it.isNotBlank() },
    behavioralInstructions = behavioralInstructions,
    voiceConfig = VoiceCustomizationConfig(
        voiceProfileName = voiceProfileName,
        genderPresentation = GenderPresentation.valueOf(voiceGender),
        pitchOffset = pitchOffset,
        speakingRate = speakingRate,
        preferredLocaleTag = preferredLocaleTag,
        authorizedVoiceAssetId = authorizedVoiceAssetId,
    ),
    avatarConfig = AvatarCustomizationConfig(
        avatarProfileId = avatarProfileId,
        distinctFaceIdentityId = distinctFaceIdentityId ?: "",
        clothingStyle = clothingStyle,
        appearanceNotes = appearanceNotes,
        visualStylePreset = VisualStylePreset.valueOf(visualStylePreset),
        presentationMode = AvatarPresentationMode.valueOf(presentationMode),
        animationIntensity = animationIntensity,
        idleMotionEnabled = idleMotionEnabled,
        authorizedPhotoAssetIds = authorizedPhotoAssetIdsCsv.split("||").filter { it.isNotBlank() },
        authorizedVideoAssetIds = authorizedVideoAssetIdsCsv.split("||").filter { it.isNotBlank() },
    ),
    interactionPreferences = InteractionPreferencesConfig(
        preferredLanguage = AppLanguage.valueOf(preferredLanguage),
        responseLength = ResponseLengthPreference.valueOf(responseLength),
        proactivityLevel = proactivityLevel,
        confirmBeforeExternalActions = confirmBeforeExternalActions,
    ),
    securityRoleAnchor = AfrinaPersona.valueOf(securityRoleAnchor),
    minimumDataClassification = DataClassification.valueOf(minimumDataClassification),
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun PersonaIdentityRevision.toEntity(): PersonaIdentityRevisionEntity = PersonaIdentityRevisionEntity(
    id = "${tenantKey}-${persona.name}-v${version}",
    tenantKey = tenantKey,
    persona = persona.name,
    version = version,
    configurationJson = configuration.toJsonString(),
    changeSummary = changeSummary,
    changedAtEpochMs = changedAtEpochMs,
)

private fun DigitalTwinCustomizationProfile.toEntity(): DigitalTwinProfileEntity = DigitalTwinProfileEntity(
    tenantKey = tenantKey,
    gender = gender.name,
    likenessAuthorized = likenessAuthorized,
    voiceAuthorized = voiceAuthorized,
    subjectAgeStatus = subjectAgeStatus.name,
    authorizedPhotoAssetIdsCsv = authorizedPhotoAssetIds.joinToString("||"),
    authorizedVideoAssetIdsCsv = authorizedVideoAssetIds.joinToString("||"),
    authorizedVoiceAssetIdsCsv = authorizedVoiceAssetIds.joinToString("||"),
    communicationStyleSummary = communicationStyleSummary,
    approvedVocabularyCsv = approvedVocabulary.joinToString("||"),
    approvedKnowledgeMemoryIdsCsv = approvedKnowledgeMemoryIds.joinToString("||"),
    editablePersonalityJson = null,
    behaviorNotes = behaviorNotes,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun SharedMemoryEntry.toEntity(tenantKey: String): SharedMemoryEntity = SharedMemoryEntity(
    id = id,
    tenantKey = tenantKey,
    layer = layer.name,
    contentSummary = contentSummary,
    dataClassification = dataClassification.name,
    sourcePersona = provenance.sourcePersona.name,
    sourceKind = provenance.sourceKind.name,
    sourceReferenceId = provenance.sourceReferenceId,
    lastReviewedByOwner = provenance.lastReviewedByOwner,
    recordedAtEpochMs = provenance.recordedAtEpochMs,
    confidence = confidence,
    reviewStatus = reviewStatus.name,
    ownerCorrectionNote = ownerCorrectionNote,
    projectId = projectId,
    preferenceKey = preferenceKey,
    verifiedExperienceCount = verifiedExperienceCount,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun SharedMemoryEntity.toDomain(): SharedMemoryEntry = SharedMemoryEntry(
    id = id,
    layer = MemoryLayer.valueOf(layer),
    contentSummary = contentSummary,
    dataClassification = DataClassification.valueOf(dataClassification),
    provenance = MemoryProvenance(
        sourcePersona = AfrinaPersona.valueOf(sourcePersona),
        sourceKind = MemorySourceKind.valueOf(sourceKind),
        sourceReferenceId = sourceReferenceId,
        lastReviewedByOwner = lastReviewedByOwner,
        recordedAtEpochMs = recordedAtEpochMs,
    ),
    confidence = confidence,
    reviewStatus = MemoryReviewStatus.valueOf(reviewStatus),
    ownerCorrectionNote = ownerCorrectionNote,
    projectId = projectId,
    preferenceKey = preferenceKey,
    verifiedExperienceCount = verifiedExperienceCount,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun DigitalTwinProfileEntity.toDomain(): DigitalTwinCustomizationProfile = DigitalTwinCustomizationProfile(
    tenantKey = tenantKey,
    gender = GenderPresentation.valueOf(gender),
    likenessAuthorized = likenessAuthorized,
    voiceAuthorized = voiceAuthorized,
    subjectAgeStatus = SubjectAgePolicyStatus.valueOf(subjectAgeStatus),
    authorizedPhotoAssetIds = authorizedPhotoAssetIdsCsv.split("||").filter { it.isNotBlank() },
    authorizedVideoAssetIds = authorizedVideoAssetIdsCsv.split("||").filter { it.isNotBlank() },
    authorizedVoiceAssetIds = authorizedVoiceAssetIdsCsv.split("||").filter { it.isNotBlank() },
    communicationStyleSummary = communicationStyleSummary,
    approvedVocabulary = approvedVocabularyCsv.split("||").filter { it.isNotBlank() },
    approvedKnowledgeMemoryIds = approvedKnowledgeMemoryIdsCsv.split("||").filter { it.isNotBlank() },
    editablePersonality = null,
    behaviorNotes = behaviorNotes,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun PersonaIdentityConfiguration.toJsonString(): String {
    val root = JSONObject()
    root.put("persona", persona.name)
    root.put("tenantKey", tenantKey)
    root.put("customName", customName)
    root.put("identitySubtitleEn", identitySubtitleEn)
    root.put("identitySubtitleFa", identitySubtitleFa)
    root.put("roleSummaryEn", roleSummaryEn)
    root.put("roleSummaryFa", roleSummaryFa)
    root.put("responsibilities", JSONArray(responsibilities))

    val p = JSONObject()
    p.put("warmth", personality.warmth)
    p.put("formality", personality.formality)
    p.put("directness", personality.directness)
    p.put("empathy", personality.empathy)
    p.put("technicalDepth", personality.technicalDepth)
    p.put("communicationStyleEn", personality.communicationStyleEn)
    p.put("communicationStyleFa", personality.communicationStyleFa)
    p.put("vocabularyNotes", JSONArray(personality.vocabularyNotes))
    root.put("personality", p)

    root.put("knowledgeScopeTags", JSONArray(knowledgeScopeTags))
    root.put("behavioralInstructions", behavioralInstructions)

    val v = JSONObject()
    v.put("voiceProfileName", voiceConfig.voiceProfileName)
    v.put("genderPresentation", voiceConfig.genderPresentation.name)
    v.put("pitchOffset", voiceConfig.pitchOffset)
    v.put("speakingRate", voiceConfig.speakingRate)
    v.put("preferredLocaleTag", voiceConfig.preferredLocaleTag)
    v.put("authorizedVoiceAssetId", voiceConfig.authorizedVoiceAssetId ?: JSONObject.NULL)
    v.put("requiresExplicitVoiceAuthorization", voiceConfig.requiresExplicitVoiceAuthorization)
    root.put("voiceConfig", v)

    val a = JSONObject()
    a.put("avatarProfileId", avatarConfig.avatarProfileId)
    a.put("distinctFaceIdentityId", avatarConfig.distinctFaceIdentityId ?: JSONObject.NULL)
    a.put("clothingStyle", avatarConfig.clothingStyle)
    a.put("appearanceNotes", avatarConfig.appearanceNotes)
    a.put("visualStylePreset", avatarConfig.visualStylePreset.name)
    a.put("presentationMode", avatarConfig.presentationMode.name)
    a.put("animationIntensity", avatarConfig.animationIntensity)
    a.put("idleMotionEnabled", avatarConfig.idleMotionEnabled)
    a.put("authorizedPhotoAssetIds", JSONArray(avatarConfig.authorizedPhotoAssetIds))
    a.put("authorizedVideoAssetIds", JSONArray(avatarConfig.authorizedVideoAssetIds))
    root.put("avatarConfig", a)

    val ip = JSONObject()
    ip.put("preferredLanguage", interactionPreferences.preferredLanguage.name)
    ip.put("responseLength", interactionPreferences.responseLength.name)
    ip.put("proactivityLevel", interactionPreferences.proactivityLevel)
    ip.put("confirmBeforeExternalActions", interactionPreferences.confirmBeforeExternalActions)
    root.put("interactionPreferences", ip)

    root.put("securityRoleAnchor", securityRoleAnchor.name)
    root.put("minimumDataClassification", minimumDataClassification.name)
    root.put("updatedAtEpochMs", updatedAtEpochMs)
    return root.toString()
}

private fun String.toConfigurationOrNull(): PersonaIdentityConfiguration? = runCatching {
    val root = JSONObject(this)
    val persona = AfrinaPersona.valueOf(root.getString("persona"))
    val tenantKey = root.getString("tenantKey")
    val customName = root.getString("customName")
    val identitySubtitleEn = root.optString("identitySubtitleEn", "")
    val identitySubtitleFa = root.optString("identitySubtitleFa", "")
    val roleSummaryEn = root.optString("roleSummaryEn", "")
    val roleSummaryFa = root.optString("roleSummaryFa", "")
    val respArray = root.optJSONArray("responsibilities")
    val responsibilities = if (respArray != null) (0 until respArray.length()).map { respArray.getString(it) } else emptyList()

    val pObj = root.optJSONObject("personality")
    val personality = if (pObj != null) {
        val vocabArray = pObj.optJSONArray("vocabularyNotes")
        val vocab = if (vocabArray != null) (0 until vocabArray.length()).map { vocabArray.getString(it) } else emptyList()
        PersonalityTraitsConfig(
            warmth = pObj.optDouble("warmth", 0.5),
            formality = pObj.optDouble("formality", 0.5),
            directness = pObj.optDouble("directness", 0.5),
            empathy = pObj.optDouble("empathy", 0.5),
            technicalDepth = pObj.optDouble("technicalDepth", 0.5),
            communicationStyleEn = pObj.optString("communicationStyleEn", "Executive"),
            communicationStyleFa = pObj.optString("communicationStyleFa", "حرفه‌ای"),
            vocabularyNotes = vocab,
        )
    } else PersonalityTraitsConfig()

    val tagsArray = root.optJSONArray("knowledgeScopeTags")
    val knowledgeScopeTags = if (tagsArray != null) (0 until tagsArray.length()).map { tagsArray.getString(it) } else emptyList()
    val behavioralInstructions = root.optString("behavioralInstructions", "")

    val vObj = root.optJSONObject("voiceConfig")
    val voiceConfig = if (vObj != null) {
        VoiceCustomizationConfig(
            voiceProfileName = vObj.optString("voiceProfileName", "Default Voice"),
            genderPresentation = runCatching { GenderPresentation.valueOf(vObj.optString("genderPresentation")) }.getOrDefault(GenderPresentation.NEUTRAL),
            pitchOffset = vObj.optDouble("pitchOffset", 0.0),
            speakingRate = vObj.optDouble("speakingRate", 1.0),
            preferredLocaleTag = vObj.optString("preferredLocaleTag", "fa-IR"),
            authorizedVoiceAssetId = if (vObj.isNull("authorizedVoiceAssetId")) null else vObj.optString("authorizedVoiceAssetId"),
            requiresExplicitVoiceAuthorization = vObj.optBoolean("requiresExplicitVoiceAuthorization", true),
        )
    } else VoiceCustomizationConfig()

    val aObj = root.optJSONObject("avatarConfig")
    val avatarConfig = if (aObj != null) {
        val photosArray = aObj.optJSONArray("authorizedPhotoAssetIds")
        val photos = if (photosArray != null) (0 until photosArray.length()).map { photosArray.getString(it) } else emptyList()
        val videosArray = aObj.optJSONArray("authorizedVideoAssetIds")
        val videos = if (videosArray != null) (0 until videosArray.length()).map { videosArray.getString(it) } else emptyList()
        AvatarCustomizationConfig(
            avatarProfileId = aObj.optString("avatarProfileId", "av-${persona.name.lowercase()}"),
            distinctFaceIdentityId = if (aObj.isNull("distinctFaceIdentityId")) null else aObj.optString("distinctFaceIdentityId"),
            clothingStyle = aObj.optString("clothingStyle", "Classic"),
            appearanceNotes = aObj.optString("appearanceNotes", ""),
            visualStylePreset = runCatching { VisualStylePreset.valueOf(aObj.optString("visualStylePreset")) }.getOrDefault(VisualStylePreset.TERRACOTTA_ATELIER),
            presentationMode = runCatching { AvatarPresentationMode.valueOf(aObj.optString("presentationMode")) }.getOrDefault(AvatarPresentationMode.STATIC_PORTRAIT),
            animationIntensity = aObj.optDouble("animationIntensity", 0.2),
            idleMotionEnabled = aObj.optBoolean("idleMotionEnabled", false),
            authorizedPhotoAssetIds = photos,
            authorizedVideoAssetIds = videos,
        )
    } else AvatarCustomizationConfig()

    val ipObj = root.optJSONObject("interactionPreferences")
    val interactionPreferences = if (ipObj != null) {
        InteractionPreferencesConfig(
            preferredLanguage = runCatching { AppLanguage.valueOf(ipObj.optString("preferredLanguage")) }.getOrDefault(AppLanguage.PERSIAN),
            responseLength = runCatching { ResponseLengthPreference.valueOf(ipObj.optString("responseLength")) }.getOrDefault(ResponseLengthPreference.ADAPTIVE),
            proactivityLevel = ipObj.optDouble("proactivityLevel", 0.6),
            confirmBeforeExternalActions = ipObj.optBoolean("confirmBeforeExternalActions", true),
        )
    } else InteractionPreferencesConfig()

    val securityRoleAnchor = runCatching { AfrinaPersona.valueOf(root.optString("securityRoleAnchor")) }.getOrDefault(persona)
    val minimumDataClassification = runCatching { DataClassification.valueOf(root.optString("minimumDataClassification")) }.getOrDefault(DataClassification.PROJECT_CODE)
    val updatedAtEpochMs = root.optLong("updatedAtEpochMs", System.currentTimeMillis())

    PersonaIdentityConfiguration(
        persona = persona,
        tenantKey = tenantKey,
        customName = customName,
        identitySubtitleEn = identitySubtitleEn,
        identitySubtitleFa = identitySubtitleFa,
        roleSummaryEn = roleSummaryEn,
        roleSummaryFa = roleSummaryFa,
        responsibilities = responsibilities,
        personality = personality,
        knowledgeScopeTags = knowledgeScopeTags,
        behavioralInstructions = behavioralInstructions,
        voiceConfig = voiceConfig,
        avatarConfig = avatarConfig,
        interactionPreferences = interactionPreferences,
        securityRoleAnchor = securityRoleAnchor,
        minimumDataClassification = minimumDataClassification,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}.getOrNull()
