package com.example.afrina.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.example.afrina.core.model.AfrinaPersona
import com.example.afrina.core.model.AiRouteOutcome
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.model.DataClassification
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.ProviderTier
import com.example.afrina.core.model.TaskComplexity
import com.example.afrina.core.persona.AfrinaEdition
import com.example.afrina.core.persona.GenderPresentation
import com.example.afrina.core.persona.MemoryLayer
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeFirstRouterPanel(
    uiState: AgentsUiState,
    onSelectPersona: (AfrinaPersona) -> Unit,
    onSelectComplexity: (TaskComplexity) -> Unit,
    onSelectClassification: (DataClassification) -> Unit,
    onToggleVideoModality: (Boolean) -> Unit,
    onToggleLocalOpenWeight: (Boolean) -> Unit,
    onRunRouterTask: (Boolean) -> Unit,
    onExhaustPrimaryQuota: () -> Unit,
    onResetQuotas: () -> Unit,
    onApprovePaidInference: (String) -> Unit,
    onRejectPaidInference: (String) -> Unit,
    onExecuteApprovedPaidTask: (String) -> Unit,
    onToggleAffectiveFreeze: () -> Unit = {},
    onResetAffectiveState: () -> Unit = {},
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN
    val selection = uiState.selection

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(
            title = strings.routerSectionTitle,
            icon = Icons.Outlined.Route,
            origin = ImplementationOrigin.SIMULATED_PHASE_0,
        )

        // $0.00 Spending Protection + Controls Card
        AfrinaSurfaceCard(
            modifier = Modifier.testTag("router_controls_card"),
            highlightColor = AfrinaPalette.SageHealthy,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    label = strings.routerZeroBudgetBanner,
                    color = AfrinaPalette.SageHealthy,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.routerSimulatedNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = strings.routerPersonaLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AfrinaPersona.entries.forEach { persona ->
                    FilterChip(
                        selected = selection.selectedPersona == persona,
                        onClick = { onSelectPersona(persona) },
                        label = { Text(if (isFa) persona.titleFa else persona.titleEn) },
                        modifier = Modifier.testTag("persona_chip_${persona.name}"),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = strings.routerComplexityLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TaskComplexity.entries.forEach { complexity ->
                    FilterChip(
                        selected = selection.selectedComplexity == complexity,
                        onClick = { onSelectComplexity(complexity) },
                        label = { Text(complexity.name) },
                        modifier = Modifier.testTag("complexity_chip_${complexity.name}"),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = strings.routerClassificationLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DataClassification.entries.forEach { classification ->
                    FilterChip(
                        selected = selection.selectedClassification == classification,
                        onClick = { onSelectClassification(classification) },
                        label = { Text(classification.name) },
                        modifier = Modifier.testTag("classification_chip_${classification.name}"),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.routerVideoModalityLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = selection.requireVideoModality,
                    onCheckedChange = onToggleVideoModality,
                    modifier = Modifier.testTag("toggle_video_modality"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isFa) "اجازه مسیر سرور شخصی متن‌باز (VPS)" else "Allow Self-Hosted VPS Open-Weight",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = selection.allowLocalOpenWeight,
                    onCheckedChange = onToggleLocalOpenWeight,
                    modifier = Modifier.testTag("toggle_local_openweight"),
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onRunRouterTask(false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("run_router_task_button"),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Text(strings.routerRunTaskAction, modifier = Modifier.padding(start = 6.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onExhaustPrimaryQuota,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("exhaust_primary_quota_button"),
                ) {
                    Text(strings.routerExhaustQuotaAction, maxLines = 2)
                }
                OutlinedButton(
                    onClick = onResetQuotas,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reset_quotas_button"),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text(strings.routerResetQuotaAction, modifier = Modifier.padding(start = 4.dp), maxLines = 2)
                }
            }
        }

        // Phase 2 Four-Persona Foundation Summary Card
        val snapshot = uiState.personaSnapshot
        val selectedProfile = snapshot.profiles.firstOrNull { it.persona == selection.selectedPersona }
        val selectedContext = snapshot.contextsByPersona[selection.selectedPersona]
        val shortCount = snapshot.memories.count { it.layer == MemoryLayer.SHORT_TERM_WORKING }
        val medCount = snapshot.memories.count { it.layer == MemoryLayer.MEDIUM_TERM_EPISODIC_PROJECT }
        val longCount = snapshot.memories.count { it.layer == MemoryLayer.LONG_TERM_SEMANTIC_PREFERENCE }
        val affective = snapshot.affectiveState
        val affectivePolicy = snapshot.affectivePolicy
        val guardianTwin = snapshot.guardianTwin

        AfrinaSurfaceCard(
            modifier = Modifier.testTag("persona_foundation_card"),
            highlightColor = AfrinaPalette.TerracottaCopper,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = AfrinaPalette.TerracottaCopper)
                Text(
                    text = strings.personaFoundationTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            selectedProfile?.let { profile ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MonoChip(text = "${profile.canonicalName} • ${if (profile.genderPresentation == GenderPresentation.MALE) "MALE_TWIN" else "NEUTRAL"}")
                    MonoChip(text = "Context Turns: ${selectedContext?.turns?.size ?: 0}")
                    StatusPill(
                        label = if (snapshot.edition == AfrinaEdition.MASTER_OWNER) {
                            "MASTER_OWNER • Isolated+Approved Only"
                        } else {
                            "CUSTOMER_STANDARD • Core Locked"
                        },
                        color = AfrinaPalette.SageHealthy,
                        mono = true,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isFa) profile.roleFa else profile.roleEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings.personaMemoryTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonoChip(text = "Short: $shortCount")
                MonoChip(text = "Medium: $medCount")
                MonoChip(text = "Long: $longCount")
                MonoChip(text = "Delegations: ${snapshot.delegationHistory.size}")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings.personaAffectiveTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.personaAffectiveDisclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonoChip(text = "DA-like: ${String.format(Locale.US, "%.2f", affective.dopamineLike)}")
                MonoChip(text = "5HT-like: ${String.format(Locale.US, "%.2f", affective.serotoninLike)}")
                MonoChip(text = "END-like: ${String.format(Locale.US, "%.2f", affective.endorphinLike)}")
                MonoChip(text = "CORT-like: ${String.format(Locale.US, "%.2f", affective.cortisolLike)}")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleAffectiveFreeze,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_affective_freeze_button"),
                ) {
                    Text(
                        text = if (affectivePolicy.ownerLockFrozen) strings.personaUnfreezeLabel else strings.personaFreezeLabel,
                        maxLines = 2,
                    )
                }
                OutlinedButton(
                    onClick = onResetAffectiveState,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reset_affective_state_button"),
                ) {
                    Text(strings.personaResetAffectiveLabel, maxLines = 2)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${strings.personaTwinStatusTitle}: ${if (guardianTwin.isOwnerAuthorized) "Authorized" else "Locked (Owner Samples Required)"} • Voice=${guardianTwin.voiceCloneStatus.name} • Avatar=${guardianTwin.avatarRenderStatus.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Latest Routing Outcome Card
        uiState.latestOutcome?.let { outcome ->
            AfrinaSurfaceCard(
                modifier = Modifier.testTag("latest_router_outcome_card"),
                highlightColor = when (outcome) {
                    is AiRouteOutcome.Success -> AfrinaPalette.TerracottaCopper
                    is AiRouteOutcome.PaidApprovalRequired -> AfrinaPalette.OchreWarning
                    is AiRouteOutcome.PrivacyAuthorizationRequired -> AfrinaPalette.RustAlert
                    is AiRouteOutcome.PausedQuotaOrProviderUnavailable -> AfrinaPalette.OchreWarning
                },
            ) {
                Text(
                    text = strings.routerLatestOutcomeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                when (outcome) {
                    is AiRouteOutcome.Success -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusPill(
                                label = if (outcome.isSimulatedExecution) strings.routerStatusSimulated else strings.routerStatusReal,
                                color = if (outcome.isSimulatedExecution) AfrinaPalette.BronzeSimulated else AfrinaPalette.SageHealthy,
                                mono = true,
                            )
                            MonoChip(text = outcome.providerId)
                            MonoChip(text = outcome.modelId)
                            MonoChip(text = if (outcome.tier.isExternalCloud) "EXTERNAL_CLOUD" else "SELF_HOSTED_VPS")
                            if (outcome.fallbackTriggered) {
                                StatusPill(label = "FALLBACK", color = AfrinaPalette.OchreWarning, mono = true)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isFa) outcome.outputFa else outcome.outputEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (outcome.fallbackReason != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Fallback: ${outcome.fallbackReason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AfrinaPalette.OchreWarning,
                            )
                        }
                    }

                    is AiRouteOutcome.PaidApprovalRequired -> {
                        StatusPill(
                            label = "BLOCKED • $0.00 CAP",
                            color = AfrinaPalette.OchreWarning,
                            mono = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isFa) outcome.proposal.reasonFa else outcome.proposal.reasonEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    is AiRouteOutcome.PrivacyAuthorizationRequired -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = AfrinaPalette.RustAlert)
                            StatusPill(
                                label = "PRIVACY BLOCKED (${outcome.dataClassification.name})",
                                color = AfrinaPalette.RustAlert,
                                mono = true,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isFa) outcome.messageFa else outcome.messageEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onRunRouterTask(true) },
                            modifier = Modifier.testTag("authorize_sensitive_retry_button"),
                        ) {
                            Text(strings.routerAuthorizeSensitiveAction)
                        }
                    }

                    is AiRouteOutcome.PausedQuotaOrProviderUnavailable -> {
                        StatusPill(
                            label = "PAUSED • $0.00 SPENT",
                            color = AfrinaPalette.OchreWarning,
                            mono = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isFa) outcome.messageFa else outcome.messageEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Paid Inference Approvals (UI Approval != Execution)
        if (uiState.paidApprovals.isNotEmpty()) {
            AfrinaSurfaceCard(
                modifier = Modifier.testTag("paid_approvals_card"),
                highlightColor = AfrinaPalette.OchreWarning,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = AfrinaPalette.OchreWarning)
                    Text(
                        text = strings.routerPaidApprovalsTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                uiState.paidApprovals.take(5).forEach { proposal ->
                    val statusLabel = when {
                        proposal.isExecuted -> strings.routerApprovalExecutedStatus
                        proposal.status == ApprovalStatus.APPROVED -> strings.routerApprovalRecordedStatus
                        else -> proposal.status.name
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag("paid_proposal_${proposal.id}"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MonoChip(text = "${proposal.targetModelId} (~$${String.format(Locale.US, "%.4f", proposal.estimatedCostUsd)})")
                            StatusPill(
                                label = statusLabel,
                                color = when {
                                    proposal.isExecuted -> AfrinaPalette.SageHealthy
                                    proposal.status == ApprovalStatus.APPROVED -> AfrinaPalette.TerracottaCopper
                                    proposal.status == ApprovalStatus.REJECTED -> AfrinaPalette.RustAlert
                                    else -> AfrinaPalette.OchreWarning
                                },
                                mono = true,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isFa) proposal.reasonFa else proposal.reasonEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (proposal.status == ApprovalStatus.PENDING) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onRejectPaidInference(proposal.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("reject_paid_${proposal.id}"),
                                ) {
                                    Text(strings.rejectAction)
                                }
                                Button(
                                    onClick = { onApprovePaidInference(proposal.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AfrinaPalette.SageHealthy,
                                        contentColor = AfrinaPalette.IvoryInkDark,
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("approve_paid_${proposal.id}"),
                                ) {
                                    Text(strings.approveAction)
                                }
                            }
                        } else if (proposal.status == ApprovalStatus.APPROVED && !proposal.isExecuted) {
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { onExecuteApprovedPaidTask(proposal.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("execute_paid_${proposal.id}"),
                            ) {
                                Text(strings.routerExecuteApprovedAction)
                            }
                        }
                    }
                }
            }
        }

        // Provider Availability & Simulated Quotas
        AfrinaSurfaceCard(modifier = Modifier.testTag("provider_quotas_card")) {
            Text(
                text = strings.routerProviderQuotasTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            uiState.providerStatuses.forEach { status ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .testTag("provider_status_${status.providerId}"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = status.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            label = if (status.isAvailable) strings.routerAvailableLabel else strings.routerUnavailableLabel,
                            color = if (status.isAvailable) AfrinaPalette.SageHealthy else AfrinaPalette.RustAlert,
                            mono = true,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MonoChip(text = status.tier.name)
                        if (status.tier != ProviderTier.PAID_CLOUD) {
                            MonoChip(text = "Sim Quota: ${status.remainingFreeRequests}/${status.dailyFreeLimit}")
                        }
                        MonoChip(text = if (status.retentionPolicy.trainsOnInput) "May Train On Input" else "No-Train Mode")
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isFa) status.noteFa else status.noteEn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Fallback Events Card
        if (uiState.fallbackEvents.isNotEmpty()) {
            AfrinaSurfaceCard(
                modifier = Modifier.testTag("fallback_events_card"),
                highlightColor = AfrinaPalette.OchreWarning,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = AfrinaPalette.OchreWarning)
                    Text(
                        text = strings.routerFallbackEventsTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                uiState.fallbackEvents.take(5).forEach { event ->
                    Text(
                        text = "[${if (event.isSimulated) strings.routerStatusSimulated else strings.routerStatusReal}] ${event.fromProviderId} -> ${event.toProviderId} (${event.reason})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Usage & Cost Transparency Ledger (Room v2)
        AfrinaSurfaceCard(modifier = Modifier.testTag("usage_ledger_card")) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = strings.routerUsageLedgerTitle,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            val summary = uiState.costSummary
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonoChip(text = "Free: ${summary.totalFreeRequests}")
                MonoChip(text = "Self-Hosted: ${summary.totalSelfHostedRequests}")
                MonoChip(text = "Paid: ${summary.totalPaidRequests}")
                MonoChip(text = "Paid API: $${String.format(Locale.US, "%.4f", summary.totalPaidApiSpendUsd)}")
                MonoChip(text = "Auto Cap: $${String.format(Locale.US, "%.2f", summary.ownerAutoBudgetUsd)}")
            }
            Spacer(Modifier.height(8.dp))
            if (uiState.usageLedger.isEmpty()) {
                Text(
                    text = strings.routerEmptyLedger,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.usageLedger.take(8).forEach { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${entry.persona.name} • ${entry.modelId}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            StatusPill(
                                label = if (entry.isSimulated) strings.routerStatusSimulated else strings.routerStatusReal,
                                color = if (entry.isSimulated) AfrinaPalette.BronzeSimulated else AfrinaPalette.SageHealthy,
                                mono = true,
                            )
                        }
                        Text(
                            text = "${entry.usageCategory.name} • ${entry.routingKind} • ${entry.inputTokens + entry.outputTokens} tok • $${String.format(Locale.US, "%.4f", entry.estimatedApiCostUsd)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
