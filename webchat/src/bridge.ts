/**
 * Bridge contract shared with the Android host (see WebChatScreen.kt).
 *
 * Kotlin -> JS: window.__androidHost.onConfig(config) / onToken(token) / onTokenError(message)
 * JS -> Kotlin: AndroidBridge.requestToken() / log(message) / onError(message)
 */

export interface HostConfig {
  environmentId: string;
  schemaName: string;
  environment: string;
}

export interface AndroidBridge {
  requestToken(): void;
  log(message: string): void;
  onError(message: string): void;
}

export interface AndroidHost {
  onConfig(config: HostConfig): void;
  onToken(token: string): void;
  onTokenError(message: string): void;
}

declare global {
  interface Window {
    AndroidBridge?: AndroidBridge;
    __androidHost?: AndroidHost;
  }
}

type Resolver<T> = {
  resolve: (value: T) => void;
  reject: (reason: Error) => void;
};

const configWaiters: Resolver<HostConfig>[] = [];
const tokenWaiters: Resolver<string>[] = [];
let cachedConfig: HostConfig | undefined;

export const host: AndroidHost = {
  onConfig(config) {
    cachedConfig = config;
    while (configWaiters.length > 0) {
      configWaiters.shift()!.resolve(config);
    }
  },
  onToken(token) {
    while (tokenWaiters.length > 0) {
      tokenWaiters.shift()!.resolve(token);
    }
  },
  onTokenError(message) {
    const error = new Error(message);
    while (tokenWaiters.length > 0) {
      tokenWaiters.shift()!.reject(error);
    }
  }
};

window.__androidHost = host;

export function log(message: string): void {
  window.AndroidBridge?.log(message);
  console.log(message);
}

export function reportError(message: string): void {
  window.AndroidBridge?.onError(message);
  console.error(message);
}

export function waitForConfig(): Promise<HostConfig> {
  if (cachedConfig) {
    return Promise.resolve(cachedConfig);
  }
  return new Promise<HostConfig>((resolve, reject) => {
    configWaiters.push({ resolve, reject });
  });
}

/**
 * Asks the Android host for a fresh access token. Safe to call repeatedly, so the client can
 * recover when a token expires mid-conversation.
 */
export function requestToken(): Promise<string> {
  const bridge = window.AndroidBridge;
  if (!bridge) {
    return Promise.reject(new Error('Android bridge is unavailable.'));
  }
  return new Promise<string>((resolve, reject) => {
    tokenWaiters.push({ resolve, reject });
    bridge.requestToken();
  });
}
