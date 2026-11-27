package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.FreeTierFallbackProxyAdapter
import com.example.afrina.core.ai.FreeTierPrimaryProxyAdapter
import com.example.afrina.core.ai.PaidFrontierCloudAdapter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.ai.SelfHostedOpenWeightAdapter
import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.repository.AiRouterRepositoryImpl
import com.example.afrina.core.data.repository.toPaidApprovalGrant
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiModality
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.model.TaskRoutingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AfrinaDatabaseMigrationTest {

    @Test
    fun `migration 1 to 2 preserves existing v1 records and adds ai router tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_afrina.db"
        context.deleteDatabase(dbName)

        // Create version 1 database manually matching exact Phase 0 schema
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameFa` TEXT NOT NULL, `slug` TEXT NOT NULL, `description` TEXT NOT NULL, `descriptionFa` TEXT NOT NULL, `stackTagsCsv` TEXT NOT NULL, `healthStatus` TEXT NOT NULL, `repoFullName` TEXT NOT NULL, `defaultBranch` TEXT NOT NULL, `lastCommitSha` TEXT NOT NULL, `lastBuildStatus` TEXT NOT NULL, `activeEnvironment` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `dev_tasks` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `title` TEXT NOT NULL, `titleFa` TEXT NOT NULL, `priority` TEXT NOT NULL, `status` TEXT NOT NULL, `assignedAgent` TEXT, `requiresApproval` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `approval_requests` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `agentRole` TEXT NOT NULL, `actionTitleEn` TEXT NOT NULL, `actionTitleFa` TEXT NOT NULL, `commandPreview` TEXT NOT NULL, `riskLevel` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_activities` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `agentRole` TEXT NOT NULL, `summaryEn` TEXT NOT NULL, `summaryFa` TEXT NOT NULL, `toolInvoked` TEXT NOT NULL, `state` TEXT NOT NULL, `timestampLabel` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `notifications` (`id` TEXT NOT NULL, `titleEn` TEXT NOT NULL, `titleFa` TEXT NOT NULL, `bodyEn` TEXT NOT NULL, `bodyFa` TEXT NOT NULL, `severity` TEXT NOT NULL, `isRead` INTEGER NOT NULL, `timestampLabel` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `audit_logs` (`id` TEXT NOT NULL, `actor` TEXT NOT NULL, `action` TEXT NOT NULL, `target` TEXT NOT NULL, `outcome` TEXT NOT NULL, `timestampEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("INSERT INTO `projects` VALUES ('proj-keep', 'Kept Project', 'پروژه حفظ‌شده', 'kept-proj', 'Desc', 'توضیح', 'Kotlin', 'HEALTHY', 'owner/repo', 'main', 'abc1234', 'PASSED', 'local', 1000)")
                        db.execSQL("INSERT INTO `audit_logs` VALUES ('aud-keep', 'Founder', 'INIT', 'SYSTEM', 'SUCCESS', 1000)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()

        // Open with Room v2 and MIGRATION_1_2
        val roomDb = Room.databaseBuilder(context, AfrinaDatabase::class.java, dbName)
            .addMigrations(AfrinaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val projects = roomDb.projectDao().observeAll().first()
            val audits = roomDb.auditLogDao().observeRecent().first()
            assertEquals(1, projects.size)
            assertEquals("proj-keep", projects.first().id)
            assertEquals(1, audits.size)
            assertEquals("aud-keep", audits.first().id)

            // Verify AiRouterRepositoryImpl writes ledger & paid approvals without exposing sensitive prompts
            val router = createWiredRouter(roomDb)
            val repo = AiRouterRepositoryImpl(roomDb, router, PersonaDispatcher(router))

            // 1. Privacy rejection does NOT log sensitive prompt text
            val secretPrompt = "SECRET_DIGITAL_TWIN_PASSPORT_9988"
            val privacyOutcome = repo.executePersonaTask(
                TaskRoutingRequest(
                    persona = AfrinaPersona.GUARDIAN_SENTINEL,
                    taskTitle = "Sensitive check",
                    prompt = secretPrompt,
                    complexity = TaskComplexity.STANDARD,
                    dataClassification = DataClassification.DIGITAL_TWIN_SENSITIVE,
                    explicitSensitiveDataAuthorized = false,
                    allowLocalOpenWeight = false,
                )
            )
            assertTrue(privacyOutcome is AiRouteOutcome.PrivacyAuthorizationRequired)
            val logsAfterPrivacy = roomDb.auditLogDao().observeRecent().first()
            assertTrue(logsAfterPrivacy.none { it.target.contains(secretPrompt) || it.action.contains(secretPrompt) })

            // 2. Paid request creates PENDING approval, UI approval records status only without executing
            val paidOutcome = repo.executePersonaTask(
                TaskRoutingRequest(
                    persona = AfrinaPersona.ARCHITECT_STRATEGIST,
                    taskTitle = "Video inspection",
                    prompt = "Inspect clip",
                    complexity = TaskComplexity.COMPLEX_REASONING,
                    requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
                    dataClassification = DataClassification.PROJECT_CODE,
                )
            )
            assertTrue(paidOutcome is AiRouteOutcome.PaidApprovalRequired)
            val proposalId = (paidOutcome as AiRouteOutcome.PaidApprovalRequired).proposal.id

            repo.approvePaidInference(proposalId)
            val approvedRecord = roomDb.paidInferenceApprovalDao().getById(proposalId)!!
            assertEquals(ApprovalStatus.APPROVED.name, approvedRecord.status)
            assertFalse(approvedRecord.isExecuted)
            assertTrue(roomDb.aiUsageLedgerDao().observeAll().first().isEmpty())

            // 3. Separate explicit execution runs approved simulated paid inference
            val execOutcome = repo.executeApprovedPaidTask(proposalId)
            assertTrue(execOutcome is AiRouteOutcome.Success)
            assertTrue(roomDb.paidInferenceApprovalDao().getById(proposalId)!!.isExecuted)
            assertEquals(1, roomDb.aiUsageLedgerDao().observeAll().first().size)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `concurrent execution attempts on single paid approval execute at most once`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java).build()
        try {
            val router = createWiredRouter(db)
            val repo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))

            val proposalOutcome = repo.executePersonaTask(videoPaidRequest())
            val proposalId = (proposalOutcome as AiRouteOutcome.PaidApprovalRequired).proposal.id
            repo.approvePaidInference(proposalId)

            val outcomes = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { repo.executeApprovedPaidTask(proposalId) },
                    async(Dispatchers.IO) { repo.executeApprovedPaidTask(proposalId) },
                ).awaitAll()
            }

            assertEquals(1, outcomes.count { it is AiRouteOutcome.Success })
            assertEquals(1, outcomes.count { it is AiRouteOutcome.PausedQuotaOrProviderUnavailable })

            val finalEntity = db.paidInferenceApprovalDao().getById(proposalId)!!
            assertEquals(ApprovalStatus.APPROVED.name, finalEntity.status)
            assertTrue(finalEntity.isExecuted)
            assertEquals(1, db.aiUsageLedgerDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `consumed paid approval cannot be reused via executeApprovedPaidTask or executePersonaTask`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val router = createWiredRouter(db)
            val repo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))
            val baseRequest = videoPaidRequest()

            val proposalId = (repo.executePersonaTask(baseRequest) as AiRouteOutcome.PaidApprovalRequired).proposal.id
            repo.approvePaidInference(proposalId)

            assertTrue(repo.executeApprovedPaidTask(proposalId) is AiRouteOutcome.Success)
            assertEquals(1, db.aiUsageLedgerDao().observeAll().first().size)

            // Reuse via executeApprovedPaidTask must be rejected
            val replayViaButton = repo.executeApprovedPaidTask(proposalId)
            assertTrue(replayViaButton is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

            // Reuse via executePersonaTask with matching parameters must also be rejected
            val replayViaPersonaTask = repo.executePersonaTask(baseRequest.copy(explicitPaidApprovalId = proposalId))
            assertTrue(replayViaPersonaTask is AiRouteOutcome.PausedQuotaOrProviderUnavailable)
            assertEquals(1, db.aiUsageLedgerDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `tampered task parameters through executePersonaTask are blocked without consuming approval`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val router = createWiredRouter(db)
            val repo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))
            val original = videoPaidRequest()

            val proposalId = (repo.executePersonaTask(original) as AiRouteOutcome.PaidApprovalRequired).proposal.id
            repo.approvePaidInference(proposalId)

            val tamperedVariants = listOf(
                original.copy(persona = AfrinaPersona.BUILDER_ENGINEER, explicitPaidApprovalId = proposalId),
                original.copy(complexity = TaskComplexity.STANDARD, explicitPaidApprovalId = proposalId),
                original.copy(requiresCoding = true, explicitPaidApprovalId = proposalId),
                original.copy(
                    requiredModalities = setOf(AiModality.TEXT, AiModality.IMAGE, AiModality.VIDEO),
                    explicitPaidApprovalId = proposalId,
                ),
                original.copy(dataClassification = DataClassification.PUBLIC_OR_SYNTHETIC, explicitPaidApprovalId = proposalId),
                original.copy(explicitSensitiveDataAuthorized = true, explicitPaidApprovalId = proposalId),
            )

            for (tampered in tamperedVariants) {
                val blocked = repo.executePersonaTask(tampered)
                assertTrue(blocked is AiRouteOutcome.PausedQuotaOrProviderUnavailable)
            }

            // Approval remains unconsumed and still works once with exact original parameters
            val entityBeforeValidRun = db.paidInferenceApprovalDao().getById(proposalId)!!
            assertEquals(ApprovalStatus.APPROVED.name, entityBeforeValidRun.status)
            assertFalse(entityBeforeValidRun.isExecuted)
            assertTrue(db.aiUsageLedgerDao().observeAll().first().isEmpty())

            val validExecution = repo.executePersonaTask(original.copy(explicitPaidApprovalId = proposalId))
            assertTrue(validExecution is AiRouteOutcome.Success)
            assertTrue(db.paidInferenceApprovalDao().getById(proposalId)!!.isExecuted)
            assertEquals(1, db.aiUsageLedgerDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `missing blank pending rejected approvals and invalid state transitions are blocked`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val router = createWiredRouter(db)
            val repo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))

            assertTrue(repo.executeApprovedPaidTask("") is AiRouteOutcome.PausedQuotaOrProviderUnavailable)
            assertTrue(repo.executeApprovedPaidTask("missing-proposal-id") is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

            // Pending proposal cannot execute before owner approval
            val pendingId = (repo.executePersonaTask(videoPaidRequest()) as AiRouteOutcome.PaidApprovalRequired).proposal.id
            assertTrue(repo.executeApprovedPaidTask(pendingId) is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

            // Rejected proposal cannot be later approved or executed
            repo.rejectPaidInference(pendingId)
            repo.approvePaidInference(pendingId)
            assertEquals(ApprovalStatus.REJECTED.name, db.paidInferenceApprovalDao().getById(pendingId)!!.status)
            assertTrue(repo.executeApprovedPaidTask(pendingId) is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

            // Approved proposal cannot be downgraded back to REJECTED via rejectPaidInference
            val secondId = (repo.executePersonaTask(videoPaidRequest()) as AiRouteOutcome.PaidApprovalRequired).proposal.id
            repo.approvePaidInference(secondId)
            repo.rejectPaidInference(secondId)
            assertEquals(ApprovalStatus.APPROVED.name, db.paidInferenceApprovalDao().getById(secondId)!!.status)
            assertTrue(db.aiUsageLedgerDao().observeAll().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `paid execution failure transitions EXECUTING to FAILED without marking executed or retrying`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val router = createWiredRouter(db)
            val repo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))

            val proposalId = (repo.executePersonaTask(videoPaidRequest()) as AiRouteOutcome.PaidApprovalRequired).proposal.id
            repo.approvePaidInference(proposalId)

            // Simulate paid adapter failure during execution
            router.setSimulatedHealthy("server-proxy-paid-frontier", false)
            val failedOutcome = repo.executeApprovedPaidTask(proposalId)
            assertTrue(failedOutcome is AiRouteOutcome.PausedQuotaOrProviderUnavailable)

            val failedEntity = db.paidInferenceApprovalDao().getById(proposalId)!!
            assertEquals(ApprovalStatus.FAILED.name, failedEntity.status)
            assertFalse(failedEntity.isExecuted)
            assertTrue(db.aiUsageLedgerDao().observeAll().first().isEmpty())
            assertTrue(db.auditLogDao().observeRecent().first().any { it.action == "PAID_EXECUTION_FAILED" && it.target == proposalId })

            // Restoring provider health does NOT allow silent replay of FAILED approval
            router.setSimulatedHealthy("server-proxy-paid-frontier", true)
            assertTrue(repo.executeApprovedPaidTask(proposalId) is AiRouteOutcome.PausedQuotaOrProviderUnavailable)
        } finally {
            db.close()
        }
    }

    private fun createWiredRouter(db: AfrinaDatabase): CentralAiProviderRouter =
        CentralAiProviderRouter(
            adapters = listOf(
                FreeTierPrimaryProxyAdapter(),
                FreeTierFallbackProxyAdapter(),
                SelfHostedOpenWeightAdapter(),
                PaidFrontierCloudAdapter(),
            ),
            approvalGrantResolver = { proposalId ->
                db.paidInferenceApprovalDao().getById(proposalId)
                    ?.takeIf { it.status == ApprovalStatus.EXECUTING.name && !it.isExecuted }
                    ?.toPaidApprovalGrant()
            },
        )

    private fun videoPaidRequest() = TaskRoutingRequest(
        persona = AfrinaPersona.ARCHITECT_STRATEGIST,
        taskTitle = "Video inspection",
        prompt = "Inspect clip",
        complexity = TaskComplexity.COMPLEX_REASONING,
        requiresCoding = false,
        requiredModalities = setOf(AiModality.TEXT, AiModality.VIDEO),
        dataClassification = DataClassification.PROJECT_CODE,
        explicitSensitiveDataAuthorized = false,
    )
}
