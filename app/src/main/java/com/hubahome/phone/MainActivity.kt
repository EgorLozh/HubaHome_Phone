package com.hubahome.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hubahome.phone.feature.assistant.AssistantMessage
import com.hubahome.phone.feature.assistant.AssistantUiState
import com.hubahome.phone.feature.assistant.AssistantViewModel
import com.hubahome.phone.service.VoiceAssistantService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AssistantRoute()
                }
            }
        }
    }
}

@Composable
private fun AssistantRoute(
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
    }

    AssistantScreen(
        uiState = uiState,
        hasAudioPermission = hasAudioPermission,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onReconnect = viewModel::reconnect,
        onInputChanged = viewModel::updateInput,
        onSendTranscript = viewModel::sendTranscript,
        onWsUrlChanged = viewModel::updateWsUrl,
        onApiKeyChanged = viewModel::updateApiKey,
        onSaveSettings = viewModel::saveConnectionSettings,
        onResetSettings = viewModel::resetConnectionSettings,
        onToggleWakeword = viewModel::toggleWakeword,
        onStartCapture = viewModel::startCapture,
        onStopCapture = viewModel::stopCapture,
        onRequestAudioPermission = {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStartService = { VoiceAssistantService.start(context) },
        onStopService = { VoiceAssistantService.stop(context) },
    )
}

@Composable
private fun AssistantScreen(
    uiState: AssistantUiState,
    hasAudioPermission: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSendTranscript: () -> Unit,
    onWsUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onResetSettings: () -> Unit,
    onToggleWakeword: (Boolean) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Session: ${uiState.sessionStatus}",
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.wsUrlInput,
            onValueChange = onWsUrlChanged,
            label = { Text("WS URL") },
            minLines = 1,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.apiKeyInput,
            onValueChange = onApiKeyChanged,
            label = { Text("API key") },
            minLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveSettings) {
                Text("Save settings")
            }
            Button(onClick = onResetSettings) {
                Text("Reset settings")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect) {
                Text("Connect")
            }
            Button(onClick = onDisconnect) {
                Text("Disconnect")
            }
            Button(onClick = onReconnect) {
                Text("Reconnect")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartService) {
                Text("Start service")
            }
            Button(onClick = onStopService) {
                Text("Stop service")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wakeword")
            Switch(
                checked = uiState.isWakewordEnabled,
                onCheckedChange = onToggleWakeword,
            )
            Button(
                enabled = hasAudioPermission && !uiState.isRecording,
                onClick = onStartCapture,
            ) {
                Text("Start mic")
            }
            Button(
                enabled = uiState.isRecording,
                onClick = onStopCapture,
            ) {
                Text("Stop mic")
            }
        }

        if (!hasAudioPermission) {
            Button(onClick = onRequestAudioPermission) {
                Text("Grant microphone permission")
            }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.transcriptInput,
            onValueChange = onInputChanged,
            label = { Text("Final transcript") },
            placeholder = { Text("Например: Что такое выхухоль") },
            minLines = 2,
        )

        Button(
            enabled = uiState.isConnected && uiState.transcriptInput.isNotBlank(),
            onClick = onSendTranscript,
        ) {
            Text("Send")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.messages) { message ->
                MessageRow(message = message)
            }
        }
    }
}

@Composable
private fun MessageRow(message: AssistantMessage) {
    val prefix = when (message.sender) {
        AssistantMessage.Sender.USER -> "You"
        AssistantMessage.Sender.ASSISTANT -> "Huba"
        AssistantMessage.Sender.SYSTEM -> "System"
    }
    Text(
        text = "$prefix: ${message.text}",
        style = MaterialTheme.typography.bodyMedium,
    )
}
