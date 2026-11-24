package com.example.afrina.core.di

import android.content.Context
import com.example.afrina.core.ai.LocalSimulatedAiGateway
import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.local.DataStorePreferencesRepository
import com.example.afrina.core.data.repository.LocalNotificationRepository
import com.example.afrina.core.data.repository.LocalProjectRepository
import com.example.afrina.core.data.repository.SeedDataInitializer
import com.example.afrina.core.data.repository.SimulatedAgentRepository
import com.example.afrina.core.data.repository.SimulatedPipelineRepository
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.AiProviderGateway
import com.example.afrina.core.domain.NotificationRepository
import com.example.afrina.core.domain.PipelineRepository
import com.example.afrina.core.domain.PreferencesRepository
import com.example.afrina.core.domain.ProjectRepository
import com.example.afrina.core.domain.VaultCryptoManager
import com.example.afrina.core.security.AndroidKeystoreVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight Constructor-Injection container for Phase 0.
 * Structured so migrating to Hilt in multi-module Phase 1 requires zero ViewModel signature changes.
 */
class AfrinaContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: AfrinaDatabase = AfrinaDatabase.build(context)

    val vaultCryptoManager: VaultCryptoManager = AndroidKeystoreVault(context)
    val aiProviderGateway: AiProviderGateway = LocalSimulatedAiGateway()

    val preferencesRepository: PreferencesRepository = DataStorePreferencesRepository(context)
    val projectRepository: ProjectRepository = LocalProjectRepository(database)
    val agentRepository: AgentRepository = SimulatedAgentRepository(database, aiProviderGateway)
    val pipelineRepository: PipelineRepository = SimulatedPipelineRepository(database, vaultCryptoManager)
    val notificationRepository: NotificationRepository = LocalNotificationRepository(database)

    init {
        appScope.launch {
            SeedDataInitializer.ensureSeeded(database)
        }
    }
}
