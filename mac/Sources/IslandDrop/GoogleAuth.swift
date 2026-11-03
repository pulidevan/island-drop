import AppKit
import CryptoKit
import Network

/// Google OAuth 2.0 for installed apps: PKCE + loopback redirect (RFC 8252).
@MainActor
final class GoogleAuth: ObservableObject {
    static let shared = GoogleAuth()

    struct Tokens: Codable {
        var accessToken: String
        var refreshToken: String
        var expiry: Date
        var email: String?
    }

    @Published private(set) var email: String?
    @Published private(set) var isConnecting = false
    @Published private(set) var isConnected = false

    private var tokens: Tokens? {
        didSet {
            email = tokens?.email
            isConnected = tokens != nil
            if let tokens, let data = try? JSONEncoder().encode(tokens) {
                Keychain.set(data, for: "google")
            } else {
                Keychain.delete("google")
            }
        }
    }
    private var connectTask: Task<Void, Error>?
    private var server: LoopbackServer?

    static let scopes = "openid email https://www.googleapis.com/auth/drive.file"

    private init() {
        if let data = Keychain.get("google"), let t = try? JSONDecoder().decode(Tokens.self, from: data) {
            tokens = t
            email = t.email
            isConnected = true
        }
    }

    func disconnect() {
        if let t = tokens {
            var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
            req.httpMethod = "POST"
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            req.httpBody = form(["token": t.refreshToken])
            URLSession.shared.dataTask(with: req).resume()
        }
        tokens = nil
        AppSettings.shared.folderID = ""
    }

    /// Opens the browser for consent and waits for the redirect. Concurrent callers share one flow.
    func connect() async throws {
        if let connectTask { return try await connectTask.value }
        let task = Task { try await runConnectFlow() }
        connectTask = task
        isConnecting = true
        defer { connectTask = nil; isConnecting = false }
        try await task.value
    }

    private func runConnectFlow() async throws {
        let settings = AppSettings.shared
        guard settings.hasClientCredentials else { throw IslandError("Add your Google OAuth client in Settings first.") }
        let clientID = settings.clientID.trimmingCharacters(in: .whitespaces)
        let secret = settings.clientSecret.trimmingCharacters(in: .whitespaces)

        let verifier = Self.randomURLSafe(48)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URL
        let state = Self.randomURLSafe(16)

        let server = LoopbackServer()
        self.server = server
        defer { server.stop(); self.server = nil }
        let port = try await server.start()
        let redirect = "http://127.0.0.1:\(port)"

        var c = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        c.queryItems = [
            .init(name: "client_id", value: clientID),
            .init(name: "redirect_uri", value: redirect),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: Self.scopes),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "access_type", value: "offline"),
            .init(name: "prompt", value: "consent select_account"),
            .init(name: "state", value: state),
        ]
        NSWorkspace.shared.open(c.url!)

        let params = try await server.waitForCallback(timeout: 300)
        if let err = params["error"] { throw IslandError("Google sign-in failed: \(err)") }
        guard params["state"] == state, let code = params["code"] else { throw IslandError("Google sign-in was interrupted.") }

        let json = try await tokenRequest([
            "code": code,
            "client_id": clientID,
            "client_secret": secret,
            "redirect_uri": redirect,
            "grant_type": "authorization_code",
            "code_verifier": verifier,
        ])
        guard let access = json["access_token"] as? String, let refresh = json["refresh_token"] as? String else {
            throw IslandError("Google didn't return a refresh token.")
        }
        let expires = (json["expires_in"] as? Double) ?? 3600
        var t = Tokens(accessToken: access, refreshToken: refresh, expiry: Date().addingTimeInterval(expires))
        t.email = try? await fetchEmail(access)
        tokens = t
        NSApp.activate(ignoringOtherApps: true)
    }

    /// A valid access token, refreshing it if it's about to expire.
    func accessToken() async throws -> String {
        guard var t = tokens else { throw IslandError("Google Drive isn't connected.") }
        if t.expiry.timeIntervalSinceNow > 60 { return t.accessToken }
        let s = AppSettings.shared
        let json: [String: Any]
        do {
            json = try await tokenRequest([
                "client_id": s.clientID, "client_secret": s.clientSecret,
                "refresh_token": t.refreshToken, "grant_type": "refresh_token",
            ])
        } catch let e as IslandError where e.message.contains("invalid_grant") {
            tokens = nil
            throw IslandError("Google access was revoked. Connect again.")
        }
        guard let access = json["access_token"] as? String else { throw IslandError("Couldn't refresh Google token.") }
        t.accessToken = access
        t.expiry = Date().addingTimeInterval((json["expires_in"] as? Double) ?? 3600)
        tokens = t
        return access
    }

    func invalidateAccessToken() { tokens?.expiry = .distantPast }

    private func tokenRequest(_ fields: [String: String]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = form(fields)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            let e = json["error"] as? String ?? "unknown"
            let desc = json["error_description"] as? String ?? ""
            throw IslandError("Google token error: \(e) \(desc)")
        }
        return json
    }

    private func fetchEmail(_ token: String) async throws -> String? {
        var req = URLRequest(url: URL(string: "https://openidconnect.googleapis.com/v1/userinfo")!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await URLSession.shared.data(for: req)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["email"] as? String
    }

    private func form(_ fields: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return fields.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
            .joined(separator: "&").data(using: .utf8)!
    }

    private static func randomURLSafe(_ bytes: Int) -> String {
        var b = [UInt8](repeating: 0, count: bytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes, &b)
        return Data(b).base64URL
    }
}

extension Data {
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

/// Tiny HTTP server on 127.0.0.1 that catches the OAuth redirect.
final class LoopbackServer {
    private var listener: NWListener?
    private var callback: CheckedContinuation<[String: String], Error>?
    private var pending: [String: String]?

    func start() async throws -> UInt16 {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let l = try NWListener(using: params)
        listener = l
        l.newConnectionHandler = { [weak self] conn in self?.handle(conn) }
        return try await withCheckedThrowingContinuation { c in
            var resumed = false
            l.stateUpdateHandler = { st in
                guard !resumed else { return }
                switch st {
                case .ready:
                    resumed = true
                    c.resume(returning: l.port?.rawValue ?? 0)
                case .failed(let e):
                    resumed = true
                    c.resume(throwing: e)
                default: break
                }
            }
            l.start(queue: .main)
        }
    }

    func waitForCallback(timeout: TimeInterval) async throws -> [String: String] {
        if let pending { return pending }
        return try await withCheckedThrowingContinuation { c in
            callback = c
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                self?.finish(.failure(IslandError("Google sign-in timed out.")))
            }
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        finish(.failure(IslandError("Sign-in cancelled.")))
    }

    private func finish(_ r: Result<[String: String], Error>) {
        guard let c = callback else {
            if case .success(let p) = r { pending = p }
            return
        }
        callback = nil
        c.resume(with: r)
    }

    private func handle(_ conn: NWConnection) {
        conn.start(queue: .main)
        conn.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, _, _ in
            guard let self, let data, let text = String(data: data, encoding: .utf8),
                  let line = text.split(separator: "\r\n").first else { conn.cancel(); return }
            let parts = line.split(separator: " ")
            let path = parts.count > 1 ? String(parts[1]) : "/"
            guard path.hasPrefix("/?") || path.hasPrefix("/callback?") else {
                self.respond(conn, status: "404 Not Found", body: "")
                return
            }
            var params: [String: String] = [:]
            for item in URLComponents(string: "http://localhost" + path)?.queryItems ?? [] {
                params[item.name] = item.value ?? ""
            }
            let ok = params["code"] != nil
            self.respond(conn, status: "200 OK", body: Self.page(ok: ok))
            self.finish(.success(params))
        }
    }

    private func respond(_ conn: NWConnection, status: String, body: String) {
        let b = Data(body.utf8)
        let head = "HTTP/1.1 \(status)\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(b.count)\r\nConnection: close\r\n\r\n"
        conn.send(content: Data(head.utf8) + b, completion: .contentProcessed { _ in conn.cancel() })
    }

    private static func page(ok: Bool) -> String {
        let title = ok ? "You're connected" : "Sign-in failed"
        let sub = ok ? "Island Drop can now upload to Google Drive. You can close this tab." : "Go back to Island Drop and try again."
        let mark = ok ? "&#10003;" : "!"
        let color = ok ? "#30d158" : "#ff453a"
        return """
        <!doctype html><html><head><meta charset="utf-8"><title>Island Drop</title>
        <style>
        body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#000;color:#fff;
        font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display",sans-serif}
        .i{display:flex;align-items:center;gap:18px;background:#000;border-radius:44px;padding:22px 34px 22px 22px;
        box-shadow:0 0 0 1px #1c1c1e,0 30px 80px rgba(0,0,0,.6);animation:p .7s cubic-bezier(.2,1.4,.4,1)}
        .c{width:52px;height:52px;border-radius:50%;background:\(color);display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:700}
        h1{margin:0;font-size:20px;font-weight:600}p{margin:4px 0 0;color:#8e8e93;font-size:14px}
        @keyframes p{from{transform:scale(.4,.6);opacity:0}to{transform:none;opacity:1}}
        </style></head><body><div class="i"><div class="c">\(mark)</div><div><h1>\(title)</h1><p>\(sub)</p></div></div></body></html>
        """
    }
}
