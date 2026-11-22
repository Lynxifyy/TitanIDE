package dev.titanide.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatBytes
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.data.settings.EditorSettings
import dev.titanide.core.data.settings.SettingsRepository
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.ExecutionTarget
import dev.titanide.core.model.ToolchainInfo

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "Settings",
            subtitle = "${state.storageUsed.let(::formatBytes)} in app storage",
            actions = {
                IconButton(onClick = viewModel::probeToolchains) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-probe toolchain")
                }
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message ->
                TitanCard(accent = TitanColors.Secondary) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TitanColors.TextPrimary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // --- GitHub -----------------------------------------------------
            Text("GitHub", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, contentDescription = null, tint = TitanColors.Secondary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.gitHubSignedIn) state.gitHubLogin ?: "Signed in" else "Not signed in",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TitanColors.TextPrimary,
                            )
                            Text(
                                if (state.gitHubSignedIn) {
                                    "Token stored in the Android Keystore"
                                } else {
                                    "Sign in to browse, clone, and build repositories"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        StatusPill(
                            if (state.gitHubSignedIn) "connected" else "offline",
                            if (state.gitHubSignedIn) TitanColors.Success else TitanColors.TextMuted,
                        )
                    }

                    val flow = state.deviceFlow
                    when {
                        flow != null && flow.userCode.isNotBlank() -> {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Enter this code at ${flow.verificationUri}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.TextPrimary,
                            )
                            Text(
                                flow.userCode,
                                style = MaterialTheme.typography.headlineSmall,
                                color = TitanColors.Secondary,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = TitanColors.Secondary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Waiting for authorisation...", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(onClick = viewModel::cancelDeviceFlow) { Text("Cancel") }
                            }
                        }
                        flow?.error != null -> {
                            Spacer(Modifier.height(8.dp))
                            Text(flow.error, style = MaterialTheme.typography.bodySmall, color = TitanColors.Error)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = viewModel::startGitHubSignIn) { Text("Try again") }
                        }
                        !state.gitHubSignedIn -> {
                            Spacer(Modifier.height(10.dp))
                            // Sign-in cannot start without the build's OAuth client id, so the
                            // button is disabled rather than allowed to fail on tap.
                            Button(
                                onClick = viewModel::startGitHubSignIn,
                                enabled = state.gitHubOAuthAvailable,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                            ) { Text("Sign in with GitHub") }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (state.gitHubOAuthAvailable) {
                                    "Sign in opens github.com/login/device with a one-time code."
                                } else {
                                    "This build was compiled without a GitHub OAuth client id, so " +
                                        "one-tap sign-in is unavailable. Everything else works: add a " +
                                        "personal access token on the Git screen to clone, push and open " +
                                        "pull requests."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.gitHubOAuthAvailable) TitanColors.TextMuted else TitanColors.Warning,
                            )
                        }
                        else -> {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = viewModel::signOutGitHub) { Text("Sign out") }
                        }
                    }
                }
            }

            // --- AI ---------------------------------------------------------
            Text("TITAN Intelligence", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Text("Provider", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none" to "Off", "openai_compatible" to "OpenAI-compatible", "anthropic" to "Anthropic")
                            .forEach { (id, label) ->
                                FilterChip(
                                    selected = settings?.ai?.providerId == id,
                                    onClick = { viewModel.setAiProvider(id) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings?.ai?.endpoint.orEmpty(),
                        onValueChange = viewModel::setAiEndpoint,
                        label = { Text("Endpoint") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = settings?.ai?.model.orEmpty(),
                        onValueChange = viewModel::setAiModel,
                        label = { Text("Model") },
                        placeholder = { Text("gpt-4o-mini") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )

                    Spacer(Modifier.height(8.dp))
                    var keyInput by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text(if (state.aiKeyStored) "Replace API key" else "API key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        trailingIcon = {
                            Icon(Icons.Filled.Key, contentDescription = null, tint = TitanColors.TextMuted)
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                viewModel.saveAiKey(keyInput)
                                keyInput = ""
                            },
                            enabled = keyInput.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                        ) { Text("Save key") }
                        if (state.aiKeyStored) {
                            OutlinedButton(onClick = viewModel::clearAiKey) { Text("Remove") }
                            StatusPill("stored", TitanColors.Success)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send project context", style = MaterialTheme.typography.bodySmall, color = TitanColors.TextPrimary)
                            Text(
                                "Off by default. When off, TITAN sends only the code you paste or select.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.ai?.allowProjectContext ?: false,
                            onCheckedChange = viewModel::setAllowProjectContext,
                        )
                    }
                }
            }

            // --- Build ------------------------------------------------------
            Text("Build and execution", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Text("Default target", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExecutionTarget.entries.forEach { target ->
                            FilterChip(
                                selected = settings?.build?.defaultTargetId == target.id,
                                onClick = { viewModel.setBuildTarget(target.id) },
                                label = { Text(target.label.take(16), style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On-device builds need a JDK and Gradle inside app storage, which Android does not " +
                            "provide. Remote agent and GitHub Actions targets run the real toolchain elsewhere.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings?.build?.remoteAgentUrl.orEmpty(),
                        onValueChange = viewModel::setRemoteAgentUrl,
                        label = { Text("Remote agent URL") },
                        placeholder = { Text("https://build.example.dev") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    var remoteToken by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = remoteToken,
                        onValueChange = { remoteToken = it },
                        label = { Text("Remote agent token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                viewModel.saveRemoteToken(remoteToken)
                                remoteToken = ""
                            },
                            enabled = remoteToken.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                        ) { Text("Save token") }
                        if (state.remoteTokenStored) {
                            OutlinedButton(onClick = viewModel::clearRemoteToken) { Text("Remove") }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = settings?.build?.githubOwner.orEmpty(),
                            onValueChange = { owner ->
                                viewModel.setGitHubBuildRepo(owner, settings?.build?.githubRepo.orEmpty())
                            },
                            label = { Text("GH owner") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = settings?.build?.githubRepo.orEmpty(),
                            onValueChange = { repo ->
                                viewModel.setGitHubBuildRepo(settings?.build?.githubOwner.orEmpty(), repo)
                            },
                            label = { Text("Repo") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = settings?.build?.githubWorkflow.orEmpty(),
                        onValueChange = viewModel::setGitHubWorkflow,
                        label = { Text("Workflow file") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // --- OpenCode agent ---------------------------------------------
            Text("OpenCode agent", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "TITAN drives a real OpenCode server over its HTTP and SSE API. The " +
                            "server runs the agent and edits files in the project directory below.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings?.openCode?.serverUrl.orEmpty(),
                        onValueChange = viewModel::setOpenCodeUrl,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.1.20:4096") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = settings?.openCode?.remoteProjectDir.orEmpty(),
                        onValueChange = viewModel::setOpenCodeDir,
                        label = { Text("Project directory on the server") },
                        placeholder = { Text("/home/me/projects/my-app") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connect on launch", style = MaterialTheme.typography.bodySmall, color = TitanColors.TextPrimary)
                            Text(
                                "Reconnects to the server whenever a project opens.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.openCode?.autoConnect ?: false,
                            onCheckedChange = viewModel::setOpenCodeAutoConnect,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (state.openCodeTokenStored) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Bearer token stored in the Keystore",
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.Success,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = viewModel::clearOpenCodeToken) { Text("Remove") }
                        }
                    } else {
                        var openCodeToken by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = openCodeToken,
                            onValueChange = { openCodeToken = it },
                            label = { Text("Bearer token") },
                            placeholder = { Text("Only needed if the server sets one") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                viewModel.saveOpenCodeToken(openCodeToken)
                                openCodeToken = ""
                            },
                            enabled = openCodeToken.isNotBlank(),
                        ) { Text("Store token") }
                    }
                }
            }

            // --- Editor -----------------------------------------------------
            Text("Editor", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("CodeGuard analysis", style = MaterialTheme.typography.bodySmall, color = TitanColors.TextPrimary)
                            Text(
                                "Runs the local parser and typo checks as you type",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.editor?.codeGuardEnabled ?: true,
                            onCheckedChange = viewModel::setCodeGuard,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-save", style = MaterialTheme.typography.bodySmall, color = TitanColors.TextPrimary)
                            Text(
                                "Writes the buffer to disk after a short pause",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.editor?.autoSave ?: true,
                            onCheckedChange = viewModel::setAutoSave,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Font size: ${settings?.editor?.fontSize ?: 13}sp",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(11, 13, 15, 17, 20).forEach { size ->
                            FilterChip(
                                selected = (settings?.editor?.fontSize ?: 13) == size,
                                onClick = { viewModel.setEditorFontSize(size) },
                                label = { Text("$size", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tab width: ${settings?.editor?.tabWidth ?: 4} spaces",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 4, 8).forEach { width ->
                            FilterChip(
                                selected = (settings?.editor?.tabWidth ?: 4) == width,
                                onClick = { viewModel.setTabWidth(width) },
                                label = { Text("$width", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Line spacing: ${lineSpacingPercent(settings?.editor)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(110, 130, 150).forEach { percent ->
                            FilterChip(
                                selected = lineSpacingPercent(settings?.editor) == percent,
                                onClick = { viewModel.setEditorLineSpacing(percent) },
                                label = { Text("$percent%", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Word wrap", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextPrimary)
                            Text(
                                "Off scrolls long lines sideways",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.editor?.wordWrap ?: false,
                            onCheckedChange = viewModel::setWordWrap,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Line numbers", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextPrimary)
                            Text(
                                "Show the gutter",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.editor?.showLineNumbers ?: true,
                            onCheckedChange = viewModel::setShowLineNumbers,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Highlight current line",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextPrimary,
                            )
                            Text(
                                "Tint the caret's line",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings?.editor?.highlightCurrentLine ?: true,
                            onCheckedChange = viewModel::setHighlightCurrentLine,
                        )
                    }
                }
            }

            // --- Terminal ---------------------------------------------------
            Text("Terminal", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Font size: ${settings?.terminal?.fontSize ?: 13}sp",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(11, 13, 16, 20).forEach { size ->
                            FilterChip(
                                selected = (settings?.terminal?.fontSize ?: 13) == size,
                                onClick = { viewModel.setTerminalFontSize(size) },
                                label = { Text("$size", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open sessions: ${settings?.terminal?.maxSessions ?: 4}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Text(
                        "Each session can hold a running command",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 4, 8).forEach { count ->
                            FilterChip(
                                selected = (settings?.terminal?.maxSessions ?: 4) == count,
                                onClick = { viewModel.setTerminalMaxSessions(count) },
                                label = { Text("$count", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            // --- Toolchain --------------------------------------------------
            Text("Toolchain", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    if (state.probing && state.toolchains.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Probing...", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                        }
                    }
                    state.probeError?.let { failure ->
                        Text(
                            failure,
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.Error,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    state.toolchains.forEach { tool: ToolchainInfo ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (tool.available) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = if (tool.available) TitanColors.Success else TitanColors.TextMuted,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tool.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                tool.version?.take(24) ?: if (tool.available) "available" else "missing",
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The stored line spacing, clamped to the range the chips offer. */
private fun lineSpacingPercent(editor: EditorSettings?): Int =
    (editor?.lineSpacingPercent ?: SettingsRepository.DEFAULT_LINE_SPACING_PERCENT)
        .coerceIn(
            SettingsRepository.MIN_LINE_SPACING_PERCENT,
            SettingsRepository.MAX_LINE_SPACING_PERCENT,
        )
