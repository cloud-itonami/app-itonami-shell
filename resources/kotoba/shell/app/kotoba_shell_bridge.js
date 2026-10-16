// kotoba-shell in-app provider bridge — the JavaScript half.
//
// This is what makes a shipped .ipa/.apk able to reach native capabilities.
// The CLI's `native-host provider` path runs providers on a *developer's*
// machine (`xcrun simctl spawn`, `adb shell`); nothing in it survives into a
// distributed build. This shim talks to a provider implementation compiled
// into the app itself.
//
// Injected before any page script runs:
//   - Apple: WKUserScript at .atDocumentStart
//   - Android: WebViewCompat.addDocumentStartJavaScript, falling back to
//     onPageStarted on the bundled app origin when the provider is too old.
//
// The two platforms differ only in transport. Apple's
// WKScriptMessageHandlerWithReply already returns a promise from
// postMessage. Android's origin-restricted WebMessageListener uses a request
// id and replies to the originating frame. Callers see one API either way.
(function () {
  "use strict";
  // Android injects origin-scoped scripts into matching frames. The native
  // listener also rejects subframes; do not expose a facade that would hang.
  if (window.top !== window) { return; }
  if (window.kotobaShell) { return; }

  var SCHEMA = "kotoba.shell.bridge.v0";
  var pending = Object.create(null);
  var counter = 0;

  function unavailable(command) {
    return Promise.resolve({
      schema: SCHEMA,
      command: command,
      ok: false,
      error: "bridge-unavailable",
      // Deliberately not thrown: a web bundle that also runs in a plain
      // browser (`amu compile --target wasm32-browser`, a Pages preview) should be able to
      // branch on this rather than crash.
      detail: "no kotoba-shell native host is attached to this WebView"
    });
  }

  window.__kotobaShellDeliver = function (id, payload) {
    var entry = pending[id];
    if (!entry) { return; }
    delete pending[id];
    try {
      entry.resolve(JSON.parse(payload));
    } catch (err) {
      entry.resolve({ schema: SCHEMA, ok: false, error: "malformed-reply", detail: String(err) });
    }
  };

  function appleInvoke(command, args) {
    return window.webkit.messageHandlers.kotobaShell
      .postMessage({ command: command, args: args || {} })
      .then(function (reply) { return reply; })
      .catch(function (err) {
        return { schema: SCHEMA, command: command, ok: false, error: "bridge-error", detail: String(err) };
      });
  }

  function androidInvoke(command, args) {
    var id = "req-" + (++counter);
    return new Promise(function (resolve) {
      pending[id] = { resolve: resolve };
      try {
        window.KotobaShellNative.postMessage(JSON.stringify({
          id: id,
          command: command,
          args: args || {}
        }));
      } catch (err) {
        delete pending[id];
        resolve({ schema: SCHEMA, command: command, ok: false, error: "bridge-error", detail: String(err) });
      }
    });
  }

  function installAndroidReplyHandler(nativeBridge) {
    nativeBridge.onmessage = function (event) {
      try {
        var response = JSON.parse(event.data);
        if (!response || typeof response.id !== "string" || !response.payload) { return; }
        window.__kotobaShellDeliver(response.id, JSON.stringify(response.payload));
      } catch (_) {
        // Ignore malformed native replies; the bridge contract never rejects.
      }
    };
  }

  var transport = null;
  if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.kotobaShell) {
    transport = appleInvoke;
  } else if (window.KotobaShellNative && typeof window.KotobaShellNative.postMessage === "function") {
    installAndroidReplyHandler(window.KotobaShellNative);
    transport = androidInvoke;
  }

  window.kotobaShell = {
    schema: SCHEMA,
    available: transport !== null,
    // Substituted at scaffold time instead of read back from the native
    // transport, which intentionally exposes only message delivery.
    target: "{{TARGET}}",

    // invoke(command, args) -> Promise<{ok, value, audit, ...}>
    //
    // Never rejects. A denied command, a missing provider and a provider
    // failure all resolve with ok:false and a reason, because the caller has
    // to handle "the policy says no" anyway and a thrown exception makes that
    // path easy to forget.
    invoke: function (command, args) {
      if (!transport) { return unavailable(command); }
      return transport(command, args);
    }
  };
})();
