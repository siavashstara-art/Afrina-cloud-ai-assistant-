package com.example.afrina.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.afrina.core.designsystem.AfrinaPalette
import com.example.afrina.core.designsystem.AfrinaSurfaceCard
import com.example.afrina.core.designsystem.MonoChip
import com.example.afrina.core.designsystem.OriginBadge
import com.example.afrina.core.designsystem.SectionHeader
import com.example.afrina.core.designsystem.StatusPill
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.ThemePreference

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeSelected: (ThemePreference) -> Unit,
    onSaveCredential: (String) -> Unit,
    onClearCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN
    var secretInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Language & RTL/LTR Toggle
        item {
            SectionHeader(
                title = strings.languageSectionTitle,
                icon = Icons.Outlined.Language,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = uiState.preferences.language == AppLanguage.PERSIAN,
                        onClick = { onLanguageSelected(AppLanguage.PERSIAN) },
                        label = { Text(strings.persianLabel) },
                        modifier = Modifier.testTag("lang_fa_chip"),
                    )
                    FilterChip(
                        selected = uiState.preferences.language == AppLanguage.ENGLISH,
                        onClick = { onLanguageSelected(AppLanguage.ENGLISH) },
                        label = { Text(strings.englishLabel) },
                        modifier = Modifier.testTag("lang_en_chip"),
                    )
                }
            }
        }

        // Theme Preference
        item {
            SectionHeader(
                title = strings.themeSectionTitle,
                icon = Icons.Outlined.Palette,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.preferences.theme == ThemePreference.DARK,
                        onClick = { onThemeSelected(ThemePreference.DARK) },
                        label = { Text(strings.themeDark) },
                    )
                    FilterChip(
                        selected = uiState.preferences.theme == ThemePreference.LIGHT,
                        onClick = { onThemeSelected(ThemePreference.LIGHT) },
                        label = { Text(strings.themeLight) },
                    )
                    FilterChip(
                        selected = uiState.preferences.theme == ThemePreference.SYSTEM,
                        onClick = { onThemeSelected(ThemePreference.SYSTEM) },
                        label = { Text(strings.themeSystem) },
                    )
                }
            }
        }

        // Keystore Encrypted Vault
        item {
            SectionHeader(
                title = strings.vaultSectionTitle,
                icon = Icons.Outlined.Key,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
            )
        }
        item {
            AfrinaSurfaceCard(
                modifier = Modifier.widthIn(max = 760.dp),
                highlightColor = if (uiState.hardwareVaultReady) AfrinaPalette.SageHealthy else AfrinaPalette.OchreWarning,
            ) {
                Text(
                    text = strings.vaultReadyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                uiState.maskedStoredToken?.let { masked ->
                    StatusPill(
                        label = "AES-GCM Stored: $masked",
                        color = AfrinaPalette.SageHealthy,
                        mono = true,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text(strings.vaultInputHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_secret_input"),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            onSaveCredential(secretInput)
                            secretInput = ""
                        },
                        enabled = secretInput.isNotBlank(),
                        modifier = Modifier.testTag("vault_save_btn"),
                    ) {
                        Text(strings.saveEncrypted)
                    }
                    if (uiState.maskedStoredToken != null) {
                        OutlinedButton(onClick = onClearCredential) {
                            Text(strings.clearCredential)
                        }
                    }
                }
            }
        }

        // Phase 0 Truth Matrix
        item {
            SectionHeader(
                title = strings.capabilityMatrixTitle,
                icon = Icons.AutoMirrored.Outlined.FactCheck,
            )
        }
        item {
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                val matrix = if (isFa) {
                    listOf(
                        Triple("ذخیره‌سازی پروژه، تسک و ممیزی (Room)", ImplementationOrigin.LOCAL_PERSISTED, "فعال"),
                        Triple("تنظیمات RTL فارسی و تم (DataStore)", ImplementationOrigin.LOCAL_PERSISTED, "فعال"),
                        Triple("گاوصندوق رمزنگاری Keystore AES-GCM", ImplementationOrigin.LOCAL_PERSISTED, "فعال"),
                        Triple("درگاه عامل‌ها و گیت‌هاب و CI/CD", ImplementationOrigin.SIMULATED_PHASE_0, "شبیه‌سازی"),
                        Triple("کنترل‌پلن VPS، سندباکس کانتینری، GitHub OAuth", ImplementationOrigin.PLANNED_REMOTE, "فاز ۱-۴"),
                    )
                } else {
                    listOf(
                        Triple("Projects, Tasks & Audit DB (Room)", ImplementationOrigin.LOCAL_PERSISTED, "Active"),
                        Triple("Persian RTL & Theme Preferences (DataStore)", ImplementationOrigin.LOCAL_PERSISTED, "Active"),
                        Triple("Android Keystore AES-GCM Vault", ImplementationOrigin.LOCAL_PERSISTED, "Active"),
                        Triple("AI Gateway, GitHub & CI/CD Bridges", ImplementationOrigin.SIMULATED_PHASE_0, "Mocked"),
                        Triple("Linux VPS Control Plane, Docker Sandbox, Real GitHub", ImplementationOrigin.PLANNED_REMOTE, "Phase 1-4"),
                    )
                }
                matrix.forEachIndexed { idx, (label, origin, _) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        OriginBadge(origin = origin)
                    }
                    if (idx < matrix.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Security Audit Log
        item {
            SectionHeader(
                title = strings.auditTrailTitle,
                icon = Icons.Outlined.History,
                origin = ImplementationOrigin.LOCAL_PERSISTED,
            )
        }
        items(uiState.auditLogs.take(10), key = { it.id }) { log ->
            AfrinaSurfaceCard(modifier = Modifier.widthIn(max = 760.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoChip(text = log.action)
                    MonoChip(text = log.outcome)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${log.actor} -> ${log.target}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
