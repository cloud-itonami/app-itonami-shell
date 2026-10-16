// kotoba-shell in-app provider bridge — the Apple half (iOS and macOS).
//
// Why this file exists: `bin/kotoba-shell-host-ios` runs providers with
// `xcrun simctl spawn booted`, i.e. on the developer's machine against an
// attached simulator. That is a development bridge and none of it exists in a
// build handed to a user. Providers a shipped app can actually call have to be
// compiled into the app, which is what this is.
//
// Policy is enforced here, not only in the CLI. `Resources/
// kotoba-shell-policy.json` is written by `app scaffold` from the same
// `:allow`/`:deny` EDN the CLI evaluates, and the decision below matches
// `kotoba.shell.launcher/policy-decision` clause for clause: a command is
// allowed when no deny token matches and one of {command, capability, "*"} is
// in allow. Deny-by-default: a missing or unreadable policy file allows
// nothing.
//
// {{KEYCHAIN_SERVICE}} / {{DATA_DIR}} / {{OAUTH_CALLBACK_SCHEME}} are
// substituted by kotoba.shell.native-bridge/apple-bridge-swift.
import AuthenticationServices
import Foundation
import Security
import UserNotifications
import WebKit
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

enum KotobaShellBridgeError: Error {
    case invalidArgument(String)
    case providerFailure(String)
    /// A typed refusal. Its code is the reply's `error` verbatim
    /// (`cancelled`, `scheme-mismatch`, ...), so a page can branch on it
    /// without parsing a Swift enum description.
    case refused(String)
}

final class KotobaShellBridge: NSObject, WKScriptMessageHandlerWithReply,
                               ASWebAuthenticationPresentationContextProviding {
    static let messageName = "kotobaShell"
    static let authorizationCommand = "auth/open-authorization-url"

    /// The redirect scheme, from the manifest (`:ios/oauth-callback-scheme`
    /// / `:macos/oauth-callback-scheme`), never from the page. Empty means
    /// not configured, and the command refuses rather than listening on a
    /// guess that might be another app's redirect.
    private let oauthCallbackScheme = "{{OAUTH_CALLBACK_SCHEME}}"
    /// The session in flight. Held strongly: a session that only a local
    /// variable references is deallocated and its sheet vanishes at once.
    /// Also the one-at-a-time lock -- a second request while this is set is
    /// refused with `busy`.
    private var authSession: ASWebAuthenticationSession?

    private let allow: Set<String>
    private let deny: Set<String>
    private let capabilities: [String: String]
    private let keychainService: String
    private let dataRoot: URL

    #if os(iOS)
    private static let platformTarget = "ios"
    #else
    private static let platformTarget = "macos"
    #endif

    init(policy: [String: Any]) {
        self.allow = Set((policy["allow"] as? [String]) ?? [])
        self.deny = Set((policy["deny"] as? [String]) ?? [])
        self.capabilities = (policy["capabilities"] as? [String: String]) ?? [:]
        self.keychainService = "{{KEYCHAIN_SERVICE}}"
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.dataRoot = base.appendingPathComponent("{{DATA_DIR}}", isDirectory: true)
        super.init()
    }

    /// Reads the scaffolded policy. An app whose policy resource is missing
    /// gets an empty allow set rather than an open one -- a packaging mistake
    /// must fail closed, not silently grant every provider.
    static func loadFromBundle() -> KotobaShellBridge {
        guard let url = Bundle.main.url(forResource: "kotoba-shell-policy", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return KotobaShellBridge(policy: [:])
        }
        return KotobaShellBridge(policy: parsed)
    }

    /// Installs the message handler and the document-start shim on `config`.
    /// Call before creating the WKWebView: userContentController changes made
    /// after a web view exists do not apply to it.
    static func install(into config: WKWebViewConfiguration) -> KotobaShellBridge {
        let bridge = KotobaShellBridge.loadFromBundle()
        config.userContentController.addScriptMessageHandler(
            bridge, contentWorld: .page, name: KotobaShellBridge.messageName)
        if let shim = KotobaShellBridge.shimSource() {
            config.userContentController.addUserScript(
                WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
        return bridge
    }

    /// Asks for notification authorization once, at launch.
    ///
    /// Not from inside `notify/show`: `requestAuthorization` only calls back
    /// after someone answers the system dialog, so a provider call that
    /// requested inline would block for as long as the dialog stood open and
    /// its result would depend on how fast a person tapped. Android's
    /// MainActivity asks at startup for the same reason. Only asked when the
    /// policy actually allows `notify/show` — an app that never notifies
    /// should not greet its user with a permission sheet.
    func requestNotificationAuthorizationIfAllowed() {
        guard (decision(for: "notify/show")["allowed"] as? Bool) == true,
              Bundle.main.bundleIdentifier != nil else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private static func shimSource() -> String? {
        guard let url = Bundle.main.url(forResource: "kotoba-shell-bridge", withExtension: "js") else {
            return nil
        }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    // MARK: - Policy

    private func decision(for command: String) -> [String: Any] {
        let capability = capabilities[command]
        var tokens: [String] = [command, "*"]
        if let capability = capability { tokens.insert(capability, at: 1) }
        let matchedDeny = tokens.first(where: { deny.contains($0) })
        let matchedAllow = tokens.first(where: { allow.contains($0) })
        return [
            "allowed": matchedDeny == nil && matchedAllow != nil,
            "capability": capability as Any,
            "matched-allow": matchedAllow as Any,
            "matched-deny": matchedDeny as Any
        ]
    }

    private func envelope(command: String,
                          ok: Bool,
                          value: Any?,
                          error: String?,
                          decision: [String: Any]) -> [String: Any] {
        var payload: [String: Any] = [
            "schema": "kotoba.shell.bridge.v0",
            "command": command,
            "ok": ok,
            "audit": [
                "audit/schema": "kotoba.shell.audit.v0",
                "audit/authority": "kotoba-lang/shell",
                "audit/event": ok ? "provider/execute" : "provider/deny",
                "audit/target": KotobaShellBridge.platformTarget,
                "audit/command": command,
                "audit/capability": decision["capability"] ?? NSNull(),
                "audit/matched-allow": decision["matched-allow"] ?? NSNull(),
                "audit/matched-deny": decision["matched-deny"] ?? NSNull()
            ]
        ]
        if let value = value { payload["value"] = value }
        if let error = error { payload["error"] = error }
        return payload
    }

    // MARK: - WKScriptMessageHandlerWithReply

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        let origin = message.frameInfo.securityOrigin
        guard message.frameInfo.isMainFrame,
              origin.protocol.lowercased() == "kotoba-webbundle",
              origin.host.lowercased() == "app" else {
            replyHandler(nil, "kotoba-shell: bridge-origin-denied")
            return
        }
        guard let body = message.body as? [String: Any],
              let command = body["command"] as? String else {
            replyHandler(nil, "kotoba-shell: request must be {command, args}")
            return
        }
        let args = (body["args"] as? [String: Any]) ?? [:]
        let verdict = decision(for: command)
        guard (verdict["allowed"] as? Bool) == true else {
            replyHandler(envelope(command: command, ok: false, value: nil,
                                  error: "policy-denied", decision: verdict), nil)
            return
        }
        perform(command: command, args: args) { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let value):
                replyHandler(self.envelope(command: command, ok: true, value: value,
                                           error: nil, decision: verdict), nil)
            case .failure(let error):
                let message: String
                if case KotobaShellBridgeError.refused(let code) = error {
                    message = code
                } else {
                    message = String(describing: error)
                }
                replyHandler(self.envelope(command: command, ok: false, value: nil,
                                           error: message, decision: verdict), nil)
            }
        }
    }

    // MARK: - Providers

    private func perform(command: String,
                         args: [String: Any],
                         completion: @escaping (Result<Any, Error>) -> Void) {
        do {
            switch command {
            case "clipboard/read-text":
                completion(.success(["text": try clipboardRead()]))
            case "clipboard/write-text":
                try clipboardWrite(try stringArg(args, "text"))
                completion(.success(["written": true]))
            case "fs/read-text":
                completion(.success(["text": try fsRead(try stringArg(args, "path"))]))
            case "fs/write-text":
                let path = try stringArg(args, "path")
                try fsWrite(path, text: try stringArg(args, "text"), append: false)
                completion(.success(["path": path, "written": true]))
            case "fs/append-text":
                let path = try stringArg(args, "path")
                try fsWrite(path, text: try stringArg(args, "text"), append: true)
                completion(.success(["path": path, "appended": true]))
            case "keychain/read-text":
                completion(.success(["text": try keychainRead(try stringArg(args, "account"))]))
            case "keychain/write-text":
                try keychainWrite(try stringArg(args, "account"), text: try stringArg(args, "text"))
                completion(.success(["written": true]))
            case "keychain/delete":
                try keychainDelete(try stringArg(args, "account"))
                completion(.success(["deleted": true]))
            case "http/fetch":
                httpFetch(args, completion: completion)
            case KotobaShellBridge.authorizationCommand:
                openAuthorizationURL(args, completion: completion)
            case "notify/show":
                notify(title: try stringArg(args, "title"),
                       body: (args["body"] as? String) ?? "",
                       completion: completion)
            default:
                completion(.failure(KotobaShellBridgeError.providerFailure("unknown-command: \(command)")))
            }
        } catch {
            completion(.failure(error))
        }
    }

    private func stringArg(_ args: [String: Any], _ key: String) throws -> String {
        guard let value = args[key] as? String else {
            throw KotobaShellBridgeError.invalidArgument("missing string argument: \(key)")
        }
        return value
    }

    // MARK: clipboard

    private func clipboardRead() throws -> String {
        #if os(iOS)
        return UIPasteboard.general.string ?? ""
        #else
        return NSPasteboard.general.string(forType: .string) ?? ""
        #endif
    }

    private func clipboardWrite(_ text: String) throws {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }

    // MARK: fs (app-data scoped)

    /// Resolves `path` under the app's own data directory. Absolute paths and
    /// `..` are rejected before resolution rather than after, so a caller
    /// cannot reach the rest of the container by any spelling.
    private func appDataURL(_ path: String) throws -> URL {
        if path.hasPrefix("/") || path.contains("..") || path.isEmpty {
            throw KotobaShellBridgeError.invalidArgument("path must be relative and free of '..': \(path)")
        }
        try FileManager.default.createDirectory(at: dataRoot, withIntermediateDirectories: true)
        let url = dataRoot.appendingPathComponent(path)
        let parent = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        return url
    }

    private func fsRead(_ path: String) throws -> String {
        let url = try appDataURL(path)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            throw KotobaShellBridgeError.providerFailure("not readable: \(path)")
        }
        return text
    }

    private func fsWrite(_ path: String, text: String, append: Bool) throws {
        let url = try appDataURL(path)
        if append, let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: Data(text.utf8))
            return
        }
        try Data(text.utf8).write(to: url, options: .atomic)
    }

    // MARK: keychain

    private func keychainQuery(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService,
         kSecAttrAccount as String: account]
    }

    private func keychainRead(_ account: String) throws -> String {
        var query = keychainQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data,
              let text = String(data: data, encoding: .utf8) else {
            throw KotobaShellBridgeError.providerFailure("keychain read failed: OSStatus \(status)")
        }
        return text
    }

    private func keychainWrite(_ account: String, text: String) throws {
        SecItemDelete(keychainQuery(account) as CFDictionary)
        var query = keychainQuery(account)
        query[kSecValueData as String] = Data(text.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KotobaShellBridgeError.providerFailure("keychain write failed: OSStatus \(status)")
        }
    }

    private func keychainDelete(_ account: String) throws {
        let status = SecItemDelete(keychainQuery(account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KotobaShellBridgeError.providerFailure("keychain delete failed: OSStatus \(status)")
        }
    }

    // MARK: http

    private func httpFetch(_ args: [String: Any], completion: @escaping (Result<Any, Error>) -> Void) {
        guard let urlString = args["url"] as? String, let url = URL(string: urlString) else {
            completion(.failure(KotobaShellBridgeError.invalidArgument("missing or malformed url")))
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = (args["method"] as? String) ?? "GET"
        if let headers = args["headers"] as? [String: String] {
            for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        }
        if let body = args["body"] as? String { request.httpBody = Data(body.utf8) }
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                completion(.failure(KotobaShellBridgeError.providerFailure(error.localizedDescription)))
                return
            }
            let http = response as? HTTPURLResponse
            var headers: [String: String] = [:]
            for (key, value) in (http?.allHeaderFields ?? [:]) {
                headers[String(describing: key)] = String(describing: value)
            }
            completion(.success([
                "status": http?.statusCode ?? 0,
                "headers": headers,
                "body": String(data: data ?? Data(), encoding: .utf8) ?? ""
            ]))
        }.resume()
    }

    // MARK: auth/open-authorization-url
    //
    // OAuth must not run in this WebView (RFC 8252 §8.12): an IdP page inside
    // a view the app controls lets the app read the credentials typed into
    // it, and Google refuses embedded user agents outright
    // (disallowed_useragent). ASWebAuthenticationSession runs the page in a
    // browser process the app cannot script.
    //
    // The bridge decides nothing about the OAuth exchange. It opens the URL
    // and hands the redirect back verbatim; state, PKCE, DPoP and token
    // exchange stay in the page, where the same code runs on every target.

    private func openAuthorizationURL(_ args: [String: Any],
                                      completion: @escaping (Result<Any, Error>) -> Void) {
        guard !oauthCallbackScheme.isEmpty else {
            completion(.failure(KotobaShellBridgeError.refused("scheme-not-configured")))
            return
        }
        // The page names the scheme it expects so that a bundle built for
        // one app and run inside another fails loudly instead of waiting on
        // a redirect that will never arrive here.
        guard let requested = args["callbackScheme"] as? String,
              requested.lowercased() == oauthCallbackScheme else {
            completion(.failure(KotobaShellBridgeError.refused("scheme-mismatch")))
            return
        }
        // https only: anything else would make this an open launcher for
        // whatever URL an injected script supplies.
        guard let urlString = args["url"] as? String,
              let url = URL(string: urlString), url.scheme?.lowercased() == "https" else {
            completion(.failure(KotobaShellBridgeError.refused("invalid-url")))
            return
        }
        guard authSession == nil else {
            completion(.failure(KotobaShellBridgeError.refused("busy")))
            return
        }
        let session = ASWebAuthenticationSession(
            url: url,
            callbackURLScheme: oauthCallbackScheme) { [weak self] callbackURL, error in
                DispatchQueue.main.async {
                    self?.authSession = nil
                    if let error = error {
                        let code = (error as? ASWebAuthenticationSessionError)?.code
                        completion(.failure(code == .canceledLogin
                            ? KotobaShellBridgeError.refused("cancelled")
                            : KotobaShellBridgeError.providerFailure(error.localizedDescription)))
                        return
                    }
                    guard let callbackURL = callbackURL else {
                        completion(.failure(KotobaShellBridgeError.refused("cancelled")))
                        return
                    }
                    completion(.success(["callbackURL": callbackURL.absoluteString]))
                }
            }
        session.presentationContextProvider = self
        // Ephemeral, as on macOS. With a shared session the browser's
        // cookies sign the user straight back into whichever account it last
        // saw, which for a multi-account product (aozora: several PDS
        // accounts) is a silent wrong-account sign-in. It also removes iOS's
        // "wants to use … to Sign In" consent alert, which only exists
        // because a shared session can read the browser's cookies. The cost:
        // no SSO with Safari, the user types (or autofills) credentials.
        session.prefersEphemeralWebBrowserSession = true
        authSession = session
        if !session.start() {
            authSession = nil
            completion(.failure(KotobaShellBridgeError.refused("start-failed")))
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        #if os(iOS)
        // The key window: the sheet is presented over whatever the user is
        // looking at. Scaffolded iOS apps have no scene manifest, but iOS 13+
        // still attaches their window to a UIWindowScene.
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        return windows.first(where: { $0.isKeyWindow }) ?? windows.first ?? ASPresentationAnchor()
        #else
        return NSApp.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor()
        #endif
    }

    // MARK: notifications

    private func notify(title: String, body: String, completion: @escaping (Result<Any, Error>) -> Void) {
        // UNUserNotificationCenter traps when the process has no bundle
        // identity. A scaffolded app always has one; a host running the same
        // code outside a bundle should get an error, not a crash.
        guard Bundle.main.bundleIdentifier != nil else {
            completion(.failure(KotobaShellBridgeError.providerFailure("notifications require a bundled app")))
            return
        }
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            // Reads the decision rather than making it. See
            // requestNotificationAuthorizationIfAllowed().
            let authorized = settings.authorizationStatus == .authorized
                || settings.authorizationStatus == .provisional
            guard authorized else {
                completion(.failure(KotobaShellBridgeError.providerFailure(
                    "notification authorization not granted (status \(settings.authorizationStatus.rawValue))")))
                return
            }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            center.add(request) { addError in
                if let addError = addError {
                    completion(.failure(KotobaShellBridgeError.providerFailure(addError.localizedDescription)))
                } else {
                    completion(.success(["shown": true]))
                }
            }
        }
    }
}
