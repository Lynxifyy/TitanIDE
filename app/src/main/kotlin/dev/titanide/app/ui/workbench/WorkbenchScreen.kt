package dev.titanide.app.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildResult
import dev.titanide.core.model.BuildStatus
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
    val symbolResults by viewModel.symbolResults.collectAsStateWithLifecycle()
    val references by viewModel.references.collectAsStateWithLifecycle()
    val pendingRename by viewModel.pendingRename.collectAsStateWithLifecycle()
    val renameTarget by viewModel.renameTarget.collectAsStateWithLifecycle()
    val referenceTarget by viewModel.referenceTarget.collectAsStateWithLifecycle()
    val find by viewModel.find.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val revealToken by viewModel.revealRequests.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingClose by viewModel.pendingClose.collectAsStateWithLifecycle()
    val pendingExit by viewModel.pendingExit.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val build by viewModel.build.collectAsStateWithLifecycle()
    val logs by viewModel.buildLogs.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val installInProgress by viewModel.installInProgress.collectAsStateWithLifecycle()
    val installedPackage by viewModel.lastInstalledPackage.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthDp = with(density) { configuration.screenWidthDp }
    val heightDp = with(density) { configuration.screenHeightDp }

    LaunchedEffect(widthDp, heightDp) { viewModel.onSizeChanged(widthDp, heightDp) }

    val currentBuildLogs = remember(logs, build) { build?.logLines ?: logs.values.lastOrNull() ?: emptyList() }
    val modifiedPaths = remember(buffers) { buffers.filterValues { it.dirty }.keys }

    var prompt by remember { mutableStateOf<NamePrompt?>(null) }

    // The system back gesture is the exit path a phone user actually reaches for, so it has
    // to run the same unsaved-edits check as the title bar button.
    if (onExitWorkbench != null) {
        BackHandler {
            if (pendingExit) viewModel.cancelExit()
            else if (viewModel.requestExit()) onExitWorkbench()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TitanColors.Canvas)
            // statusBars for the top chrome, navigationBars so the status bar row is not
            // hidden behind the gesture bar, and ime so the software keyboard pushes the
            // editor and terminal input up instead of covering them.
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            WorkbenchTitleBar(
                projectName = state.projectName,
                onExit = onExitWorkbench?.let { exit -> { if (viewModel.requestExit()) exit() } },
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
                        onOpenHit = { hit -> viewModel.openFileAtLine(hit.path, hit.line) },
                        onOpenFindInFile = { replace -> viewModel.openFind(replace) },
                        onSearch = viewModel::search,
                        symbols = symbolResults,
                        onSearchSymbols = viewModel::searchSymbols,
                        onOpenSymbol = { declaration ->
                            viewModel.openFileAtLine(declaration.symbol.filePath, declaration.line + 1)
                        },
                        onNewFile = { prompt = NamePrompt.NewFile },
                        onNewFolder = { prompt = NamePrompt.NewFolder },
                        onRefresh = viewModel::refreshFiles,
                        onDelete = { node -> prompt = NamePrompt.Delete(node.path) },
                        onRename = { node -> prompt = NamePrompt.Rename(node.path) },
                        onDuplicate = { node -> prompt = NamePrompt.Duplicate(node.path) },
                        onToggleAndroidView = viewModel::toggleAndroidView,
                        onOpenProject = onOpenProjects,
                        build = build,
                        buildRunning = build.isRunningBuild(),
                        onBuild = { viewModel.runBuild(BuildKind.BUILD) },
                        onTest = { viewModel.runBuild(BuildKind.TEST) },
                        onClean = { viewModel.runBuild(BuildKind.CLEAN) },
                        onRebuild = { viewModel.runBuild(BuildKind.REBUILD) },
                        onRun = { viewModel.runBuild(BuildKind.RUN) },
                        onStop = viewModel::stopBuild,
                        installInProgress = installInProgress,
                        installedPackage = installedPackage,
                        onInstall = viewModel::installLastBuildApk,
                        onLaunch = viewModel::launchInstalledApp,
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
                                onIndent = viewModel::indent,
                                onUndo = viewModel::undo,
                                onRedo = viewModel::redo,
                                onGoToDefinition = viewModel::goToDefinition,
                                onFindReferences = viewModel::findReferences,
                                onRename = viewModel::beginRename,
                                referenceTarget = referenceTarget,
                                references = references,
                                onOpenReference = { reference ->
                                    viewModel.openFileAtLine(reference.filePath, reference.line + 1)
                                },
                                onCloseReferences = viewModel::closeReferences,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                showGutter = active || state.groups.size == 1,
                                find = find,
                                appearance = appearance,
                                revealToken = revealToken,
                                onFindQueryChange = viewModel::setFindQuery,
                                onFindReplaceChange = viewModel::setFindReplacement,
                                onFindNext = viewModel::findNext,
                                onFindPrevious = viewModel::findPrevious,
                                onReplaceCurrent = viewModel::replaceCurrent,
                                onReplaceAll = viewModel::replaceAll,
                                onCloseFind = viewModel::closeFind,
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
            onNewFile = { prompt = NamePrompt.NewFile },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 30.dp),
        )

        // Phone drawer sidebar.
        if (!state.policy.showSidebar && state.sidebarVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { viewModel.closeSidebar() },
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
                    onOpenHit = { hit -> viewModel.openFileAtLine(hit.path, hit.line) },
                    onOpenFindInFile = { replace -> viewModel.openFind(replace) },
                    onSearch = viewModel::search,
                    symbols = symbolResults,
                    onSearchSymbols = viewModel::searchSymbols,
                    onOpenSymbol = { declaration ->
                        viewModel.openFileAtLine(declaration.symbol.filePath, declaration.line + 1)
                    },
                    onNewFile = { prompt = NamePrompt.NewFile },
                    onNewFolder = { prompt = NamePrompt.NewFolder },
                    onRefresh = viewModel::refreshFiles,
                    onDelete = { node -> prompt = NamePrompt.Delete(node.path) },
                    onRename = { node -> prompt = NamePrompt.Rename(node.path) },
                    onDuplicate = { node -> prompt = NamePrompt.Duplicate(node.path) },
                    onToggleAndroidView = viewModel::toggleAndroidView,
                    onOpenProject = onOpenProjects,
                    build = build,
                    buildRunning = build.isRunningBuild(),
                    onBuild = { viewModel.runBuild(BuildKind.BUILD) },
                    onTest = { viewModel.runBuild(BuildKind.TEST) },
                    onClean = { viewModel.runBuild(BuildKind.CLEAN) },
                    onRebuild = { viewModel.runBuild(BuildKind.REBUILD) },
                    onRun = { viewModel.runBuild(BuildKind.RUN) },
                    onStop = viewModel::stopBuild,
                    installInProgress = installInProgress,
                    installedPackage = installedPackage,
                    onInstall = viewModel::installLastBuildApk,
                    onLaunch = viewModel::launchInstalledApp,
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
                    .clickable { viewModel.toggleAgentPanel() },
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

    prompt?.let { current ->
        NamePromptDialog(
            prompt = current,
            hasUnsavedChanges = (current as? NamePrompt.Delete)?.let { viewModel.hasUnsavedChanges(it.path) } == true,
            onDismiss = { prompt = null },
            onConfirm = { value ->
                prompt = null
                when (current) {
                    NamePrompt.NewFile -> viewModel.createFile(value)
                    NamePrompt.NewFolder -> viewModel.createDirectory(value)
                    is NamePrompt.Rename -> viewModel.renamePath(current.path, value)
                    is NamePrompt.Duplicate -> viewModel.duplicatePath(current.path, value)
                    is NamePrompt.Delete -> viewModel.deleteFile(current.path)
                }
            },
        )
    }

    pendingClose?.let { path ->
        val name = path.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingClose,
            title = { Text("Close $name?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "This file has edits that are not on disk. Closing the tab discards them.",
                    color = TitanColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::discardAndCloseTab) {
                    Text("Discard", color = TitanColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPendingClose) {
                    Text("Keep editing", color = TitanColors.Primary)
                }
            },
        )
    }

    if (pendingExit && onExitWorkbench != null) {
        val unsaved = viewModel.unsavedPaths()
        AlertDialog(
            onDismissRequest = viewModel::cancelExit,
            title = { Text("Save before leaving?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = "${unsaved.size} file" + (if (unsaved.size == 1) "" else "s") +
                            " with edits that are not on disk.",
                        color = TitanColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Naming the files is the point: the user cannot judge "discard" otherwise.
                    unsaved.take(6).forEach { path ->
                        Text(path, color = TitanColors.TextPrimary, fontSize = 11.sp)
                    }
                    if (unsaved.size > 6) {
                        Text(
                            "and ${unsaved.size - 6} more",
                            color = TitanColors.TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { if (viewModel.saveAndExit()) onExitWorkbench() }
                }) {
                    Text("Save and leave", color = TitanColors.Primary)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.discardAndExit()
                        onExitWorkbench()
                    }) {
                        Text("Discard", color = TitanColors.Error)
                    }
                    TextButton(onClick = viewModel::cancelExit) {
                        Text("Keep editing", color = TitanColors.TextMuted)
                    }
                }
            },
        )
    }

    renameTarget?.let { oldName ->
        RenameSymbolDialog(
            oldName = oldName,
            onDismiss = viewModel::cancelBeginRename,
            onConfirm = viewModel::renameSymbol,
        )
    }

    pendingRename?.let { plan ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text("Rename ${plan.oldName}?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = "${plan.newName} in ${plan.changedFiles.size} file" +
                            (if (plan.changedFiles.size == 1) "" else "s") +
                            ", ${plan.totalOccurrences} occurrence" +
                            (if (plan.totalOccurrences == 1) "" else "s") + ".",
                        color = TitanColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    // The user is asked to confirm a write to files they may not have open, so
                    // every one of them is named rather than summarised as a count.
                    plan.changedFiles.take(6).forEach { change ->
                        Text(
                            text = "${change.filePath}  (${change.occurrences})",
                            color = TitanColors.TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (plan.changedFiles.size > 6) {
                        Text(
                            text = "and ${plan.changedFiles.size - 6} more",
                            color = TitanColors.TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::applyRename) { Text("Rename", color = TitanColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text("Cancel", color = TitanColors.TextMuted) }
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
    onOpenFindInFile: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    symbols: List<dev.titanide.core.editor.SymbolDeclaration>,
    onSearchSymbols: (String) -> Unit,
    onOpenSymbol: (dev.titanide.core.editor.SymbolDeclaration) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (dev.titanide.core.workbench.ProjectNode) -> Unit,
    onRename: (dev.titanide.core.workbench.ProjectNode) -> Unit,
    onDuplicate: (dev.titanide.core.workbench.ProjectNode) -> Unit,
    onToggleAndroidView: () -> Unit,
    onOpenProject: () -> Unit,
    build: BuildResult?,
    buildRunning: Boolean,
    onBuild: () -> Unit,
    onTest: () -> Unit,
    onClean: () -> Unit,
    onRebuild: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    installInProgress: Boolean,
    installedPackage: String?,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
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
                    onCreateFolder = onNewFolder,
                    onRefresh = onRefresh,
                    onDelete = onDelete,
                    onRename = onRename,
                    onDuplicate = onDuplicate,
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
                onOpenFindInFile = onOpenFindInFile,
                symbols = symbols,
                onSearchSymbols = onSearchSymbols,
                onOpenSymbol = onOpenSymbol,
            )

            Activity.SOURCE_CONTROL -> SourceControlSidebar(
                branch = state.status.gitBranch,
                modifiedPaths = modifiedPaths,
                onOpenFile = onOpenFile,
            )

            Activity.RUN_DEBUG -> RunSidebar(
                projectName = state.projectName,
                buildRunning = buildRunning,
                lastBuildStatus = build?.status?.name,
                lastBuildExecutor = build?.executorLabel,
                canRun = build?.status == BuildStatus.SUCCEEDED,
                runHint = when {
                    buildRunning -> "A build is running. Stop it before starting another."
                    build == null -> "No build yet. Build the project to see real output here."
                    build.status == BuildStatus.SUCCEEDED ->
                        "Last build succeeded. Run installs the built app on a connected device."
                    else -> build.failureReason ?: "The last build did not succeed."
                },
                onBuild = onBuild,
                onTest = onTest,
                onClean = onClean,
                onRebuild = onRebuild,
                hasApk = build?.artifacts?.any { it.name.endsWith(".apk", ignoreCase = true) } == true,
                installInProgress = installInProgress,
                installedPackage = installedPackage,
                onInstall = onInstall,
                onLaunch = onLaunch,
                onRun = onRun,
                onStop = onStop,
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
            .clickable(onClick = onClick)
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
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.width(12.dp).height(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = TitanColors.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * What the explorer is asking for. A sealed type rather than a flag per dialog: the
 * confirm action differs per case, and a single dialog keeps the phone layout short.
 */
private sealed interface NamePrompt {
    data object NewFile : NamePrompt
    data object NewFolder : NamePrompt
    data class Rename(val path: String) : NamePrompt
    data class Duplicate(val path: String) : NamePrompt
    data class Delete(val path: String) : NamePrompt
}

private val NamePrompt.title: String
    get() = when (this) {
        NamePrompt.NewFile -> "New file"
        NamePrompt.NewFolder -> "New folder"
        is NamePrompt.Rename -> "Rename or move"
        is NamePrompt.Duplicate -> "Duplicate"
        is NamePrompt.Delete -> "Delete"
    }

private fun NamePrompt.hint(hasUnsavedChanges: Boolean): String = when (this) {
    NamePrompt.NewFile -> "Enter a project-relative path, for example src/Main.kt"
    NamePrompt.NewFolder -> "Enter a project-relative path, for example src/feature"
    is NamePrompt.Rename -> "New path. Directories may be moved by changing the folder."
    is NamePrompt.Duplicate -> "Path for the copy. It must not already exist."
    is NamePrompt.Delete -> if (hasUnsavedChanges) {
        "This removes the file from disk, and it has unsaved edits. Neither can be recovered."
    } else {
        "This removes the file from disk. It cannot be undone."
    }
}

private val NamePrompt.initial: String
    get() = when (this) {
        NamePrompt.NewFile, NamePrompt.NewFolder -> ""
        is NamePrompt.Rename -> path
        is NamePrompt.Duplicate -> path
        is NamePrompt.Delete -> path
    }

private val NamePrompt.confirmLabel: String
    get() = when (this) {
        NamePrompt.NewFile -> "Create"
        NamePrompt.NewFolder -> "Create"
        is NamePrompt.Rename -> "Rename"
        is NamePrompt.Duplicate -> "Copy"
        is NamePrompt.Delete -> "Delete"
    }

@Composable
private fun NamePromptDialog(
    prompt: NamePrompt,
    hasUnsavedChanges: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(prompt) { mutableStateOf(prompt.initial) }
    val isDelete = prompt is NamePrompt.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = prompt.hint(hasUnsavedChanges),
                    color = if (isDelete && hasUnsavedChanges) TitanColors.Error else TitanColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!isDelete) {
                    Spacer(Modifier.height(8.dp))
                    SearchField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = "src/Main.kt",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = isDelete || value.isNotBlank(),
            ) {
                Text(
                    prompt.confirmLabel,
                    color = if (isDelete) TitanColors.Error else TitanColors.Primary,
                )
            }
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
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, color = TitanColors.TextPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Asks for the new name of a symbol.
 *
 * Separate from [NamePromptDialog] because the value being typed is an identifier, not a path:
 * the hint and the placeholder differ, and a path-shaped hint here would mislead.
 */
@Composable
private fun RenameSymbolDialog(
    oldName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(oldName) { mutableStateOf(oldName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename symbol", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = "Every reference to $oldName in the project is rewritten. " +
                        "You will see the list before anything is written.",
                    color = TitanColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                SearchField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "newName",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank() && value.trim() != oldName,
            ) { Text("Preview", color = TitanColors.Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = TitanColors.Surface,
    )
}
