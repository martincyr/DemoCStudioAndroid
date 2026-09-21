package com.martincyr.demoagentsdk

import android.app.Activity
import com.microsoft.agents.client.android.models.AppSettings
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Acquires Copilot Studio access tokens natively with MSAL so the WebView never has to run
 * an MSAL.js redirect flow. Tokens are handed to the web bundle over the JavaScript bridge.
 *
 * In single-account mode MSAL rejects any request that carries an explicit account when it does
 * not match the persisted current account (`current_account_mismatch`). This provider therefore
 * uses the account-less single-account overloads and lets MSAL resolve the signed-in account.
 */
class CopilotStudioTokenProvider(
    private val activity: Activity,
    private val appSettings: AppSettings
) {
    private var application: ISingleAccountPublicClientApplication? = null

    private val authority: String
        get() {
            val tenantId = appSettings.user.auth.tenantId.ifBlank { "common" }
            return "https://login.microsoftonline.com/$tenantId"
        }

    fun acquireToken(onSuccess: (String) -> Unit, onError: (String) -> Unit) =
        acquireToken(DEFAULT_SCOPES, onSuccess, onError)

    fun acquireToken(
        scopes: List<String> = DEFAULT_SCOPES,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val existing = application
        if (existing != null) {
            resolveAccount(existing, scopes, onSuccess, onError)
            return
        }

        PublicClientApplication.createSingleAccountPublicClientApplication(
            activity,
            AuthConfigFactory.createAuthConfigFile(activity, appSettings),
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(app: ISingleAccountPublicClientApplication) {
                    application = app
                    resolveAccount(app, scopes, onSuccess, onError)
                }

                override fun onError(exception: MsalException) {
                    onError(exception.describe())
                }
            }
        )
    }

    private fun resolveAccount(
        app: ISingleAccountPublicClientApplication,
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val dispatched = AtomicBoolean(false)
        fun dispatchOnce(account: IAccount?) {
            if (dispatched.compareAndSet(false, true)) {
                dispatch(app, account, scopes, onSuccess, onError)
            }
        }

        app.getCurrentAccountAsync(
            object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    dispatchOnce(activeAccount)
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                    dispatchOnce(currentAccount)
                }

                override fun onError(exception: MsalException) {
                    if (dispatched.compareAndSet(false, true)) {
                        onError(exception.describe())
                    }
                }
            }
        )
    }

    private fun dispatch(
        app: ISingleAccountPublicClientApplication,
        account: IAccount?,
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (account == null) {
            signIn(app, scopes, onSuccess, onError)
        } else {
            acquireSilent(app, account, scopes, onSuccess, onError)
        }
    }

    private fun acquireSilent(
        app: ISingleAccountPublicClientApplication,
        account: IAccount,
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val parameters = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(account.authority)
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    onSuccess(authenticationResult.accessToken)
                }

                override fun onError(exception: MsalException) {
                    // No cached token covers this scope; re-prompt for the signed-in account.
                    signInAgain(app, scopes, onSuccess, onError)
                }
            })
            .build()

        app.acquireTokenSilentAsync(parameters)
    }

    private fun signIn(
        app: ISingleAccountPublicClientApplication,
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        app.signIn(signInParameters(scopes, onSuccess, onError))
    }

    private fun signInAgain(
        app: ISingleAccountPublicClientApplication,
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        app.signInAgain(signInParameters(scopes, onSuccess, onError))
    }

    private fun signInParameters(
        scopes: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ): SignInParameters = SignInParameters.builder()
        .withActivity(activity)
        .withScopes(scopes)
        .withCallback(authCallback(onSuccess, onError))
        .build()

    private fun authCallback(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) = object : AuthenticationCallback {
        override fun onSuccess(authenticationResult: IAuthenticationResult) {
            onSuccess(authenticationResult.accessToken)
        }

        override fun onError(exception: MsalException) {
            onError(exception.describe())
        }

        override fun onCancel() {
            onError("Sign-in was cancelled.")
        }
    }

    private fun MsalException.describe(): String =
        localizedMessage ?: message ?: "Failed to acquire a Copilot Studio access token."

    private companion object {
        val DEFAULT_SCOPES = listOf("https://api.powerplatform.com/.default")
    }
}
