package com.example.afrina.core.voice

import com.example.afrina.core.data.local.AuditLogDao
import com.example.afrina.core.data.local.AuditLogEntity
import com.example.afrina.core.domain.PersonaArchitectureRepository
import com.example.afrina.core.domain.PersonaIdentityCustomizationRepository
import com.example.afrina.core.domain.VoiceConversationRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.persona.ContentAndLikenessPolicy
import com.example.afrina.core.persona.MediaAssetKind
import com.example.afrina.core.persona.PersonaCatalog
import com.example.afrina.core.persona.PersonaProfile
import com.example.afrina.core.persona.SubjectAgePolicyStatus
import com.example.afrina.core.persona.TenantScope
import com.example.afrina.core.persona.VoiceCustomizationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Foundational Real-Time Natural Voice & Conversation Engine.
 *
 * Guarantees:
 * - Explicit microphone permission gating (fail-closed if NOT_REQUESTED / DENIED).
 * - Lightweight mobile-first VAD + pause/noise/end-of-turn detection (Redmi Note 8 friendly).
 * - Strict turn-taking lock preventing overlapping persona responses.
 * - Real-time owner barge-in / interruption during THINKING or SPEAKING states.
 * - Unified voice <-> text conversation context continuity per persona and tenant.
 * - Independent voice profiles for all 4 personas, 3 presentation styles, Persian/multilingual routing,
 *   authorized voice-cloning readiness, audio collaboration specs, and strict privacy deletion controls.
 */
class RealTimeVoiceConversationEngine(
    private val personaArchitectureRepository: PersonaArchitectureRepository? = null,
    private val identityRepository: PersonaIdentityCustomizationRepository? = null,
    private val micCapturePort: MicrophoneAudioCapturePort = PreparedUnconfiguredMicrophoneCapturePort(),
    private val speechToTextEngine: SpeechToTextEngine = PreparedUnconfiguredSpeechToTextEngine(),
    private val speechOutputPort: SpeechOutputPort = PreparedUnconfiguredSpeechOutputPort(),
    private val vadDetector: VoiceActivityDetector = LightweightEnergyVadDetector(),
    private val collaborationPort: AudioCollaborationPort = PreparedAudioCollaborationGateway(),
    private val auditLogDao: AuditLogDao? = null,
    private val deviceProfile: DeviceAudioCapabilityProfile = DeviceAudioCapabilityProfile(),
    private val defaultProfiles: Map<AfrinaPersona, PersonaProfile> = PersonaCatalog.defaultProfiles(),
) : VoiceConversationRepository {

    private class MutableVoiceTenantState(
        val tenantScope: TenantScope,
        var permissionState: MicrophonePermissionState = MicrophonePermissionState.NOT_REQUESTED,
        var audioState: ConversationAudioState = ConversationAudioState.IDLE,
        var activePersona: AfrinaPersona = AfrinaPersona.COMPANION_EMPATHY,
        var activeLocaleTag: String = "fa-IR",
        var activeSttSessionId: String? = null,
        var activeTurnLock: TurnOwnershipLock? = null,
        val interruptedTurnIds: MutableSet<String> = mutableSetOf(),
        var lastVadEvaluation: VadEvaluation? = null,
        var livePartialTranscript: SpeechRecognitionPartial? = null,
        var statusNotice: String? = null,
        val voiceProfiles: MutableMap<AfrinaPersona, PersonaVoiceProfile> = mutableMapOf(),
        val unifiedHistory: MutableList<UnifiedConversationTurn> = mutableListOf(),
        val recordings: MutableList<VoiceRecordingPrivacyRecord> = mutableListOf(),
        val collaborationSessions: MutableList<AudioCollaborationReadinessResult> = mutableListOf(),
    )

    private val mutex = Mutex()
    private val tenants = linkedMapOf<String, MutableVoiceTenantState>()
    private val snapshotsFlow = MutableStateFlow<Map<String, VoiceConversationSnapshot>>(emptyMap())

    init {
        val master = createInitialTenantState(TenantScope.MasterOwner)
        tenants[TenantScope.MasterOwner.key] = master
        snapshotsFlow.value = mapOf(TenantScope.MasterOwner.key to master.toSnapshot())
    }

    override fun observeVoiceSnapshot(tenantScope: TenantScope): Flow<VoiceConversationSnapshot> =
        snapshotsFlow.map { map ->
            map[tenantScope.key] ?: createInitialTenantState(tenantScope).toSnapshot()
        }

    override suspend fun setMicrophonePermission(
        tenantScope: TenantScope,
        permissionState: MicrophonePermissionState,
    ) {
        mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            tenant.permissionState = permissionState
            if (permissionState != MicrophonePermissionState.GRANTED &&
                tenant.audioState == ConversationAudioState.LISTENING
            ) {
                tenant.audioState = ConversationAudioState.PERMISSION_REQUIRED
                tenant.activeSttSessionId = null
            } else if (permissionState == MicrophonePermissionState.GRANTED &&
                tenant.audioState == ConversationAudioState.PERMISSION_REQUIRED
            ) {
                tenant.audioState = ConversationAudioState.IDLE
            }
            tenant.statusNotice = "Microphone permission state: ${permissionState.name}"
            publishLocked()
        }
        recordAudit(
            actor = tenantScope.key,
            action = "VOICE_MIC_PERMISSION_SET",
            target = permissionState.name,
            outcome = if (permissionState == MicrophonePermissionState.GRANTED) "GRANTED" else "RESTRICTED",
        )
    }

    override suspend fun startVoiceListening(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        localeTag: String,
    ): Result<Unit> {
        val normalizedLocale = normalizeLocaleTag(localeTag)
        val beginResult = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            if (tenant.permissionState != MicrophonePermissionState.GRANTED) {
                tenant.audioState = ConversationAudioState.PERMISSION_REQUIRED
                tenant.statusNotice = "Explicit RECORD_AUDIO permission is required before listening."
                publishLocked()
                return Result.failure(SecurityException("Explicit microphone permission is required."))
            }
            if (tenant.activeTurnLock != null) {
                return Result.failure(
                    IllegalStateException("Cannot start listening while turn '${tenant.activeTurnLock?.activeTurnId}' is active (${tenant.audioState}). Interrupt first.")
                )
            }
            vadDetector.reset()
            tenant.activePersona = persona
            tenant.activeLocaleTag = normalizedLocale
            tenant.livePartialTranscript = null
            tenant.audioState = ConversationAudioState.LISTENING
            tenant.statusNotice = "Listening for ${persona.name} ($normalizedLocale)"
            publishLocked()
            Unit
        }

        // Open streaming STT session if a configured engine is attached; fail gracefully without breaking state tracking
        if (speechToTextEngine.isConfigured) {
            val sttResult = speechToTextEngine.beginStream(normalizedLocale)
            sttResult.onSuccess { sessionId ->
                mutex.withLock {
                    val tenant = getOrCreateTenantLocked(tenantScope)
                    tenant.activeSttSessionId = sessionId
                    publishLocked()
                }
            }.onFailure { err ->
                mutex.withLock {
                    val tenant = getOrCreateTenantLocked(tenantScope)
                    tenant.audioState = ConversationAudioState.PAUSED_ERROR
                    tenant.statusNotice = err.message ?: "STT initialization failed"
                    publishLocked()
                }
                return Result.failure(err)
            }
        }
        return Result.success(beginResult)
    }

    override suspend fun processMicrophoneFrame(
        tenantScope: TenantScope,
        frame: AudioFramePacket,
        policy: EndOfTurnPolicy,
    ): Result<VadEvaluation> {
        val (permissionOk, currentState, sttSessionId) = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            Triple(tenant.permissionState, tenant.audioState, tenant.activeSttSessionId)
        }

        if (permissionOk != MicrophonePermissionState.GRANTED) {
            return Result.failure(SecurityException("Microphone frame rejected: permission not granted."))
        }

        val evaluation = vadDetector.evaluateFrame(frame, policy)

        // Barge-in detection: if owner speaks clearly while persona is SPEAKING, interrupt immediately!
        if (currentState == ConversationAudioState.SPEAKING && evaluation.decision == VadDecision.SPEECH_ACTIVE) {
            interruptSpeakingPersona(
                tenantScope = tenantScope,
                resumeListening = true,
                reason = "Owner voice barge-in detected during playback",
            )
            mutex.withLock {
                val tenant = getOrCreateTenantLocked(tenantScope)
                tenant.lastVadEvaluation = evaluation
                publishLocked()
            }
            return Result.success(evaluation)
        }

        var partial: SpeechRecognitionPartial? = null
        if (currentState == ConversationAudioState.LISTENING && sttSessionId != null && speechToTextEngine.isConfigured) {
            partial = speechToTextEngine.pushAudioFrame(sttSessionId, frame).getOrNull()
        }

        mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            tenant.lastVadEvaluation = evaluation
            if (partial != null) {
                tenant.livePartialTranscript = partial
            }
            tenant.statusNotice = when (evaluation.decision) {
                VadDecision.SILENCE -> "Idle silence (noiseFloor=${"%.3f".format(evaluation.noiseFloorRms)})"
                VadDecision.NOISE_REJECTED -> "Background noise suppressed (SNR=${"%.1f".format(evaluation.signalToNoiseRatioDb)}dB)"
                VadDecision.SPEECH_ACTIVE -> "Speech active (${evaluation.activeSpeechDurationMs}ms)"
                VadDecision.PAUSE_HOLDING -> "Natural pause held (${evaluation.silenceElapsedMs}ms/${policy.endOfTurnSilenceMs}ms)"
                VadDecision.END_OF_TURN -> "End-of-turn confirmed (${evaluation.silenceElapsedMs}ms silence)"
            }
            publishLocked()
        }
        return Result.success(evaluation)
    }

    override suspend fun completeVoiceUtterance(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        localeTag: String,
        transcriptOverride: String?,
        projectId: String?,
        dataClassification: DataClassification,
        explicitSensitiveDataAuthorized: Boolean,
        useGuardianDigitalTwinIdentity: Boolean,
    ): Result<UnifiedConversationTurn> {
        val normalizedLocale = normalizeLocaleTag(localeTag)
        val preCheck = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            if (tenant.permissionState != MicrophonePermissionState.GRANTED) {
                tenant.audioState = ConversationAudioState.PERMISSION_REQUIRED
                publishLocked()
                return Result.failure(SecurityException("Microphone permission required to finalize voice utterance."))
            }
            if (tenant.activeTurnLock != null) {
                return Result.failure(
                    IllegalStateException("Overlapping turn blocked: ${tenant.activeTurnLock?.persona?.name} is currently ${tenant.activeTurnLock?.state}.")
                )
            }
            tenant.activeSttSessionId
        }

        val resolvedText = when {
            !transcriptOverride.isNullOrBlank() -> transcriptOverride.trim()
            preCheck != null && speechToTextEngine.isConfigured -> {
                val finalized = speechToTextEngine.finalizeUtterance(preCheck)
                finalized.getOrElse { err ->
                    mutex.withLock {
                        val tenant = getOrCreateTenantLocked(tenantScope)
                        tenant.activeSttSessionId = null
                        tenant.audioState = ConversationAudioState.PAUSED_ERROR
                        tenant.statusNotice = err.message
                        publishLocked()
                    }
                    return Result.failure(err)
                }.text.trim()
            }
            else -> {
                mutex.withLock {
                    val tenant = getOrCreateTenantLocked(tenantScope)
                    tenant.activeSttSessionId = null
                    tenant.audioState = ConversationAudioState.PAUSED_ERROR
                    tenant.statusNotice = "No STT engine configured and no transcript supplied."
                    publishLocked()
                }
                return Result.failure(
                    IllegalStateException("Speech-to-text engine is not configured; cannot transcribe live audio without a configured ASR adapter.")
                )
            }
        }

        return executeConversationalTurn(
            tenantScope = tenantScope,
            persona = persona,
            modality = ConversationModality.VOICE,
            userText = resolvedText,
            localeTag = normalizedLocale,
            projectId = projectId,
            dataClassification = dataClassification,
            explicitSensitiveDataAuthorized = explicitSensitiveDataAuthorized,
            useGuardianDigitalTwinIdentity = useGuardianDigitalTwinIdentity,
            speakAloud = true,
        )
    }

    override suspend fun submitTextTurn(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        text: String,
        localeTag: String,
        projectId: String?,
        dataClassification: DataClassification,
        explicitSensitiveDataAuthorized: Boolean,
        speakResponseAloud: Boolean,
    ): Result<UnifiedConversationTurn> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Text prompt must be non-blank."))
        }
        return executeConversationalTurn(
            tenantScope = tenantScope,
            persona = persona,
            modality = ConversationModality.TEXT,
            userText = text.trim(),
            localeTag = normalizeLocaleTag(localeTag),
            projectId = projectId,
            dataClassification = dataClassification,
            explicitSensitiveDataAuthorized = explicitSensitiveDataAuthorized,
            useGuardianDigitalTwinIdentity = false,
            speakAloud = speakResponseAloud,
        )
    }

    override suspend fun interruptSpeakingPersona(
        tenantScope: TenantScope,
        resumeListening: Boolean,
        reason: String,
    ): Boolean {
        val interruptedTurn: TurnOwnershipLock = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val lock = tenant.activeTurnLock
            if (lock != null && (lock.state == ConversationAudioState.SPEAKING || lock.state == ConversationAudioState.THINKING)) {
                tenant.interruptedTurnIds += lock.activeTurnId
                tenant.activeTurnLock = null
                tenant.audioState = if (resumeListening && tenant.permissionState == MicrophonePermissionState.GRANTED) {
                    vadDetector.reset()
                    ConversationAudioState.LISTENING
                } else {
                    ConversationAudioState.INTERRUPTED
                }
                tenant.statusNotice = reason
                publishLocked()
                lock
            } else {
                null
            }
        } ?: return false

        speechOutputPort.interruptPlayback()

        mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val idx = tenant.unifiedHistory.indexOfFirst { it.turnId == interruptedTurn.activeTurnId }
            if (idx >= 0) {
                tenant.unifiedHistory[idx] = tenant.unifiedHistory[idx].copy(interruptedByOwner = true)
                publishLocked()
            }
        }

        recordAudit(
            actor = tenantScope.key,
            action = "VOICE_PERSONA_INTERRUPTED",
            target = interruptedTurn.persona.name,
            outcome = "OWNER_BARGE_IN",
        )
        return true
    }

    override suspend fun configurePersonaVoiceProfile(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        voiceName: String?,
        activeLocaleTag: String?,
        activeStyle: VoicePresentationStyle?,
        updatedTuning: VoiceStyleTuning?,
    ): Result<PersonaVoiceProfile> {
        voiceName?.let {
            ContentAndLikenessPolicy.validateTextContent(it, "voiceName").onFailure { err -> return Result.failure(err) }
        }
        val locale = activeLocaleTag?.let(::normalizeLocaleTag)
        val updated = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val current = tenant.voiceProfiles.getValue(persona)
            val nextStyle = activeStyle ?: current.activeStyle
            val nextTunings = current.styleTunings.toMutableMap()
            if (updatedTuning != null) {
                nextTunings[updatedTuning.style] = updatedTuning.normalized()
            }
            val now = System.currentTimeMillis()
            val nextProfile = current.copy(
                voiceName = voiceName?.trim()?.takeIf { it.isNotEmpty() } ?: current.voiceName,
                activeLocaleTag = locale ?: current.activeLocaleTag,
                supportedLocaleTags = if (locale != null && locale !in current.supportedLocaleTags) {
                    current.supportedLocaleTags + locale
                } else {
                    current.supportedLocaleTags
                },
                activeStyle = nextStyle,
                styleTunings = nextTunings,
                isDeleted = false,
                updatedAtEpochMs = now,
            )
            tenant.voiceProfiles[persona] = nextProfile
            publishLocked()
            nextProfile
        }

        // Synchronize non-cloning voice preferences to Identity Engine if available
        identityRepository?.updateVoiceConfig(
            tenantScope = tenantScope,
            persona = persona,
            voiceConfig = VoiceCustomizationConfig(
                voiceProfileName = updated.voiceName,
                genderPresentation = updated.genderPresentation,
                pitchOffset = updated.styleTunings.getValue(updated.activeStyle).pitchShift,
                speakingRate = updated.styleTunings.getValue(updated.activeStyle).speakingRate,
                preferredLocaleTag = updated.activeLocaleTag,
                authorizedVoiceAssetId = updated.authorizedVoiceAssetId,
                requiresExplicitVoiceAuthorization = true,
            ),
            explicitVoiceAuthorized = updated.explicitOwnerVoiceAuthorized,
            changeSummary = "Voice profile sync (${updated.activeStyle.name})",
        )

        recordAudit(
            actor = tenantScope.key,
            action = "VOICE_PROFILE_CONFIGURED",
            target = "${persona.name}/${updated.activeStyle.name}",
            outcome = updated.activeLocaleTag,
        )
        return Result.success(updated)
    }

    override suspend fun linkAuthorizedVoiceCloneSample(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        voiceAssetId: String,
        explicitOwnerAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
    ): Result<PersonaVoiceProfile> {
        ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
            explicitAuthorized = explicitOwnerAuthorized,
            subjectAgeStatus = subjectAgeStatus,
            assetKindLabel = "VOICE_CLONE_SAMPLE",
        ).onFailure { return Result.failure(it) }

        if (voiceAssetId.isBlank()) {
            return Result.failure(IllegalArgumentException("Voice asset ID must be non-blank."))
        }

        // Enforce strict tenant isolation against registered assets or recordings
        val ownedInIdentityRepo = identityRepository?.let { repo ->
            val snap = repo.observeTenantWorkspace(tenantScope).first()
            snap.mediaAssets.any {
                it.id == voiceAssetId &&
                    it.ownerTenantKey == tenantScope.key &&
                    it.kind == MediaAssetKind.VOICE_SAMPLE &&
                    it.explicitLikenessOrVoiceAuthorized
            }
        } ?: false

        val ownedInRecordings = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            tenant.recordings.any {
                it.recordingId == voiceAssetId &&
                    it.tenantKey == tenantScope.key &&
                    !it.isDeleted &&
                    it.explicitOwnerAuthorized &&
                    it.allowVoiceCloningUsage
            }
        }

        if (!ownedInIdentityRepo && !ownedInRecordings) {
            return Result.failure(
                SecurityException("Voice asset '$voiceAssetId' not found, deleted, unauthorized for cloning, or outside tenant '${tenantScope.key}'.")
            )
        }

        val updated = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val current = tenant.voiceProfiles.getValue(persona)
            val next = current.copy(
                authorizedVoiceAssetId = voiceAssetId,
                explicitOwnerVoiceAuthorized = true,
                subjectAgeStatus = subjectAgeStatus,
                cloneReadiness = VoiceCloneReadiness.AUTHORIZED_SAMPLE_LINKED_AWAITING_ENGINE,
                isDeleted = false,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            tenant.voiceProfiles[persona] = next
            publishLocked()
            next
        }

        recordAudit(
            actor = tenantScope.key,
            action = "VOICE_CLONE_SAMPLE_LINKED",
            target = persona.name,
            outcome = "AUTHORIZED_AWAITING_ENGINE",
        )
        return Result.success(updated)
    }

    override suspend fun deleteVoiceProfile(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
    ): Boolean {
        val deleted = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val current = tenant.voiceProfiles[persona] ?: return false
            val base = defaultProfiles.getValue(persona)
            val reset = defaultVoiceProfile(tenantScope, base, System.currentTimeMillis()).copy(
                authorizedVoiceAssetId = null,
                explicitOwnerVoiceAuthorized = false,
                cloneReadiness = VoiceCloneReadiness.BLOCKED_UNAUTHORIZED,
                isDeleted = current.authorizedVoiceAssetId != null || !current.isDeleted,
            )
            tenant.voiceProfiles[persona] = reset
            publishLocked()
            true
        }
        if (deleted) {
            recordAudit(
                actor = tenantScope.key,
                action = "VOICE_PROFILE_DELETED",
                target = persona.name,
                outcome = "CLEARED_RESET",
            )
        }
        return deleted
    }

    override suspend fun registerVoiceRecording(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        privateVaultAlias: String,
        durationMs: Long,
        explicitOwnerAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
        allowSttTranscription: Boolean,
        allowVoiceCloningUsage: Boolean,
    ): Result<VoiceRecordingPrivacyRecord> {
        ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
            explicitAuthorized = explicitOwnerAuthorized,
            subjectAgeStatus = subjectAgeStatus,
            assetKindLabel = "VOICE_RECORDING",
        ).onFailure { return Result.failure(it) }

        if (privateVaultAlias.isBlank() || durationMs <= 0L) {
            return Result.failure(IllegalArgumentException("Recording alias must be non-blank and duration positive."))
        }

        val record = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val created = VoiceRecordingPrivacyRecord(
                recordingId = "rec-${UUID.randomUUID().toString().take(8)}",
                tenantKey = tenantScope.key,
                persona = persona,
                privateVaultAlias = privateVaultAlias.trim(),
                durationMs = durationMs,
                dataClassification = if (persona == AfrinaPersona.GUARDIAN_SENTINEL) {
                    DataClassification.DIGITAL_TWIN_SENSITIVE
                } else {
                    DataClassification.OWNER_PRIVATE
                },
                explicitOwnerAuthorized = true,
                subjectAgeStatus = subjectAgeStatus,
                allowSttTranscription = allowSttTranscription,
                allowVoiceCloningUsage = allowVoiceCloningUsage,
                isDeleted = false,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            tenant.recordings.add(0, created)
            publishLocked()
            created
        }

        recordAudit(
            actor = tenantScope.key,
            action = "VOICE_RECORDING_REGISTERED",
            target = persona.name,
            outcome = "ISOLATED_PRIVATE_VAULT",
        )
        return Result.success(record)
    }

    override suspend fun updateRecordingAccessControl(
        tenantScope: TenantScope,
        recordingId: String,
        allowSttTranscription: Boolean,
        allowVoiceCloningUsage: Boolean,
    ): Result<VoiceRecordingPrivacyRecord> {
        val updated = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val idx = tenant.recordings.indexOfFirst { it.recordingId == recordingId && !it.isDeleted }
            if (idx < 0) {
                return Result.failure(SecurityException("Recording '$recordingId' missing, deleted, or outside tenant isolation boundary."))
            }
            val next = tenant.recordings[idx].copy(
                allowSttTranscription = allowSttTranscription,
                allowVoiceCloningUsage = allowVoiceCloningUsage,
            )
            tenant.recordings[idx] = next
            // If cloning was revoked, unlink any profile referencing this recording
            if (!allowVoiceCloningUsage) {
                for ((p, profile) in tenant.voiceProfiles) {
                    if (profile.authorizedVoiceAssetId == recordingId) {
                        tenant.voiceProfiles[p] = profile.copy(
                            authorizedVoiceAssetId = null,
                            explicitOwnerVoiceAuthorized = false,
                            cloneReadiness = VoiceCloneReadiness.BLOCKED_UNAUTHORIZED,
                        )
                    }
                }
            }
            publishLocked()
            next
        }
        return Result.success(updated)
    }

    override suspend fun deleteVoiceRecording(
        tenantScope: TenantScope,
        recordingId: String,
    ): Boolean {
        val deleted = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val idx = tenant.recordings.indexOfFirst { it.recordingId == recordingId && !it.isDeleted }
            if (idx < 0) return false
            tenant.recordings[idx] = tenant.recordings[idx].copy(
                privateVaultAlias = "[DELETED]",
                explicitOwnerAuthorized = false,
                allowSttTranscription = false,
                allowVoiceCloningUsage = false,
                isDeleted = true,
            )
            for ((p, profile) in tenant.voiceProfiles) {
                if (profile.authorizedVoiceAssetId == recordingId) {
                    tenant.voiceProfiles[p] = profile.copy(
                        authorizedVoiceAssetId = null,
                        explicitOwnerVoiceAuthorized = false,
                        cloneReadiness = VoiceCloneReadiness.BLOCKED_UNAUTHORIZED,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
            publishLocked()
            true
        }
        if (deleted) {
            recordAudit(
                actor = tenantScope.key,
                action = "VOICE_RECORDING_DELETED",
                target = "VOICE_VAULT_ENTRY",
                outcome = "REDACTED_DELETED",
            )
        }
        return deleted
    }

    override suspend fun prepareAudioCollaboration(
        tenantScope: TenantScope,
        spec: AudioCollaborationSessionSpec,
    ): Result<AudioCollaborationReadinessResult> {
        // Cross-tenant voice asset check for Duets / Singing / Dubbing when referencing a voice asset
        val referencedAssetId = when (spec) {
            is AudioCollaborationSessionSpec.DuetPerformanceSpec -> spec.authorizedPartnerVoiceAssetId
            is AudioCollaborationSessionSpec.SingingSessionSpec -> spec.authorizedHumanVoiceAssetId
            is AudioCollaborationSessionSpec.DubbingSessionSpec -> spec.authorizedHumanVoiceAssetId
            else -> null
        }
        if (referencedAssetId != null) {
            val ownedInIdentity = identityRepository?.observeTenantWorkspace(tenantScope)?.first()?.mediaAssets?.any {
                it.id == referencedAssetId &&
                    it.ownerTenantKey == tenantScope.key &&
                    it.kind == MediaAssetKind.VOICE_SAMPLE &&
                    it.explicitLikenessOrVoiceAuthorized
            } ?: false
            val ownedInVoiceVault = mutex.withLock {
                val tenant = getOrCreateTenantLocked(tenantScope)
                tenant.recordings.any {
                    it.recordingId == referencedAssetId &&
                        it.tenantKey == tenantScope.key &&
                        !it.isDeleted &&
                        it.explicitOwnerAuthorized
                }
            }
            if (!ownedInIdentity && !ownedInVoiceVault) {
                return Result.failure(
                    SecurityException("Collaboration voice asset '$referencedAssetId' not found or outside tenant boundary.")
                )
            }
        }

        val res = collaborationPort.prepareSession(spec, tenantScope.key, deviceProfile)
        res.onSuccess { ready ->
            mutex.withLock {
                val tenant = getOrCreateTenantLocked(tenantScope)
                tenant.collaborationSessions.add(0, ready)
                publishLocked()
            }
            recordAudit(
                actor = tenantScope.key,
                action = "AUDIO_COLLAB_PREPARED",
                target = spec.mode.name,
                outcome = ready.offloadTarget.name,
            )
        }
        return res
    }

    private suspend fun executeConversationalTurn(
        tenantScope: TenantScope,
        persona: AfrinaPersona,
        modality: ConversationModality,
        userText: String,
        localeTag: String,
        projectId: String?,
        dataClassification: DataClassification,
        explicitSensitiveDataAuthorized: Boolean,
        useGuardianDigitalTwinIdentity: Boolean,
        speakAloud: Boolean,
    ): Result<UnifiedConversationTurn> {
        val turnId = "vturn-${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        val (recentContextLines, activeVoiceProfile) = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            if (tenant.activeTurnLock != null) {
                return Result.failure(
                    IllegalStateException("Overlapping response prevented: '${tenant.activeTurnLock?.persona?.name}' is currently ${tenant.activeTurnLock?.state}.")
                )
            }
            tenant.activeTurnLock = TurnOwnershipLock(
                activeTurnId = turnId,
                persona = persona,
                state = ConversationAudioState.THINKING,
                startedAtEpochMs = now,
            )
            tenant.activePersona = persona
            tenant.activeLocaleTag = localeTag
            tenant.activeSttSessionId = null
            tenant.audioState = ConversationAudioState.THINKING
            tenant.statusNotice = "${persona.name} thinking (${modality.name}, $localeTag)"
            publishLocked()

            val contextLines = tenant.unifiedHistory
                .filter { it.persona == persona }
                .take(3)
                .reversed()
                .map { "[${it.modality.name}] User: ${it.userInputSummary} -> ${it.assistantResponseEn.take(80)}" }
            Pair(contextLines, tenant.voiceProfiles.getValue(persona))
        }

        val promptWithContinuity = if (recentContextLines.isEmpty()) {
            userText
        } else {
            "Prior conversation context:\n${recentContextLines.joinToString("\n")}\n\nCurrent ${modality.name} turn ($localeTag): $userText"
        }

        val outcome = try {
            if (personaArchitectureRepository != null) {
                personaArchitectureRepository.interactWithPersona(
                    persona = persona,
                    taskTitle = "${modality.name} dialogue ($localeTag)",
                    prompt = promptWithContinuity,
                    projectId = projectId,
                    requiredModalities = if (modality == ConversationModality.VOICE) {
                        setOf(AiModality.TEXT, AiModality.AUDIO)
                    } else {
                        setOf(AiModality.TEXT)
                    },
                    dataClassification = dataClassification,
                    explicitSensitiveDataAuthorized = explicitSensitiveDataAuthorized,
                    allowLocalOpenWeight = true,
                    useGuardianDigitalTwinIdentity = useGuardianDigitalTwinIdentity,
                )
            } else {
                AiRouteOutcome.PausedQuotaOrProviderUnavailable(
                    messageEn = "Persona architecture repository not connected.",
                    messageFa = "مخزن معماری پرسونا متصل نیست.",
                    attemptedProviders = emptyList(),
                )
            }
        } catch (t: Throwable) {
            releaseTurnLockOnError(tenantScope, turnId, t.message ?: "Turn execution error")
            return Result.failure(t)
        }

        // Check if owner interrupted while THINKING
        val wasInterruptedWhileThinking = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            turnId in tenant.interruptedTurnIds
        }
        if (wasInterruptedWhileThinking) {
            val interruptedTurn = UnifiedConversationTurn(
                turnId = turnId,
                tenantKey = tenantScope.key,
                persona = persona,
                modality = modality,
                localeTag = localeTag,
                userInputSummary = sanitizeInputForHistory(userText, dataClassification),
                assistantResponseEn = "[Interrupted during thinking]",
                assistantResponseFa = "[در حال تفکر توسط مالک متوقف شد]",
                styleUsed = activeVoiceProfile.activeStyle,
                interruptedByOwner = true,
                isSynthesisEngineConfigured = speechOutputPort.isSynthesisEngineConfigured,
                timestampEpochMs = System.currentTimeMillis(),
            )
            mutex.withLock {
                val tenant = getOrCreateTenantLocked(tenantScope)
                tenant.unifiedHistory.add(0, interruptedTurn)
                publishLocked()
            }
            return Result.success(interruptedTurn)
        }

        val (replyEn, replyFa) = extractReplyPair(persona, outcome)

        val preliminaryTurn = UnifiedConversationTurn(
            turnId = turnId,
            tenantKey = tenantScope.key,
            persona = persona,
            modality = modality,
            localeTag = localeTag,
            userInputSummary = sanitizeInputForHistory(userText, dataClassification),
            assistantResponseEn = replyEn,
            assistantResponseFa = replyFa,
            styleUsed = if (speakAloud) activeVoiceProfile.activeStyle else null,
            interruptedByOwner = false,
            isSynthesisEngineConfigured = speechOutputPort.isSynthesisEngineConfigured,
            timestampEpochMs = System.currentTimeMillis(),
        )

        if (!speakAloud) {
            mutex.withLock {
                val tenant = getOrCreateTenantLocked(tenantScope)
                tenant.unifiedHistory.add(0, preliminaryTurn)
                if (tenant.activeTurnLock?.activeTurnId == turnId) {
                    tenant.activeTurnLock = null
                    tenant.audioState = ConversationAudioState.IDLE
                }
                tenant.statusNotice = "Text turn preserved in unified context"
                publishLocked()
            }
            return Result.success(preliminaryTurn)
        }

        // Transition lock from THINKING -> SPEAKING before calling output port (release mutex during playback)
        val tuning = activeVoiceProfile.styleTunings.getValue(activeVoiceProfile.activeStyle)
        val offloadTarget = if (deviceProfile.offloadTtsAndCloningToServer) {
            ProcessingOffloadTarget.SERVER_ASSISTED_OFFLOAD
        } else {
            ProcessingOffloadTarget.CLIENT_LIGHTWEIGHT_MOBILE
        }
        mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            tenant.unifiedHistory.add(0, preliminaryTurn)
            tenant.activeTurnLock = TurnOwnershipLock(
                activeTurnId = turnId,
                persona = persona,
                state = ConversationAudioState.SPEAKING,
                startedAtEpochMs = System.currentTimeMillis(),
            )
            tenant.audioState = ConversationAudioState.SPEAKING
            tenant.statusNotice = "${persona.name} speaking (${activeVoiceProfile.activeStyle.name})"
            publishLocked()
        }

        val playbackResult = speechOutputPort.synthesizeAndPlay(
            SpeechSynthesisRequest(
                turnId = turnId,
                tenantKey = tenantScope.key,
                persona = persona,
                textEn = replyEn,
                textFa = replyFa,
                localeTag = localeTag,
                voiceProfile = activeVoiceProfile,
                activeTuning = tuning,
                offloadTarget = offloadTarget,
            )
        )

        val finalTurn = mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            val interrupted = turnId in tenant.interruptedTurnIds ||
                playbackResult.getOrNull()?.interruptedByOwner == true
            if (tenant.activeTurnLock?.activeTurnId == turnId) {
                tenant.activeTurnLock = null
                tenant.audioState = when {
                    interrupted -> ConversationAudioState.INTERRUPTED
                    playbackResult.isFailure -> ConversationAudioState.PAUSED_ERROR
                    else -> ConversationAudioState.IDLE
                }
            }
            val idx = tenant.unifiedHistory.indexOfFirst { it.turnId == turnId }
            val updatedTurn = if (idx >= 0) {
                tenant.unifiedHistory[idx].copy(
                    interruptedByOwner = interrupted,
                    isSynthesisEngineConfigured = playbackResult.getOrNull()?.isSynthesisEngineConfigured
                        ?: speechOutputPort.isSynthesisEngineConfigured,
                ).also { tenant.unifiedHistory[idx] = it }
            } else {
                preliminaryTurn.copy(interruptedByOwner = interrupted)
            }
            tenant.statusNotice = playbackResult.fold(
                onSuccess = { if (interrupted) "Speech interrupted by owner" else it.statusNoteEn },
                onFailure = { it.message ?: "Speech synthesis failed" },
            )
            publishLocked()
            updatedTurn
        }

        return Result.success(finalTurn)
    }

    private suspend fun releaseTurnLockOnError(
        tenantScope: TenantScope,
        turnId: String,
        message: String,
    ) {
        mutex.withLock {
            val tenant = getOrCreateTenantLocked(tenantScope)
            if (tenant.activeTurnLock?.activeTurnId == turnId) {
                tenant.activeTurnLock = null
            }
            tenant.audioState = ConversationAudioState.PAUSED_ERROR
            tenant.statusNotice = message
            publishLocked()
        }
    }

    private fun extractReplyPair(persona: AfrinaPersona, outcome: AiRouteOutcome): Pair<String, String> =
        when (outcome) {
            is AiRouteOutcome.Success -> outcome.outputEn to outcome.outputFa
            is AiRouteOutcome.PrivacyAuthorizationRequired -> outcome.messageEn to outcome.messageFa
            is AiRouteOutcome.PaidApprovalRequired -> outcome.proposal.reasonEn to outcome.proposal.reasonFa
            is AiRouteOutcome.PausedQuotaOrProviderUnavailable -> outcome.messageEn to outcome.messageFa
        }.let { (en, fa) ->
            en.ifBlank { "${persona.titleEn} ready." } to fa.ifBlank { "${persona.titleFa} آماده است." }
        }

    private fun sanitizeInputForHistory(text: String, classification: DataClassification): String =
        if (classification.isSensitiveOwnerData) {
            "[Owner-Private Voice/Text Turn (${text.length} chars)]"
        } else {
            text.take(120)
        }

    private fun getOrCreateTenantLocked(scope: TenantScope): MutableVoiceTenantState =
        tenants.getOrPut(scope.key) { createInitialTenantState(scope) }

    private fun createInitialTenantState(scope: TenantScope): MutableVoiceTenantState {
        val state = MutableVoiceTenantState(tenantScope = scope)
        val now = System.currentTimeMillis()
        for (persona in AfrinaPersona.entries) {
            val base = defaultProfiles.getValue(persona)
            state.voiceProfiles[persona] = defaultVoiceProfile(scope, base, now)
        }
        return state
    }

    private fun defaultVoiceProfile(
        scope: TenantScope,
        base: PersonaProfile,
        now: Long,
    ): PersonaVoiceProfile {
        val tunings = mapOf(
            VoicePresentationStyle.NATURAL_CONVERSATIONAL to VoiceStyleTuning(
                style = VoicePresentationStyle.NATURAL_CONVERSATIONAL,
                pitchShift = if (base.persona == AfrinaPersona.GUARDIAN_SENTINEL) -0.05 else 0.0,
                speakingRate = 1.0,
                warmthLevel = 0.72,
                pauseCadenceFactor = 1.0,
            ),
            VoicePresentationStyle.EXECUTIVE_CONCISE to VoiceStyleTuning(
                style = VoicePresentationStyle.EXECUTIVE_CONCISE,
                pitchShift = if (base.persona == AfrinaPersona.GUARDIAN_SENTINEL) -0.08 else -0.02,
                speakingRate = 1.08,
                warmthLevel = 0.45,
                pauseCadenceFactor = 0.85,
            ),
            VoicePresentationStyle.EXPRESSIVE_STORYTELLING to VoiceStyleTuning(
                style = VoicePresentationStyle.EXPRESSIVE_STORYTELLING,
                pitchShift = if (base.persona == AfrinaPersona.GUARDIAN_SENTINEL) -0.02 else 0.05,
                speakingRate = 0.94,
                warmthLevel = 0.88,
                pauseCadenceFactor = 1.2,
            ),
        )
        return PersonaVoiceProfile(
            profileId = "vp-${scope.key}-${base.persona.name.lowercase()}",
            tenantKey = scope.key,
            persona = base.persona,
            voiceName = "${base.canonicalName} Studio Voice",
            genderPresentation = base.genderPresentation,
            supportedLocaleTags = listOf("fa-IR", "en-US"),
            activeLocaleTag = "fa-IR",
            activeStyle = when (base.persona) {
                AfrinaPersona.ARCHITECT_STRATEGIST,
                AfrinaPersona.BUILDER_ENGINEER,
                AfrinaPersona.GUARDIAN_SENTINEL -> VoicePresentationStyle.EXECUTIVE_CONCISE
                AfrinaPersona.COMPANION_EMPATHY -> VoicePresentationStyle.NATURAL_CONVERSATIONAL
            },
            styleTunings = tunings,
            updatedAtEpochMs = now,
        )
    }

    private fun MutableVoiceTenantState.toSnapshot(): VoiceConversationSnapshot =
        VoiceConversationSnapshot(
            tenantScope = tenantScope,
            permissionState = permissionState,
            audioState = audioState,
            activePersona = activePersona,
            activeLocaleTag = activeLocaleTag,
            activeTurnLock = activeTurnLock,
            lastVadEvaluation = lastVadEvaluation,
            livePartialTranscript = livePartialTranscript,
            statusNotice = statusNotice,
            deviceProfile = deviceProfile,
            voiceProfiles = voiceProfiles.toMap(),
            unifiedHistory = unifiedHistory.toList(),
            recordings = recordings.toList(),
            collaborationSessions = collaborationSessions.toList(),
        )

    private fun publishLocked() {
        snapshotsFlow.value = tenants.mapValues { (_, state) -> state.toSnapshot() }
    }

    private fun normalizeLocaleTag(localeTag: String): String =
        localeTag.trim().ifBlank { "fa-IR" }

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
