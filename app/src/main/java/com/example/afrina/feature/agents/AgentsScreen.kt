package com.example.afrina.feature.agents

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
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.model.AgentExecutionState
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.ImplementationOrigin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentsScreen(
    uiState: AgentsUiState,
    onResolveApproval: (String, Boolean) -> Unit,
    onSimulateAgent: (AgentRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN
    val pending = uiState.approvals.filter { it.status == ApprovalStatus.PENDING }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("agents_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Human Approval Gate Section
        item {
            SectionHeader(
                title = strings.pendingHumanApproval,
                icon = Icons.Outlined.Security,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
            )
        }
        if (pending.isEmpty()) {
            item {
                AfrinaSurfaceCard(
                    modifier = Modifier.widthIn(max = 760.dp),
                    highlightColor = AfrinaPalette.EmeraldHealthy,
                ) {
                    Text(
                        text = strings.emptyApprovals,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(pending, key = { it.id }) { approval ->
                AfrinaSurfaceCard(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .testTag("approval_card_${approval.id}"),
                    highlightColor = AfrinaPalette.AmberWarning,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoChip(text = approval.agentRole.name)
                        StatusPill(label = approval.riskLevel.name, color = AfrinaPalette.AmberWarning, mono = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isFa) approval.actionTitleFa else approval.actionTitleEn,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    MonoChip(text = approval.commandPreview)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.approvalGateHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onResolveApproval(approval.id, false) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reject_${approval.id}"),
                        ) {
                            Text(strings.rejectAction)
                        }
                        Button(
                            onClick = { onResolveApproval(approval.id, true) },
                            colors = ButtonDefaults.buttonColors(containerColor = AfrinaPalette.EmeraldHealthy),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("approve_${approval.id}"),
                        ) {
                            Text(strings.approveAction)
                        }
                    }
                }
            }
        }

        // Provider-Independent AI Gateway Card
        item {
            SectionHeader(
                title = strings.aiGatewayTitle,
                icon = Icons.Outlined.Hub,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                MonoChip(text = "Active Adapter: ${uiState.gatewayProviderId}")
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.supportedModels.forEach { MonoChip(it) }
                }
            }
        }

        // 7-Agent Architecture Catalog
        item {
            SectionHeader(
                title = strings.agentCatalogTitle,
                icon = Icons.Outlined.SmartToy,
                origin = ImplementationOrigin.SIMULATED_PHASE_0,
            )
        }
        items(uiState.agents, key = { it.role.name }) { agent ->
            val stateColor = when (agent.currentState) {
                AgentExecutionState.RUNNING -> AfrinaPalette.ElectricCyan
                AgentExecutionState.AWAITING_APPROVAL -> AfrinaPalette.AmberWarning
                AgentExecutionState.COMPLETED -> AfrinaPalette.EmeraldHealthy
                AgentExecutionState.IDLE -> AfrinaPalette.TextSecondaryDark
            }
            AfrinaSurfaceCard(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .testTag("agent_card_${agent.role.name}"),
                highlightColor = stateColor,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) agent.titleFa else agent.titleEn,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = agent.preferredModelId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    StatusPill(label = agent.currentState.name, color = stateColor, mono = true)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isFa) agent.purposeFa else agent.purposeEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = strings.allowedToolsLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    agent.allowedTools.forEach { MonoChip(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.gatedActionsLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = AfrinaPalette.AmberWarning,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    agent.requiresHumanGateFor.forEach { MonoChip(it) }
                }
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = { onSimulateAgent(agent.role) },
                    modifier = Modifier.testTag("simulate_agent_${agent.role.name}"),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text(strings.runSimulatedAgent, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
