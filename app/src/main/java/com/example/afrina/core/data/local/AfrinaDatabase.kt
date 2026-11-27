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
    ],
    version = 2,
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

        fun build(context: Context): AfrinaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AfrinaDatabase::class.java,
                "afrina_phase0.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
