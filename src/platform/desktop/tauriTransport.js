import { dispatch as dispatchService } from './tauriYouTubeService.js';

class TauriTransport {
  constructor(serviceDispatch = dispatchService) {
    this.connected = true;
    this.handlers = new Map();
    this.serviceDispatch = serviceDispatch;
  }

  on(event, callback) {
    if (!this.handlers.has(event)) this.handlers.set(event, new Set());
    this.handlers.get(event).add(callback);
    if (event === 'connect' && this.connected) queueMicrotask(() => callback());
    return this;
  }

  off(event, callback) {
    this.handlers.get(event)?.delete(callback);
    return this;
  }

  emit(event, payload = {}, callback) {
    if (typeof payload === 'function') {
      callback = payload;
      payload = {};
    }
    const authCommands = {
      'auth:status': 'auth_status',
      'auth:login': 'auth_login',
      'auth:logout': 'auth_logout',
      'auth:switch-account': 'auth_switch_account'
    };
    const command = authCommands[event];
    const context = {
      publish: (publishedEvent, publishedPayload) => this.dispatch(publishedEvent, publishedPayload)
    };
    const request = command
      ? globalThis.__TAURI_INTERNALS__.invoke(command).then((session) => this.serviceDispatch({
          event: 'auth:session',
          payload: session
        }, context))
      : this.serviceDispatch({ event, payload }, context);
    request
      .then((data) => callback?.({ ok: true, data }))
      .catch((error) => callback?.({ ok: false, error: String(error) }));
    return this;
  }

  removeAllListeners() {
    this.handlers.clear();
    return this;
  }

  disconnect() {
    if (!this.connected) return this;
    this.connected = false;
    this.dispatch('disconnect');
    return this;
  }

  dispatch(event, payload) {
    this.handlers.get(event)?.forEach((callback) => callback(payload));
  }
}

export function createTauriTransport(serviceDispatch) {
  return new TauriTransport(serviceDispatch);
}
