# Demo AgentSDK

This Android sample connects to a Microsoft Copilot Studio agent through the
Microsoft Agents Client SDK. After authentication and connection, the app sends
the message `Who am I?` and displays the agent's response. When authentication
and the agent connection are successful, the response should contain
information about the currently authenticated user.

## Authentication

Authentication is provided by the Microsoft Authentication Library (MSAL).
`MainActivity` implements the SDK's `IAuthenticationUI` callbacks and explicitly
starts interactive authentication with:

```kotlin
AgentsClientSDK.signIn(this)
```

Use the **Sign out** button to create a separate single-account MSAL
client with the same configuration as the Agents Client SDK and sign out its
current account. This removes the app's cached MSAL account and tokens, resets
the displayed conversation, and reinitializes the SDK. It does not clear
Microsoft identity cookies outside the app.

After authentication succeeds, the Agents Client SDK establishes the agent
connection and the app sends its message.

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
dependencies support 16 KB memory page sizes on 64-bit devices.

1. Create `appsettings_local.json` and fill in the required values.
2. Publish the agent in Copilot Studio.
3. Build and run the app from Android Studio.
4. Complete the Microsoft sign-in prompt.
5. Confirm that the app displays information about the currently authenticated
   user.
