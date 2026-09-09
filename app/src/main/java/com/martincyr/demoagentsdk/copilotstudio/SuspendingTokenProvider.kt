package com.martincyr.demoagentsdk.copilotstudio

import com.martincyr.demoagentsdk.CopilotStudioTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies bearer tokens to [DirectToEngineClient]. */
fun interface TokenSource {
    suspend fun accessToken(forceRefresh: Boolean): String
}

/**
 * Bridges the callback-based MSAL [CopilotStudioTokenProvider] to coroutines and caches the last
 * token so every turn does not have to round-trip through MSAL. `forceRefresh` drops the cache,
 * which is what the client does after a 401/403.
 *
 * MSAL must be driven from the main thread because interactive sign-in needs the Activity.
 */
class MsalTokenSource(
    private val provider: CopilotStudioTokenProvider
) : TokenSource {
    private val mutex = Mutex()
    private var cachedToken: String? = null

    override suspend fun accessToken(forceRefresh: Boolean): String = mutex.withLock {
        if (forceRefresh) cachedToken = null
        cachedToken?.let { return it }

        val token = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                provider.acquireToken(
                    onSuccess = { token ->
                        if (continuation.isActive) continuation.resume(token)
                    },
                    onError = { message ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException(message))
                        }
                    }
                )
            }
        }
        cachedToken = token
        token
    }
}
