package com.example.afrina.core.di

import android.content.Context
import com.example.afrina.core.ai.CentralAiProviderRouter
import com.example.afrina.core.ai.FreeTierFallbackProxyAdapter
import com.example.afrina.core.ai.FreeTierPrimaryProxyAdapter
import com.example.afrina.core.ai.PaidFrontierCloudAdapter
import com.example.afrina.core.ai.PersonaDispatcher
import com.example.afrina.core.ai.SelfHostedOpenWeightAdapter
import com.example.afrina.core.data.local.AfrinaDatabase
import com.example.afrina.core.data.local.DataStorePreferencesRepository
import com.example.afrina.core.data.repository.AiRouterRepositoryImpl
import com.example.afrina.core.data.repository.LocalNotificationRepository
import com.example.afrina.core.data.repository.LocalProjectRepository
import com.example.afrina.core.data.repository.SeedDataInitializer
import com.example.afrina.core.data.repository.SimulatedAgentRepository
import com.example.afrina.core.data.repository.SimulatedPipelineRepository
import com.example.afrina.core.data.repository.toPaidApprovalGrant
import com.example.afrina.core.domain.AgentRepository
import com.example.afrina.core.domain.AiRouterRepository
import com.example.afrina.core.domain.NotificationRepository
import com.example.afrina.core.domain.PersonaArchitectureRepository
import com.example.afrina.core.domain.PersonaIdentityCustomizationRepository
import com.example.afrina.core.domain.PipelineRepository
import com.example.afrina.core.domain.PreferencesRepository
import com.example.afrina.core.domain.ProjectRepository
import com.example.afrina.core.domain.VaultCryptoManager
import com.example.afrina.core.domain.VoiceConversationRepository
import com.example.afrina.core.model.ApprovalStatus
import com.example.afrina.core.persona.AfrinaEdition
import com.example.afrina.core.persona.CentralPersonaOrchestrator
import com.example.afrina.core.persona.PersonaIdentityEngine
import com.example.afrina.core.persona.SharedMemoryManager
import com.example.afrina.core.security.AndroidKeystoreVault
import com.example.afrina.core.voice.RealTimeVoiceConversationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight Constructor-Injection container for Phase 0 / Phase 1 / Phase 2 foundation.
 * Structured so migrating to Hilt in multi-module phases requires zero ViewModel signature changes.
 */
class AfrinaContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: AfrinaDatabase = AfrinaDatabase.build(context)

    val vaultCryptoManager: VaultCryptoManager = AndroidKeystoreVault(context)

    private val centralAiProviderRouter = CentralAiProviderRouter(
        adapters = listOf(
            FreeTierPrimaryProxyAdapter(),
            FreeTierFallbackProxyAdapter(),
            SelfHostedOpenWeightAdapter(),
            PaidFrontierCloudAdapter(),
        ),
        approvalGrantResolver = { proposalId ->
            database.paidInferenceApprovalDao().getById(proposalId)
                ?.takeIf { it.status == ApprovalStatus.EXECUTING.name && !it.isExecuted }
                ?.toPaidApprovalGrant()
        },
    )
    private val personaDispatcher = PersonaDispatcher(centralAiProviderRouter)

    val preferencesRepository: PreferencesRepository = DataStorePreferencesRepository(context)
    val projectRepository: ProjectRepository = LocalProjectRepository(database)
    val aiRouterRepository: AiRouterRepository = AiRouterRepositoryImpl(
        db = database,
        router = centralAiProviderRouter,
        dispatcher = personaDispatcher,
    )
    private val masterMemoryManager = SharedMemoryManager()

    val personaArchitectureRepository: PersonaArchitectureRepository = CentralPersonaOrchestrator(
        edition = AfrinaEdition.MASTER_OWNER,
        aiRouterRepository = aiRouterRepository,
        auditLogDao = database.auditLogDao(),
        memoryManager = masterMemoryManager,
    )
    val personaIdentityRepository: PersonaIdentityCustomizationRepository = PersonaIdentityEngine(
        masterMemoryManager = masterMemoryManager,
        auditLogDao = database.auditLogDao(),
        database = database,
    )
    val voiceConversationRepository: VoiceConversationRepository = RealTimeVoiceConversationEngine(
        personaArchitectureRepository = personaArchitectureRepository,
        identityRepository = personaIdentityRepository,
        auditLogDao = database.auditLogDao(),
    )
    val agentRepository: AgentRepository = SimulatedAgentRepository(database, aiRouterRepository)
    val pipelineRepository: PipelineRepository = SimulatedPipelineRepository(database, vaultCryptoManager)
    val notificationRepository: NotificationRepository = LocalNotificationRepository(database)

    init {
        appScope.launch {
            SeedDataInitializer.ensureSeeded(database)
        }
    }
}
