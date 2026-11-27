package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.FreeTierFallbackProxyAdapter
import com.example.afrina.core.ai.FreeTierPrimaryProxyAdapter
import com.example.afrina.core.ai.LocalSimulatedAiGateway
import com.example.afrina.core.ai.PaidFrontierCloudAdapter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.ai.SelfHostedOpenWeightAdapter
import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.repository.AiRouterRepositoryImpl
import com.example.afrina.core.data.repository.SimulatedAgentRepository
import com.example.afrina.core.data.repository.toPaidApprovalGrant
import com.example.afrina.core.designsystem.AfrinaTheme
import com.example.afrina.core.domain.AiCompletionRequest
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.ThemePreference
import com.example.afrina.core.model.UsageCostCategory
import com.example.afrina.core.persona.AfrinaEdition
import com.example.afrina.core.persona.CentralPersonaOrchestrator
import com.example.afrina.core.persona.DevelopmentCapability
import com.example.afrina.core.persona.DevelopmentPermissionDecision
import com.example.afrina.core.persona.PersonaCatalog
import com.example.afrina.core.persona.PersonaFoundationSnapshot
import com.example.afrina.feature.agents.AgentsScreen
import com.example.afrina.feature.agents.AgentsUiState
import com.example.afrina.feature.dashboard.DashboardScreen
import com.example.afrina.feature.dashboard.DashboardUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read app name matches AFRINA AI Development Cloud`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AFRINA AI Development Cloud", appName)
    }

    @Test
    fun `dashboard renders KPI tiles in Persian RTL`() {
        composeRule.setContent {
            AfrinaTheme(language = AppLanguage.PERSIAN, themePreference = ThemePreference.DARK) {
                DashboardScreen(
                    uiState = DashboardUiState(),
                    onToggleTask = {},
                    onNavigateProjects = {},
                    onNavigateAgents = {},
                    onNavigatePipelines = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("kpi_projects").assertIsDisplayed()
        composeRule.onNodeWithTag("kpi_approvals").assertIsDisplayed()
        composeRule.onNodeWithTag("kpi_projects").performClick()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy simulated AI gateway rejects direct execution`() = runTest {
        val gateway = LocalSimulatedAiGateway()
        var blocked = false
        try {
            gateway.executePrompt(
                AiCompletionRequest(
                    agentRole = AgentRole.SECURITY_AUDITOR,
                    modelId = "provider-agnostic/security-auditor-v1",
                    prompt = "Audit phase 0",
                    contextProjectSlug = "afrina-control-plane",
                )
            )
        } catch (_: UnsupportedOperationException) {
            blocked = true
        }
        assertTrue(blocked)
    }

    @Test
    fun `all seven legacy agent roles route through central AI router and record usage ledger`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val router = CentralAiProviderRouter(
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
            val routerRepo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))
            val agentRepo = SimulatedAgentRepository(db, routerRepo)
            val uniquePromptMarker = "AGENT_PROMPT_MARKER_9911"

            for (role in AgentRole.entries) {
                agentRepo.triggerSimulatedAgentPlan(
                    projectId = "afrina-control-plane",
                    role = role,
                    promptSummary = "$uniquePromptMarker for ${role.name}",
                )
            }

            val ledger = db.aiUsageLedgerDao().observeAll().first()
            assertEquals(AgentRole.entries.size, ledger.size)
            assertTrue(ledger.all { it.usageCategory == UsageCostCategory.FREE_TIER.name })
            assertTrue(ledger.all { it.estimatedApiCostUsd == 0.0 })
            assertTrue(ledger.all { it.isSimulated })

            val activities = db.agentActivityDao().observeAll().first()
            assertEquals(AgentRole.entries.size, activities.size)
            assertTrue(activities.all { it.toolInvoked.startsWith("central_ai_router:") })

            val audits = db.auditLogDao().observeRecent().first()
            assertTrue(audits.none { it.target.contains(uniquePromptMarker) || it.action.contains(uniquePromptMarker) })
        } finally {
            db.close()
        }
    }

    @Test
    fun `persona foundation orchestrator integrates with Room router repository without leaking sensitive prompts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AfrinaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val router = CentralAiProviderRouter(
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
            val routerRepo = AiRouterRepositoryImpl(db, router, PersonaDispatcher(router))
            val orchestrator = CentralPersonaOrchestrator(
                edition = AfrinaEdition.MASTER_OWNER,
                aiRouterRepository = routerRepo,
                auditLogDao = db.auditLogDao(),
            )
            val sensitiveSecret = "TOP_SECRET_FOUNDER_PHRASE_4488"

            val blockedOutcome = orchestrator.interactWithPersona(
                persona = com.example.afrina.core.model.AfrinaPersona.GUARDIAN_SENTINEL,
                taskTitle = "Unauthorized twin test",
                prompt = sensitiveSecret,
                useGuardianDigitalTwinIdentity = true,
            )
            assertTrue(blockedOutcome is com.example.afrina.core.model.AiRouteOutcome.PrivacyAuthorizationRequired)

            val safeOutcome = orchestrator.interactWithPersona(
                persona = com.example.afrina.core.model.AfrinaPersona.ARCHITECT_STRATEGIST,
                taskTitle = "Architecture check",
                prompt = "Outline Phase 2 module boundaries",
            )
            assertTrue(safeOutcome is com.example.afrina.core.model.AiRouteOutcome.Success)

            val decision = orchestrator.evaluateDevelopmentCapability(
                capability = DevelopmentCapability.PROPOSE_CODE,
                inIsolatedDevEnvironment = true,
                ownerApprovedWorkflow = true,
            )
            assertTrue(decision is DevelopmentPermissionDecision.Allowed)

            val ledger = db.aiUsageLedgerDao().observeAll().first()
            assertEquals(1, ledger.size)
            assertEquals(UsageCostCategory.FREE_TIER.name, ledger.first().usageCategory)

            val audits = db.auditLogDao().observeRecent().first()
            assertTrue(audits.any { it.action == "GUARDIAN_TWIN_BLOCKED" })
            assertTrue(audits.any { it.action == "DEV_PERMISSION_CHECK" })
            assertTrue(audits.none { it.target.contains(sensitiveSecret) || it.action.contains(sensitiveSecret) })
        } finally {
            db.close()
        }
    }

    @Test
    fun `agents screen displays Phase 2 Persona Foundation card cleanly`() {
        composeRule.setContent {
            AfrinaTheme(language = AppLanguage.ENGLISH, themePreference = ThemePreference.DARK) {
                AgentsScreen(
                    uiState = AgentsUiState(
                        personaSnapshot = PersonaFoundationSnapshot(
                            edition = AfrinaEdition.MASTER_OWNER,
                            profiles = PersonaCatalog.defaultProfiles().values.toList(),
                        )
                    ),
                    onResolveApproval = { _, _ -> },
                    onSimulateAgent = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("persona_foundation_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_affective_freeze_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reset_affective_state_button").performScrollTo().assertIsDisplayed()
    }
}
