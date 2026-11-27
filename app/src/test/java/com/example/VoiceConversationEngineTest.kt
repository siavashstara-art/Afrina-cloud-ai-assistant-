package com.example

import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.FreeTierFallbackProxyAdapter
import com.example.afrina.core.ai.FreeTierPrimaryProxyAdapter
import com.example.afrina.core.ai.PaidFrontierCloudAdapter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.ai.SelfHostedOpenWeightAdapter
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AiUsageLedgerEntry
import com.example.afrina.core.model.CostTransparencySummary
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.FallbackEvent
import com.example.afrina.core.model.OwnerSpendingPolicy
import com.example.afrina.core.model.PaidInferenceProposal
import com.example.afrina.core.model.ProviderQuotaStatus
import com.example.afrina.core.model.TaskRoutingRequest
import com.example.afrina.core.persona.AfrinaEdition
import com.example.afrina.core.persona.CentralPersonaOrchestrator
import com.example.afrina.core.persona.MediaAssetKind
import com.example.afrina.core.persona.PersonaIdentityEngine
import com.example.afrina.core.persona.SharedMemoryManager
import com.example.afrina.core.persona.SubjectAgePolicyStatus
import com.example.afrina.core.persona.TenantScope
import com.example.afrina.core.voice.AudioCollaborationMode
import com.example.afrina.core.voice.AudioCollaborationSessionSpec
import com.example.afrina.core.voice.AudioFramePacket
import com.example.afrina.core.voice.ConversationAudioState
import com.example.afrina.core.voice.ConversationModality
import com.example.afrina.core.voice.EndOfTurnPolicy
import com.example.afrina.core.voice.MicrophonePermissionState
import com.example.afrina.core.voice.ProcessingOffloadTarget
import com.example.afrina.core.voice.RealTimeVoiceConversationEngine
import com.example.afrina.core.voice.SpeechOutputPort
import com.example.afrina.core.voice.SpeechPlaybackResult
import com.example.afrina.core.voice.SpeechRecognitionFinal
import com.example.afrina.core.voice.SpeechRecognitionPartial
import com.example.afrina.core.voice.SpeechSynthesisRequest
import com.example.afrina.core.voice.SpeechToTextEngine
import com.example.afrina.core.voice.VadDecision
import com.example.afrina.core.voice.VoiceCloneReadiness
import com.example.afrina.core.voice.VoicePresentationStyle
import com.example.afrina.core.voice.VoiceStyleTuning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationEngineTest {

    private fun createRouterAndOrchestrator(
        capturedPrompts: MutableList<String> = mutableListOf(),
    ): Pair<CentralPersonaOrchestrator, PersonaIdentityEngine> {
        val router = CentralAiProviderRouter(
            adapters = listOf(
                FreeTierPrimaryProxyAdapter(),
                FreeTierFallbackProxyAdapter(),
                SelfHostedOpenWeightAdapter(),
                PaidFrontierCloudAdapter(),
            ),
            approvalGrantResolver = { null },
        )
        val dispatcher = PersonaDispatcher(router)
        val statuses = MutableStateFlow(router.providerStatuses())
        val policy = MutableStateFlow(router.spendingPolicy())
        val ledger = MutableStateFlow<List<AiUsageLedgerEntry>>(emptyList())
        val cost = MutableStateFlow(CostTransparencySummary())
        val paid = MutableStateFlow<List<PaidInferenceProposal>>(emptyList())
        val fallbacks = MutableStateFlow<List<FallbackEvent>>(emptyList())
        val latest = MutableStateFlow<AiRouteOutcome?>(null)

        val routerRepo = object : AiRouterRepository {
            override fun observeProviderStatuses(): Flow<List<ProviderQuotaStatus>> = statuses
            override fun observeSpendingPolicy(): Flow<OwnerSpendingPolicy> = policy
            override fun observeUsageLedger(): Flow<List<AiUsageLedgerEntry>> = ledger
            override fun observeCostSummary(): Flow<CostTransparencySummary> = cost
            override fun observePaidApprovals(): Flow<List<PaidInferenceProposal>> = paid
            override fun observeFallbackEvents(): Flow<List<FallbackEvent>> = fallbacks
            override fun observeLatestOutcome(): Flow<AiRouteOutcome?> = latest
            override suspend fun executePersonaTask(request: TaskRoutingRequest): AiRouteOutcome {
                capturedPrompts += request.prompt
                return dispatcher.runPersonaTask(
                    persona = request.persona,
                    taskTitle = request.taskTitle,
                    prompt = request.prompt,
                    complexity = request.complexity,
                    requiresCoding = request.requiresCoding,
                    requiredModalities = request.requiredModalities,
                    dataClassification = request.dataClassification,
                    explicitSensitiveDataAuthorized = request.explicitSensitiveDataAuthorized,
                    explicitPaidApprovalId = request.explicitPaidApprovalId,
                    allowLocalOpenWeight = request.allowLocalOpenWeight,
                ).also { latest.value = it }
            }
            override suspend fun approvePaidInference(proposalId: String) = Unit
            override suspend fun rejectPaidInference(proposalId: String) = Unit
            override suspend fun executeApprovedPaidTask(proposalId: String): AiRouteOutcome =
                throw UnsupportedOperationException()
            override suspend fun simulateExhaustPrimaryQuota() = Unit
            override suspend fun resetSimulatedQuotas() = Unit
        }

        val memoryManager = SharedMemoryManager()
        val orchestrator = CentralPersonaOrchestrator(
            edition = AfrinaEdition.MASTER_OWNER,
            aiRouterRepository = routerRepo,
            memoryManager = memoryManager,
        )
        val identityEngine = PersonaIdentityEngine(masterMemoryManager = memoryManager)
        return orchestrator to identityEngine
    }

    private class FakeControlledSttEngine(
        private val finalTranscript: String = "سلام آفرینا، معماری سیستم را بررسی کن",
    ) : SpeechToTextEngine {
        override val engineId: String = "test-stt-double"
        override val isConfigured: Boolean = true
        override val supportedLocales: List<String> = listOf("fa-IR", "en-US")
        private var counter = 0

        override suspend fun beginStream(localeTag: String): Result<String> {
            counter++
            return Result.success("stt-session-$counter")
        }

        override suspend fun pushAudioFrame(sessionId: String, frame: AudioFramePacket): Result<SpeechRecognitionPartial?> =
            Result.success(
                SpeechRecognitionPartial(
                    utteranceId = sessionId,
                    text = finalTranscript.take(12),
                    localeTag = "fa-IR",
                    confidence = 0.88,
                )
            )

        override suspend fun finalizeUtterance(sessionId: String): Result<SpeechRecognitionFinal> =
            Result.success(
                SpeechRecognitionFinal(
                    utteranceId = sessionId,
                    text = finalTranscript,
                    localeTag = "fa-IR",
                    confidence = 0.94,
                    engineId = engineId,
                    isRealSpeechRecognition = false,
                )
            )

        override suspend fun cancelStream(sessionId: String) = Unit
    }

    private class ControllableSpeechOutputPort : SpeechOutputPort {
        override val portId: String = "test-controllable-tts"
        override val isSynthesisEngineConfigured: Boolean = true
        private val playing = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = playing.asStateFlow()
        var gate: CompletableDeferred<Boolean> = CompletableDeferred()
        var interruptCalls: Int = 0
        val receivedRequests: MutableList<SpeechSynthesisRequest> = mutableListOf()

        override suspend fun synthesizeAndPlay(request: SpeechSynthesisRequest): Result<SpeechPlaybackResult> {
            receivedRequests += request
            playing.value = true
            val completedNaturally = gate.await()
            playing.value = false
            return Result.success(
                SpeechPlaybackResult(
                    turnId = request.turnId,
                    portId = portId,
                    isSynthesisEngineConfigured = true,
                    completedNaturally = completedNaturally,
                    interruptedByOwner = !completedNaturally,
                    durationMs = 1200L,
                    statusNoteEn = "Test playback finished",
                    statusNoteFa = "پخش تست پایان یافت",
                )
            )
        }

        override suspend fun interruptPlayback(): Boolean {
            interruptCalls++
            val wasPlaying = playing.value
            playing.value = false
            if (!gate.isCompleted) {
                gate.complete(false)
            }
            return wasPlaying
        }
    }

    @Test
    fun `explicit microphone permission blocks ungranted listening and revoking pauses listening`() = runTest {
        val (orchestrator, identityEngine) = createRouterAndOrchestrator()
        val engine = RealTimeVoiceConversationEngine(orchestrator, identityEngine)
        val master = TenantScope.MasterOwner

        // Cannot listen before explicit permission
        val blockedBeforeGrant = engine.startVoiceListening(master, AfrinaPersona.COMPANION_EMPATHY)
        assertTrue(blockedBeforeGrant.isFailure)
        assertEquals(ConversationAudioState.PERMISSION_REQUIRED, engine.observeVoiceSnapshot(master).first().audioState)

        // Cannot inject audio frames while permission is DENIED
        engine.setMicrophonePermission(master, MicrophonePermissionState.DENIED)
        val rejectedFrame = engine.processMicrophoneFrame(
            master,
            AudioFramePacket(sequenceId = 1, timestampMs = 20L, rmsEnergy = 0.25),
        )
        assertTrue(rejectedFrame.isFailure)

        // Grant permission -> listening starts cleanly
        engine.setMicrophonePermission(master, MicrophonePermissionState.GRANTED)
        assertTrue(engine.startVoiceListening(master, AfrinaPersona.COMPANION_EMPATHY, "fa-IR").isSuccess)
        assertEquals(ConversationAudioState.LISTENING, engine.observeVoiceSnapshot(master).first().audioState)

        // Revoking permission during listening immediately forces PERMISSION_REQUIRED
        engine.setMicrophonePermission(master, MicrophonePermissionState.DENIED)
        assertEquals(ConversationAudioState.PERMISSION_REQUIRED, engine.observeVoiceSnapshot(master).first().audioState)
    }

    @Test
    fun `vad suppresses transient background noise preserves short thinking pauses and confirms end of turn`() = runTest {
        val engine = RealTimeVoiceConversationEngine()
        val master = TenantScope.MasterOwner
        val policy = EndOfTurnPolicy(
            frameDurationMs = 100,
            minSpeechBurstMs = 200L,
            pauseToleranceMs = 300L,
            endOfTurnSilenceMs = 600L,
            minSnrDb = 6.0,
            initialNoiseFloorRms = 0.02,
        )

        engine.setMicrophonePermission(master, MicrophonePermissionState.GRANTED)
        assertTrue(engine.startVoiceListening(master, AfrinaPersona.ARCHITECT_STRATEGIST, "fa-IR").isSuccess)

        // 1. Short click/noise spike (100ms < minSpeechBurstMs=200ms) -> NOISE_REJECTED
        val transientNoise = engine.processMicrophoneFrame(
            master,
            AudioFramePacket(sequenceId = 1, timestampMs = 100L, rmsEnergy = 0.08, zeroCrossingRate = 0.20, durationMs = 100),
            policy,
        ).getOrThrow()
        assertEquals(VadDecision.NOISE_REJECTED, transientNoise.decision)

        // 2. Real speech (2 frames of 100ms -> 200ms) -> SPEECH_ACTIVE
        engine.processMicrophoneFrame(
            master,
            AudioFramePacket(sequenceId = 2, timestampMs = 200L, rmsEnergy = 0.22, zeroCrossingRate = 0.18, durationMs = 100),
            policy,
        )
        val speechActive = engine.processMicrophoneFrame(
            master,
            AudioFramePacket(sequenceId = 3, timestampMs = 300L, rmsEnergy = 0.24, zeroCrossingRate = 0.18, durationMs = 100),
            policy,
        ).getOrThrow()
        assertEquals(VadDecision.SPEECH_ACTIVE, speechActive.decision)

        // 3. Natural thinking pause (300ms < 600ms endOfTurnSilenceMs) -> PAUSE_HOLDING (not prematurely ended)
        var lastPause = speechActive
        repeat(3) { i ->
            lastPause = engine.processMicrophoneFrame(
                master,
                AudioFramePacket(sequenceId = 4L + i, timestampMs = 400L + i * 100L, rmsEnergy = 0.015, zeroCrossingRate = 0.10, durationMs = 100),
                policy,
            ).getOrThrow()
        }
        assertEquals(VadDecision.PAUSE_HOLDING, lastPause.decision)

        // 4. Continued silence reaching 600ms -> END_OF_TURN
        var eotEval = lastPause
        repeat(3) { i ->
            eotEval = engine.processMicrophoneFrame(
                master,
                AudioFramePacket(sequenceId = 7L + i, timestampMs = 700L + i * 100L, rmsEnergy = 0.015, zeroCrossingRate = 0.10, durationMs = 100),
                policy,
            ).getOrThrow()
        }
        assertEquals(VadDecision.END_OF_TURN, eotEval.decision)
    }

    @Test
    fun `turn taking prevents overlapping responses and supports owner barge in interruption`() = runTest {
        val (orchestrator, identityEngine) = createRouterAndOrchestrator()
        val stt = FakeControlledSttEngine("برنامه توسعه ماژول بعدی چیست؟")
        val tts = ControllableSpeechOutputPort()
        val engine = RealTimeVoiceConversationEngine(
            personaArchitectureRepository = orchestrator,
            identityRepository = identityEngine,
            speechToTextEngine = stt,
            speechOutputPort = tts,
        )
        val master = TenantScope.MasterOwner

        engine.setMicrophonePermission(master, MicrophonePermissionState.GRANTED)
        assertTrue(engine.startVoiceListening(master, AfrinaPersona.COMPANION_EMPATHY, "fa-IR").isSuccess)

        // Start voice response asynchronously; it suspends in SPEAKING state while tts.gate is open
        val firstTurnJob = async {
            engine.completeVoiceUtterance(
                tenantScope = master,
                persona = AfrinaPersona.COMPANION_EMPATHY,
                localeTag = "fa-IR",
                dataClassification = DataClassification.PROJECT_CODE,
            )
        }
        while (engine.observeVoiceSnapshot(master).first().audioState != ConversationAudioState.SPEAKING) {
            delay(25)
        }
        val speakingSnap = engine.observeVoiceSnapshot(master).first()
        assertEquals(ConversationAudioState.SPEAKING, speakingSnap.audioState)
        assertNotNull(speakingSnap.activeTurnLock)

        // Overlapping response from another persona must be blocked while active turn lock is held
        val overlapAttempt = engine.submitTextTurn(
            tenantScope = master,
            persona = AfrinaPersona.BUILDER_ENGINEER,
            text = "Speak over companion now",
            speakResponseAloud = true,
        )
        assertTrue(overlapAttempt.isFailure)

        // Owner interrupts speaking persona via voice barge-in frame!
        val bargeInVad = engine.processMicrophoneFrame(
            master,
            AudioFramePacket(sequenceId = 10, timestampMs = 100L, rmsEnergy = 0.30, zeroCrossingRate = 0.20, durationMs = 200),
            EndOfTurnPolicy(frameDurationMs = 200, minSpeechBurstMs = 160L),
        ).getOrThrow()
        assertEquals(VadDecision.SPEECH_ACTIVE, bargeInVad.decision)

        val firstTurnResult = firstTurnJob.await().getOrThrow()
        assertTrue(firstTurnResult.interruptedByOwner)
        assertTrue(tts.interruptCalls >= 1)

        // State transitioned cleanly back to LISTENING with turn lock released
        val afterInterrupt = engine.observeVoiceSnapshot(master).first()
        assertEquals(ConversationAudioState.LISTENING, afterInterrupt.audioState)
        assertNull(afterInterrupt.activeTurnLock)

        // Reset TTS gate for next natural completion turn
        tts.gate = CompletableDeferred<Boolean>().apply { complete(true) }
        val followUp = engine.completeVoiceUtterance(
            tenantScope = master,
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            localeTag = "fa-IR",
            transcriptOverride = "فقط خلاصه را بگو",
            dataClassification = DataClassification.PROJECT_CODE,
        ).getOrThrow()
        assertFalse(followUp.interruptedByOwner)
        assertEquals(ConversationAudioState.IDLE, engine.observeVoiceSnapshot(master).first().audioState)
    }

    @Test
    fun `voice and text preserve unified conversation context across language switches`() = runTest {
        val capturedPrompts = mutableListOf<String>()
        val (orchestrator, identityEngine) = createRouterAndOrchestrator(capturedPrompts)
        val engine = RealTimeVoiceConversationEngine(
            personaArchitectureRepository = orchestrator,
            identityRepository = identityEngine,
        )
        val master = TenantScope.MasterOwner

        engine.setMicrophonePermission(master, MicrophonePermissionState.GRANTED)
        val voiceTurn = engine.completeVoiceUtterance(
            tenantScope = master,
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            localeTag = "fa-IR",
            transcriptOverride = "طراحی ماژول صوتی را مرور کن",
            dataClassification = DataClassification.PROJECT_CODE,
        ).getOrThrow()
        assertEquals(ConversationModality.VOICE, voiceTurn.modality)

        // Follow up via text -> prompt must automatically carry prior [VOICE] turn context
        val textTurn = engine.submitTextTurn(
            tenantScope = master,
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            text = "Now list the key API boundaries in English",
            localeTag = "en-US",
            dataClassification = DataClassification.PROJECT_CODE,
        ).getOrThrow()
        assertEquals(ConversationModality.TEXT, textTurn.modality)
        assertEquals(2, capturedPrompts.size)
        assertTrue(capturedPrompts[1].contains("[VOICE]"))
        assertTrue(capturedPrompts[1].contains("طراحی ماژول صوتی را مرور کن"))

        val history = engine.observeVoiceSnapshot(master).first().unifiedHistory
        assertEquals(2, history.size)
    }

    @Test
    fun `voice profiles support 4 personas 3 styles tenant isolation cloning gates deletion and audio collaboration`() = runTest {
        val (orchestrator, identityEngine) = createRouterAndOrchestrator()
        val engine = RealTimeVoiceConversationEngine(
            personaArchitectureRepository = orchestrator,
            identityRepository = identityEngine,
        )
        val master = TenantScope.MasterOwner
        val customerA = TenantScope.Customer("cust-voice-a")
        val customerB = TenantScope.Customer("cust-voice-b")

        identityEngine.onboardCustomer(
            customerId = "cust-voice-a",
            displayName = "Voice Customer A",
            subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
            contentPolicyAccepted = true,
            likenessUseAuthorized = true,
            voiceUseAuthorized = true,
        ).getOrThrow()
        identityEngine.onboardCustomer(
            customerId = "cust-voice-b",
            displayName = "Voice Customer B",
            subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
            contentPolicyAccepted = true,
            likenessUseAuthorized = true,
            voiceUseAuthorized = true,
        ).getOrThrow()

        // Configure 3 distinct presentation styles cleanly across personas
        val archVoice = engine.configurePersonaVoiceProfile(
            tenantScope = master,
            persona = AfrinaPersona.ARCHITECT_STRATEGIST,
            activeLocaleTag = "en-US",
            activeStyle = VoicePresentationStyle.EXECUTIVE_CONCISE,
            updatedTuning = VoiceStyleTuning(
                style = VoicePresentationStyle.EXECUTIVE_CONCISE,
                pitchShift = -0.05,
                speakingRate = 1.10,
                warmthLevel = 0.40,
            ),
        ).getOrThrow()
        val compVoice = engine.configurePersonaVoiceProfile(
            tenantScope = master,
            persona = AfrinaPersona.COMPANION_EMPATHY,
            activeLocaleTag = "fa-IR",
            activeStyle = VoicePresentationStyle.EXPRESSIVE_STORYTELLING,
        ).getOrThrow()
        assertEquals(VoicePresentationStyle.EXECUTIVE_CONCISE, archVoice.activeStyle)
        assertEquals(VoicePresentationStyle.EXPRESSIVE_STORYTELLING, compVoice.activeStyle)
        assertEquals(3, compVoice.styleTunings.size)

        // Register authorized Master recording & Identity voice sample
        val masterVoiceAsset = identityEngine.registerAuthorizedMediaAsset(
            tenantScope = master,
            kind = MediaAssetKind.VOICE_SAMPLE,
            privateVaultAlias = "vault://master/owner_voice_01",
            mimeType = "audio/wav",
            durationMs = 18_000L,
            explicitLikenessOrVoiceAuthorized = true,
            subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
        ).getOrThrow()
        val masterRecording = engine.registerVoiceRecording(
            tenantScope = master,
            persona = AfrinaPersona.GUARDIAN_SENTINEL,
            privateVaultAlias = "vault://master/rec_01",
            durationMs = 9_500L,
            explicitOwnerAuthorized = true,
            subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
            allowSttTranscription = true,
            allowVoiceCloningUsage = true,
        ).getOrThrow()

        // Minor or unauthorized voice cloning fails closed
        assertTrue(
            engine.linkAuthorizedVoiceCloneSample(
                tenantScope = master,
                persona = AfrinaPersona.GUARDIAN_SENTINEL,
                voiceAssetId = masterVoiceAsset.id,
                explicitOwnerAuthorized = false,
                subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
            ).isFailure
        )
        assertTrue(
            engine.linkAuthorizedVoiceCloneSample(
                tenantScope = master,
                persona = AfrinaPersona.GUARDIAN_SENTINEL,
                voiceAssetId = masterVoiceAsset.id,
                explicitOwnerAuthorized = true,
                subjectAgeStatus = SubjectAgePolicyStatus.MINOR_RESTRICTED,
            ).isFailure
        )

        // Cross-tenant isolation: Customer A cannot link Master's or Customer B's voice asset
        assertTrue(
            engine.linkAuthorizedVoiceCloneSample(
                tenantScope = customerA,
                persona = AfrinaPersona.GUARDIAN_SENTINEL,
                voiceAssetId = masterVoiceAsset.id,
                explicitOwnerAuthorized = true,
                subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
            ).isFailure
        )

        // Authorized Master recording links cleanly to Guardian voice profile
        val linked = engine.linkAuthorizedVoiceCloneSample(
            tenantScope = master,
            persona = AfrinaPersona.GUARDIAN_SENTINEL,
            voiceAssetId = masterRecording.recordingId,
            explicitOwnerAuthorized = true,
            subjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
        ).getOrThrow()
        assertEquals(VoiceCloneReadiness.AUTHORIZED_SAMPLE_LINKED_AWAITING_ENGINE, linked.cloneReadiness)

        // Deleting recording redacts alias, revokes cloning access, and unlinks Guardian profile
        assertTrue(engine.deleteVoiceRecording(master, masterRecording.recordingId))
        val masterSnap = engine.observeVoiceSnapshot(master).first()
        val deletedRec = masterSnap.recordings.first { it.recordingId == masterRecording.recordingId }
        assertTrue(deletedRec.isDeleted)
        assertFalse(deletedRec.allowVoiceCloningUsage)
        assertNull(masterSnap.voiceProfiles.getValue(AfrinaPersona.GUARDIAN_SENTINEL).authorizedVoiceAssetId)
        assertEquals(
            VoiceCloneReadiness.BLOCKED_UNAUTHORIZED,
            masterSnap.voiceProfiles.getValue(AfrinaPersona.GUARDIAN_SENTINEL).cloneReadiness,
        )

        // Customer B's voice profile remains completely unaffected by Master's edits/deletions
        assertEquals(
            VoiceCloneReadiness.STANDARD_SYNTHETIC_ONLY,
            engine.observeVoiceSnapshot(customerB).first().voiceProfiles.getValue(AfrinaPersona.GUARDIAN_SENTINEL).cloneReadiness,
        )

        // Audio collaboration specs prepare for server offload (avoiding heavy processing on Redmi Note 8)
        val duetReady = engine.prepareAudioCollaboration(
            tenantScope = master,
            spec = AudioCollaborationSessionSpec.DuetPerformanceSpec(
                sessionId = "collab-duet-1",
                title = "Persian Acoustic Duet",
                partnerLabel = "Owner Authorized Twin",
                authorizedPartnerVoiceAssetId = masterVoiceAsset.id,
                explicitPartnerVoiceAuthorized = true,
                partnerSubjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
                tempoBpm = 92,
                harmonyArrangement = "Call & response chorus",
            ),
        ).getOrThrow()
        assertEquals(AudioCollaborationMode.DUET_PERFORMANCE, duetReady.mode)
        assertEquals(ProcessingOffloadTarget.SERVER_ASSISTED_OFFLOAD, duetReady.offloadTarget)
        assertTrue(duetReady.isPreparedOnly)

        val musicReady = engine.prepareAudioCollaboration(
            tenantScope = master,
            spec = AudioCollaborationSessionSpec.MusicCreationSpec(
                sessionId = "collab-music-1",
                title = "Ambient Studio Score",
                genre = "Neoclassical",
                tempoBpm = 78,
                keySignature = "D minor",
                stemRoles = listOf("Piano", "Strings", "Vocal Guide"),
            ),
        ).getOrThrow()
        assertEquals(AudioCollaborationMode.MUSIC_CREATION, musicReady.mode)

        // Cross-tenant unauthorized duet voice asset must fail closed
        assertTrue(
            engine.prepareAudioCollaboration(
                tenantScope = customerA,
                spec = AudioCollaborationSessionSpec.DuetPerformanceSpec(
                    sessionId = "collab-duet-illegal",
                    title = "Cross-tenant theft test",
                    partnerLabel = "Master",
                    authorizedPartnerVoiceAssetId = masterVoiceAsset.id,
                    explicitPartnerVoiceAuthorized = true,
                    partnerSubjectAgeStatus = SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS,
                    tempoBpm = 90,
                    harmonyArrangement = "None",
                ),
            ).isFailure
        )
    }
}
