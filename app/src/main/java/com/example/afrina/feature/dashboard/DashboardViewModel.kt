package com.example.afrina.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.NotificationRepository
import com.example.afrina.core.domain.PipelineRepository
import com.example.afrina.core.domain.ProjectRepository
import com.example.afrina.core.model.AfrinaProject
import com.example.afrina.core.model.AgentActivity
import com.example.afrina.core.model.ApprovalRequest
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.BuildPipelineRun
import com.example.afrina.core.model.CloudNotification
import com.example.afrina.core.model.DeploymentRecord
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.GitHubRepoConnection
import com.example.afrina.core.model.SystemHealthSnapshot
import com.example.afrina.core.model.TaskStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val projects: List<AfrinaProject> = emptyList(),
    val tasks: List<DevTask> = emptyList(),
    val activities: List<AgentActivity> = emptyList(),
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    val githubConnections: List<GitHubRepoConnection> = emptyList(),
    val latestBuilds: List<BuildPipelineRun> = emptyList(),
    val latestDeployments: List<DeploymentRecord> = emptyList(),
    val systemHealth: SystemHealthSnapshot? = null,
    val unreadNotificationsCount: Int = 0,
    val notifications: List<CloudNotification> = emptyList(),
)

class DashboardViewModel(
    private val projectRepository: ProjectRepository,
    private val agentRepository: AgentRepository,
    private val pipelineRepository: PipelineRepository,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    private val coreStreams = combine(
        projectRepository.observeProjects(),
        projectRepository.observeTasks(),
        agentRepository.observeActivities(),
        agentRepository.observeApprovals(),
    ) { projects, tasks, activities, approvals ->
        Quad(projects, tasks, activities, approvals)
    }

    private val opsStreams = combine(
        pipelineRepository.observeGitHubConnections(),
        pipelineRepository.observeBuilds(),
        pipelineRepository.observeDeployments(),
        pipelineRepository.observeSystemHealth(),
        notificationRepository.observeNotifications(),
    ) { repos, builds, deployments, health, notifications ->
        Quint(repos, builds, deployments, health, notifications)
    }

    val uiState: StateFlow<DashboardUiState> = combine(coreStreams, opsStreams) { core, ops ->
        val pending = core.fourth.filter { it.status == ApprovalStatus.PENDING }
        DashboardUiState(
            projects = core.first,
            tasks = core.second,
            activities = core.third,
            pendingApprovals = pending,
            githubConnections = ops.first,
            latestBuilds = ops.second,
            latestDeployments = ops.third,
            systemHealth = ops.fourth,
            unreadNotificationsCount = ops.fifth.count { !it.isRead },
            notifications = ops.fifth,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun toggleTaskCompletion(task: DevTask) {
        viewModelScope.launch {
            val next = if (task.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE
            projectRepository.updateTaskStatus(task.id, next)
        }
    }

    fun markNotificationsRead() {
        viewModelScope.launch {
            notificationRepository.markAllRead()
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    private data class Quint<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )
}
