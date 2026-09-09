# Demo AgentSDK

This Android sample connects to a Microsoft Copilot Studio agent and offers three
client implementations, selected from a screen shown at launch:

- **Android SDK** — the native Compose experience backed by
  `AgentsClientSDK.Android`. It sends the message `Who am I?` and displays the
  agent's response. When authentication and the agent connection are successful,
  the response should contain information about the currently authenticated user.
- **Copilot Studio WebChat** — a web bundle built from
  [`@microsoft/agents-copilotstudio-client`](https://www.npmjs.com/package/@microsoft/agents-copilotstudio-client)
  and rendered in an Android `WebView`. It provides a free-form chat transcript
  with a composer and suggested actions.
- **Native Copilot Studio client** — a Kotlin implementation of the Copilot
  Studio Direct-to-Engine protocol. It acquires tokens with MSAL, connects over
  Server-Sent Events (SSE), parses activity payloads with
  `kotlinx.serialization`, and renders the conversation with Compose.

Use the **< Back** control (or the system back gesture) to return to the
selection screen. The Android SDK is initialized lazily when its mode is entered
and torn down when you go back, so switching modes starts clean.

## Authentication

All modes authenticate natively with the Microsoft Authentication Library
(MSAL); no MSAL.js redirect flow runs inside the `WebView`.

For the **Android SDK** mode, `MainActivity` implements
`AgentsClientSDK.Android`'s `IAuthenticationUI` callbacks and explicitly starts
interactive authentication with:

```kotlin
AgentsClientSDK.signIn(this)
```

For the **WebChat** mode, `CopilotStudioTokenProvider` acquires an access token
for the `https://api.powerplatform.com/.default` scope using a single-account
MSAL client (silent first, falling back to interactive). The token is handed to
the page over a JavaScript bridge, and the page can request a fresh token at any
time when the current one expires.

For the **Native Copilot Studio client** mode, the same
`CopilotStudioTokenProvider` supplies tokens to the Kotlin Direct-to-Engine
client. The client uses MSAL-backed authentication, OkHttp for the SSE
connection, and Compose for the native chat UI.

All paths share the same generated MSAL configuration via `AuthConfigFactory`,
so they use one client ID, tenant, and redirect URI.

Use the **Sign out** button to create a separate single-account MSAL client with
the same configuration as `AgentsClientSDK.Android` and sign out its current
account. This removes the app's cached MSAL account and tokens, resets the
displayed conversation, and reinitializes the Android SDK. It does not clear
Microsoft identity cookies outside the app.

After authentication succeeds, the Android SDK establishes the agent connection
and the app sends its message. The native Copilot Studio client establishes its
own Direct-to-Engine SSE connection and sends the conversation message.

## WebChat bundle

The web client lives in `webchat/` as a small TypeScript project bundled with
esbuild:

```text
webchat/
  build.mjs        esbuild bundling script
  package.json
  src/bridge.ts    JavaScript <-> Kotlin bridge contract
  src/index.ts     Copilot Studio client wiring and transcript rendering
  src/index.html
  src/styles.css
```

The build emits `bundle.js`, `index.html`, and `styles.css` into
`app/src/main/assets/webchat/`, which the `WebView` loads from
`file:///android_asset/webchat/index.html`. Generated assets and
`node_modules/` are excluded by `.gitignore`.

Gradle runs the bundle automatically: the `buildWebChat` task (which depends on
`installWebChat`) is a dependency of `preBuild`, so a normal `assemble` keeps the
assets in sync. Both tasks declare inputs and outputs, so they are skipped when
nothing changed. On Windows the tasks invoke `npm.cmd`. Node.js and npm must be
on the `PATH`.

You can also build the bundle manually:

```powershell
cd webchat
npm install
npm run build      # bundle into app/src/main/assets/webchat
npm run typecheck  # type-check without emitting
```

### Bridge contract

The `WebView` and the host activity communicate through a narrow, explicit
contract defined in `webchat/src/bridge.ts` and `WebChatScreen.kt`:

| Direction     | Call                                   | Purpose                                        |
| ------------- | -------------------------------------- | ---------------------------------------------- |
| Kotlin -> JS  | `window.__androidHost.onConfig(config)` | Delivers `environmentId`, `schemaName`, `environment` |
| Kotlin -> JS  | `window.__androidHost.onToken(token)`   | Delivers a freshly acquired access token       |
| Kotlin -> JS  | `window.__androidHost.onTokenError(msg)`| Reports a failed token acquisition             |
| JS -> Kotlin  | `AndroidBridge.requestToken()`          | Requests a token (callable repeatedly)         |
| JS -> Kotlin  | `AndroidBridge.log(message)`            | Forwards a diagnostic message to logcat        |
| JS -> Kotlin  | `AndroidBridge.onError(message)`        | Surfaces an error in the Compose UI            |

Adaptive Cards in the WebChat mode are handled by the web renderer, not by the
Android Adaptive Cards library used by the native Android modes.

## Configure the Copilot Studio agent

The repository includes a placeholder configuration file:

```text
app/src/main/res/raw/appsettings.json
```

Do not put environment-specific values directly in that file. Instead, copy it
to:

```text
app/src/main/res/raw/appsettings_local.json
```

Fill in the local copy:

```json
{
  "user": {
    "environmentId": "YOUR_COPILOT_STUDIO_ENVIRONMENT_ID",
    "schemaName": "YOUR_AGENT_SCHEMA_NAME",
    "environment": "prod",
    "isAuthEnabled": true,
    "auth": {
      "clientId": "YOUR_ENTRA_APPLICATION_CLIENT_ID",
      "tenantId": "YOUR_ENTRA_TENANT_ID",
      "redirectUri": "http://localhost"
    }
  },
  "speech": {
    "enabled": false,
    "speechSubscriptionKey": "",
    "speechServiceRegion": ""
  }
}
```

The app prefers `appsettings_local.json` when it exists and otherwise falls
back to `appsettings.json`. The local file is excluded by `.gitignore`, so its
values are not committed.

The client ID is for a public client application; do not place client secrets,
passwords, or other confidential credentials in Android resources. Resource
files are packaged into the APK.

## Copilot Studio connector consent bypass

When an agent uses a connector on behalf of a user for the first time, Copilot
Studio normally displays a connector consent card. An administrator can
configure a specific agent to bypass these cards through the Power Platform
API. This is an agent-level administrative setting; the Android app does not
need to fabricate or send a `connectors/consentCard` invoke activity.

See the official Microsoft documentation:
[Bypass connector consent cards for an agent](https://learn.microsoft.com/en-us/microsoft-copilot-studio/admin-connector-consent-bypass).

The documented setup requires:

- A single-tenant Microsoft Entra public client application.
- The delegated Power Platform API permission
  `CopilotStudio.AdminActions.Invoke`.
- A signed-in administrator with the **Power Platform Administrator**,
  **AI Administrator**, or **Global Administrator** role. Power Platform
  Administrator is the least-privileged option in this list.
- The environment ID and agent ID from the agent's Copilot Studio URL.
- Microsoft's `ConsentBypass-CopilotStudio.ps1` script from the linked
  documentation.

After loading the script and running `Connect-CopilotStudioAdmin`, inspect the
current setting with:

```powershell
Get-AdminCopilotStudioBotConnectorConsentBypass `
    -EnvironmentId "YOUR_ENVIRONMENT_ID" `
    -BotId "YOUR_AGENT_ID"
```

Enable consent bypass with:

```powershell
Set-AdminCopilotStudioBotConnectorConsentBypass `
    -EnvironmentId "YOUR_ENVIRONMENT_ID" `
    -BotId "YOUR_AGENT_ID" `
    -BypassConsent $true
```

Restore the normal consent-card behavior by running the same command with
`-BypassConsent $false`. Read the setting again afterward to verify the change.

Consent bypass applies only to agents powered by the Copilot Studio standard
harness. It does not apply to the GitHub Copilot harness. Review the security
and governance implications before enabling it because users will no longer be
shown the normal connector consent card for that agent.

## Run

The app requires Android 8.0 (API 26) or later. Its Speech and Adaptive Cards
dependencies support 16 KB memory page sizes on 64-bit devices. Building also
requires Node.js and npm on the `PATH` for the WebChat bundle.

1. Create `appsettings_local.json` and fill in the required values.
2. Publish the agent in Copilot Studio.
3. Build and run the app from Android Studio. The WebChat bundle is built
   automatically as part of the Gradle build.
4. Pick **Android SDK**, **Copilot Studio WebChat**, or **Native Copilot Studio
   client** on the selection screen.
5. Complete the Microsoft sign-in prompt.
6. Confirm that the app displays information about the currently authenticated
   user.
