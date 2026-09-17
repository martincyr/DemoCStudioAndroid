package com.martincyr.demoagentsdk

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.martincyr.demoagentsdk.copilotstudio.AdaptiveCardView
import com.martincyr.demoagentsdk.copilotstudio.ActivityMarker
import com.martincyr.demoagentsdk.copilotstudio.Attachment
import com.martincyr.demoagentsdk.copilotstudio.CardAction
import com.martincyr.demoagentsdk.copilotstudio.ConnectionState
import com.martincyr.demoagentsdk.copilotstudio.CopilotStudioConnection
import com.martincyr.demoagentsdk.copilotstudio.DirectToEngineClient
import com.martincyr.demoagentsdk.copilotstudio.Activity
import com.martincyr.demoagentsdk.copilotstudio.ActivityTypes
import com.martincyr.demoagentsdk.copilotstudio.MsalTokenSource
import com.martincyr.demoagentsdk.copilotstudio.NativeChatViewModel
import com.martincyr.demoagentsdk.copilotstudio.PowerPlatformCloud
import com.martincyr.demoagentsdk.copilotstudio.TranscriptItem
import com.martincyr.demoagentsdk.ui.theme.DemoAgentSDKTheme
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.microsoft.agents.client.android.models.AppSettings
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

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

    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var selectedActivityMarker by remember { mutableStateOf<ActivityMarker?>(null) }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = pendingPhotoUri
        pendingPhotoUri = null
        if (!captured || uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) {
                    context.imageAttachment(uri)
                }
                actionError = null
                chat.sendMessages(
                    Activity(
                        type = ActivityTypes.MESSAGE,
                        text = draft,
                        attachments = listOf(attachment)
                    )
                )
                draft = ""
            } catch (error: Exception) {
                actionError = error.message ?: "Could not send the captured photo."
            }
        }
    }
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
        actionError?.let { message ->
            ErrorBanner(message = message, onRetry = { actionError = null })
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
                        onAction = {
                            chat.sendMessages(
                                Activity(type = ActivityTypes.MESSAGE, text = it.submitText)
                            )
                        },
                        onCardSubmit = { displayText, value ->
                            chat.sendMessages(
                                Activity(
                                    type = ActivityTypes.MESSAGE,
                                    text = displayText,
                                    value = value
                                )
                            )
                        },
                        onActivityJson = { selectedActivityMarker = it }
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
            onTakePicture = {
                try {
                    val uri = context.createImageCaptureUri()
                    actionError = null
                    pendingPhotoUri = uri
                    takePicture.launch(uri)
                } catch (error: IllegalArgumentException) {
                    actionError = error.message ?: "Could not open the camera."
                }
            },
            onScanBarcode = {
                GmsBarcodeScanning.getClient(context)
                    .startScan()
                    .addOnSuccessListener { barcode ->
                        val rawValue = barcode.rawValue
                        if (rawValue.isNullOrBlank()) {
                            actionError = "The barcode did not contain text."
                        } else {
                            actionError = null
                            draft = draft.withPastedBarcode(rawValue)
                        }
                    }
                    .addOnCanceledListener {
                        actionError = null
                    }
                    .addOnFailureListener { error ->
                        actionError = error.message ?: "Could not scan the barcode."
                    }
            },
            onSend = {
                chat.sendMessages(Activity(type = ActivityTypes.MESSAGE, text = draft))
                draft = ""
            }
        )
    }

    selectedActivityMarker?.let { marker ->
        ActivityJsonDialog(
            marker = marker,
            onDismiss = { selectedActivityMarker = null }
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
    onCardSubmit: (String, JsonElement) -> Unit,
    onActivityJson: (ActivityMarker) -> Unit
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

                if (item.activityMarkers.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.activityMarkers.forEach { marker ->
                            AssistChip(
                                onClick = { onActivityJson(marker) },
                                label = { Text(marker.label) }
                            )
                        }
                    }
                }

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
private fun ActivityJsonDialog(
    marker: ActivityMarker,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activity JSON") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = marker.json,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
    onTakePicture: () -> Unit,
    onScanBarcode: () -> Unit,
    onSend: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

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
        Box {
            Button(
                onClick = { expanded = true },
                enabled = enabled
            ) {
                Text("Actions")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Send message") },
                    enabled = value.isNotBlank(),
                    onClick = {
                        expanded = false
                        onSend()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Take photo") },
                    onClick = {
                        expanded = false
                        onTakePicture()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Scan barcode") },
                    onClick = {
                        expanded = false
                        onScanBarcode()
                    }
                )
            }
        }
    }
}

private fun Context.createImageCaptureUri(): Uri {
    val directory = File(cacheDir, "captured_images").apply { mkdirs() }
    val file = File(directory, "agent-photo-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
}

private fun Context.imageAttachment(uri: Uri): Attachment {
    val bytes = checkNotNull(contentResolver.openInputStream(uri)) {
        "Could not open captured image."
    }.use { it.readBytes() }
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return Attachment(
        contentType = IMAGE_JPEG_CONTENT_TYPE,
        contentUrl = "data:$IMAGE_JPEG_CONTENT_TYPE;base64,$base64",
        name = "agent-photo.jpg"
    )
}

private fun String.withPastedBarcode(barcode: String): String =
    if (isBlank()) barcode else "$this $barcode"

private const val IMAGE_JPEG_CONTENT_TYPE = "image/jpeg"

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
                onCardSubmit = { _, _ -> },
                onActivityJson = {}
            )
            MessageBubble(
                item = TranscriptItem(
                    author = TranscriptItem.Author.Agent,
                    text = "You are signed in as a **demo user**.",
                    suggestedActions = listOf(
                        CardAction(title = "Tell me more"),
                        CardAction(title = "Start over")
                    ),
                    activityMarkers = listOf(
                        ActivityMarker(label = "message", json = "{\n  \"type\": \"message\"\n}")
                    )
                ),
                onAction = {},
                onCardSubmit = { _, _ -> },
                onActivityJson = {}
            )
        }
    }
}
