package com.martincyr.demoagentsdk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.martincyr.demoagentsdk.copilotstudio.AdaptiveCardView
import com.martincyr.demoagentsdk.copilotstudio.Attachment
import com.martincyr.demoagentsdk.copilotstudio.CardAction
import com.martincyr.demoagentsdk.copilotstudio.ConnectionState
import com.martincyr.demoagentsdk.copilotstudio.CopilotStudioConnection
import com.martincyr.demoagentsdk.copilotstudio.DirectToEngineClient
import com.martincyr.demoagentsdk.copilotstudio.MsalTokenSource
import com.martincyr.demoagentsdk.copilotstudio.NativeChatViewModel
import com.martincyr.demoagentsdk.copilotstudio.PowerPlatformCloud
import com.martincyr.demoagentsdk.copilotstudio.TranscriptItem
import com.martincyr.demoagentsdk.ui.theme.DemoAgentSDKTheme
import com.microsoft.agents.client.android.models.AppSettings
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.serialization.json.JsonElement

/**
 * Chat experience backed by a native Kotlin implementation of the Copilot Studio
 * "Direct to Engine" protocol: MSAL for auth, OkHttp + SSE for transport, kotlinx.serialization
 * for the activity payloads, and Compose for rendering.
 */
@Composable
fun NativeClientScreen(
    appSettings: AppSettings,
    tokenProvider: CopilotStudioTokenProvider,
    modifier: Modifier = Modifier
) {
    val user = appSettings.user
    if (user.environmentId.isBlank() || user.schemaName.isBlank()) {
        ConfigurationMissing(modifier)
        return
    }

    val chat: NativeChatViewModel = viewModel(
        factory = NativeChatViewModel.Factory {
            val connection = CopilotStudioConnection(
                environmentId = user.environmentId,
                schemaName = user.schemaName,
                cloud = PowerPlatformCloud.fromName(user.environment)
            )
            DirectToEngineClient(connection, MsalTokenSource(tokenProvider))
        }
    )

    LaunchedEffect(chat) { chat.startIfNeeded() }

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest message visible as the agent streams its answer in.
    LaunchedEffect(chat.transcript.size, chat.transcript.lastOrNull()?.text) {
        if (chat.transcript.isNotEmpty()) {
            listState.animateScrollToItem(chat.transcript.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        chat.errorText?.let { message ->
            ErrorBanner(message = message, onRetry = chat::retry)
        }

        if (chat.transcript.isEmpty() && chat.connectionState == ConnectionState.Connecting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = chat.statusText ?: "Connecting to Copilot Studio...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(chat.transcript, key = { it.id }) { item ->
                    MessageBubble(
                        item = item,
                        onAction = { chat.send(it.submitText) },
                        onCardSubmit = chat::submitCard
                    )
                }
            }
        }

        chat.statusText?.takeIf { chat.transcript.isNotEmpty() }?.let { status ->
            StatusRow(status)
        }

        Composer(
            value = draft,
            enabled = chat.canSend,
            onValueChange = { draft = it },
            onSend = {
                chat.send(draft)
                draft = ""
            }
        )
    }
}

@Composable
private fun ConfigurationMissing(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "appsettings is missing environmentId or schemaName. Configure them before " +
                "using the native client.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun StatusRow(status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    item: TranscriptItem,
    onAction: (CardAction) -> Unit,
    onCardSubmit: (String, JsonElement) -> Unit
) {
    val isUser = item.author == TranscriptItem.Author.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isUser) "You" else "Agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (item.text.isNotBlank()) {
                    if (item.isMarkdown) {
                        // Copilot Studio answers are markdown by default, so raw Text would show
                        // literal ** and link syntax.
                        MarkdownText(
                            markdown = item.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    } else {
                        Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                item.attachments.forEach { attachment ->
                    if (attachment.isAdaptiveCard) {
                        AdaptiveCardView(attachment = attachment, onSubmit = onCardSubmit)
                    } else {
                        AttachmentPlaceholder(attachment)
                    }
                }

                if (item.isStreaming) {
                    Text(
                        text = "typing...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (item.suggestedActions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.suggestedActions.forEach { action ->
                    AssistChip(
                        onClick = { onAction(action) },
                        label = { Text(action.title ?: action.submitText) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentPlaceholder(attachment: Attachment) {
    val label = attachment.name ?: attachment.contentType ?: "Attachment"
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("Ask the agent...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onSend,
            enabled = enabled && value.isNotBlank()
        ) {
            Text("Send")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageBubblePreview() {
    DemoAgentSDKTheme {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MessageBubble(
                item = TranscriptItem(
                    author = TranscriptItem.Author.User,
                    text = "Who am I?"
                ),
                onAction = {},
                onCardSubmit = { _, _ -> }
            )
            MessageBubble(
                item = TranscriptItem(
                    author = TranscriptItem.Author.Agent,
                    text = "You are signed in as a **demo user**.",
                    suggestedActions = listOf(
                        CardAction(title = "Tell me more"),
                        CardAction(title = "Start over")
                    )
                ),
                onAction = {},
                onCardSubmit = { _, _ -> }
            )
        }
    }
}
