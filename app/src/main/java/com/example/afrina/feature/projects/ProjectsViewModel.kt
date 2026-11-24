package com.example.afrina.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.afrina.core.domain.ProjectRepository
import com.example.afrina.core.model.AfrinaProject
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.TaskPriority
import com.example.afrina.core.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val projects: List<AfrinaProject> = emptyList(),
    val tasks: List<DevTask> = emptyList(),
    val searchQuery: String = "",
    val selectedProjectId: String? = null,
)

class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedProjectId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProjectsUiState> = combine(
        projectRepository.observeProjects(),
        projectRepository.observeTasks(),
        searchQuery,
        selectedProjectId,
    ) { projects, tasks, query, selectedId ->
        val filtered = if (query.isBlank()) {
            projects
        } else {
            val q = query.trim().lowercase()
            projects.filter {
                it.name.lowercase().contains(q) ||
                    it.nameFa.lowercase().contains(q) ||
                    it.repoFullName.lowercase().contains(q)
            }
        }
        ProjectsUiState(
            projects = filtered,
            tasks = tasks,
            searchQuery = query,
            selectedProjectId = selectedId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProjectsUiState(),
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun selectProject(projectId: String?) {
        selectedProjectId.value = projectId
    }

    fun createProject(name: String, repo: String, stackCsv: String, description: String) {
        if (name.isBlank()) return
        val tags = stackCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        viewModelScope.launch {
            projectRepository.createProject(name, repo, tags, description)
        }
    }

    fun createTask(projectId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            projectRepository.createTask(
                projectId = projectId,
                title = title,
                priority = TaskPriority.HIGH,
                assignedAgent = AgentRole.PROGRAMMER,
            )
        }
    }

    fun toggleTask(task: DevTask) {
        viewModelScope.launch {
            val next = if (task.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE
            projectRepository.updateTaskStatus(task.id, next)
        }
    }
}
