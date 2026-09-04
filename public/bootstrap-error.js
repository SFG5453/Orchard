(function installBootstrapErrorHandler() {
  function errorText(value) {
    if (value instanceof Error) return value.stack || value.message;
    if (value && value.reason instanceof Error) return value.reason.stack || value.reason.message;
    return String((value && (value.reason || value.message)) || value || 'Unknown renderer startup error');
  }

  function report(value) {
    var message = errorText(value);
    var internals = window.__TAURI_INTERNALS__;
    if (internals) internals.invoke('renderer_error', { message: message }).catch(function ignore() {});
    window.setTimeout(function showError() {
      var root = document.querySelector('#app');
      if (!root || root.childElementCount) return;
      document.documentElement.style.background = '#090b09';
      document.body.style.margin = '0';
      root.style.cssText = 'box-sizing:border-box;min-height:100vh;padding:32px;color:#f1f5f2;font:14px/1.5 monospace;white-space:pre-wrap';
      root.textContent = 'Orchard could not start\n\n' + message;
    }, 0);
  }

  window.addEventListener('error', report);
  window.addEventListener('unhandledrejection', report);
}());
