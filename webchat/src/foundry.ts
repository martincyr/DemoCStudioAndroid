interface FoundryConfig {
  projectEndpoint: string;
  agentId: string;
  apiVersion: string;
}

export {};

declare global {
  interface Window {
    __foundryHost?: {
      onConfig(config: FoundryConfig): void;
      onToken(token: string): void;
      onTokenError(message: string): void;
    };
  }
}

const statusEl = document.getElementById('status') as HTMLDivElement;
const transcriptEl = document.getElementById('transcript') as HTMLDivElement;
const formEl = document.getElementById('composer') as HTMLFormElement;
const inputEl = document.getElementById('composer-input') as HTMLInputElement;
const sendEl = document.getElementById('composer-send') as HTMLButtonElement;
let config: FoundryConfig | undefined;
let token: string | undefined;
let threadId: string | undefined;
let tokenWaiter: ((value: string) => void) | undefined;
let tokenError: ((reason: Error) => void) | undefined;

window.__foundryHost = {
  onConfig(value) {
    config = value;
    void start();
  },
  onToken(value) {
    token = value;
    tokenWaiter?.(value);
    tokenWaiter = undefined;
    tokenError = undefined;
  },
  onTokenError(message) {
    tokenError?.(new Error(message));
    tokenWaiter = undefined;
    tokenError = undefined;
  }
};
const initialConfig = window.AndroidBridge?.getConfig?.();
if (initialConfig) {
  try {
    window.__foundryHost.onConfig(JSON.parse(initialConfig) as FoundryConfig);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    setStatus(message, true);
    window.AndroidBridge?.onError(message);
  }
}

function setStatus(value: string, error = false): void {
  statusEl.textContent = value;
  statusEl.classList.toggle('error', error);
}

function append(role: 'user' | 'agent', text: string): HTMLDivElement {
  const item = document.createElement('div');
  item.className = `message ${role}`;
  item.textContent = `${role === 'user' ? 'You' : 'Foundry'}\n${text}`;
  transcriptEl.appendChild(item);
  transcriptEl.scrollTop = transcriptEl.scrollHeight;
  return item;
}

function requestAccessToken(): Promise<string> {
  if (token) return Promise.resolve(token);
  return new Promise((resolve, reject) => {
    tokenWaiter = resolve;
    tokenError = reject;
    window.AndroidBridge?.requestToken();
  });
}

function url(path: string): string {
  if (!config) throw new Error('Foundry configuration is unavailable.');
  return `${config.projectEndpoint.replace(/\/$/, '')}/${path}${path.includes('?') ? '&' : '?'}api-version=${encodeURIComponent(config.apiVersion)}`;
}

async function request(path: string, body: unknown, stream = false): Promise<Response> {
  const response = await fetch(url(path), {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${await requestAccessToken()}`,
      Accept: stream ? 'text/event-stream' : 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body)
  });
  if (!response.ok) throw new Error(`The Foundry request failed with status ${response.status}.`);
  return response;
}

async function start(): Promise<void> {
  try {
    if (!config?.projectEndpoint || !config.agentId) throw new Error('Configure Foundry settings before using this screen.');
    setStatus('Signing in...');
    const response = await request(`agents/${encodeURIComponent(config.agentId)}/endpoint/protocols/openai/conversations`, {});
    const body = await response.json() as { id?: string };
    threadId = body.id;
    if (!threadId) throw new Error('Foundry did not return a thread id.');
    setStatus('Connected.');
    inputEl.disabled = false;
    sendEl.disabled = false;
    inputEl.focus();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    setStatus(message, true);
    window.AndroidBridge?.onError(message);
  }
}

async function send(text: string): Promise<void> {
  const value = text.trim();
  if (!value || !threadId || !config) return;
  append('user', value);
  inputEl.disabled = true;
  sendEl.disabled = true;
  setStatus('Waiting for the agent...');
  try {
    const response = await request(
      `agents/${encodeURIComponent(config.agentId)}/endpoint/protocols/openai/responses`,
      { conversation: threadId, input: [{ role: 'user', content: value }], stream: true },
      true
    );
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Foundry did not return a streaming response.');
    const decoder = new TextDecoder();
    let agentItem: HTMLDivElement | undefined;
    let buffer = '';
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      buffer += decoder.decode(next.value, { stream: true });
      const events = buffer.split('\n\n');
      buffer = events.pop() ?? '';
      for (const event of events) {
        const data = event.split('\n').find(line => line.startsWith('data:'))?.slice(5).trim();
        if (!data || data === '[DONE]') continue;
        const parsed = JSON.parse(data) as { delta?: string | { text?: string }; text?: string };
        const delta = typeof parsed.delta === 'string' ? parsed.delta : parsed.delta?.text ?? parsed.text ?? '';
        if (!delta) continue;
        if (!agentItem) agentItem = append('agent', '');
        agentItem.textContent += delta;
      }
    }
    setStatus('Connected.');
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    setStatus(message, true);
    window.AndroidBridge?.onError(message);
  } finally {
    inputEl.disabled = false;
    sendEl.disabled = false;
    inputEl.focus();
  }
}

formEl.addEventListener('submit', event => {
  event.preventDefault();
  const value = inputEl.value;
  inputEl.value = '';
  void send(value);
});
