package com.example.afrina.feature.pipelines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.afrina.core.domain.PipelineRepository
import com.example.afrina.core.model.BuildPipelineRun
import com.example.afrina.core.model.DeploymentRecord
import com.example.afrina.core.model.DeploymentTarget
import com.example.afrina.core.model.GitHubRepoConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PipelinesUiState(
    val githubConnections: List<GitHubRepoConnection> = emptyList(),
    val builds: List<BuildPipelineRun> = emptyList(),
    val deployments: List<DeploymentRecord> = emptyList(),
)

class PipelinesViewModel(
    private val pipelineRepository: PipelineRepository,
) : ViewModel() {

    val uiState: StateFlow<PipelinesUiState> = combine(
        pipelineRepository.observeGitHubConnections(),
        pipelineRepository.observeBuilds(),
        pipelineRepository.observeDeployments(),
    ) { repos, builds, deployments ->
        PipelinesUiState(
            githubConnections = repos,
            builds = builds,
            deployments = deployments,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PipelinesUiState(),
    )

    fun simulateBuild() {
        viewModelScope.launch {
            pipelineRepository.triggerSimulatedBuild("afrina-android-client", "phase-0/scaffold")
        }
    }

    fun simulateDryRunDeploy() {
        viewModelScope.launch {
            pipelineRepository.triggerSimulatedDeploy("afrina-control-plane", DeploymentTarget.API_CONTAINER)
        }
    }
}
