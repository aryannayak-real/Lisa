package com.lisaai.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lisaai.data.ChatSummary
import com.lisaai.inference.Message
import com.lisaai.inference.MessageKind
import com.lisaai.inference.Role
import com.lisaai.terminal.TerminalActivity
import com.lisaai.terminal.TerminalBootstrap
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showSettings by remember { mutableStateOf(false) }

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    fun openTerminal() {
        context.startActivity(Intent(context, TerminalActivity::class.java))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    state = state,
                    versionName = versionName,
                    onNewChat = {
                        vm.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpenChat = { id ->
                        vm.openChat(id)
                        scope.launch { drawerState.close() }
                    },
                    onSettings = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    title = {
                        Column {
                            Text("Lisa")
                            Text(state.status, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        OutlinedButton(onClick = ::openTerminal) {
                            Text("Terminal")
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                MessageList(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onDownload = vm::downloadDefaultModel,
                    onPickFile = { picker.launch(arrayOf("*/*")) },
                )
                ModeSwitcher(state = state, onMode = vm::setAgentMode)
                val pendingCommand = state.pendingCommand
                if (pendingCommand != null) {
                    Spacer(Modifier.height(8.dp))
                    CommandApproval(
                        command = pendingCommand,
                        onRun = vm::approvePendingCommand,
                        onSkip = vm::skipPendingCommand,
                    )
                }
                Composer(
                    state = state,
                    onInput = vm::onInput,
                    onSend = vm::send,
                    onStop = vm::stop,
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            versionName = versionName,
            onDismiss = { showSettings = false },
            onAgentMode = vm::setAgentMode,
            onTerminal = {
                showSettings = false
                openTerminal()
            },
            onPickModel = {
                showSettings = false
                picker.launch(arrayOf("*/*"))
            },
            onNewChat = {
                showSettings = false
                vm.newChat()
            },
        )
    }
}

@Composable
private fun DrawerContent(
    state: ChatUiState,
    versionName: String,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxHeight().padding(vertical = 24.dp)) {
        Text(
            text = "Lisa",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Text(
            text = "Local-first chat",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(16.dp))

        NavigationDrawerItem(
            label = { Text("New chat") },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            text = "Recent chats",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            if (state.chats.isEmpty()) {
                item {
                    Text(
                        text = "No saved chats yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    )
                }
            }
            items(state.chats, key = { it.id }) { chat ->
                ChatDrawerItem(
                    chat = chat,
                    selected = chat.id == state.currentChatId,
                    onClick = { onOpenChat(chat.id) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        if (versionName.isNotBlank()) {
            Text(
                text = "Lisa $versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ChatDrawerItem(chat: ChatSummary, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = {
            Text(
                text = chat.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun MessageList(
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit,
    onPickFile: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.text,
        state.pendingCommand,
        state.currentChatId,
    ) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.messages.isEmpty()) {
            item {
                ModelCard(
                    state = state,
                    onDownload = onDownload,
                    onPickFile = onPickFile,
                )
            }
        }
        items(state.messages) { message -> MessageBubble(message) }
    }
}

@Composable
private fun ModelCard(state: ChatUiState, onDownload: () -> Unit, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lisa", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Runs models fully on your phone. Start with Qwen3.5 0.8B (~508 MB, 4-bit).",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))

        if (state.downloading) {
            LinearProgressIndicator(
                progress = { state.downloadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${(state.downloadProgress * 100).toInt()}%  ·  ${state.status}",
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            Button(onClick = onDownload, enabled = !state.importing) {
                Text("Download Qwen3.5 0.8B")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPickFile, enabled = !state.importing) {
                Text("Choose GGUF file")
            }
        }

        if (state.status.startsWith("Download failed") || state.status.startsWith("Load failed")) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.status,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No model loaded yet? Pick one above, then type below.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: Message) {
    if (message.kind == MessageKind.Note) {
        Text(
            text = message.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }

    val fromUser = message.role == Role.User
    val isCommand = message.kind == MessageKind.Command
    val isOutput = message.kind == MessageKind.Output
    val monospace = isCommand || isOutput

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                isCommand -> MaterialTheme.colorScheme.secondaryContainer
                isOutput -> MaterialTheme.colorScheme.surfaceVariant
                fromUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(if (monospace) 12.dp else 16.dp),
            modifier = if (monospace) Modifier.fillMaxWidth() else Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (monospace) {
                    Text(
                        text = if (isCommand) "COMMAND" else "OUTPUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = when {
                        isCommand -> "$ ${message.text}"
                        message.text.isEmpty() -> "..."
                        else -> message.text
                    },
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    style = if (monospace) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    modifier = if (isOutput) {
                        Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeSwitcher(state: ChatUiState, onMode: (Boolean) -> Unit) {
    val terminalReady = TerminalBootstrap.isInstalled()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !state.agentMode,
            onClick = { onMode(false) },
            enabled = !state.generating,
            label = { Text("Chat") },
        )
        FilterChip(
            selected = state.agentMode,
            onClick = { onMode(true) },
            enabled = !state.generating,
            label = { Text("Agent") },
        )
        Text(
            text = when {
                !state.modelLoaded -> "Load a model (☰ → Settings)"
                state.agentMode && !terminalReady -> "Open Terminal once"
                state.agentMode -> "Commands need approval"
                else -> "Chat only"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (!state.modelLoaded || (state.agentMode && !terminalReady)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CommandApproval(command: String, onRun: () -> Unit, onSkip: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Run this command?", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "$ $command",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun) { Text("Run") }
                OutlinedButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (state.pendingCommand != null) {
                        "Approve or skip the command above"
                    } else {
                        "Message Lisa"
                    }
                )
            },
            maxLines = 5,
            enabled = !state.generating && state.pendingCommand == null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (state.input.isNotBlank() && state.pendingCommand == null) onSend()
                },
            ),
        )

        if (state.generating) {
            OutlinedButton(onClick = onStop) { Text("Stop") }
        } else {
            Button(
                onClick = onSend,
                enabled = state.input.isNotBlank() &&
                    state.pendingCommand == null &&
                    !state.generating,
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    state: ChatUiState,
    versionName: String,
    onDismiss: () -> Unit,
    onAgentMode: (Boolean) -> Unit,
    onTerminal: () -> Unit,
    onPickModel: () -> Unit,
    onNewChat: () -> Unit,
) {
    val terminalReady = TerminalBootstrap.isInstalled()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Agent mode")
                        Text(
                            text = "Lisa proposes commands; you approve each one.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.agentMode,
                        onCheckedChange = onAgentMode,
                        enabled = !state.generating,
                    )
                }

                if (!terminalReady) {
                    Text(
                        text = "Open Terminal once to install the shell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()
                Text(
                    text = if (state.modelLoaded) "Model loaded" else "No model loaded",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTerminal) { Text("Terminal") }
                    OutlinedButton(onClick = onPickModel) { Text("Change model") }
                }

                HorizontalDivider()
                OutlinedButton(onClick = onNewChat) { Text("New chat") }

                Text(
                    text = if (versionName.isBlank()) "Lisa" else "Lisa $versionName · on-device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
