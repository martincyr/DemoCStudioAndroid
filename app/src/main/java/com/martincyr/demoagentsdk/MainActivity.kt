package com.martincyr.demoagentsdk

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.martincyr.demoagentsdk.ui.theme.DemoAgentSDKTheme
import com.google.gson.Gson
import com.microsoft.agents.client.android.AgentsClientSDK
import com.microsoft.agents.client.android.exceptions.SDKError
import com.microsoft.agents.client.android.models.AppSettings
import com.microsoft.agents.client.android.models.ChatMessage
import com.microsoft.agents.client.android.models.MessageResponse
import com.microsoft.agents.client.android.sdks.ClientSDK
import com.microsoft.agents.client.android.services.auth.IAuthenticationUI

class MainActivity : AppCompatActivity(), IAuthenticationUI {
    private var agentsClientSdk: ClientSDK? = null
    private var initializationError by mutableStateOf<String?>(null)
    private var authenticationError by mutableStateOf<String?>(null)
    private var isSignInRequired by mutableStateOf(false)
    private var isSignInLoading by mutableStateOf(false)
    private var hasStartedInteractiveSignIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializeAgentsClient()

        setContent {
            DemoAgentSDKTheme {
                Scaffold { innerPadding ->
                    WhoAmIResponse(
                        agentsClientSdk = agentsClientSdk,
                        initializationError = initializationError,
                        authenticationError = authenticationError,
                        isSignInRequired = isSignInRequired,
                        isSignInLoading = isSignInLoading,
                        onSignIn = ::startSignIn,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun initializeAgentsClient() {
        try {
            agentsClientSdk = AgentsClientSDK.initSDK(this, loadAppSettings(this))
        } catch (error: SDKError) {
            initializationError = error.message ?: getString(R.string.sdk_initialization_failed)
        }
    }

    private fun startSignIn() {
        authenticationError = null
        isSignInRequired = false
        isSignInLoading = true
        hasStartedInteractiveSignIn = true
        AgentsClientSDK.signIn(this)
    }

    override fun showSignInContent() {
        runOnUiThread {
            isSignInLoading = false
            isSignInRequired = true
            if (!hasStartedInteractiveSignIn) {
                startSignIn()
            }
        }
    }

    override fun hideSignInContent() {
        runOnUiThread {
            isSignInRequired = false
        }
    }

    override fun showSignInLoading() {
        runOnUiThread {
            isSignInLoading = true
        }
    }

    override fun hideSignInLoading() {
        runOnUiThread {
            isSignInLoading = false
        }
    }

    override fun showToast(msg: String) {
        runOnUiThread {
            authenticationError = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadAppSettings(context: Context): AppSettings {
        val localResourceId = context.resources.getIdentifier(
            LOCAL_APP_SETTINGS_RESOURCE,
            "raw",
            context.packageName
        )
        val resourceId = localResourceId.takeIf { it != 0 }
            ?: R.raw.appsettings
        val json = context.resources.openRawResource(resourceId)
            .bufferedReader()
            .use { it.readText() }
        return Gson().fromJson(json, AppSettings::class.java)
    }
}

@Composable
fun WhoAmIResponse(
    agentsClientSdk: ClientSDK?,
    initializationError: String?,
    authenticationError: String?,
    isSignInRequired: Boolean,
    isSignInLoading: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    var promptSent by remember(agentsClientSdk) { mutableStateOf(false) }
    var incomingActivities by remember {
        mutableStateOf(emptyList<ChatMessage>())
    }
    var agentError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(agentsClientSdk) {
        agentsClientSdk?.liveData?.collect { response ->
            when (response) {
                MessageResponse.ConnectionReady -> {
                    if (!promptSent) {
                        promptSent = true
                        agentsClientSdk.sendMessage(WHO_AM_I_PROMPT)
                    }
                }

                is MessageResponse.Success<*> -> {
                    val message = response.value as? ChatMessage
                    if (message != null) {
                        incomingActivities = incomingActivities + message
                    }
                }

                is MessageResponse.Failure<*> -> {
                    val message = response.value as? ChatMessage
                    if (message != null) {
                        incomingActivities = incomingActivities + message
                    } else {
                        agentError = "The agent could not answer the request."
                    }
                }

                is MessageResponse.Error -> {
                    agentError = response.exception.message ?: "The request failed."
                }

                MessageResponse.Initial,
                MessageResponse.Loading,
                is MessageResponse.Typing<*> -> Unit
            }
        }
    }

    Card(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Who am I?",
                style = MaterialTheme.typography.headlineSmall
            )

            when {
                initializationError != null -> Text(
                    text = initializationError,
                    color = MaterialTheme.colorScheme.error
                )

                isSignInLoading -> {
                    CircularProgressIndicator()
                    Text("Signing in with Microsoft...")
                }

                isSignInRequired -> {
                    authenticationError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(onClick = onSignIn) {
                        Text("Sign in with Microsoft")
                    }
                }

                agentError != null -> Text(
                    text = agentError.orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )

                incomingActivities.isNotEmpty() -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(incomingActivities) { index, activity ->
                        IncomingActivity(activity)
                        if (index < incomingActivities.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }

                else -> {
                    CircularProgressIndicator()
                    Text("Waiting for the Copilot Studio agent...")
                }
            }
        }
    }
}

@Composable
private fun IncomingActivity(activity: ChatMessage) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = activity.role.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        if (activity.text.isNotBlank()) {
            Text(
                text = activity.text,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        activity.customView?.let { customView ->
            AndroidView(
                factory = { customView },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (activity.text.isBlank() && activity.customView == null) {
            Text(
                text = "Activity contained no displayable content.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WhoAmIResponsePreview() {
    DemoAgentSDKTheme {
        WhoAmIResponse(
            agentsClientSdk = null,
            initializationError = "Configure the Copilot Studio agent to load a response.",
            authenticationError = null,
            isSignInRequired = false,
            isSignInLoading = false,
            onSignIn = {}
        )
    }
}

private const val WHO_AM_I_PROMPT = "Who am I?"
private const val LOCAL_APP_SETTINGS_RESOURCE = "appsettings_local"