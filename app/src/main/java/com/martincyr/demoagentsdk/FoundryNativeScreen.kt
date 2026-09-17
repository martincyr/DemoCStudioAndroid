package com.martincyr.demoagentsdk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.martincyr.demoagentsdk.foundry.FoundryChatViewModel
import com.martincyr.demoagentsdk.foundry.FoundryClient
import com.martincyr.demoagentsdk.foundry.FoundryConnection
import com.martincyr.demoagentsdk.copilotstudio.MsalTokenSource

@Composable
fun FoundryNativeScreen(
    configuration: AppConfiguration,
    tokenProvider: CopilotStudioTokenProvider,
    modifier: Modifier = Modifier
) {
    val foundry = configuration.foundry
    if (!foundry.isConfigured) {
        Text(
            text = "Configure foundry.projectEndpoint and foundry.agentId in appsettings_local.json.",
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val chat: FoundryChatViewModel = viewModel {
        val tokenSource = MsalTokenSource(tokenProvider, listOf(foundry.scope))
        FoundryChatViewModel(
            FoundryClient(
                settings = FoundryConnection(
                    foundry.projectEndpoint,
                    foundry.apiVersion,
                    foundry.agentId
                ),
                tokenSource = { forceRefresh -> tokenSource.accessToken(forceRefresh) }
            )
        )
    }
    LaunchedEffect(chat) { chat.start() }

    var draft by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        chat.error.value?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (!chat.isReady.value && chat.isBusy.value) {
            CircularProgressIndicator()
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chat.transcript) { item ->
                Text(
                    text = "${if (item.user) "You" else "Foundry"}: ${item.text}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = chat.isReady.value && !chat.isBusy.value,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the Foundry agent...") }
            )
            Button(
                onClick = {
                    chat.send(draft)
                    draft = ""
                },
                enabled = chat.isReady.value && !chat.isBusy.value && draft.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }
}
