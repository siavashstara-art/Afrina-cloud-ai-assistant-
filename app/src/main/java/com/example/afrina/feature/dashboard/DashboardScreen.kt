package com.example.afrina.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.afrina.core.designsystem.AfrinaPalette
import com.example.afrina.core.designsystem.AfrinaSurfaceCard
import com.example.afrina.core.designsystem.KpiMetricTile
import com.example.afrina.core.designsystem.MonoChip
import com.example.afrina.core.designsystem.OriginBadge
import com.example.afrina.core.designsystem.SectionHeader
import com.example.afrina.core.designsystem.StatusPill
import com.example.afrina.core.designsystem.healthColor
import com.example.afrina.core.designsystem.pipelineColor
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.DevTask
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.PipelineStatus
import com.example.afrina.core.model.ProjectHealthStatus
import com.example.afrina.core.model.TaskStatus

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onToggleTask: (DevTask) -> Unit,
    onNavigateProjects: () -> Unit,
    onNavigateAgents: () -> Unit,
    onNavigatePipelines: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Phase 0 Truth-in-UI Banner
        item {
            AfrinaSurfaceCard(
                modifier = Modifier.widthIn(max = 760.dp),
                highlightColor = AfrinaPalette.VioletSimulated,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OriginBadge(origin = ImplementationOrigin.SIMULATED_PHASE_0)
                    Text(
                        text = strings.phase0BannerTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = strings.phase0BannerDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // KPI Metrics Grid (2x2 clean mobile layout)
        item {
            val openTasks = uiState.tasks.count { it.status != TaskStatus.DONE }
            Column(
                modifier = Modifier.widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiMetricTile(
                        label = strings.projectsTotalLabel,
                        value = uiState.projects.size.toString(),
                        accent = MaterialTheme.colorScheme.primary,
                        icon = Icons.Outlined.FolderCopy,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateProjects)
                            .testTag("kpi_projects"),
                    )
                    KpiMetricTile(
                        label = strings.tasksOpenLabel,
                        value = openTasks.toString(),
                        accent = AfrinaPalette.CobaltBlue,
                        icon = Icons.Outlined.TaskAlt,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("kpi_tasks"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiMetricTile(
                        label = strings.approvalsPendingLabel,
                        value = uiState.pendingApprovals.size.toString(),
                        accent = if (uiState.pendingApprovals.isNotEmpty()) AfrinaPalette.AmberWarning else AfrinaPalette.EmeraldHealthy,
                        icon = Icons.Outlined.Security,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateAgents)
                            .testTag("kpi_approvals"),
                    )
                    KpiMetricTile(
                        label = strings.activeSandboxLabel,
                        value = (uiState.systemHealth?.activeSandboxContainers ?: 0).toString(),
                        accent = AfrinaPalette.VioletSimulated,
                        icon = Icons.Outlined.Memory,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigatePipelines)
                            .testTag("kpi_sandboxes"),
                    )
                }
            }
        }

        // Section 1: Project Overview (Room DB)
        item {
            SectionHeader(
                title = strings.sectionProjectOverview,
                icon = Icons.Outlined.FolderCopy,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
                actionLabel = strings.viewAll,
                onActionClick = onNavigateProjects,
            )
        }
        items(uiState.projects.take(3), key = { it.id }) { project ->
            AfrinaSurfaceCard(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .clickable(onClick = onNavigateProjects)
                    .testTag("dashboard_project_${project.id}"),
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
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = project.repoFullName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    project.stackTags.take(3).forEach { tag ->
                        MonoChip(text = tag)
                    }
                    MonoChip(text = "${project.defaultBranch}@${project.lastCommitSha}")
                }
            }
        }

        // Section 2: Active Development Tasks (Room DB)
        item {
            SectionHeader(
                title = strings.sectionActiveTasks,
                icon = Icons.Outlined.TaskAlt,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                uiState.tasks.take(4).forEachIndexed { index, task ->
                    val isDone = task.status == TaskStatus.DONE
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { onToggleTask(task) },
                            modifier = Modifier.testTag("toggle_task_${task.id}"),
                        ) {
                            Icon(
                                imageVector = if (isDone) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = strings.taskDoneToggleDesc,
                                tint = if (isDone) AfrinaPalette.EmeraldHealthy else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) task.titleFa else task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                MonoChip(text = task.priority.name)
                                task.assignedAgent?.let { MonoChip(text = it.name) }
                            }
                        }
                    }
                    if (index < uiState.tasks.take(4).lastIndex) {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Section 3: AI Agent Activity (Simulated Phase 0)
        item {
            SectionHeader(
                title = strings.sectionAgentActivity,
                icon = Icons.Outlined.SmartToy,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
                actionLabel = strings.viewAll,
                onActionClick = onNavigateAgents,
            )
        }
        item {
            AfrinaSurfaceCard(
                modifier = Modifier.widthIn(max = 760.dp),
                highlightColor = AfrinaPalette.ElectricCyan,
            ) {
                uiState.activities.take(3).forEachIndexed { idx, activity ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MonoChip(text = activity.agentRole.name)
                            Text(
                                text = activity.timestampLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isFa) activity.summaryFa else activity.summaryEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (idx < uiState.activities.take(3).lastIndex) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        // Section 4: GitHub Connections & Build/Deploy Status
        item {
            SectionHeader(
                title = strings.sectionGitHubConnections,
                icon = Icons.Outlined.AccountTree,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                uiState.githubConnections.forEachIndexed { idx, repo ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = repo.repoFullName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (isFa) repo.syncStatusFa else repo.syncStatusEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MonoChip(text = "${repo.branch} • ${repo.commitSha}")
                    }
                    if (idx < uiState.githubConnections.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = strings.sectionBuildAndDeploy,
                icon = Icons.Outlined.BuildCircle,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
                actionLabel = strings.viewAll,
                onActionClick = onNavigatePipelines,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                val latestBuild = uiState.latestBuilds.firstOrNull()
                val latestDeploy = uiState.latestDeployments.firstOrNull()
                if (latestBuild != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CI: ${latestBuild.projectName}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (isFa) latestBuild.qualitySummaryFa else latestBuild.qualitySummaryEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(
                            label = pipelineLabel(latestBuild.status, strings),
                            color = pipelineColor(latestBuild.status),
                        )
                    }
                }
                if (latestDeploy != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CD: ${latestDeploy.projectName} (${latestDeploy.target.name})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = latestDeploy.endpointLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(
                            label = latestDeploy.versionTag,
                            color = pipelineColor(latestDeploy.status),
                            mono = true,
                        )
                    }
                }
            }
        }

        // Section 5: System Health
        item {
            SectionHeader(
                title = strings.sectionSystemHealth,
                icon = Icons.Outlined.CloudDone,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        item {
            val health = uiState.systemHealth
            if (health != null) {
                AfrinaSurfaceCard(
                    modifier = Modifier.widthIn(max = 760.dp),
                    highlightColor = AfrinaPalette.EmeraldHealthy,
                ) {
                    Text(
                        text = if (isFa) health.controlPlaneStatusFa else health.controlPlaneStatusEn,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = health.vpsTargetProfile,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${strings.cpuLabel}: ${health.cpuUsagePercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { health.cpuUsagePercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${strings.memoryLabel}: ${health.memoryUsagePercent}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { health.memoryUsagePercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun pipelineLabel(
    status: PipelineStatus,
    strings: com.example.afrina.core.i18n.AfrinaStrings,
): String =
    when (status) {
        PipelineStatus.PASSED -> strings.statusPassed
        PipelineStatus.RUNNING -> strings.statusRunning
        PipelineStatus.FAILED -> strings.statusFailed
        PipelineStatus.QUEUED -> strings.statusQueued
    }
