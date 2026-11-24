package com.example.afrina.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        DevTaskEntity::class,
        ApprovalRequestEntity::class,
        AgentActivityEntity::class,
        NotificationEntity::class,
        AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AfrinaDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun devTaskDao(): DevTaskDao
    abstract fun approvalDao(): ApprovalDao
    abstract fun agentActivityDao(): AgentActivityDao
    abstract fun notificationDao(): NotificationDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        fun build(context: Context): AfrinaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AfrinaDatabase::class.java,
                "afrina_phase0.db",
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
