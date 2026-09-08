import type { Activity } from '@microsoft/agents-activity';
import {
  CopilotStudioClient,
  ConnectionSettings,
  PowerPlatformCloud
} from '@microsoft/agents-copilotstudio-client';
import { HostConfig, log, reportError, requestToken, waitForConfig } from './bridge';

const statusEl = document.getElementById('status') as HTMLDivElement;
const transcriptEl = document.getElementById('transcript') as HTMLDivElement;
const formEl = document.getElementById('composer') as HTMLFormElement;
const inputEl = document.getElementById('composer-input') as HTMLInputElement;
const sendEl = document.getElementById('composer-send') as HTMLButtonElement;

let client: CopilotStudioClient | undefined;
let conversationId: string | undefined;
let settings: ConnectionSettings | undefined;

function setStatus(message: string, isError = false): void {
  statusEl.textContent = message;
  statusEl.classList.toggle('error', isError);
}

function setComposerEnabled(enabled: boolean): void {
  inputEl.disabled = !enabled;
  sendEl.disabled = !enabled;
}

function appendMessage(role: 'user' | 'agent', text: string): HTMLDivElement {
  const item = document.createElement('div');
  item.className = `message ${role}`;

  const roleLabel = document.createElement('span');
  roleLabel.className = 'role';
  roleLabel.textContent = role === 'user' ? 'You' : 'Agent';
  item.appendChild(roleLabel);

  const body = document.createElement('span');
  body.textContent = text;
  item.appendChild(body);

  transcriptEl.appendChild(item);
  transcriptEl.scrollTop = transcriptEl.scrollHeight;
  return item;
}

function renderSuggestedActions(container: HTMLDivElement, activity: Activity): void {
  const actions = activity.suggestedActions?.actions ?? [];
  if (actions.length === 0) {
    return;
  }

  const wrapper = document.createElement('div');
  wrapper.className = 'suggested-actions';
  for (const action of actions) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = action.title ?? String(action.value ?? '');
    button.addEventListener('click', () => {
      wrapper.remove();
      void send(button.textContent ?? '');
    });
    wrapper.appendChild(button);
  }
  container.appendChild(wrapper);
}

function renderActivity(activity: Activity): void {
  if (activity.type !== 'message') {
    return;
  }
  if (activity.conversation?.id) {
    conversationId = activity.conversation.id;
  }

  const text = activity.text ?? '';
  const hasAttachments = (activity.attachments?.length ?? 0) > 0;
  const item = appendMessage('agent', text || (hasAttachments ? '[card attachment]' : ''));
  renderSuggestedActions(item, activity);
}

function buildSettings(config: HostConfig): ConnectionSettings {
  const cloudKey = config.environment
    ? (config.environment.charAt(0).toUpperCase() +
        config.environment.slice(1).toLowerCase())
    : 'Prod';
  const cloud = (PowerPlatformCloud as Record<string, PowerPlatformCloud>)[cloudKey];

  return new ConnectionSettings({
    environmentId: config.environmentId,
    schemaName: config.schemaName,
    cloud: cloud ?? PowerPlatformCloud.Prod
  });
}

/**
 * Rebuilds the client with a freshly acquired token. Called on startup and whenever a request
 * fails in a way that suggests the token has expired.
 */
async function refreshClient(): Promise<CopilotStudioClient> {
  if (!settings) {
    throw new Error('Connection settings are not available yet.');
  }
  const token = await requestToken();
  client = new CopilotStudioClient(settings, token);
  return client;
}

function isAuthFailure(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /401|403|unauthorized|forbidden|token/i.test(message);
}

async function drain(
  run: (activeClient: CopilotStudioClient) => AsyncGenerator<Activity>
): Promise<void> {
  const activeClient = client ?? (await refreshClient());
  try {
    for await (const activity of run(activeClient)) {
      renderActivity(activity);
    }
  } catch (error) {
    if (!isAuthFailure(error)) {
      throw error;
    }
    log('Retrying after a probable token expiry.');
    const retryClient = await refreshClient();
    for await (const activity of run(retryClient)) {
      renderActivity(activity);
    }
  }
}

async function send(text: string): Promise<void> {
  const trimmed = text.trim();
  if (!trimmed || !conversationId) {
    return;
  }

  appendMessage('user', trimmed);
  setComposerEnabled(false);
  setStatus('Waiting for the agent...');

  try {
    await drain((activeClient) =>
      activeClient.sendActivityStreaming(
        { type: 'message', text: trimmed } as Activity,
        conversationId
      )
    );
    setStatus('Connected.');
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    setStatus(message, true);
    reportError(message);
  } finally {
    setComposerEnabled(true);
    inputEl.focus();
  }
}

formEl.addEventListener('submit', (event) => {
  event.preventDefault();
  const value = inputEl.value;
  inputEl.value = '';
  void send(value);
});

async function start(): Promise<void> {
  try {
    setStatus('Waiting for configuration from the host app...');
    const config = await waitForConfig();
    if (!config.environmentId || !config.schemaName) {
      throw new Error(
        'appsettings is missing environmentId or schemaName; configure them before using WebChat.'
      );
    }
    settings = buildSettings(config);

    setStatus('Signing in...');
    await refreshClient();

    setStatus('Starting the conversation...');
    await drain((activeClient) => activeClient.startConversationStreaming(true));

    setStatus('Connected.');
    setComposerEnabled(true);
    inputEl.focus();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    setStatus(message, true);
    reportError(message);
  }
}

void start();
