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
import androidx.compose.foundation.layout.Row
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
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import java.io.File

class MainActivity : AppCompatActivity(), IAuthenticationUI {
    private var agentsClientSdk by mutableStateOf<ClientSDK?>(null)
    private var initializationError by mutableStateOf<String?>(null)
    private var authenticationError by mutableStateOf<String?>(null)
    private var isAuthenticationEnabled by mutableStateOf(false)
    private var isClearingTokenCache by mutableStateOf(false)
    private var isSignInRequired by mutableStateOf(false)
    private var isSignInLoading by mutableStateOf(false)
    private var hasStartedInteractiveSignIn = false
    private lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appSettings = loadAppSettings(this)
        isAuthenticationEnabled = appSettings.user.isAuthEnabled
        initializeAgentsClient(appSettings)

        setContent {
            DemoAgentSDKTheme {
                Scaffold { innerPadding ->
                    WhoAmIResponse(
                        agentsClientSdk = agentsClientSdk,
                        initializationError = initializationError,
                        authenticationError = authenticationError,
                        isAuthenticationEnabled = isAuthenticationEnabled,
                        isClearingTokenCache = isClearingTokenCache,
                        isSignInRequired = isSignInRequired,
                        isSignInLoading = isSignInLoading,
                        onSignIn = ::startSignIn,
                        onClearTokenCache = ::clearTokenCache,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun clearTokenCache() {
        authenticationError = null
        isClearingTokenCache = true
        PublicClientApplication.createSingleAccountPublicClientApplication(
            this,
            createAuthConfigFile(appSettings),
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    application.signOut(
                        object : ISingleAccountPublicClientApplication.SignOutCallback {
                            override fun onSignOut() {
                                runOnUiThread {
                                    agentsClientSdk = null
                                    hasStartedInteractiveSignIn = false
                                    isClearingTokenCache = false
                                    isSignInRequired = false
                                    initializeAgentsClient(appSettings)
                                }
                            }

                            override fun onError(exception: MsalException) {
                                showTokenCacheError(exception)
                            }
                        }
                    )
                }

                override fun onError(exception: MsalException) {
                    showTokenCacheError(exception)
                }
            }
        )
    }

    private fun initializeAgentsClient(appSettings: AppSettings) {
        try {
            agentsClientSdk = AgentsClientSDK.initSDK(this, appSettings)
        } catch (error: SDKError) {
            initializationError = error.message
        }
    }

    private fun showTokenCacheError(exception: MsalException) {
        runOnUiThread {
            isClearingTokenCache = false
            authenticationError = exception.localizedMessage
                ?.let { "Failed to clear the MSAL token cache. $it" }
                ?: "Failed to clear the MSAL token cache."
        }
    }

    private fun createAuthConfigFile(appSettings: AppSettings): File {
        val auth = appSettings.user.auth
        val tenantId = auth.tenantId.ifBlank { "common" }
        val config: Map<String, Any> = mapOf(
            "client_id" to auth.clientId,
            "authorization_user_agent" to "WEBVIEW",
            "redirect_uri" to auth.redirectUri,
            "account_mode" to "SINGLE",
            "broker_redirect_uri_registered" to true,
            "authorities" to listOf(
                mapOf(
                    "type" to "AAD",
                    "authority_url" to "https://login.microsoftonline.com/$tenantId",
                    "audience" to mapOf(
                        "type" to "AzureADandPersonalMicrosoftAccount",
                        "tenant_id" to tenantId
                    )
                )
            )
        )

        return File(cacheDir, AUTH_CONFIG_FILE_NAME).apply {
            writeText(Gson().toJson(config))
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
    isAuthenticationEnabled: Boolean,
    isClearingTokenCache: Boolean,
    isSignInRequired: Boolean,
    isSignInLoading: Boolean,
    onSignIn: () -> Unit,
    onClearTokenCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var promptSent by remember(agentsClientSdk) { mutableStateOf(false) }
    var connectionReady by remember(agentsClientSdk) { mutableStateOf(false) }
    var incomingActivities by remember {
        mutableStateOf(emptyList<ChatMessage>())
    }
    var agentError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(agentsClientSdk) {
        agentsClientSdk?.liveData?.collect { response ->
            when (response) {
                MessageResponse.ConnectionReady -> {
                    connectionReady = true
                    incomingActivities = emptyList()
                    agentError = null
                    if (!promptSent) {
                        promptSent = true
                        agentsClientSdk.sendMessage(WHO_AM_I_PROMPT)
                    }
                }

                is MessageResponse.Success<*> -> {
                    val message = response.value as? ChatMessage
                    if (connectionReady && message != null) {
                        incomingActivities = incomingActivities + message
                    }
                }

                is MessageResponse.Failure<*> -> {
                    val message = response.value as? ChatMessage
                    if (!connectionReady) {
                        Unit
                    } else if (message != null) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Who am I?",
                    style = MaterialTheme.typography.headlineSmall
                )

                if (isAuthenticationEnabled) {
                    Button(
                        onClick = onClearTokenCache,
                        enabled = !isClearingTokenCache
                    ) {
                        Text(
                            if (isClearingTokenCache) {
                                "Signing out..."
                            } else {
                                "Sign out"
                            }
                        )
                    }
                }
            }

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
            isAuthenticationEnabled = true,
            isClearingTokenCache = false,
            isSignInRequired = false,
            isSignInLoading = false,
            onSignIn = {},
            onClearTokenCache = {}
        )
    }
}

private const val WHO_AM_I_PROMPT = "Who am I?"
private const val LOCAL_APP_SETTINGS_RESOURCE = "appsettings_local"
private const val AUTH_CONFIG_FILE_NAME = "auth_config_temp.json"