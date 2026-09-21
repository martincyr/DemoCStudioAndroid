package com.martincyr.demoagentsdk

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.google.gson.Gson

private const val FOUNDRY_TAG = "FoundryWebChat"
private const val FOUNDRY_DOMAIN = "appassets.androidplatform.net"
private const val FOUNDRY_URL = "https://$FOUNDRY_DOMAIN/assets/foundry/index.html"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FoundryWebChatScreen(
    configuration: AppConfiguration,
    tokenProvider: CopilotStudioTokenProvider,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(FOUNDRY_DOMAIN)
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ) = assetLoader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        evaluateJavascript(
                            "window.__foundryHost && window.__foundryHost.onConfig(${Gson().toJson(configuration.foundry)});",
                            null
                        )
                    }
                }
                addJavascriptInterface(
                    FoundryWebBridge(this, configuration, tokenProvider),
                    "AndroidBridge"
                )
                loadUrl(FOUNDRY_URL)
                webView = this
            }
        }
    )
}

private class FoundryWebBridge(
    private val webView: WebView,
    private val configuration: AppConfiguration,
    private val tokenProvider: CopilotStudioTokenProvider
) {
    @JavascriptInterface
    fun getConfig(): String = Gson().toJson(configuration.foundry)

    @JavascriptInterface
    fun requestToken() {
        webView.post {
            tokenProvider.acquireToken(
                scopes = listOf(configuration.foundry.scope),
                onSuccess = { webView.deliver("onToken", it) },
                onError = { webView.deliver("onTokenError", it) }
            )
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(FOUNDRY_TAG, message)
    }

    @JavascriptInterface
    fun onError(message: String) {
        Log.e(FOUNDRY_TAG, message)
    }
}

private fun WebView.deliver(callback: String, value: String) {
    post {
        evaluateJavascript(
            "window.__foundryHost && window.__foundryHost.$callback(${Gson().toJson(value)});",
            null
        )
    }
}
