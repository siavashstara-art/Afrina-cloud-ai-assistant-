package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.afrina.AfrinaApp
import com.example.afrina.core.designsystem.AfrinaTheme
import com.example.afrina.core.di.AfrinaContainer
import com.example.afrina.core.model.UserPreferences
import com.example.afrina.feature.agents.AgentsViewModel
import com.example.afrina.feature.dashboard.DashboardViewModel
import com.example.afrina.feature.pipelines.PipelinesViewModel
import com.example.afrina.feature.projects.ProjectsViewModel
import com.example.afrina.feature.settings.SettingsViewModel
import com.example.afrina.navigation.AfrinaRootShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AfrinaApp).container
        val factory = AfrinaViewModelFactory(container)

        setContent {
            val prefs by container.preferencesRepository
                .observePreferences()
                .collectAsStateWithLifecycle(initialValue = UserPreferences())

            AfrinaTheme(
                language = prefs.language,
                themePreference = prefs.theme,
            ) {
                val dashboardVm: DashboardViewModel = viewModel(factory = factory)
                val projectsVm: ProjectsViewModel = viewModel(factory = factory)
                val agentsVm: AgentsViewModel = viewModel(factory = factory)
                val pipelinesVm: PipelinesViewModel = viewModel(factory = factory)
                val settingsVm: SettingsViewModel = viewModel(factory = factory)

                AfrinaRootShell(
                    dashboardViewModel = dashboardVm,
                    projectsViewModel = projectsVm,
                    agentsViewModel = agentsVm,
                    pipelinesViewModel = pipelinesVm,
                    settingsViewModel = settingsVm,
                )
            }
        }
    }
}

private class AfrinaViewModelFactory(
    private val container: AfrinaContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        when (modelClass) {
            DashboardViewModel::class.java -> DashboardViewModel(
                projectRepository = container.projectRepository,
                agentRepository = container.agentRepository,
                pipelineRepository = container.pipelineRepository,
                notificationRepository = container.notificationRepository,
            ) as T

            ProjectsViewModel::class.java -> ProjectsViewModel(
                projectRepository = container.projectRepository,
            ) as T

            AgentsViewModel::class.java -> AgentsViewModel(
                agentRepository = container.agentRepository,
                aiRouterRepository = container.aiRouterRepository,
                personaArchitectureRepository = container.personaArchitectureRepository,
            ) as T

            PipelinesViewModel::class.java -> PipelinesViewModel(
                pipelineRepository = container.pipelineRepository,
            ) as T

            SettingsViewModel::class.java -> SettingsViewModel(
                preferencesRepository = container.preferencesRepository,
                vaultCryptoManager = container.vaultCryptoManager,
                notificationRepository = container.notificationRepository,
            ) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
}
