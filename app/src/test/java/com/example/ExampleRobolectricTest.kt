package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.afrina.core.ai.LocalSimulatedAiGateway
import com.example.afrina.core.designsystem.AfrinaTheme
import com.example.afrina.core.domain.AiCompletionRequest
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ThemePreference
import com.example.afrina.feature.dashboard.DashboardScreen
import com.example.afrina.feature.dashboard.DashboardUiState
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

    @Test
    fun `simulated AI gateway returns explicit simulated flag`() = runTest {
        val gateway = LocalSimulatedAiGateway()
        val res = gateway.executePrompt(
            AiCompletionRequest(
                agentRole = AgentRole.SECURITY_AUDITOR,
                modelId = "provider-agnostic/security-auditor-v1",
                prompt = "Audit phase 0",
                contextProjectSlug = "afrina-control-plane",
            )
        )
        assertTrue(res.isSimulated)
        assertTrue(res.outputTextFa.isNotBlank())
    }
}
