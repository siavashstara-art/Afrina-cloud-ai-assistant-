package com.example.afrina.core.voice

import com.example.afrina.core.persona.ContentAndLikenessPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.log10
import kotlin.math.max

/**
 * Explicit microphone hardware input contract.
 * Actual Android AudioRecord capture must check RECORD_AUDIO permission before calling [startCapture].
 */
interface MicrophoneAudioCapturePort {
    val isCapturing: StateFlow<Boolean>
    suspend fun startCapture(frameDurationMs: Int = 20, sampleRateHz: Int = 16_000): Result<Unit>
    suspend fun stopCapture()
    fun frames(): Flow<AudioFramePacket>
}

class PreparedUnconfiguredMicrophoneCapturePort : MicrophoneAudioCapturePort {
    private val capturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = capturing.asStateFlow()

    override suspend fun startCapture(frameDurationMs: Int, sampleRateHz: Int): Result<Unit> =
        Result.failure(
            IllegalStateException("Live AudioRecord capture is not attached in this foundation build; supply frames or platform AudioRecord adapter.")
        )

    override suspend fun stopCapture() {
        capturing.value = false
    }

    override fun frames(): Flow<AudioFramePacket> = emptyFlow()
}

/**
 * Lightweight, mobile-first Voice Activity & End-of-Turn detector designed to avoid
 * heavy neural inference on mid-range hardware such as Redmi Note 8.
 */
interface VoiceActivityDetector {
    fun reset()
    fun evaluateFrame(frame: AudioFramePacket, policy: EndOfTurnPolicy = EndOfTurnPolicy()): VadEvaluation
}

class LightweightEnergyVadDetector(
    initialNoiseFloorRms: Double = 0.02,
) : VoiceActivityDetector {

    private var noiseFloorRms: Double = initialNoiseFloorRms.coerceAtLeast(0.002)
    private var speechDetectedInTurn: Boolean = false
    private var activeSpeechDurationMs: Long = 0L
    private var silenceElapsedMs: Long = 0L
    private var continuousCandidateSpeechMs: Long = 0L

    @Synchronized
    override fun reset() {
        speechDetectedInTurn = false
        activeSpeechDurationMs = 0L
        silenceElapsedMs = 0L
        continuousCandidateSpeechMs = 0L
    }

    @Synchronized
    override fun evaluateFrame(frame: AudioFramePacket, policy: EndOfTurnPolicy): VadEvaluation {
        val safeNoiseFloor = max(noiseFloorRms, 0.002)
        val snrDb = 20.0 * log10(max(frame.rmsEnergy, 0.0001) / safeNoiseFloor)
        val passesEnergyAndSnr = snrDb >= policy.minSnrDb && frame.rmsEnergy > safeNoiseFloor * 1.5
        val looksLikeSpeech = passesEnergyAndSnr && frame.zeroCrossingRate in 0.03..0.65

        val decision = if (looksLikeSpeech) {
            continuousCandidateSpeechMs += frame.durationMs
            silenceElapsedMs = 0L
            if (continuousCandidateSpeechMs >= policy.minSpeechBurstMs || speechDetectedInTurn) {
                speechDetectedInTurn = true
                activeSpeechDurationMs += frame.durationMs
                VadDecision.SPEECH_ACTIVE
            } else {
                // Short impulse not yet confirmed as speech (protects against transient knocks/clicks)
                VadDecision.NOISE_REJECTED
            }
        } else {
            continuousCandidateSpeechMs = 0L
            // Slowly adapt noise floor only during non-speech frames
            noiseFloorRms = ((1.0 - policy.noiseAdaptationRate) * noiseFloorRms) +
                (policy.noiseAdaptationRate * frame.rmsEnergy.coerceAtLeast(0.002))

            if (!speechDetectedInTurn) {
                if (frame.rmsEnergy > safeNoiseFloor * 1.2) VadDecision.NOISE_REJECTED else VadDecision.SILENCE
            } else {
                silenceElapsedMs += frame.durationMs
                when {
                    silenceElapsedMs >= policy.endOfTurnSilenceMs -> VadDecision.END_OF_TURN
                    silenceElapsedMs >= 0L -> VadDecision.PAUSE_HOLDING
                    else -> VadDecision.SILENCE
                }
            }
        }

        return VadEvaluation(
            decision = decision,
            signalToNoiseRatioDb = snrDb,
            noiseFloorRms = noiseFloorRms,
            activeSpeechDurationMs = activeSpeechDurationMs,
            silenceElapsedMs = silenceElapsedMs,
            timestampMs = frame.timestampMs,
        )
    }
}

/**
 * Streaming Speech-to-Text abstraction supporting Persian (fa-IR) and multilingual locales,
 * usable with lightweight on-device recognizers or server-assisted ASR offload.
 */
interface SpeechToTextEngine {
    val engineId: String
    val isConfigured: Boolean
    val supportedLocales: List<String>

    suspend fun beginStream(localeTag: String): Result<String>
    suspend fun pushAudioFrame(sessionId: String, frame: AudioFramePacket): Result<SpeechRecognitionPartial?>
    suspend fun finalizeUtterance(sessionId: String): Result<SpeechRecognitionFinal>
    suspend fun cancelStream(sessionId: String)
}

class PreparedUnconfiguredSpeechToTextEngine : SpeechToTextEngine {
    override val engineId: String = "prepared-unconfigured-stt"
    override val isConfigured: Boolean = false
    override val supportedLocales: List<String> = listOf("fa-IR", "en-US")

    override suspend fun beginStream(localeTag: String): Result<String> =
        Result.failure(
            IllegalStateException("Real-time STT adapter is prepared-only and not configured (no external API or local ASR model active).")
        )

    override suspend fun pushAudioFrame(sessionId: String, frame: AudioFramePacket): Result<SpeechRecognitionPartial?> =
        Result.failure(IllegalStateException("STT stream '$sessionId' unavailable: STT adapter not configured."))

    override suspend fun finalizeUtterance(sessionId: String): Result<SpeechRecognitionFinal> =
        Result.failure(IllegalStateException("STT finalization unavailable: STT adapter not configured."))

    override suspend fun cancelStream(sessionId: String) = Unit
}

/**
 * Voice synthesis & interruption-capable audio playback contract.
 */
interface SpeechOutputPort {
    val portId: String
    val isSynthesisEngineConfigured: Boolean
    val isPlaying: StateFlow<Boolean>

    suspend fun synthesizeAndPlay(request: SpeechSynthesisRequest): Result<SpeechPlaybackResult>
    suspend fun interruptPlayback(): Boolean
}

class PreparedUnconfiguredSpeechOutputPort : SpeechOutputPort {
    override val portId: String = "prepared-unconfigured-tts"
    override val isSynthesisEngineConfigured: Boolean = false
    private val playing = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = playing.asStateFlow()

    override suspend fun synthesizeAndPlay(request: SpeechSynthesisRequest): Result<SpeechPlaybackResult> {
        return Result.success(
            SpeechPlaybackResult(
                turnId = request.turnId,
                portId = portId,
                isSynthesisEngineConfigured = false,
                completedNaturally = false,
                interruptedByOwner = false,
                durationMs = 0L,
                statusNoteEn = "Speech output prepared (${request.activeTuning.style.name}, ${request.localeTag}); live TTS/voice-cloning engine not yet connected.",
                statusNoteFa = "خروجی صوتی آماده است (${request.activeTuning.style.titleFa}، ${request.localeTag})؛ موتور سنتز صدا/کلون هنوز متصل نشده است.",
            )
        )
    }

    override suspend fun interruptPlayback(): Boolean {
        val wasPlaying = playing.value
        playing.value = false
        return wasPlaying
    }
}

/**
 * Prepared interface for singing, narration, dubbing, music creation, and duet collaboration.
 */
interface AudioCollaborationPort {
    suspend fun prepareSession(
        spec: AudioCollaborationSessionSpec,
        tenantKey: String,
        deviceProfile: DeviceAudioCapabilityProfile,
    ): Result<AudioCollaborationReadinessResult>
}

class PreparedAudioCollaborationGateway : AudioCollaborationPort {
    override suspend fun prepareSession(
        spec: AudioCollaborationSessionSpec,
        tenantKey: String,
        deviceProfile: DeviceAudioCapabilityProfile,
    ): Result<AudioCollaborationReadinessResult> {
        ContentAndLikenessPolicy.validateTextContent(spec.title, "collaborationTitle")
            .onFailure { return Result.failure(it) }

        when (spec) {
            is AudioCollaborationSessionSpec.SingingSessionSpec -> {
                if (spec.tempoBpm !in 30..240) {
                    return Result.failure(IllegalArgumentException("Tempo BPM must be between 30 and 240."))
                }
                if (spec.authorizedHumanVoiceAssetId != null) {
                    ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                        explicitAuthorized = spec.explicitVoiceAuthorized,
                        subjectAgeStatus = spec.subjectAgeStatus,
                        assetKindLabel = "SINGING_HUMAN_VOICE",
                    ).onFailure { return Result.failure(it) }
                }
            }
            is AudioCollaborationSessionSpec.DubbingSessionSpec -> {
                if (spec.authorizedHumanVoiceAssetId != null) {
                    ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                        explicitAuthorized = spec.explicitVoiceAuthorized,
                        subjectAgeStatus = spec.subjectAgeStatus,
                        assetKindLabel = "DUBBING_HUMAN_VOICE",
                    ).onFailure { return Result.failure(it) }
                }
            }
            is AudioCollaborationSessionSpec.DuetPerformanceSpec -> {
                if (spec.tempoBpm !in 30..240) {
                    return Result.failure(IllegalArgumentException("Duet tempo BPM must be between 30 and 240."))
                }
                ContentAndLikenessPolicy.validateLikenessOrVoiceAuthorization(
                    explicitAuthorized = spec.explicitPartnerVoiceAuthorized,
                    subjectAgeStatus = spec.partnerSubjectAgeStatus,
                    assetKindLabel = "DUET_PARTNER_VOICE",
                ).onFailure { return Result.failure(it) }
            }
            is AudioCollaborationSessionSpec.MusicCreationSpec -> {
                if (spec.tempoBpm !in 30..240 || spec.stemRoles.isEmpty()) {
                    return Result.failure(IllegalArgumentException("Music session requires valid tempo (30..240 BPM) and at least one stem role."))
                }
            }
            is AudioCollaborationSessionSpec.NarrationSessionSpec -> {
                if (spec.scriptSummary.isBlank()) {
                    return Result.failure(IllegalArgumentException("Narration script summary must be non-blank."))
                }
            }
        }

        val offloadTarget = if (deviceProfile.offloadAudioCollaborationToServer) {
            ProcessingOffloadTarget.SERVER_ASSISTED_OFFLOAD
        } else {
            ProcessingOffloadTarget.CLIENT_LIGHTWEIGHT_MOBILE
        }

        return Result.success(
            AudioCollaborationReadinessResult(
                sessionId = spec.sessionId,
                tenantKey = tenantKey,
                mode = spec.mode,
                title = spec.title.trim(),
                accepted = true,
                offloadTarget = offloadTarget,
                isPreparedOnly = true,
                statusNote = "Prepared ${spec.mode.name} collaboration session for $offloadTarget (no external synthesis active).",
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
    }
}
