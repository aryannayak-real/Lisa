package com.lisaai.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lisaai.inference.Message
import com.lisaai.inference.Role
import com.lisaai.terminal.TerminalActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lisa")
                        Text(state.status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, TerminalActivity::class.java)) },
                    ) {
                        Text("Terminal")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (state.modelLoaded) {
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !state.importing && !state.generating,
                        ) {
                            Text("Change model")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MessageList(
                state = state,
                modifier = Modifier.weight(1f),
                onDownload = vm::downloadDefaultModel,
                onPickFile = { picker.launch(arrayOf("*/*")) },
            )
            Composer(
                state = state,
                onInput = vm::onInput,
                onSend = vm::send,
                onStop = vm::stop,
            )
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit,
    onPickFile: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
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
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val fromUser = message.role == Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = message.text.ifEmpty { "..." },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
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
            placeholder = { Text("Message Lisa") },
            maxLines = 5,
            enabled = state.modelLoaded,
        )

        if (state.generating) {
            OutlinedButton(onClick = onStop) { Text("Stop") }
        } else {
            Button(
                onClick = onSend,
                enabled = state.modelLoaded && state.input.isNotBlank(),
            ) {
                Text("Send")
            }
        }
    }
}
