package com.example.afrina.feature.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.afrina.core.designsystem.AfrinaPalette
import com.example.afrina.core.designsystem.AfrinaSurfaceCard
import com.example.afrina.core.designsystem.MonoChip
import com.example.afrina.core.designsystem.SectionHeader
import com.example.afrina.core.designsystem.StatusPill
import com.example.afrina.core.designsystem.healthColor
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.model.AfrinaProject
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.ProjectHealthStatus
import com.example.afrina.core.model.TaskStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectsScreen(
    uiState: ProjectsUiState,
    onSearchChange: (String) -> Unit,
    onCreateProject: (name: String, repo: String, stack: String, desc: String) -> Unit,
    onCreateTask: (projectId: String, title: String) -> Unit,
    onToggleTask: (DevTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN
    var showCreateProject by remember { mutableStateOf(false) }
    var taskTargetProject by remember { mutableStateOf<AfrinaProject?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("projects_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    title = strings.sectionProjectOverview,
                    icon = Icons.Outlined.FolderCopy,
                    origin = ImplementationOrigin.LOCAL_PERSISTED,
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text(strings.searchProjectsHint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("search_projects_input"),
                )
                Button(
                    onClick = { showCreateProject = true },
                    modifier = Modifier.testTag("create_project_button"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(strings.addProject, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        if (uiState.projects.isEmpty()) {
            item {
                AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                    Text(
                        text = strings.emptyProjects,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(uiState.projects, key = { it.id }) { project ->
                val projectTasks = uiState.tasks.filter { it.projectId == project.id }
                AfrinaSurfaceCard(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .testTag("project_card_${project.id}"),
                    highlightColor = healthColor(project.healthStatus),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) project.nameFa else project.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = project.repoFullName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        StatusPill(
                            label = when (project.healthStatus) {
                                ProjectHealthStatus.HEALTHY -> strings.statusHealthy
                                ProjectHealthStatus.WARNING -> strings.statusWarning
                                ProjectHealthStatus.DEGRADED -> strings.statusDegraded
                                ProjectHealthStatus.IDLE -> strings.statusIdle
                            },
                            color = healthColor(project.healthStatus),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isFa) project.descriptionFa else project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        project.stackTags.forEach { MonoChip(it) }
                        MonoChip("${project.defaultBranch}@${project.lastCommitSha}")
                        MonoChip(project.activeEnvironment)
                    }
                    if (projectTasks.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        projectTasks.forEach { task ->
                            val isDone = task.status == TaskStatus.DONE
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleTask(task) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { onToggleTask(task) }) {
                                    Icon(
                                        imageVector = if (isDone) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                        contentDescription = strings.taskDoneToggleDesc,
                                        tint = if (isDone) AfrinaPalette.EmeraldHealthy else MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = if (isFa) task.titleFa else task.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilledTonalButton(
                            onClick = { taskTargetProject = project },
                            modifier = Modifier.testTag("add_task_btn_${project.id}"),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(strings.addTask, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }

    if (showCreateProject) {
        CreateProjectDialog(
            onDismiss = { showCreateProject = false },
            onConfirm = { name, repo, stack, desc ->
                onCreateProject(name, repo, stack, desc)
                showCreateProject = false
            },
        )
    }

    taskTargetProject?.let { target ->
        CreateTaskDialog(
            projectName = if (isFa) target.nameFa else target.name,
            onDismiss = { taskTargetProject = null },
            onConfirm = { title ->
                onCreateTask(target.id, title)
                taskTargetProject = null
            },
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    val strings = LocalAfrinaStrings.current
    var name by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var stack by remember { mutableStateOf("Kotlin, Compose, API") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addProject) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.projectNameHint) },
                    singleLine = true,
                    modifier = Modifier.testTag("dialog_project_name"),
                )
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text(strings.repoHint) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = stack,
                    onValueChange = { stack = it },
                    label = { Text(strings.stackHint) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(strings.descriptionHint) },
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, repo, stack, desc) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("dialog_project_confirm"),
            ) {
                Text(strings.createAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelAction)
            }
        },
    )
}

@Composable
private fun CreateTaskDialog(
    projectName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalAfrinaStrings.current
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${strings.addTask} • $projectName") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(strings.taskTitleHint) },
                singleLine = true,
                modifier = Modifier.testTag("dialog_task_title"),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("dialog_task_confirm"),
            ) {
                Text(strings.createAction)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelAction)
            }
        },
    )
}
