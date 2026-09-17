package com.martincyr.demoagentsdk

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
    private var isAndroidSdkVisible by mutableStateOf(false)
    private lateinit var appSettings: AppSettings
    private val tokenProvider by lazy { CopilotStudioTokenProvider(this, appSettings) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appSettings = loadAppSettings(this)
        isAuthenticationEnabled = appSettings.user.isAuthEnabled

        setContent {
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides this@MainActivity
            ) {
                DemoAgentSDKTheme {
                    val navController = rememberNavController()

                    // The Agents SDK screen reports sign-in state through IAuthenticationUI callbacks,
                    // which need to know whether that screen is still on top.
                    LaunchedEffect(navController) {
                        navController.currentBackStackEntryFlow.collect { entry ->
                            isAndroidSdkVisible =
                                entry.destination.hasRoute(AppScreen.AndroidSdk::class)
                        }
                    }

                    Scaffold { innerPadding ->
                        val contentModifier = Modifier.padding(innerPadding)
                        NavHost(
                            navController = navController,
                            startDestination = AppScreen.Selection
                        ) {
                            composable<AppScreen.Selection> {
                                ModeSelectionScreen(
                                    onSelectAndroidSdk = {
                                        prepareAndroidSdk()
                                        navController.navigate(AppScreen.AndroidSdk)
                                    },
                                    onSelectWebChat = { navController.navigate(AppScreen.WebChat) },
                                    onSelectNativeClient = {
                                        navController.navigate(AppScreen.NativeClient)
                                    },
                                    onSignOut = ::clearTokenCache,
                                    isSigningOut = isClearingTokenCache,
                                    modifier = contentModifier
                                )
                            }

                            composable<AppScreen.AndroidSdk> {
                                ScreenWithBack(
                                    onBack = navController::popBackStack,
                                    modifier = contentModifier
                                ) {
                                    WhoAmIResponse(
                                        agentsClientSdk = agentsClientSdk,
                                        initializationError = initializationError,
                                        authenticationError = authenticationError,
                                        isAuthenticationEnabled = isAuthenticationEnabled,
                                        isClearingTokenCache = isClearingTokenCache,
                                        isSignInRequired = isSignInRequired,
                                        isSignInLoading = isSignInLoading,
                                        onSignIn = ::startSignIn,
                                        onClearTokenCache = ::clearTokenCache
                                    )
                                }
                            }

                            composable<AppScreen.WebChat> {
                                ScreenWithBack(
                                    onBack = navController::popBackStack,
                                    modifier = contentModifier
                                ) {
                                    WebChatScreen(
                                        appSettings = appSettings,
                                        tokenProvider = tokenProvider
                                    )
                                }
                            }

                            composable<AppScreen.NativeClient> {
                                ScreenWithBack(
                                    onBack = navController::popBackStack,
                                    modifier = contentModifier
                                ) {
                                    NativeClientScreen(
                                        appSettings = appSettings,
                                        tokenProvider = tokenProvider
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prepareAndroidSdk() {
        if (agentsClientSdk == null) {
            initializationError = null
            initializeAgentsClient(appSettings)
        }
    }

    private fun clearTokenCache() {
        if (isClearingTokenCache) return
        authenticationError = null
        isClearingTokenCache = true

        try {
            PublicClientApplication.createSingleAccountPublicClientApplication(
                this,
                createAuthConfigFile(appSettings),
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        try {
                            application.signOut(
                                object : ISingleAccountPublicClientApplication.SignOutCallback {
                                    override fun onSignOut() {
                                        runOnUiThread {
                                            resetAfterSignOut()
                                        }
                                    }

                                    override fun onError(exception: MsalException) {
                                        showTokenCacheError(exception)
                                    }
                                }
                            )
                        } catch (error: RuntimeException) {
                            showTokenCacheError(error)
                        }
                    }

                    override fun onError(exception: MsalException) {
                        showTokenCacheError(exception)
                    }
                }
            )
        } catch (error: RuntimeException) {
            showTokenCacheError(error)
        }
    }

    private fun resetAfterSignOut() {
        agentsClientSdk = null
        initializationError = null
        hasStartedInteractiveSignIn = false
        isClearingTokenCache = false
        isSignInRequired = false
        isSignInLoading = false
        if (isAndroidSdkVisible) {
            initializeAgentsClient(appSettings)
        }
    }
    private fun initializeAgentsClient(appSettings: AppSettings) {
        try {
            agentsClientSdk = AgentsClientSDK.initSDK(this, appSettings)
        } catch (error: SDKError) {
            initializationError = error.message
        }
    }

    private fun showTokenCacheError(error: Throwable) {
        runOnUiThread {
            isClearingTokenCache = false
            authenticationError = error.localizedMessage
                ?.let { "Failed to clear the MSAL token cache. $it" }
                ?: "Failed to clear the MSAL token cache."
        }
    }

    private fun createAuthConfigFile(appSettings: AppSettings): File =
        AuthConfigFactory.createAuthConfigFile(this, appSettings)

    private fun startSignIn() {
        authenticationError = null
        isSignInRequired = false
        isSignInLoading = true
        hasStartedInteractiveSignIn = true
        AgentsClientSDK.signIn(this)
    }

    override fun showSignInContent() {
        runOnUiThread {
            if (!isAndroidSdkVisible) return@runOnUiThread
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
        val json = context.resources.openRawResource(R.raw.appsettings_local)
            .bufferedReader()
            .use { it.readText() }
        return Gson().fromJson(json, AppSettings::class.java)
    }
}

@Composable
fun ModeSelectionScreen(
    onSelectAndroidSdk: () -> Unit,
    onSelectWebChat: () -> Unit,
    onSelectNativeClient: () -> Unit,
    onSignOut: () -> Unit,
    isSigningOut: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onSignOut,
                enabled = !isSigningOut
            ) {
                Text(if (isSigningOut) "Signing out..." else "Sign out")
            }
        }

        Text(
            text = "Choose a client",
            style = MaterialTheme.typography.headlineSmall
        )

        ModeCard(
            title = "Android SDK",
            description = "Native Compose experience backed by the Agents Client SDK for Android.",
            buttonLabel = "Use Android SDK",
            onClick = onSelectAndroidSdk
        )

        ModeCard(
            title = "Copilot Studio WebChat",
            description = "Web bundle using @microsoft/agents-copilotstudio-client rendered in a " +
                "WebView. The access token is acquired natively with MSAL and passed to the page.",
            buttonLabel = "Use WebChat",
            onClick = onSelectWebChat
        )

        ModeCard(
            title = "Native Copilot Studio client",
            description = "Kotlin implementation of the Direct-to-Engine protocol: MSAL tokens, " +
                "an SSE activity stream parsed with kotlinx.serialization, and native Compose UI.",
            buttonLabel = "Use native client",
            onClick = onSelectNativeClient
        )
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
fun ScreenWithBack(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BackHandler(onBack = onBack)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("< Back")
            }
        }
        content()
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
                    if (connectionReady) {
                        if (message != null) {
                            incomingActivities = incomingActivities + message
                        } else {
                            agentError = "The agent could not answer the request."
                        }
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