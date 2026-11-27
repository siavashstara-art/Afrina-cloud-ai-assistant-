package com.example.afrina.core.persona

import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.DataClassification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Shared three-layer memory store across all 4 AFRINA personas:
 * 1. SHORT_TERM_WORKING
 * 2. MEDIUM_TERM_EPISODIC_PROJECT
 * 3. LONG_TERM_SEMANTIC_PREFERENCE
 *
 * Preserves provenance, confidence, owner review/correction controls,
 * and gradual learning from verified experiences.
 */
class SharedMemoryManager {
    private val entriesFlow = MutableStateFlow<List<SharedMemoryEntry>>(emptyList())

    fun observeMemories(): StateFlow<List<SharedMemoryEntry>> = entriesFlow.asStateFlow()

    @Synchronized
    fun recordMemory(
        layer: MemoryLayer,
        contentSummary: String,
        sourcePersona: AfrinaPersona,
        sourceKind: MemorySourceKind,
        sourceReferenceId: String,
        dataClassification: DataClassification,
        initialConfidence: Double = 0.60,
        projectId: String? = null,
        preferenceKey: String? = null,
        ownerDirect: Boolean = false,
    ): SharedMemoryEntry {
        val now = System.currentTimeMillis()
        val entry = SharedMemoryEntry(
            id = "mem-${UUID.randomUUID().toString().take(8)}",
            layer = layer,
            contentSummary = contentSummary.trim(),
            dataClassification = dataClassification,
            provenance = MemoryProvenance(
                sourcePersona = sourcePersona,
                sourceKind = sourceKind,
                sourceReferenceId = sourceReferenceId,
                lastReviewedByOwner = ownerDirect,
                recordedAtEpochMs = now,
            ),
            confidence = initialConfidence.coerceIn(0.0, 1.0),
            reviewStatus = if (ownerDirect) MemoryReviewStatus.OWNER_VERIFIED else MemoryReviewStatus.UNREVIEWED,
            projectId = projectId,
            preferenceKey = preferenceKey,
            verifiedExperienceCount = if (ownerDirect || sourceKind == MemorySourceKind.VERIFIED_EXPERIENCE) 1 else 0,
            updatedAtEpochMs = now,
        )
        entriesFlow.value = listOf(entry) + entriesFlow.value
        return entry
    }

    @Synchronized
    fun queryMemories(
        layer: MemoryLayer? = null,
        projectId: String? = null,
        includeSensitiveOwnerData: Boolean = false,
    ): List<SharedMemoryEntry> =
        entriesFlow.value.filter { item ->
            (layer == null || item.layer == layer) &&
                (projectId == null || item.projectId == projectId) &&
                (includeSensitiveOwnerData || !item.dataClassification.isSensitiveOwnerData) &&
                item.reviewStatus != MemoryReviewStatus.OWNER_DISPUTED
        }

    @Synchronized
    fun verifyMemoryByOwner(memoryId: String, confidenceBoost: Double = 0.15): SharedMemoryEntry? {
        val current = entriesFlow.value.firstOrNull { it.id == memoryId } ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            confidence = (current.confidence + confidenceBoost.coerceAtLeast(0.0)).coerceIn(0.0, 1.0),
            reviewStatus = MemoryReviewStatus.OWNER_VERIFIED,
            provenance = current.provenance.copy(lastReviewedByOwner = true),
            verifiedExperienceCount = current.verifiedExperienceCount + 1,
            updatedAtEpochMs = now,
        )
        replaceEntry(updated)
        return updated
    }

    @Synchronized
    fun correctMemoryByOwner(
        memoryId: String,
        correctedSummary: String,
        correctionNote: String?,
        newConfidence: Double = 0.95,
    ): SharedMemoryEntry? {
        val current = entriesFlow.value.firstOrNull { it.id == memoryId } ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            contentSummary = correctedSummary.trim(),
            ownerCorrectionNote = correctionNote?.trim(),
            confidence = newConfidence.coerceIn(0.0, 1.0),
            reviewStatus = MemoryReviewStatus.OWNER_CORRECTED,
            provenance = current.provenance.copy(lastReviewedByOwner = true),
            updatedAtEpochMs = now,
        )
        replaceEntry(updated)
        return updated
    }

    @Synchronized
    fun disputeMemoryByOwner(memoryId: String, reasonNote: String?): SharedMemoryEntry? {
        val current = entriesFlow.value.firstOrNull { it.id == memoryId } ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            confidence = 0.0,
            reviewStatus = MemoryReviewStatus.OWNER_DISPUTED,
            ownerCorrectionNote = reasonNote?.trim(),
            provenance = current.provenance.copy(lastReviewedByOwner = true),
            updatedAtEpochMs = now,
        )
        replaceEntry(updated)
        return updated
    }

    @Synchronized
    fun deleteMemoryByOwner(memoryId: String): Boolean {
        val before = entriesFlow.value.size
        val filtered = entriesFlow.value.filterNot { it.id == memoryId }
        if (filtered.size == before) return false
        entriesFlow.value = filtered
        return true
    }

    /**
     * Gradual learning: when a working/episodic memory accumulates verified experiences
     * (and has NOT been disputed or overridden by owner correction), promote a semantic/preference
     * summary to LONG_TERM_SEMANTIC_PREFERENCE while preserving original provenance.
     */
    @Synchronized
    fun recordVerifiedExperienceAndConsolidate(
        memoryId: String,
        preferenceKey: String,
        minVerifiedExperiencesForPromotion: Int = 2,
    ): SharedMemoryEntry? {
        val source = entriesFlow.value.firstOrNull { it.id == memoryId } ?: return null
        if (source.reviewStatus == MemoryReviewStatus.OWNER_DISPUTED) return null

        val now = System.currentTimeMillis()
        val incremented = source.copy(
            verifiedExperienceCount = source.verifiedExperienceCount + 1,
            confidence = (source.confidence + 0.10).coerceIn(0.0, 1.0),
            updatedAtEpochMs = now,
        )
        replaceEntry(incremented)

        if (incremented.layer == MemoryLayer.LONG_TERM_SEMANTIC_PREFERENCE ||
            incremented.verifiedExperienceCount < minVerifiedExperiencesForPromotion
        ) {
            return incremented
        }

        val existingLongTerm = entriesFlow.value.firstOrNull {
            it.layer == MemoryLayer.LONG_TERM_SEMANTIC_PREFERENCE && it.preferenceKey == preferenceKey
        }
        if (existingLongTerm != null) {
            // Never overwrite an owner-corrected or owner-disputed long-term memory automatically.
            if (existingLongTerm.reviewStatus == MemoryReviewStatus.OWNER_CORRECTED ||
                existingLongTerm.reviewStatus == MemoryReviewStatus.OWNER_DISPUTED
            ) {
                return existingLongTerm
            }
            val reinforced = existingLongTerm.copy(
                confidence = (existingLongTerm.confidence + 0.08).coerceIn(0.0, 1.0),
                verifiedExperienceCount = existingLongTerm.verifiedExperienceCount + 1,
                updatedAtEpochMs = now,
            )
            replaceEntry(reinforced)
            return reinforced
        }

        return recordMemory(
            layer = MemoryLayer.LONG_TERM_SEMANTIC_PREFERENCE,
            contentSummary = incremented.contentSummary,
            sourcePersona = incremented.provenance.sourcePersona,
            sourceKind = MemorySourceKind.GRADUAL_CONSOLIDATION,
            sourceReferenceId = incremented.id,
            dataClassification = incremented.dataClassification,
            initialConfidence = incremented.confidence,
            projectId = incremented.projectId,
            preferenceKey = preferenceKey,
            ownerDirect = incremented.provenance.lastReviewedByOwner,
        )
    }

    @Synchronized
    fun restoreEntry(entry: SharedMemoryEntry) {
        if (entriesFlow.value.none { it.id == entry.id }) {
            entriesFlow.value = listOf(entry) + entriesFlow.value
        }
    }

    private fun replaceEntry(updated: SharedMemoryEntry) {
        entriesFlow.value = entriesFlow.value.map { if (it.id == updated.id) updated else it }
    }
}
