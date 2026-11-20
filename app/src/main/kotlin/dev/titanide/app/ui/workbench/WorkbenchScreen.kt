package dev.titanide.app.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.Severity
import dev.titanide.core.workbench.Activity
import dev.titanide.core.workbench.BottomTool
import dev.titanide.core.workbench.WorkbenchState

/**
 * The workbench: activity bar, sidebar, editor, bottom tool window, agent panel and status
 * bar, arranged by the layout policy rather than by ad-hoc size checks.
 *
 * The same composables serve all three layouts. Phones get a drawer sidebar and a
 * full-height agent sheet; tablets get a persistent three-column layout.
 */
@Composable
fun WorkbenchScreen(
    viewModel: WorkbenchViewModel,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    modifier: Modifier = Modifier,
    onExitWorkbench: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val buffers by viewModel.buffers.collectAsStateWithLifecycle()
    val project by viewModel.project.collectAsStateWithLifecycle()
    val androidTree by viewModel.androidTree.collectAsStateWithLifecycle()
    val projectTree by viewModel.projectTree.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val searchHits by viewModel.searchHits.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val build by viewModel.build.collectAsStateWithLifecycle()
    val logs by viewModel.buildLogs.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthDp = with(density) { configuration.screenWidthDp }
    val heightDp = with(density) { configuration.screenHeightDp }

    LaunchedEffect(widthDp, heightDp) { viewModel.onSizeChanged(widthDp, heightDp) }

    val currentBuildLogs = remember(logs, build) { build?.logLines ?: logs.values.lastOrNull() ?: emptyList() }
    val modifiedPaths = remember(buffers) { buffers.filterValues { it.dirty }.keys }

    var showNewFileDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TitanColors.Canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            WorkbenchTitleBar(
                projectName = state.projectName,
                onExit = onExitWorkbench,
                onOpenProjects = onOpenProjects,
                onToggleAgent = viewModel::toggleAgentPanel,
                agentConnected = agent.isConnected,
            )
            HorizontalDivider()
            Row(Modifier.weight(1f)) {
                ActivityBar(
                    selected = state.activity,
                    expanded = state.policy.activityBarExpanded,
                    badges = ActivityBadges(
                        problems = diagnostics.count { it.severity == Severity.ERROR },
                        sourceControl = modifiedPaths.size,
                        agentRunning = agent.running,
                    ),
                    onSelect = viewModel::selectActivity,
                    onOpenSettings = onOpenSettings,
                )
                VerticalDivider()

                // Persistent sidebar on tablet/landscape.
                if (state.policy.showSidebar && state.sidebarVisible) {
                    SidebarHost(
                        state = state,
                        androidTree = androidTree,
                        projectTree = projectTree,
                        expanded = expanded,
                        searchHits = searchHits,
                        activePath = state.activeTab?.path,
                        modifiedPaths = modifiedPaths,
                        agent = agent,
                        onToggleExpanded = viewModel::toggleExpanded,
                        onOpenFile = viewModel::openFile,
                        onOpenHit = { hit -> viewModel.openFile(hit.path) },
                        onSearch = viewModel::search,
                        onNewFile = { showNewFileDialog = true },
                        onRefresh = viewModel::refreshFiles,
                        onDelete = { node -> viewModel.deleteFile(node.path) },
                        onToggleAndroidView = viewModel::toggleAndroidView,
                        onOpenProject = onOpenProjects,
                        modifier = Modifier
                            .width(state.sidebarWidthDp.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                }

                // Editor area, optionally split.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f)) {
                        state.groups.forEachIndexed { index, group ->
                            if (index > 0) VerticalDivider()
                            val active = index == state.activeGroupIndex
                            EditorArea(
                                group = group,
                                buffer = group.activeTab?.let { buffers[it.path] },
                                diagnostics = diagnostics,
                                rootName = project?.name,
                                onSelectTab = viewModel::activateTab,
                                onCloseTab = viewModel::closeTab,
                                onChange = { text, caret ->
                                    group.activeTab?.path?.let { path ->
                                        viewModel.edit(path, text, caret)
                                    }
                                },
                                onSave = viewModel::saveActive,
                                onToggleComment = viewModel::toggleComment,
                                onFormat = viewModel::formatDocument,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                showGutter = active || state.groups.size == 1,
                            )
                        }
                    }
                    if (state.policy.showBottomPanel && state.bottomPanelExpanded && state.bottomTool != null) {
                        HorizontalDivider()
                        BottomPanel(
                            activeTool = state.bottomTool ?: BottomTool.BUILD,
                            onSelectTool = viewModel::selectBottomTool,
                            onCollapse = viewModel::toggleBottomPanel,
                            diagnostics = diagnostics,
                            buildResult = build,
                            buildLogs = currentBuildLogs,
                            buildRunning = build.isRunningBuild(),
                            onOpenDiagnostic = { diagnostic ->
                                viewModel.openFile(diagnostic.filePath)
                            },
                            onRunBuild = { viewModel.runBuild(BuildKind.BUILD) },
                            onStopBuild = viewModel::stopBuild,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(state.bottomPanelHeightDp.dp),
                        )
                    }
                }

                // Persistent agent panel on tablet.
                if (state.policy.showAgentPanel && state.agentPanelVisible) {
                    VerticalDivider()
                    AgentPanel(
                        state = agent,
                        onSend = viewModel::sendAgentPrompt,
                        onStop = viewModel::stopAgent,
                        onAnswerPermission = viewModel::answerPermission,
                        onReconnect = viewModel::reconnectAgent,
                        onClose = viewModel::toggleAgentPanel,
                        modifier = Modifier.width(state.policy.agentPanelWidthDp.dp).fillMaxHeight(),
                    )
                }
            }

            HorizontalDivider()
            StatusBar(
                branch = state.status.gitBranch ?: project?.gitBranch,
                dirtyCount = modifiedPaths.size,
                errors = diagnostics.count { it.severity == Severity.ERROR },
                warnings = diagnostics.count { it.severity == Severity.WARNING },
                line = state.status.cursorLine,
                column = state.status.cursorColumn,
                language = state.status.languageId,
                buildState = build?.status?.name,
                agentState = when {
                    agent.running -> "agent: working"
                    agent.isConnected -> "agent: ready"
                    else -> null
                },
                onProblemsClick = { viewModel.selectBottomTool(BottomTool.PROBLEMS) },
            )
        }

        // Quick action rail: build, agent, new file. Always reachable, one tap.
        WorkbenchQuickActions(
            buildRunning = build.isRunningBuild(),
            agentRunning = agent.running,
            onBuild = { viewModel.runBuild(BuildKind.BUILD) },
            onTest = { viewModel.runBuild(BuildKind.TEST) },
            onToggleAgent = viewModel::toggleAgentPanel,
            onNewFile = { showNewFileDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 30.dp),
        )

        // Phone drawer sidebar.
        if (!state.policy.showSidebar && state.sidebarVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .androidxClickable { viewModel.closeSidebar() },
            )
            AnimatedVisibility(
                visible = state.sidebarVisible,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.fillMaxHeight(),
            ) {
                SidebarHost(
                    state = state,
                    androidTree = androidTree,
                    projectTree = projectTree,
                    expanded = expanded,
                    searchHits = searchHits,
                    activePath = state.activeTab?.path,
                    modifiedPaths = modifiedPaths,
                    agent = agent,
                    onToggleExpanded = viewModel::toggleExpanded,
                    onOpenFile = viewModel::openFile,
                    onOpenHit = { hit -> viewModel.openFile(hit.path) },
                    onSearch = viewModel::search,
                    onNewFile = { showNewFileDialog = true },
                    onRefresh = viewModel::refreshFiles,
                    onDelete = { node -> viewModel.deleteFile(node.path) },
                    onToggleAndroidView = viewModel::toggleAndroidView,
                    onOpenProject = onOpenProjects,
                    modifier = Modifier
                        .width(state.policy.sidebarWidthDp.dp)
                        .fillMaxHeight()
                        .background(TitanColors.CanvasElevated),
                )
            }
        }

        // Phone agent sheet.
        if (!state.policy.showAgentPanel && state.agentPanelVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .androidxClickable { viewModel.toggleAgentPanel() },
            )
            AnimatedVisibility(
                visible = state.agentPanelVisible,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                AgentPanel(
                    state = agent,
                    onSend = viewModel::sendAgentPrompt,
                    onStop = viewModel::stopAgent,
                    onAnswerPermission = viewModel::answerPermission,
                    onReconnect = viewModel::reconnectAgent,
                    onClose = viewModel::toggleAgentPanel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showNewFileDialog) {
        NewFileDialog(
            onDismiss = { showNewFileDialog = false },
            onCreate = { path ->
                showNewFileDialog = false
                viewModel.createFile(path)
            },
        )
    }

    message?.let { text ->
        LaunchedEffect(text) {
            kotlinx.coroutines.delay(2400)
            viewModel.clearMessage()
        }
        SnackMessage(text, onDismiss = viewModel::clearMessage)
    }
}

/**
 * A thin title bar. It exists because the workbench is full bleed: without it there is no
 * way back to the rest of the app on a phone.
 */
@Composable
private fun WorkbenchTitleBar(
    projectName: String?,
    onExit: (() -> Unit)?,
    onOpenProjects: () -> Unit,
    onToggleAgent: () -> Unit,
    agentConnected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(TitanColors.CanvasElevated)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onExit != null) {
            DenseIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to dashboard", onExit)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = projectName ?: "No project",
            color = TitanColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        DenseIconButton(Icons.Filled.SmartToy, if (agentConnected) "Agent ready" else "Connect agent", onToggleAgent, tint = if (agentConnected) TitanColors.Success else TitanColors.TextMuted)
        DenseIconButton(Icons.Filled.Folder, "All projects", onOpenProjects)
    }
}

/** Sidebar router: one place that maps the selected activity to its view. */
@Composable
private fun SidebarHost(
    state: WorkbenchState,
    androidTree: List<dev.titanide.core.workbench.ProjectNode>,
    projectTree: List<dev.titanide.core.workbench.ProjectNode>,
    expanded: Set<String>,
    searchHits: List<SearchHit>,
    activePath: String?,
    modifiedPaths: Set<String>,
    agent: dev.titanide.core.opencode.session.OpenCodeState,
    onToggleExpanded: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onSearch: (String) -> Unit,
    onNewFile: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (dev.titanide.core.workbench.ProjectNode) -> Unit,
    onToggleAndroidView: () -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = modifier) {
        when (state.activity) {
            Activity.EXPLORER -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WorkbenchMetrics.SectionHeaderHeight)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ViewModeTab(
                        label = "Android",
                        selected = state.androidViewMode,
                        onClick = { if (!state.androidViewMode) onToggleAndroidView() },
                    )
                    ViewModeTab(
                        label = "Project",
                        selected = !state.androidViewMode,
                        onClick = { if (state.androidViewMode) onToggleAndroidView() },
                    )
                    Spacer(Modifier.weight(1f))
                    DenseIconButton(Icons.Filled.Upload, "Open another project", onOpenProject)
                }
                FileTree(
                    nodes = if (state.androidViewMode) androidTree else projectTree,
                    expanded = expanded,
                    activePath = activePath,
                    modifiedPaths = modifiedPaths,
                    onToggle = onToggleExpanded,
                    onOpen = { node -> if (!node.isDirectory) onOpenFile(node.path) },
                    onCreateFile = onNewFile,
                    onRefresh = onRefresh,
                    onDelete = onDelete,
                    emptyDetail = if (androidTree.isEmpty() && projectTree.isEmpty()) {
                        "This project has no files yet. Create one to get started."
                    } else {
                        "Nothing matches the current filter."
                    },
                )
            }

            Activity.SEARCH -> SearchView(
                query = query,
                onQueryChange = {
                    query = it
                    onSearch(it)
                },
                hits = searchHits,
                onOpenHit = onOpenHit,
            )

            Activity.SOURCE_CONTROL -> SourceControlSidebar(
                branch = state.status.gitBranch,
                modifiedPaths = modifiedPaths,
                onOpenFile = onOpenFile,
            )

            Activity.RUN_DEBUG -> RunSidebar(
                onBuild = { },
                onOpenProject = onOpenProject,
            )

            Activity.EXTENSIONS -> ExtensionsSidebar()

            Activity.GITHUB -> GitHubSidebar(onOpenProject = onOpenProject)

            Activity.AGENT -> AgentSidebar(agent)
        }
    }
}

@Composable
private fun ViewModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) TitanColors.TextPrimary else TitanColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .androidxClickable(onClick = onClick)
            .background(if (selected) TitanColors.Surface else Color.Transparent)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Floats the three most used actions above the status bar. */
@Composable
private fun WorkbenchQuickActions(
    buildRunning: Boolean,
    agentRunning: Boolean,
    onBuild: () -> Unit,
    onTest: () -> Unit,
    onToggleAgent: () -> Unit,
    onNewFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QuickActionButton(
            label = if (buildRunning) "Building" else "Build",
            icon = Icons.Filled.Refresh,
            tint = if (buildRunning) TitanColors.Warning else TitanColors.Success,
            onClick = onBuild,
        )
        QuickActionButton("Test", Icons.Filled.Menu, TitanColors.Secondary, onTest)
        QuickActionButton(
            label = if (agentRunning) "Agent" else "Agent",
            icon = Icons.Filled.SmartToy,
            tint = TitanColors.Primary,
            onClick = onToggleAgent,
        )
        QuickActionButton("New", Icons.Filled.Add, TitanColors.TextMuted, onNewFile)
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(TitanColors.Surface.copy(alpha = 0.95f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .androidxClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.width(12.dp).height(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = TitanColors.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = "Enter a project-relative path, for example src/Main.kt",
                    color = TitanColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                SearchField(
                    value = path,
                    onValueChange = { path = it },
                    placeholder = "src/Main.kt",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotBlank()) onCreate(path.trim()) },
                enabled = path.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = TitanColors.Surface,
    )
}

@Composable
private fun SnackMessage(text: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 40.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .background(TitanColors.SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .androidxClickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, color = TitanColors.TextPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Local alias so the dense chrome can use clickable without importing it everywhere. */
internal fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))
