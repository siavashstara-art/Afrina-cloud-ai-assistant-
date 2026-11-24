package com.example.afrina.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.afrina.core.designsystem.AfrinaPalette
import com.example.afrina.core.designsystem.AfrinaSurfaceCard
import com.example.afrina.core.designsystem.StatusPill
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.CloudNotification
import com.example.afrina.core.model.NotificationSeverity
import com.example.afrina.feature.agents.AgentsScreen
import com.example.afrina.feature.agents.AgentsViewModel
import com.example.afrina.feature.dashboard.DashboardScreen
import com.example.afrina.feature.dashboard.DashboardViewModel
import com.example.afrina.feature.pipelines.PipelinesScreen
import com.example.afrina.feature.pipelines.PipelinesViewModel
import com.example.afrina.feature.projects.ProjectsScreen
import com.example.afrina.feature.projects.ProjectsViewModel
import com.example.afrina.feature.settings.SettingsScreen
import com.example.afrina.feature.settings.SettingsViewModel

enum class AfrinaDestination(val route: String, val icon: ImageVector) {
    Dashboard("dashboard", Icons.Outlined.Dashboard),
    Projects("projects", Icons.Outlined.FolderCopy),
    Agents("agents", Icons.Outlined.SmartToy),
    Pipelines("pipelines", Icons.Outlined.BuildCircle),
    Settings("settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfrinaRootShell(
    dashboardViewModel: DashboardViewModel,
    projectsViewModel: ProjectsViewModel,
    agentsViewModel: AgentsViewModel,
    pipelinesViewModel: PipelinesViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val strings = LocalAfrinaStrings.current
    val isFa = LocalAppLanguage.current == AppLanguage.PERSIAN
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AfrinaDestination.Dashboard.route

    val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
    val projectsState by projectsViewModel.uiState.collectAsStateWithLifecycle()
    val agentsState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val pipelinesState by pipelinesViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    var showNotifications by remember { mutableStateOf(false) }

    val navigateTo: (AfrinaDestination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun labelFor(dest: AfrinaDestination): String = when (dest) {
        AfrinaDestination.Dashboard -> strings.navDashboard
        AfrinaDestination.Projects -> strings.navProjects
        AfrinaDestination.Agents -> strings.navAgents
        AfrinaDestination.Pipelines -> strings.navPipelines
        AfrinaDestination.Settings -> strings.navSettings
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = {
                        Column {
                            Text(
                                text = strings.appTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = strings.appSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showNotifications = true },
                            modifier = Modifier.testTag("notifications_button"),
                        ) {
                            BadgedBox(
                                badge = {
                                    if (dashboardState.unreadNotificationsCount > 0) {
                                        Badge {
                                            Text(dashboardState.unreadNotificationsCount.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = strings.notificationsTitle,
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AfrinaDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest) },
                                icon = { Icon(dest.icon, contentDescription = labelFor(dest)) },
                                label = {
                                    Text(
                                        text = labelFor(dest),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.testTag("nav_${dest.route}"),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Spacer(Modifier.height(12.dp))
                        AfrinaDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest) },
                                icon = { Icon(dest.icon, contentDescription = labelFor(dest)) },
                                label = { Text(labelFor(dest)) },
                                modifier = Modifier.testTag("rail_${dest.route}"),
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = AfrinaDestination.Dashboard.route,
                    ) {
                        composable(AfrinaDestination.Dashboard.route) {
                            DashboardScreen(
                                uiState = dashboardState,
                                onToggleTask = dashboardViewModel::toggleTaskCompletion,
                                onNavigateProjects = { navigateTo(AfrinaDestination.Projects) },
                                onNavigateAgents = { navigateTo(AfrinaDestination.Agents) },
                                onNavigatePipelines = { navigateTo(AfrinaDestination.Pipelines) },
                            )
                        }
                        composable(AfrinaDestination.Projects.route) {
                            ProjectsScreen(
                                uiState = projectsState,
                                onSearchChange = projectsViewModel::onSearchQueryChanged,
                                onCreateProject = projectsViewModel::createProject,
                                onCreateTask = projectsViewModel::createTask,
                                onToggleTask = projectsViewModel::toggleTask,
                            )
                        }
                        composable(AfrinaDestination.Agents.route) {
                            AgentsScreen(
                                uiState = agentsState,
                                onResolveApproval = agentsViewModel::resolveApproval,
                                onSimulateAgent = agentsViewModel::triggerSimulatedRun,
                            )
                        }
                        composable(AfrinaDestination.Pipelines.route) {
                            PipelinesScreen(
                                uiState = pipelinesState,
                                onSimulateBuild = pipelinesViewModel::simulateBuild,
                                onSimulateDeploy = pipelinesViewModel::simulateDryRunDeploy,
                            )
                        }
                        composable(AfrinaDestination.Settings.route) {
                            SettingsScreen(
                                uiState = settingsState,
                                onLanguageSelected = settingsViewModel::setLanguage,
                                onThemeSelected = settingsViewModel::setTheme,
                                onSaveCredential = settingsViewModel::saveCredential,
                                onClearCredential = settingsViewModel::clearCredential,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNotifications) {
        NotificationsSheet(
            notifications = dashboardState.notifications,
            isFa = isFa,
            onMarkAllRead = dashboardViewModel::markNotificationsRead,
            onDismiss = { showNotifications = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsSheet(
    notifications: List<CloudNotification>,
    isFa: Boolean,
    onMarkAllRead: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAfrinaStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.notificationsTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onMarkAllRead) {
                    Text(strings.markAllRead)
                }
            }
            if (notifications.isEmpty()) {
                Text(
                    text = strings.noNotifications,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(notifications, key = { it.id }) { item ->
                        val sevColor = when (item.severity) {
                            NotificationSeverity.SUCCESS -> AfrinaPalette.EmeraldHealthy
                            NotificationSeverity.WARNING -> AfrinaPalette.AmberWarning
                            NotificationSeverity.CRITICAL -> AfrinaPalette.RoseAlert
                            NotificationSeverity.INFO -> AfrinaPalette.ElectricCyan
                        }
                        AfrinaSurfaceCard(highlightColor = sevColor) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (isFa) item.titleFa else item.titleEn,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusPill(label = item.timestampLabel, color = sevColor, mono = true)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isFa) item.bodyFa else item.bodyEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
