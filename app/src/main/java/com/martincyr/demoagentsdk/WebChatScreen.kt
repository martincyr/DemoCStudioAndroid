package com.martincyr.demoagentsdk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.google.gson.Gson
import com.microsoft.agents.client.android.models.AppSettings

private const val WEBCHAT_TAG = "CopilotStudioWebChat"

/**
 * The bundle is served from a real https:// origin rather than file://. A file:// page has an
 * opaque ("null") origin, which browsers reject for the cross-origin fetch/SSE calls the
 * Copilot Studio client makes.
 */
private const val WEBCHAT_DOMAIN = "appassets.androidplatform.net"
private const val WEBCHAT_URL = "https://$WEBCHAT_DOMAIN/assets/webchat/index.html"

/**
 * Hosts the bundled `@microsoft/agents-copilotstudio-client` web experience in a WebView.
 * The page requests tokens through [WebChatBridge]; tokens are acquired natively with MSAL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebChatScreen(
    appSettings: AppSettings,
    tokenProvider: CopilotStudioTokenProvider,
    modifier: Modifier = Modifier
) {
    var bridgeError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        bridgeError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .setDomain(WEBCHAT_DOMAIN)
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()

                val view = WebView(context)
                // Without explicit MATCH_PARENT params a WebView defaults to WRAP_CONTENT, so its
                // height is derived from the page content. The page in turn sizes itself from the
                // viewport, so 100vh/100% collapse to 0 and the transcript is clipped away.
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Allows inspecting this WebView from chrome://inspect on a connected machine.
                if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        target: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(target: WebView, url: String) {
                        target.pushConfig(appSettings)
                    }

                    override fun onReceivedHttpError(
                        target: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        Log.w(
                            WEBCHAT_TAG,
                            "HTTP ${errorResponse.statusCode} for ${request.method} ${request.url}"
                        )
                    }

                    override fun onReceivedError(
                        target: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        Log.w(
                            WEBCHAT_TAG,
                            "Load error ${error.errorCode} ${error.description} for ${request.url}"
                        )
                    }
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(
                            WEBCHAT_TAG,
                            "${message.messageLevel()} ${message.sourceId()}:" +
                                "${message.lineNumber()} ${message.message()}"
                        )
                        return true
                    }
                }
                view.addJavascriptInterface(
                    WebChatBridge(
                        webViewProvider = { view },
                        appSettings = appSettings,
                        tokenProvider = tokenProvider,
                        reportError = { bridgeError = it }
                    ),
                    "AndroidBridge"
                )
                view.loadUrl(WEBCHAT_URL)
                webView = view
                view
            }
        )
    }
}

private fun WebView.pushConfig(appSettings: AppSettings) {
    evaluateJavascript(
        "window.__androidHost && window.__androidHost.onConfig(${configJson(appSettings)});",
        null
    )
}

private fun configJson(appSettings: AppSettings): String {
    val user = appSettings.user
    return Gson().toJson(
        mapOf(
            "environmentId" to user.environmentId,
            "schemaName" to user.schemaName,
            "environment" to user.environment
        )
    )
}

/**
 * JavaScript-callable bridge. Exposed to the page as `window.AndroidBridge`.
 */
class WebChatBridge(
    private val webViewProvider: () -> WebView,
    private val appSettings: AppSettings,
    private val tokenProvider: CopilotStudioTokenProvider,
    private val reportError: (String) -> Unit
) {
    /**
     * Lets the page pull configuration itself, which avoids a race where the script finishes
     * loading before `onPageFinished` pushes the config.
     */
    @JavascriptInterface
    fun getConfig(): String = configJson(appSettings)

    @JavascriptInterface
    fun requestToken() {
        val webView = webViewProvider()
        webView.post {
            tokenProvider.acquireToken(
                onSuccess = { token -> webView.deliver("onToken", token) },
                onError = { message ->
                    reportError(message)
                    webView.deliver("onTokenError", message)
                }
            )
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(WEBCHAT_TAG, message)
    }

    @JavascriptInterface
    fun onError(message: String) {
        webViewProvider().post { reportError(message) }
    }
}

private fun WebView.deliver(callback: String, value: String) {
    post {
        val json = Gson().toJson(value)
        evaluateJavascript(
            "window.__androidHost && window.__androidHost.$callback($json);",
            null
        )
    }
}
