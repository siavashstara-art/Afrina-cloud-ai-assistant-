package com.example.afrina.core.persona

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Conceptual computational affective self-regulation engine.
 * Models dopamine-like, serotonin-like, endorphin-like, and cortisol-like variables
 * as bounded computational signals under strict owner policy control.
 */
class AffectiveRegulationEngine(
    initialPolicy: AffectiveControlPolicy = AffectiveControlPolicy(),
    initialState: AffectiveState = AffectiveState(
        dopamineLike = initialPolicy.baselineDopamine,
        serotoninLike = initialPolicy.baselineSerotonin,
        endorphinLike = initialPolicy.baselineEndorphin,
        cortisolLike = initialPolicy.baselineCortisol,
        isConceptualComputationalModel = true,
        isFrozenByOwner = initialPolicy.ownerLockFrozen,
    ),
) {
    private val policyFlow = MutableStateFlow(initialPolicy)
    private val stateFlow = MutableStateFlow(initialState)

    fun observeState(): StateFlow<AffectiveState> = stateFlow.asStateFlow()
    fun observePolicy(): StateFlow<AffectiveControlPolicy> = policyFlow.asStateFlow()

    @Synchronized
    fun currentState(): AffectiveState = stateFlow.value

    @Synchronized
    fun currentPolicy(): AffectiveControlPolicy = policyFlow.value

    @Synchronized
    fun updateOwnerPolicy(newPolicy: AffectiveControlPolicy): AffectiveControlPolicy {
        val sanitized = newPolicy.copy(
            maxStepDelta = newPolicy.maxStepDelta.coerceIn(0.01, 0.25),
            baselineDopamine = newPolicy.baselineDopamine.coerceIn(0.0, 1.0),
            baselineSerotonin = newPolicy.baselineSerotonin.coerceIn(0.0, 1.0),
            baselineEndorphin = newPolicy.baselineEndorphin.coerceIn(0.0, 1.0),
            baselineCortisol = newPolicy.baselineCortisol.coerceIn(0.0, 1.0),
        )
        policyFlow.value = sanitized
        stateFlow.value = stateFlow.value.copy(
            isFrozenByOwner = sanitized.ownerLockFrozen,
            isConceptualComputationalModel = true,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        return sanitized
    }

    @Synchronized
    fun applyEvent(event: AffectiveSignalEvent, reason: String): AffectiveState {
        val policy = policyFlow.value
        val current = stateFlow.value
        if (policy.ownerLockFrozen || !policy.allowAdaptiveLearning) {
            return current
        }

        val step = policy.maxStepDelta
        val (dDelta, sDelta, eDelta, cDelta) = when (event) {
            AffectiveSignalEvent.TASK_SUCCESS -> Quadruple(+0.06, +0.02, +0.04, -0.04)
            AffectiveSignalEvent.COMPLEX_PROGRESS -> Quadruple(+0.08, +0.01, +0.05, -0.02)
            AffectiveSignalEvent.RISK_OR_PRIVACY_ALERT -> Quadruple(-0.04, -0.03, -0.02, +0.10)
            AffectiveSignalEvent.CALM_STEADY_INTERACTION -> Quadruple(+0.01, +0.05, +0.02, -0.05)
            AffectiveSignalEvent.OWNER_POSITIVE_FEEDBACK -> Quadruple(+0.08, +0.05, +0.06, -0.06)
        }

        val updated = current.copy(
            dopamineLike = (current.dopamineLike + dDelta.coerceIn(-step, step)).coerceIn(0.0, 1.0),
            serotoninLike = (current.serotoninLike + sDelta.coerceIn(-step, step)).coerceIn(0.0, 1.0),
            endorphinLike = (current.endorphinLike + eDelta.coerceIn(-step, step)).coerceIn(0.0, 1.0),
            cortisolLike = (current.cortisolLike + cDelta.coerceIn(-step, step)).coerceIn(0.0, 1.0),
            isConceptualComputationalModel = true,
            isFrozenByOwner = false,
            lastRegulationReason = reason,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        stateFlow.value = updated
        return updated
    }

    @Synchronized
    fun regulateHomeostasis(factor: Double = 0.25): AffectiveState {
        val policy = policyFlow.value
        val current = stateFlow.value
        if (policy.ownerLockFrozen || !policy.allowSelfRegulation) {
            return current
        }

        val alpha = factor.coerceIn(0.05, 1.0)
        val step = policy.maxStepDelta

        fun moveToward(value: Double, target: Double): Double =
            (value + ((target - value) * alpha).coerceIn(-step, step)).coerceIn(0.0, 1.0)

        val regulated = current.copy(
            dopamineLike = moveToward(current.dopamineLike, policy.baselineDopamine),
            serotoninLike = moveToward(current.serotoninLike, policy.baselineSerotonin),
            endorphinLike = moveToward(current.endorphinLike, policy.baselineEndorphin),
            cortisolLike = moveToward(current.cortisolLike, policy.baselineCortisol),
            isConceptualComputationalModel = true,
            lastRegulationReason = "Homeostatic self-regulation toward owner baseline",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        stateFlow.value = regulated
        return regulated
    }

    @Synchronized
    fun resetToOwnerBaselines(): AffectiveState {
        val policy = policyFlow.value
        val reset = AffectiveState(
            dopamineLike = policy.baselineDopamine,
            serotoninLike = policy.baselineSerotonin,
            endorphinLike = policy.baselineEndorphin,
            cortisolLike = policy.baselineCortisol,
            isConceptualComputationalModel = true,
            isFrozenByOwner = policy.ownerLockFrozen,
            lastRegulationReason = "Owner reset to baseline",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        stateFlow.value = reset
        return reset
    }

    private data class Quadruple(
        val dopamine: Double,
        val serotonin: Double,
        val endorphin: Double,
        val cortisol: Double,
    )
}
