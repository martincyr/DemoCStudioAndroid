package com.martincyr.demoagentsdk.copilotstudio

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.adaptivecards.objectmodel.ActionType
import io.adaptivecards.objectmodel.AdaptiveCard
import io.adaptivecards.objectmodel.AdaptiveCardParseException
import io.adaptivecards.objectmodel.BaseActionElement
import io.adaptivecards.objectmodel.BaseCardElement
import io.adaptivecards.objectmodel.HostConfig
import io.adaptivecards.objectmodel.OpenUrlAction
import io.adaptivecards.objectmodel.SubmitAction
import io.adaptivecards.renderer.AdaptiveCardRenderer
import io.adaptivecards.renderer.RenderedAdaptiveCard
import io.adaptivecards.renderer.Util
import io.adaptivecards.renderer.actionhandler.ICardActionHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renders an Adaptive Card attachment with the official `adaptivecards-android` renderer.
 *
 * The renderer is a view-based library, so it is hosted in an [AndroidView]. It also needs a real
 * `FragmentManager` — date/time pickers and `Action.ShowCard` are shown as fragments — which is
 * why this requires the host activity to be an [AppCompatActivity].
 */
@Composable
fun AdaptiveCardView(
    attachment: Attachment,
    onSubmit: (displayText: String, value: JsonElement) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? AppCompatActivity
    val cardJson = attachment.content?.toString()

    if (activity == null || cardJson.isNullOrBlank()) {
        FallbackText(attachment.name ?: "Adaptive Card could not be displayed.", modifier)
        return
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        // The card is static for a given attachment, so it is rendered once rather than on every
        // recomposition; re-rendering would discard any input the user has typed into it.
        factory = { viewContext ->
            renderCard(
                context = viewContext,
                cardJson = cardJson,
                fragmentManager = activity.supportFragmentManager,
                handler = CardActionHandler(viewContext, onSubmit)
            )
        }
    )
}

@Composable
private fun FallbackText(message: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = message,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        modifier = modifier
    )
}

private fun renderCard(
    context: Context,
    cardJson: String,
    fragmentManager: androidx.fragment.app.FragmentManager,
    handler: ICardActionHandler
): View = try {
    // VERSION is the supported *schema* version ("1.6"), not the library version.
    val parseResult = AdaptiveCard.DeserializeFromString(cardJson, AdaptiveCardRenderer.VERSION)
    val card = parseResult?.GetAdaptiveCard()
    if (card == null) {
        errorView(context, "The Adaptive Card could not be parsed.")
    } else {
        AdaptiveCardRenderer.getInstance()
            .render(context, fragmentManager, card, handler, HostConfig())
            .view
            ?: errorView(context, "The Adaptive Card could not be rendered.")
    }
} catch (error: AdaptiveCardParseException) {
    errorView(context, "Invalid Adaptive Card: ${error.GetReason()}")
} catch (error: Exception) {
    errorView(context, "Invalid Adaptive Card: ${error.message}")
}

private fun errorView(context: Context, message: String): View =
    TextView(context).apply { text = message }

/**
 * Bridges card actions back into the conversation. Submit actions merge the card's own `data`
 * payload with the values the user entered; open-url actions are handed to the system browser.
 */
private class CardActionHandler(
    private val context: Context,
    private val onSubmit: (displayText: String, value: JsonElement) -> Unit
) : ICardActionHandler {

    override fun onAction(actionElement: BaseActionElement, renderedCard: RenderedAdaptiveCard) {
        when (actionElement.GetElementType()) {
            ActionType.Submit -> handleSubmit(actionElement, renderedCard)
            ActionType.OpenUrl -> handleOpenUrl(actionElement)
            // ShowCard and ToggleVisibility are handled entirely inside the renderer.
            else -> Unit
        }
    }

    private fun handleSubmit(
        actionElement: BaseActionElement,
        renderedCard: RenderedAdaptiveCard
    ) {
        // Inputs are only populated once validation succeeds; bail out so the renderer can show
        // its own validation errors.
        if (!renderedCard.areInputsValid()) return

        val submit = Util.tryCastTo(actionElement, SubmitAction::class.java)
        val payload = buildJsonObject {
            submit?.GetDataJson()
                ?.let { runCatching { CopilotStudioJson.parseToJsonElement(it) }.getOrNull() }
                ?.let { it as? JsonObject }
                ?.forEach { (key, value) -> put(key, value) }
            renderedCard.inputs?.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

        val title = actionElement.GetTitle().orEmpty()
        onSubmit(title, payload)
    }

    private fun handleOpenUrl(actionElement: BaseActionElement) {
        val url = Util.tryCastTo(actionElement, OpenUrlAction::class.java)?.GetUrl()
        if (url.isNullOrBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        }
    }

    override fun onMediaPlay(mediaElement: BaseCardElement, renderedCard: RenderedAdaptiveCard) =
        Unit

    override fun onMediaStop(mediaElement: BaseCardElement, renderedCard: RenderedAdaptiveCard) =
        Unit
}
