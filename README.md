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

If MSAL already has an account, the SDK attempts to acquire a token silently.
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

## Run

1. Create `appsettings_local.json` and fill in the required values.
2. Publish the agent in Copilot Studio.
3. Build and run the app from Android Studio.
4. Complete the Microsoft sign-in prompt.
5. Confirm that the app displays information about the currently authenticated
   user.
