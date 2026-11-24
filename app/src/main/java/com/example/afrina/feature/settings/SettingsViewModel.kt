package com.example.afrina.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.afrina.core.domain.NotificationRepository
import com.example.afrina.core.domain.PreferencesRepository
import com.example.afrina.core.domain.VaultCryptoManager
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.AuditLogEntry
import com.example.afrina.core.model.ThemePreference
import com.example.afrina.core.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val hardwareVaultReady: Boolean = false,
    val maskedStoredToken: String? = null,
    val auditLogs: List<AuditLogEntry> = emptyList(),
)

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val vaultCryptoManager: VaultCryptoManager,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    private val maskedTokenState = MutableStateFlow(
        vaultCryptoManager.readDecryptedCredentialMasked(CREDENTIAL_ALIAS)
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.observePreferences(),
        maskedTokenState,
        notificationRepository.observeAuditLogs(),
    ) { prefs, masked, logs ->
        SettingsUiState(
            preferences = prefs,
            hardwareVaultReady = vaultCryptoManager.isHardwareVaultAvailable(),
            maskedStoredToken = masked,
            auditLogs = logs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            preferencesRepository.setLanguage(language)
            notificationRepository.recordAudit(
                actor = "Founder",
                action = "SWITCH_LANGUAGE",
                target = language.code,
                outcome = "PERSISTED_DATASTORE",
            )
        }
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch {
            preferencesRepository.setTheme(theme)
        }
    }

    fun saveCredential(rawSecret: String) {
        if (rawSecret.isBlank()) return
        viewModelScope.launch {
            val result = vaultCryptoManager.storeEncryptedCredential(CREDENTIAL_ALIAS, rawSecret.trim())
            if (result.isSuccess) {
                maskedTokenState.value = vaultCryptoManager.readDecryptedCredentialMasked(CREDENTIAL_ALIAS)
                notificationRepository.recordAudit(
                    actor = "Founder",
                    action = "STORE_ENCRYPTED_CREDENTIAL",
                    target = CREDENTIAL_ALIAS,
                    outcome = "KEYSTORE_AES256_GCM",
                )
            }
        }
    }

    fun clearCredential() {
        viewModelScope.launch {
            vaultCryptoManager.deleteCredential(CREDENTIAL_ALIAS)
            maskedTokenState.value = null
            notificationRepository.recordAudit(
                actor = "Founder",
                action = "DELETE_CREDENTIAL",
                target = CREDENTIAL_ALIAS,
                outcome = "PURGED",
            )
        }
    }

    private companion object {
        const val CREDENTIAL_ALIAS = "vps_control_plane_token"
    }
}
