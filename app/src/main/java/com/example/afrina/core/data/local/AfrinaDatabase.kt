package com.example.afrina.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class,
        DevTaskEntity::class,
        ApprovalRequestEntity::class,
        AgentActivityEntity::class,
        NotificationEntity::class,
        AuditLogEntity::class,
        AiUsageLedgerEntity::class,
        PaidInferenceApprovalEntity::class,
        PersonaIdentityConfigEntity::class,
        PersonaIdentityRevisionEntity::class,
        IdentityMediaAssetEntity::class,
        DigitalTwinProfileEntity::class,
        CustomerOnboardingEntity::class,
        SharedMemoryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AfrinaDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun devTaskDao(): DevTaskDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun agentActivityDao(): AgentActivityDao
    abstract fun notificationDao(): NotificationDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun aiUsageLedgerDao(): AiUsageLedgerDao
    abstract fun paidInferenceApprovalDao(): PaidInferenceApprovalDao
    abstract fun personaIdentityDao(): PersonaIdentityDao
    abstract fun identityMediaAssetDao(): IdentityMediaAssetDao
    abstract fun digitalTwinDao(): DigitalTwinProfileDao
    abstract fun customerOnboardingDao(): CustomerOnboardingDao
    abstract fun sharedMemoryDao(): SharedMemoryDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_usage_ledger` (
                        `id` TEXT NOT NULL,
                        `persona` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `taskTitle` TEXT NOT NULL,
                        `usageCategory` TEXT NOT NULL,
                        `dataClassification` TEXT NOT NULL,
                        `routingKind` TEXT NOT NULL,
                        `fallbackTriggered` INTEGER NOT NULL,
                        `fallbackReason` TEXT,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `estimatedApiCostUsd` REAL NOT NULL,
                        `estimatedInfraCostUsd` REAL NOT NULL,
                        `isSimulated` INTEGER NOT NULL,
                        `timestampEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `paid_inference_approvals` (
                        `id` TEXT NOT NULL,
                        `persona` TEXT NOT NULL,
                        `taskTitle` TEXT NOT NULL,
                        `complexity` TEXT NOT NULL,
                        `requiresCoding` INTEGER NOT NULL,
                        `requiredModalitiesCsv` TEXT NOT NULL,
                        `dataClassification` TEXT NOT NULL,
                        `explicitSensitiveAuthorized` INTEGER NOT NULL,
                        `targetProviderId` TEXT NOT NULL,
                        `targetModelId` TEXT NOT NULL,
                        `estimatedCostUsd` REAL NOT NULL,
                        `reasonEn` TEXT NOT NULL,
                        `reasonFa` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `isExecuted` INTEGER NOT NULL,
                        `isSimulated` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `persona_identities` (
                        `tenantKey` TEXT NOT NULL,
                        `persona` TEXT NOT NULL,
                        `customName` TEXT NOT NULL,
                        `identitySubtitleEn` TEXT NOT NULL,
                        `identitySubtitleFa` TEXT NOT NULL,
                        `roleSummaryEn` TEXT NOT NULL,
                        `roleSummaryFa` TEXT NOT NULL,
                        `responsibilitiesCsv` TEXT NOT NULL,
                        `warmth` REAL NOT NULL,
                        `formality` REAL NOT NULL,
                        `directness` REAL NOT NULL,
                        `empathy` REAL NOT NULL,
                        `technicalDepth` REAL NOT NULL,
                        `communicationStyleEn` TEXT NOT NULL,
                        `communicationStyleFa` TEXT NOT NULL,
                        `vocabularyNotesCsv` TEXT NOT NULL,
                        `knowledgeScopeTagsCsv` TEXT NOT NULL,
                        `behavioralInstructions` TEXT NOT NULL,
                        `voiceProfileName` TEXT NOT NULL,
                        `voiceGender` TEXT NOT NULL,
                        `pitchOffset` REAL NOT NULL,
                        `speakingRate` REAL NOT NULL,
                        `preferredLocaleTag` TEXT NOT NULL,
                        `authorizedVoiceAssetId` TEXT,
                        `avatarProfileId` TEXT NOT NULL,
                        `distinctFaceIdentityId` TEXT,
                        `clothingStyle` TEXT NOT NULL,
                        `appearanceNotes` TEXT NOT NULL,
                        `visualStylePreset` TEXT NOT NULL,
                        `presentationMode` TEXT NOT NULL,
                        `animationIntensity` REAL NOT NULL,
                        `idleMotionEnabled` INTEGER NOT NULL,
                        `authorizedPhotoAssetIdsCsv` TEXT NOT NULL,
                        `authorizedVideoAssetIdsCsv` TEXT NOT NULL,
                        `preferredLanguage` TEXT NOT NULL,
                        `responseLength` TEXT NOT NULL,
                        `proactivityLevel` REAL NOT NULL,
                        `confirmBeforeExternalActions` INTEGER NOT NULL,
                        `securityRoleAnchor` TEXT NOT NULL,
                        `minimumDataClassification` TEXT NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`tenantKey`, `persona`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `persona_identity_revisions` (
                        `id` TEXT NOT NULL,
                        `tenantKey` TEXT NOT NULL,
                        `persona` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `configurationJson` TEXT NOT NULL,
                        `changeSummary` TEXT NOT NULL,
                        `changedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `identity_media_assets` (
                        `id` TEXT NOT NULL,
                        `ownerTenantKey` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `privateVaultAlias` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `distinctFaceIdentityId` TEXT,
                        `durationMs` INTEGER,
                        `explicitLikenessOrVoiceAuthorized` INTEGER NOT NULL,
                        `subjectAgeStatus` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `digital_twin_profiles` (
                        `tenantKey` TEXT NOT NULL,
                        `gender` TEXT NOT NULL,
                        `likenessAuthorized` INTEGER NOT NULL,
                        `voiceAuthorized` INTEGER NOT NULL,
                        `subjectAgeStatus` TEXT NOT NULL,
                        `authorizedPhotoAssetIdsCsv` TEXT NOT NULL,
                        `authorizedVideoAssetIdsCsv` TEXT NOT NULL,
                        `authorizedVoiceAssetIdsCsv` TEXT NOT NULL,
                        `communicationStyleSummary` TEXT,
                        `approvedVocabularyCsv` TEXT NOT NULL,
                        `approvedKnowledgeMemoryIdsCsv` TEXT NOT NULL,
                        `editablePersonalityJson` TEXT,
                        `behaviorNotes` TEXT,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`tenantKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `customer_onboardings` (
                        `customerId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `subjectAgeStatus` TEXT NOT NULL,
                        `contentPolicyAccepted` INTEGER NOT NULL,
                        `likenessUseAuthorized` INTEGER NOT NULL,
                        `voiceUseAuthorized` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`customerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shared_memories` (
                        `id` TEXT NOT NULL,
                        `tenantKey` TEXT NOT NULL,
                        `layer` TEXT NOT NULL,
                        `contentSummary` TEXT NOT NULL,
                        `dataClassification` TEXT NOT NULL,
                        `sourcePersona` TEXT NOT NULL,
                        `sourceKind` TEXT NOT NULL,
                        `sourceReferenceId` TEXT NOT NULL,
                        `lastReviewedByOwner` INTEGER NOT NULL,
                        `recordedAtEpochMs` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `reviewStatus` TEXT NOT NULL,
                        `ownerCorrectionNote` TEXT,
                        `projectId` TEXT,
                        `preferenceKey` TEXT,
                        `verifiedExperienceCount` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context, databaseName: String = "afrina_phase0.db"): AfrinaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AfrinaDatabase::class.java,
                databaseName,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
