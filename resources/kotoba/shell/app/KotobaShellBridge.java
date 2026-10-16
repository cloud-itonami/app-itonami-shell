package {{PACKAGE}};

// kotoba-shell in-app provider bridge — the Android half.
//
// Counterpart to KotobaShellBridge.swift. `bin/kotoba-shell-host-android`
// reaches providers with `adb shell`, which is a developer's machine talking
// to an attached device; a distributed .apk has no adb. These providers are
// compiled into the app.
//
// WebMessageListener provides the caller's origin and a reply proxy scoped to
// that frame. Requests are handed to an executor; the JS shim hides the
// transport difference from Apple's WKScriptMessageHandlerWithReply.
//
// The policy decision below matches kotoba.shell.launcher/policy-decision and
// KotobaShellBridge.swift clause for clause. A missing policy asset denies
// everything.

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public final class KotobaShellBridge {

    public static final String INTERFACE_NAME = "KotobaShellNative";
    private static final String SCHEMA = "kotoba.shell.bridge.v0";
    private static final String KEY_ALIAS = "kotoba-shell.keychain";
    private static final String KEYCHAIN_PREFS = "{{KEYCHAIN_PREFS}}";
    private static final String DATA_DIR = "{{DATA_DIR}}";
    private static final String NOTIFICATION_CHANNEL = "kotoba-shell";
    public static final String AUTHORIZATION_COMMAND = "auth/open-authorization-url";
    /**
     * The redirect scheme, from :android/oauth-callback-scheme -- never from
     * the page. Empty means not configured; the command then refuses rather
     * than waiting on a redirect no intent-filter would deliver.
     */
    public static final String OAUTH_CALLBACK_SCHEME = "{{OAUTH_CALLBACK_SCHEME}}";

    /**
     * Opens an authorization URL outside the WebView. Implemented by the
     * generated KotobaShellAuthCallbackActivity.CustomTabsLauncher, which
     * exists (with its androidx.browser dependency) only when the manifest
     * declares a scheme -- so this file compiles either way.
     */
    public interface AuthorizationLauncher {
        void launch(Activity activity, Uri uri);
    }

    // The redirect arrives in a different Activity than the one that owns the
    // WebView, so the receiving side finds the bridge through this. Weak: the
    // bridge must not outlive its Activity because a sign-in was pending.
    private static volatile WeakReference<KotobaShellBridge> active = new WeakReference<>(null);

    private final Activity activity;
    private final Set<String> allow = new HashSet<>();
    private final Set<String> deny = new HashSet<>();
    private final JSONObject capabilities;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, JavaScriptReplyProxy> pendingReplies = new ConcurrentHashMap<>();
    private AuthorizationLauncher authorizationLauncher;
    // One sign-in at a time: the request id waiting for a redirect, and
    // whether the browser has actually been launched for it (only then does
    // returning to the app without a redirect mean "cancelled").
    private String pendingAuthorizationId;
    private boolean authorizationLaunched;

    public KotobaShellBridge(Activity activity, JSONObject policy) {
        this.activity = activity;
        this.capabilities = policy.optJSONObject("capabilities") != null
                ? policy.optJSONObject("capabilities")
                : new JSONObject();
        readTokens(policy, "allow", allow);
        readTokens(policy, "deny", deny);
        active = new WeakReference<>(this);
    }

    public void setAuthorizationLauncher(AuthorizationLauncher launcher) {
        this.authorizationLauncher = launcher;
    }

    private static void readTokens(JSONObject policy, String key, Set<String> into) {
        org.json.JSONArray array = policy.optJSONArray(key);
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            String token = array.optString(i, null);
            if (token != null) {
                into.add(token);
            }
        }
    }

    /**
     * Reads the scaffolded policy asset. A packaging mistake must fail closed:
     * an unreadable policy yields an empty allow set, not an open one.
     */
    public static JSONObject loadPolicy(Context context) {
        try (InputStream in = context.getAssets().open("kotoba-shell-policy.json")) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The document-start shim, read from assets so both halves share one source. */
    public static String shimSource(Context context) {
        try (InputStream in = context.getAssets().open("kotoba-shell-bridge.js")) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    // ---- transport -------------------------------------------------------

    public void request(final String requestJson, final JavaScriptReplyProxy replyProxy) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject request;
                try {
                    request = new JSONObject(requestJson);
                } catch (Exception e) {
                    postResponse(replyProxy, "", envelope("", false, null, "malformed-request", denial("")));
                    return;
                }
                String id = request.optString("id", "");
                String command = request.optString("command", "");
                Object rawArgs = request.opt("args");
                JSONObject args;
                if (rawArgs == null || rawArgs == JSONObject.NULL) {
                    args = new JSONObject();
                } else if (rawArgs instanceof JSONObject) {
                    args = (JSONObject) rawArgs;
                } else {
                    postResponse(replyProxy, id, envelope(command, false, null, "malformed-args", denial(command)));
                    return;
                }
                if (id.isEmpty() || command.isEmpty()) {
                    postResponse(replyProxy, id, envelope(command, false, null, "malformed-request", denial(command)));
                    return;
                }
                if (pendingReplies.putIfAbsent(id, replyProxy) != null) {
                    postResponse(replyProxy, id, envelope(command, false, null, "duplicate-request-id", denial(command)));
                    return;
                }
                JSONObject verdict = denial(command);
                if (!verdict.optBoolean("allowed", false)) {
                    deliver(id, envelope(command, false, null, "policy-denied", verdict));
                    return;
                }
                if (AUTHORIZATION_COMMAND.equals(command)) {
                    // Resolves later, from the redirect or from onHostResume.
                    openAuthorization(id, args, verdict);
                    return;
                }
                try {
                    Object value = perform(command, args);
                    deliver(id, envelope(command, true, value, null, verdict));
                } catch (Exception e) {
                    deliver(id, envelope(command, false, null, String.valueOf(e.getMessage()), verdict));
                }
            }
        });
    }

    private void deliver(final String id, final JSONObject payload) {
        final JavaScriptReplyProxy replyProxy = pendingReplies.remove(id);
        if (replyProxy != null) {
            postResponse(replyProxy, id, payload);
        }
    }

    private void postResponse(final JavaScriptReplyProxy replyProxy, final String id, final JSONObject payload) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSONObject response = new JSONObject();
                try {
                    response.put("id", id);
                    response.put("payload", payload);
                    replyProxy.postMessage(response.toString());
                } catch (Exception ignored) {
                    // JSONObject only rejects null keys; the bridge uses fixed keys.
                }
            }
        });
    }

    // ---- auth/open-authorization-url -------------------------------------
    //
    // OAuth must not run in this WebView (RFC 8252 section 8.12): the app
    // could read what is typed into a page it hosts, and Google refuses
    // embedded user agents (disallowed_useragent). Custom Tabs runs the page
    // in the browser's process; the redirect to the declared scheme comes
    // back through KotobaShellAuthCallbackActivity's intent-filter.
    //
    // The bridge decides nothing about OAuth itself -- state, PKCE, DPoP and
    // the token exchange stay in the page.

    private void openAuthorization(final String id, JSONObject args, final JSONObject verdict) {
        if (OAUTH_CALLBACK_SCHEME.isEmpty() || authorizationLauncher == null) {
            deliver(id, envelope(AUTHORIZATION_COMMAND, false, null, "scheme-not-configured", verdict));
            return;
        }
        String requested = args.optString("callbackScheme", "");
        if (!OAUTH_CALLBACK_SCHEME.equalsIgnoreCase(requested)) {
            deliver(id, envelope(AUTHORIZATION_COMMAND, false, null, "scheme-mismatch", verdict));
            return;
        }
        // https only: otherwise any injected script could use the bridge to
        // launch arbitrary intents/URLs outside the app.
        final Uri uri = Uri.parse(args.optString("url", ""));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            deliver(id, envelope(AUTHORIZATION_COMMAND, false, null, "invalid-url", verdict));
            return;
        }
        synchronized (this) {
            if (pendingAuthorizationId != null) {
                deliver(id, envelope(AUTHORIZATION_COMMAND, false, null, "busy", verdict));
                return;
            }
            pendingAuthorizationId = id;
            authorizationLaunched = false;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (KotobaShellBridge.this) {
                    authorizationLaunched = true;
                }
                String failure = null;
                try {
                    authorizationLauncher.launch(activity, uri);
                } catch (ActivityNotFoundException e) {
                    failure = "no-browser";
                } catch (RuntimeException e) {
                    failure = "start-failed";
                }
                if (failure != null) {
                    String pending = takePendingAuthorization();
                    if (pending != null) {
                        deliver(pending, envelope(AUTHORIZATION_COMMAND, false, null, failure, denial(AUTHORIZATION_COMMAND)));
                    }
                }
            }
        });
    }

    private synchronized String takePendingAuthorization() {
        String pending = pendingAuthorizationId;
        pendingAuthorizationId = null;
        authorizationLaunched = false;
        return pending;
    }

    /**
     * Called by KotobaShellAuthCallbackActivity with the redirect it
     * received. Returns false when nothing was waiting -- a redirect that
     * arrives unasked (another app firing our scheme, or a sign-in whose
     * Activity was destroyed meanwhile) is dropped, not delivered to
     * whatever page happens to be loaded.
     */
    public static boolean deliverAuthorizationCallback(Uri uri) {
        KotobaShellBridge bridge = active.get();
        if (bridge == null || uri == null || !OAUTH_CALLBACK_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String pending = bridge.takePendingAuthorization();
        if (pending == null) {
            return false;
        }
        try {
            bridge.deliver(pending, bridge.envelope(AUTHORIZATION_COMMAND, true,
                    object("callbackURL", uri.toString()), null, bridge.denial(AUTHORIZATION_COMMAND)));
        } catch (Exception e) {
            bridge.deliver(pending, bridge.envelope(AUTHORIZATION_COMMAND, false, null,
                    String.valueOf(e.getMessage()), bridge.denial(AUTHORIZATION_COMMAND)));
        }
        return true;
    }

    /**
     * Called from MainActivity.onResume. Custom Tabs has no "closed"
     * callback, so the only sign of cancellation is the app coming back to
     * the foreground with the browser launched and no redirect delivered.
     * The redirect, when there is one, is handled in the callback Activity's
     * onCreate, which runs before MainActivity resumes.
     */
    public void onHostResume() {
        String pending;
        synchronized (this) {
            if (pendingAuthorizationId == null || !authorizationLaunched) {
                return;
            }
            pending = pendingAuthorizationId;
            pendingAuthorizationId = null;
            authorizationLaunched = false;
        }
        deliver(pending, envelope(AUTHORIZATION_COMMAND, false, null, "cancelled", denial(AUTHORIZATION_COMMAND)));
    }

    // ---- policy ----------------------------------------------------------

    private JSONObject denial(String command) {
        String capability = capabilities.optString(command, null);
        String matchedDeny = firstMatch(deny, command, capability);
        String matchedAllow = firstMatch(allow, command, capability);
        JSONObject verdict = new JSONObject();
        try {
            verdict.put("allowed", matchedDeny == null && matchedAllow != null);
            verdict.put("capability", capability == null ? JSONObject.NULL : capability);
            verdict.put("matched-allow", matchedAllow == null ? JSONObject.NULL : matchedAllow);
            verdict.put("matched-deny", matchedDeny == null ? JSONObject.NULL : matchedDeny);
        } catch (Exception ignored) {
            // JSONObject.put only throws on a null key, which cannot happen here.
        }
        return verdict;
    }

    private static String firstMatch(Set<String> tokens, String command, String capability) {
        if (tokens.contains(command)) {
            return command;
        }
        if (capability != null && tokens.contains(capability)) {
            return capability;
        }
        if (tokens.contains("*")) {
            return "*";
        }
        return null;
    }

    private JSONObject envelope(String command, boolean ok, Object value, String error, JSONObject verdict) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("schema", SCHEMA);
            payload.put("command", command);
            payload.put("ok", ok);
            JSONObject audit = new JSONObject();
            audit.put("audit/schema", "kotoba.shell.audit.v0");
            audit.put("audit/authority", "kotoba-lang/shell");
            audit.put("audit/event", ok ? "provider/execute" : "provider/deny");
            audit.put("audit/target", "android");
            audit.put("audit/command", command);
            audit.put("audit/capability", verdict.opt("capability"));
            audit.put("audit/matched-allow", verdict.opt("matched-allow"));
            audit.put("audit/matched-deny", verdict.opt("matched-deny"));
            payload.put("audit", audit);
            if (value != null) {
                payload.put("value", value);
            }
            if (error != null) {
                payload.put("error", error);
            }
        } catch (Exception ignored) {
            // as above: no null keys are used.
        }
        return payload;
    }

    // ---- providers -------------------------------------------------------

    private Object perform(String command, JSONObject args) throws Exception {
        switch (command) {
            case "clipboard/read-text":
                return object("text", clipboardRead());
            case "clipboard/write-text":
                clipboardWrite(stringArg(args, "text"));
                return object("written", true);
            case "fs/read-text":
                return object("text", fsRead(stringArg(args, "path")));
            case "fs/write-text":
                fsWrite(stringArg(args, "path"), stringArg(args, "text"), false);
                return object("path", args.getString("path"), "written", true);
            case "fs/append-text":
                fsWrite(stringArg(args, "path"), stringArg(args, "text"), true);
                return object("path", args.getString("path"), "appended", true);
            case "keychain/read-text":
                return object("text", keychainRead(stringArg(args, "account")));
            case "keychain/write-text":
                keychainWrite(stringArg(args, "account"), stringArg(args, "text"));
                return object("written", true);
            case "keychain/delete":
                keychainDelete(stringArg(args, "account"));
                return object("deleted", true);
            case "http/fetch":
                return httpFetch(args);
            case "notify/show":
                return notify(stringArg(args, "title"), args.optString("body", ""));
            default:
                throw new IllegalArgumentException("unknown-command: " + command);
        }
    }

    private static String stringArg(JSONObject args, String key) {
        String value = args.optString(key, null);
        if (value == null) {
            throw new IllegalArgumentException("missing string argument: " + key);
        }
        return value;
    }

    private static JSONObject object(Object... pairs) throws Exception {
        JSONObject out = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    // clipboard: ClipboardManager is main-thread only, and the provider runs on
    // the executor, so both directions hop and wait.

    /**
     * Reads the clipboard, or says why it could not.
     *
     * From Android 10 the system returns nothing to an app that does not hold
     * window focus, and it does so silently: getPrimaryClip returns null and
     * no exception is raised. Returning "" for that would be
     * indistinguishable from an empty clipboard, which is how the first run
     * of this bridge reported a read that the system had actually refused
     * (observed on a booted emulator whose System UI dialog held focus).
     */
    private String clipboardRead() throws Exception {
        final String[] result = new String[]{null};
        final boolean[] focused = new boolean[]{false};
        final boolean[] empty = new boolean[]{false};
        final CountDownLatch latch = new CountDownLatch(1);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    focused[0] = activity.hasWindowFocus();
                    ClipboardManager manager =
                            (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager == null) {
                        return;
                    }
                    if (!manager.hasPrimaryClip()) {
                        empty[0] = true;
                        return;
                    }
                    ClipData clip = manager.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).coerceToText(activity);
                        result[0] = text == null ? "" : text.toString();
                    }
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await();
        if (empty[0]) {
            return "";
        }
        if (result[0] == null) {
            throw new IllegalStateException(focused[0]
                    ? "clipboard read returned nothing while focused"
                    : "clipboard read refused: Android 10+ only allows the focused app to read the clipboard");
        }
        return result[0];
    }

    private void clipboardWrite(final String text) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager manager =
                            (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager != null) {
                        manager.setPrimaryClip(ClipData.newPlainText("kotoba-shell", text));
                    }
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await();
    }

    // fs: scoped to the app's own files dir. Absolute paths and ".." are
    // rejected before resolution, so no spelling reaches the rest of the
    // sandbox.
    private File appDataFile(String path) throws Exception {
        if (path.isEmpty() || path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("path must be relative and free of '..': " + path);
        }
        File root = new File(activity.getFilesDir(), DATA_DIR);
        File target = new File(root, path);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("could not create " + parent.getPath());
        }
        return target;
    }

    private String fsRead(String path) throws Exception {
        File file = appDataFile(path);
        if (!file.isFile()) {
            throw new IllegalStateException("not readable: " + path);
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void fsWrite(String path, String text, boolean append) throws Exception {
        File file = appDataFile(path);
        try (OutputStream out = new FileOutputStream(file, append)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    // keychain: Android has no Keychain Services equivalent, so the closest
    // honest mapping is an AndroidKeyStore-held AES-GCM key encrypting values
    // in the app's own SharedPreferences. The key material never leaves the
    // keystore; only iv||ciphertext is stored.
    private SecretKey keychainKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private SharedPreferences keychainPrefs() {
        return activity.getSharedPreferences(KEYCHAIN_PREFS, Context.MODE_PRIVATE);
    }

    private String keychainRead(String account) throws Exception {
        String stored = keychainPrefs().getString(account, null);
        if (stored == null) {
            throw new IllegalStateException("keychain read failed: no item for " + account);
        }
        byte[] raw = Base64.decode(stored, Base64.NO_WRAP);
        byte[] iv = new byte[12];
        System.arraycopy(raw, 0, iv, 0, 12);
        byte[] cipherText = new byte[raw.length - 12];
        System.arraycopy(raw, 12, cipherText, 0, cipherText.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keychainKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    private void keychainWrite(String account, String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keychainKey());
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] joined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(cipherText, 0, joined, iv.length, cipherText.length);
        keychainPrefs().edit().putString(account, Base64.encodeToString(joined, Base64.NO_WRAP)).commit();
    }

    private void keychainDelete(String account) {
        keychainPrefs().edit().remove(account).commit();
    }

    private JSONObject httpFetch(JSONObject args) throws Exception {
        String urlString = args.optString("url", null);
        if (urlString == null) {
            throw new IllegalArgumentException("missing or malformed url");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod(args.optString("method", "GET"));
        JSONObject headers = args.optJSONObject("headers");
        if (headers != null) {
            for (Iterator<String> keys = headers.keys(); keys.hasNext(); ) {
                String key = keys.next();
                connection.setRequestProperty(key, headers.optString(key, ""));
            }
        }
        String body = args.optString("body", null);
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : new String(readAll(stream), StandardCharsets.UTF_8);
        JSONObject responseHeaders = new JSONObject();
        for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null) {
                responseHeaders.put(entry.getKey(), String.join(",", entry.getValue()));
            }
        }
        connection.disconnect();
        return object("status", status, "headers", responseHeaders, "body", text);
    }

    private JSONObject notify(String title, String body) throws Exception {
        NotificationManager manager =
                (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("no notification manager");
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL, "Kotoba Shell", NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
        Notification.Builder builder = new Notification.Builder(activity, NOTIFICATION_CHANNEL)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info);
        manager.notify((int) (System.nanoTime() & 0x7fffffff), builder.build());
        // Posting succeeds even when the user has denied POST_NOTIFICATIONS;
        // the system drops it silently. Report what was actually done rather
        // than claiming the notification was seen.
        boolean enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || manager.areNotificationsEnabled();
        return object("posted", true, "notifications-enabled", enabled);
    }
}
