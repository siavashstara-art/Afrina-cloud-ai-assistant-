package com.example.afrina.feature.pipelines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.afrina.core.designsystem.AfrinaPalette
import com.example.afrina.core.designsystem.AfrinaSurfaceCard
import com.example.afrina.core.designsystem.MonoChip
import com.example.afrina.core.designsystem.SectionHeader
import com.example.afrina.core.designsystem.StatusPill
import com.example.afrina.core.designsystem.pipelineColor
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.PipelineStatus

@Composable
fun PipelinesScreen(
    uiState: PipelinesUiState,
    onSimulateBuild: () -> Unit,
    onSimulateDeploy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("pipelines_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onSimulateBuild,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_build_btn"),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text(strings.simulateBuild, modifier = Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(
                    onClick = onSimulateDeploy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_deploy_btn"),
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    Text(strings.dryRunDeploy, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        item {
            SectionHeader(
                title = strings.buildHistoryTitle,
                icon = Icons.Outlined.BuildCircle,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        items(uiState.builds, key = { it.id }) { run ->
            val color = pipelineColor(run.status)
            AfrinaSurfaceCard(
                modifier = Modifier.widthIn(max = 760.dp),
                highlightColor = color,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = run.projectName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        MonoChip(text = "${run.branch} • ${run.commitSha} • ${run.durationSeconds}s")
                    }
                    StatusPill(
                        label = when (run.status) {
                            PipelineStatus.PASSED -> strings.statusPassed
                            PipelineStatus.RUNNING -> strings.statusRunning
                            PipelineStatus.FAILED -> strings.statusFailed
                            PipelineStatus.QUEUED -> strings.statusQueued
                        },
                        color = color,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isFa) run.qualitySummaryFa else run.qualitySummaryEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionHeader(
                title = strings.deploymentHistoryTitle,
                icon = Icons.Outlined.CloudUpload,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        items(uiState.deployments, key = { it.id }) { dep ->
            val color = pipelineColor(dep.status)
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp), highlightColor = color) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${dep.projectName} • ${dep.target.name}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = dep.endpointLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(label = dep.versionTag, color = color, mono = true)
                }
                if (dep.rollbackAvailable) {
                    Spacer(Modifier.height(8.dp))
                    StatusPill(label = strings.rollbackReady, color = AfrinaPalette.TerracottaCopper)
                }
            }
        }

        item {
            SectionHeader(
                title = strings.qualityGatesTitle,
                icon = Icons.Outlined.VerifiedUser,
                origin = ImplementationOrigin.PLANNED_REMOTE,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                val gates = if (isFa) {
                    listOf(
                        "تحلیل ایستا (Detekt / Ktlint) — پیکربندی فاز ۱",
                        "اسکن آسیب‌پذیری وابستگی‌ها و کلیدها (SBOM / SAST)",
                        "تاییدیه سلامت کانتینر پیش از سوئیچ ترافیک",
                    )
                } else {
                    listOf(
                        "Static Analysis (Detekt / Ktlint) — Phase 1 Runner",
                        "SBOM & Secret Entropy Scan — Phase 3 Security Gate",
                        "Container Healthcheck & Rollback Guard — Phase 4 CD",
                    )
                }
                gates.forEachIndexed { idx, gate ->
                    Text(
                        text = "• $gate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (idx < gates.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }

        item {
            SectionHeader(
                title = strings.sectionGitHubConnections,
                icon = Icons.Outlined.AccountTree,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        items(uiState.githubConnections, key = { it.id }) { repo ->
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = repo.repoFullName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    MonoChip(text = "${repo.branch}@${repo.commitSha}")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isFa) repo.syncStatusFa else repo.syncStatusEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
