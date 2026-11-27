package com.example.afrina.core.voice

import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.persona.GenderPresentation
import com.example.afrina.core.persona.SubjectAgePolicyStatus
import com.example.afrina.core.persona.TenantScope

enum class MicrophonePermissionState {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
}

enum class ConversationAudioState {
    IDLE,
    PERMISSION_REQUIRED,
    LISTENING,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    PAUSED_ERROR,
}

enum class ConversationModality {
    VOICE,
    TEXT,
}

enum class VadDecision {
    SILENCE,
    NOISE_REJECTED,
    SPEECH_ACTIVE,
    PAUSE_HOLDING,
    END_OF_TURN,
}

data class AudioFramePacket(
    val sequenceId: Long,
    val timestampMs: Long,
    val rmsEnergy: Double,
    val peakAmplitude: Double = rmsEnergy,
    val zeroCrossingRate: Double = 0.15,
    val durationMs: Int = 20,
) {
    init {
        require(rmsEnergy in 0.0..1.0) { "rmsEnergy must be normalized between 0.0 and 1.0." }
        require(peakAmplitude in 0.0..1.0) { "peakAmplitude must be normalized between 0.0 and 1.0." }
        require(durationMs in 5..200) { "frame durationMs must be lightweight mobile frame (5..200ms)." }
    }
}

data class EndOfTurnPolicy(
    val sampleRateHz: Int = 16_000,
    val frameDurationMs: Int = 20,
    val pauseToleranceMs: Long = 550L,
    val endOfTurnSilenceMs: Long = 1_100L,
    val minSpeechBurstMs: Long = 160L,
    val minSnrDb: Double = 6.0,
    val initialNoiseFloorRms: Double = 0.02,
    val noiseAdaptationRate: Double = 0.08,
)

data class VadEvaluation(
    val decision: VadDecision,
    val signalToNoiseRatioDb: Double,
    val noiseFloorRms: Double,
    val activeSpeechDurationMs: Long,
    val silenceElapsedMs: Long,
    val timestampMs: Long,
)

data class SpeechRecognitionPartial(
    val utteranceId: String,
    val text: String,
    val localeTag: String,
    val confidence: Double,
    val isFinal: Boolean = false,
)

data class SpeechRecognitionFinal(
    val utteranceId: String,
    val text: String,
    val localeTag: String,
    val confidence: Double,
    val engineId: String,
    val isRealSpeechRecognition: Boolean,
)

enum class VoicePresentationStyle(
    val titleEn: String,
    val titleFa: String,
) {
    NATURAL_CONVERSATIONAL(
        titleEn = "Natural Conversational",
        titleFa = "گفت‌وگوی طبیعی و روان",
    ),
    EXECUTIVE_CONCISE(
        titleEn = "Executive Concise",
        titleFa = "رسمی، کوتاه و دقیق",
    ),
    EXPRESSIVE_STORYTELLING(
        titleEn = "Expressive Storytelling",
        titleFa = "بیانی و روایت‌گر",
    ),
}

data class VoiceStyleTuning(
    val style: VoicePresentationStyle,
    val pitchShift: Double = 0.0,
    val speakingRate: Double = 1.0,
    val warmthLevel: Double = 0.65,
    val pauseCadenceFactor: Double = 1.0,
) {
    fun normalized(): VoiceStyleTuning = copy(
        pitchShift = pitchShift.coerceIn(-0.5, 0.5),
        speakingRate = speakingRate.coerceIn(0.5, 1.8),
        warmthLevel = warmthLevel.coerceIn(0.0, 1.0),
        pauseCadenceFactor = pauseCadenceFactor.coerceIn(0.5, 1.5),
    )
}

enum class VoiceCloneReadiness {
    STANDARD_SYNTHETIC_ONLY,
    AUTHORIZED_SAMPLE_LINKED_AWAITING_ENGINE,
    BLOCKED_UNAUTHORIZED,
}

data class PersonaVoiceProfile(
    val profileId: String,
    val tenantKey: String,
    val persona: AfrinaPersona,
    val voiceName: String,
    val genderPresentation: GenderPresentation,
    val supportedLocaleTags: List<String> = listOf("fa-IR", "en-US"),
    val activeLocaleTag: String = "fa-IR",
    val activeStyle: VoicePresentationStyle = VoicePresentationStyle.NATURAL_CONVERSATIONAL,
    val styleTunings: Map<VoicePresentationStyle, VoiceStyleTuning>,
    val authorizedVoiceAssetId: String? = null,
    val explicitOwnerVoiceAuthorized: Boolean = false,
    val subjectAgeStatus: SubjectAgePolicyStatus = SubjectAgePolicyStatus.UNVERIFIED,
    val cloneReadiness: VoiceCloneReadiness = VoiceCloneReadiness.STANDARD_SYNTHETIC_ONLY,
    val isDeleted: Boolean = false,
    val updatedAtEpochMs: Long,
)

data class SpeechSynthesisRequest(
    val turnId: String,
    val tenantKey: String,
    val persona: AfrinaPersona,
    val textEn: String,
    val textFa: String,
    val localeTag: String,
    val voiceProfile: PersonaVoiceProfile,
    val activeTuning: VoiceStyleTuning,
    val offloadTarget: ProcessingOffloadTarget,
)

data class SpeechPlaybackResult(
    val turnId: String,
    val portId: String,
    val isSynthesisEngineConfigured: Boolean,
    val completedNaturally: Boolean,
    val interruptedByOwner: Boolean,
    val durationMs: Long,
    val statusNoteEn: String,
    val statusNoteFa: String,
)

enum class ProcessingOffloadTarget {
    CLIENT_LIGHTWEIGHT_MOBILE,
    SERVER_ASSISTED_OFFLOAD,
}

data class DeviceAudioCapabilityProfile(
    val deviceTierName: String = "RedmiNote8_Snapdragon665_4GB_SafeProfile",
    val preferLightweightEdgeVad: Boolean = true,
    val offloadAsrToServerWhenAvailable: Boolean = true,
    val offloadTtsAndCloningToServer: Boolean = true,
    val offloadAudioCollaborationToServer: Boolean = true,
    val maxConcurrentAudioStreams: Int = 1,
    val maxBufferedFrames: Int = 32,
)

data class TurnOwnershipLock(
    val activeTurnId: String,
    val persona: AfrinaPersona,
    val state: ConversationAudioState,
    val startedAtEpochMs: Long,
)

data class UnifiedConversationTurn(
    val turnId: String,
    val tenantKey: String,
    val persona: AfrinaPersona,
    val modality: ConversationModality,
    val localeTag: String,
    val userInputSummary: String,
    val assistantResponseEn: String,
    val assistantResponseFa: String,
    val styleUsed: VoicePresentationStyle? = null,
    val interruptedByOwner: Boolean = false,
    val isSynthesisEngineConfigured: Boolean = false,
    val timestampEpochMs: Long,
)

enum class AudioCollaborationMode {
    SINGING,
    NARRATION,
    DUBBING,
    MUSIC_CREATION,
    DUET_PERFORMANCE,
}

sealed interface AudioCollaborationSessionSpec {
    val sessionId: String
    val mode: AudioCollaborationMode
    val title: String
    val localeTag: String
    val leadPersona: AfrinaPersona

    data class SingingSessionSpec(
        override val sessionId: String,
        override val title: String,
        override val localeTag: String = "fa-IR",
        override val leadPersona: AfrinaPersona = AfrinaPersona.COMPANION_EMPATHY,
        val keySignature: String,
        val tempoBpm: Int,
        val lyricsOutline: String,
        val authorizedHumanVoiceAssetId: String? = null,
        val explicitVoiceAuthorized: Boolean = false,
        val subjectAgeStatus: SubjectAgePolicyStatus = SubjectAgePolicyStatus.UNVERIFIED,
    ) : AudioCollaborationSessionSpec {
        override val mode: AudioCollaborationMode = AudioCollaborationMode.SINGING
    }

    data class NarrationSessionSpec(
        override val sessionId: String,
        override val title: String,
        override val localeTag: String = "fa-IR",
        override val leadPersona: AfrinaPersona = AfrinaPersona.ARCHITECT_STRATEGIST,
        val scriptSummary: String,
        val presentationStyle: VoicePresentationStyle = VoicePresentationStyle.EXPRESSIVE_STORYTELLING,
    ) : AudioCollaborationSessionSpec {
        override val mode: AudioCollaborationMode = AudioCollaborationMode.NARRATION
    }

    data class DubbingSessionSpec(
        override val sessionId: String,
        override val title: String,
        override val localeTag: String = "fa-IR",
        override val leadPersona: AfrinaPersona = AfrinaPersona.BUILDER_ENGINEER,
        val sourceLanguageTag: String,
        val preserveOriginalTimingCues: Boolean = true,
        val targetSpeakerPersona: AfrinaPersona = leadPersona,
        val authorizedHumanVoiceAssetId: String? = null,
        val explicitVoiceAuthorized: Boolean = false,
        val subjectAgeStatus: SubjectAgePolicyStatus = SubjectAgePolicyStatus.UNVERIFIED,
    ) : AudioCollaborationSessionSpec {
        override val mode: AudioCollaborationMode = AudioCollaborationMode.DUBBING
    }

    data class MusicCreationSpec(
        override val sessionId: String,
        override val title: String,
        override val localeTag: String = "fa-IR",
        override val leadPersona: AfrinaPersona = AfrinaPersona.COMPANION_EMPATHY,
        val genre: String,
        val tempoBpm: Int,
        val keySignature: String,
        val stemRoles: List<String>,
    ) : AudioCollaborationSessionSpec {
        override val mode: AudioCollaborationMode = AudioCollaborationMode.MUSIC_CREATION
    }

    data class DuetPerformanceSpec(
        override val sessionId: String,
        override val title: String,
        override val localeTag: String = "fa-IR",
        override val leadPersona: AfrinaPersona = AfrinaPersona.GUARDIAN_SENTINEL,
        val partnerLabel: String,
        val authorizedPartnerVoiceAssetId: String,
        val explicitPartnerVoiceAuthorized: Boolean,
        val partnerSubjectAgeStatus: SubjectAgePolicyStatus,
        val tempoBpm: Int,
        val harmonyArrangement: String,
    ) : AudioCollaborationSessionSpec {
        override val mode: AudioCollaborationMode = AudioCollaborationMode.DUET_PERFORMANCE
    }
}

data class AudioCollaborationReadinessResult(
    val sessionId: String,
    val tenantKey: String,
    val mode: AudioCollaborationMode,
    val title: String,
    val accepted: Boolean,
    val offloadTarget: ProcessingOffloadTarget,
    val isPreparedOnly: Boolean = true,
    val statusNote: String,
    val createdAtEpochMs: Long,
)

data class VoiceRecordingPrivacyRecord(
    val recordingId: String,
    val tenantKey: String,
    val persona: AfrinaPersona,
    val privateVaultAlias: String,
    val durationMs: Long,
    val dataClassification: DataClassification,
    val explicitOwnerAuthorized: Boolean,
    val subjectAgeStatus: SubjectAgePolicyStatus,
    val allowSttTranscription: Boolean,
    val allowVoiceCloningUsage: Boolean,
    val isDeleted: Boolean = false,
    val createdAtEpochMs: Long,
)

data class VoiceConversationSnapshot(
    val tenantScope: TenantScope = TenantScope.MasterOwner,
    val permissionState: MicrophonePermissionState = MicrophonePermissionState.NOT_REQUESTED,
    val audioState: ConversationAudioState = ConversationAudioState.IDLE,
    val activePersona: AfrinaPersona = AfrinaPersona.COMPANION_EMPATHY,
    val activeLocaleTag: String = "fa-IR",
    val activeTurnLock: TurnOwnershipLock? = null,
    val lastVadEvaluation: VadEvaluation? = null,
    val livePartialTranscript: SpeechRecognitionPartial? = null,
    val statusNotice: String? = null,
    val deviceProfile: DeviceAudioCapabilityProfile = DeviceAudioCapabilityProfile(),
    val voiceProfiles: Map<AfrinaPersona, PersonaVoiceProfile> = emptyMap(),
    val unifiedHistory: List<UnifiedConversationTurn> = emptyList(),
    val recordings: List<VoiceRecordingPrivacyRecord> = emptyList(),
    val collaborationSessions: List<AudioCollaborationReadinessResult> = emptyList(),
)
