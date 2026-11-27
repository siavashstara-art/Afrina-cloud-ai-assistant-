package com.example.afrina.core.persona

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Owner-controlled Guardian Digital Twin registry (male owner-authorized digital twin).
 * Never fabricates identity data or generates voice clones/renders without supplied authorized samples.
 */
class GuardianDigitalTwinGuard(
    initialState: GuardianDigitalTwinState = GuardianDigitalTwinState(),
) {
    private val stateFlow = MutableStateFlow(initialState)

    fun observeState(): StateFlow<GuardianDigitalTwinState> = stateFlow.asStateFlow()

    @Synchronized
    fun currentState(): GuardianDigitalTwinState = stateFlow.value

    @Synchronized
    fun setIdentityAuthorization(
        authorized: Boolean,
        approvedIdentitySummary: String?,
        approvedCommunicationStyle: String?,
    ): Result<GuardianDigitalTwinState> {
        if (!authorized) {
            val revoked = stateFlow.value.copy(
                isOwnerAuthorized = false,
                identityAuthorized = false,
                voiceCloneAuthorized = false,
                avatarRenderAuthorized = false,
                voiceCloneStatus = TwinCapabilityStatus.AWAITING_OWNER_AUTHORIZATION,
                avatarRenderStatus = TwinCapabilityStatus.AWAITING_OWNER_AUTHORIZATION,
            )
            stateFlow.value = revoked
            return Result.success(revoked)
        }

        val cleanIdentity = approvedIdentitySummary?.trim().orEmpty()
        if (cleanIdentity.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("Cannot authorize Guardian identity without owner-supplied identity summary.")
            )
        }

        val next = stateFlow.value.copy(
            isOwnerAuthorized = true,
            identityAuthorized = true,
            approvedIdentitySummary = cleanIdentity,
            approvedCommunicationStyle = approvedCommunicationStyle?.trim()?.takeIf { it.isNotEmpty() },
        )
        stateFlow.value = next
        return Result.success(next)
    }

    @Synchronized
    fun registerSuppliedMediaSample(
        kind: TwinMediaKind,
        privateVaultAlias: String,
        mimeType: String,
        durationMs: Long? = null,
        ownerAuthorized: Boolean,
    ): Result<DigitalTwinMediaSample> {
        if (!ownerAuthorized) {
            return Result.failure(
                SecurityException("Media sample rejected: explicit owner authorization is required.")
            )
        }
        if (privateVaultAlias.isBlank() || mimeType.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Media sample rejected: private vault alias and MIME type must be non-blank.")
            )
        }
        val sample = DigitalTwinMediaSample(
            id = "tws-${UUID.randomUUID().toString().take(8)}",
            kind = kind,
            privateVaultAlias = privateVaultAlias.trim(),
            mimeType = mimeType.trim(),
            durationMs = durationMs,
            ownerAuthorized = true,
            addedAtEpochMs = System.currentTimeMillis(),
        )
        val current = stateFlow.value
        val updatedSamples = current.suppliedSamples + sample
        stateFlow.value = current.copy(
            suppliedSamples = updatedSamples,
            voiceCloneStatus = recomputeVoiceStatus(current.isOwnerAuthorized, current.voiceCloneAuthorized, updatedSamples),
            avatarRenderStatus = recomputeAvatarStatus(current.isOwnerAuthorized, current.avatarRenderAuthorized, updatedSamples),
        )
        return Result.success(sample)
    }

    @Synchronized
    fun prepareVoiceCloneCapability(ownerAuthorized: Boolean): Result<GuardianDigitalTwinState> {
        val current = stateFlow.value
        val hasVoiceSample = current.suppliedSamples.any { it.kind == TwinMediaKind.VOICE_SAMPLE && it.ownerAuthorized }
        if (!ownerAuthorized || !current.isOwnerAuthorized) {
            return Result.failure(
                SecurityException("Voice synthesis preparation denied: owner identity and voice authorization required.")
            )
        }
        if (!hasVoiceSample) {
            return Result.failure(
                IllegalStateException("Voice synthesis cannot be prepared or fabricated without an owner-authorized voice sample.")
            )
        }
        val next = current.copy(
            voiceCloneAuthorized = true,
            voiceCloneStatus = TwinCapabilityStatus.PREPARED_AWAITING_SYNTHESIS_ENGINE,
        )
        stateFlow.value = next
        return Result.success(next)
    }

    @Synchronized
    fun prepareVisualAvatarCapability(ownerAuthorized: Boolean): Result<GuardianDigitalTwinState> {
        val current = stateFlow.value
        val hasVisualSample = current.suppliedSamples.any {
            (it.kind == TwinMediaKind.PHOTOGRAPH || it.kind == TwinMediaKind.VIDEO) && it.ownerAuthorized
        }
        if (!ownerAuthorized || !current.isOwnerAuthorized) {
            return Result.failure(
                SecurityException("Visual avatar preparation denied: owner identity and visual authorization required.")
            )
        }
        if (!hasVisualSample) {
            return Result.failure(
                IllegalStateException("Visual avatar cannot be prepared or fabricated without owner-authorized photo or video samples.")
            )
        }
        val next = current.copy(
            avatarRenderAuthorized = true,
            avatarRenderStatus = TwinCapabilityStatus.PREPARED_AWAITING_SYNTHESIS_ENGINE,
        )
        stateFlow.value = next
        return Result.success(next)
    }

    private fun recomputeVoiceStatus(
        identityAuth: Boolean,
        voiceAuth: Boolean,
        samples: List<DigitalTwinMediaSample>,
    ): TwinCapabilityStatus {
        val hasVoice = samples.any { it.kind == TwinMediaKind.VOICE_SAMPLE && it.ownerAuthorized }
        return when {
            !hasVoice -> TwinCapabilityStatus.NOT_CONFIGURED_REQUIRES_SUPPLIED_SAMPLES
            !identityAuth || !voiceAuth -> TwinCapabilityStatus.AWAITING_OWNER_AUTHORIZATION
            else -> TwinCapabilityStatus.PREPARED_AWAITING_SYNTHESIS_ENGINE
        }
    }

    private fun recomputeAvatarStatus(
        identityAuth: Boolean,
        avatarAuth: Boolean,
        samples: List<DigitalTwinMediaSample>,
    ): TwinCapabilityStatus {
        val hasVisual = samples.any {
            (it.kind == TwinMediaKind.PHOTOGRAPH || it.kind == TwinMediaKind.VIDEO) && it.ownerAuthorized
        }
        return when {
            !hasVisual -> TwinCapabilityStatus.NOT_CONFIGURED_REQUIRES_SUPPLIED_SAMPLES
            !identityAuth || !avatarAuth -> TwinCapabilityStatus.AWAITING_OWNER_AUTHORIZATION
            else -> TwinCapabilityStatus.PREPARED_AWAITING_SYNTHESIS_ENGINE
        }
    }
}
