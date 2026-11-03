import Foundation
import UniformTypeIdentifiers

struct DriveFile: Codable, Hashable, Identifiable {
    let id: String
    let name: String

    var viewLink: String { "https://drive.google.com/file/d/\(id)/view?usp=sharing" }
    var downloadLink: String { "https://drive.google.com/uc?export=download&id=\(id)" }
    func link(_ access: ShareAccess) -> String { access == .view ? viewLink : downloadLink }
}

/// Google Drive v3 REST: resumable upload with progress, then "anyone with the link" sharing.
///
/// - Can view:     anyone-with-link reader + `copyRequiresWriterPermission = true`
///                 (Drive hides download / print / copy for viewers).
/// - Can download: anyone-with-link reader, downloads allowed, direct `uc?export=download` link.
final class DriveClient {
    static let shared = DriveClient()
    private let api = "https://www.googleapis.com/drive/v3"

    func upload(fileURL: URL, access: ShareAccess, progress: @escaping @Sendable (Double) -> Void) async throws -> DriveFile {
        let folder = try await ensureFolder()
        let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
        let mime = UTType(filenameExtension: fileURL.pathExtension)?.preferredMIMEType ?? "application/octet-stream"

        // 1. Start a resumable session.
        let start = URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name&supportsAllDrives=true")!
        var req = try await authorized(start, method: "POST")
        req.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.setValue(mime, forHTTPHeaderField: "X-Upload-Content-Type")
        req.setValue(String(size), forHTTPHeaderField: "X-Upload-Content-Length")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["name": fileURL.lastPathComponent, "parents": [folder]])
        let (sdata, sresp) = try await URLSession.shared.data(for: req)
        guard let http = sresp as? HTTPURLResponse, http.statusCode == 200,
              let loc = http.value(forHTTPHeaderField: "Location"), let session = URL(string: loc)
        else { throw Self.apiError(sdata, sresp, "Couldn't start upload") }

        // 2. Stream the file.
        var put = URLRequest(url: session)
        put.httpMethod = "PUT"
        put.setValue(mime, forHTTPHeaderField: "Content-Type")
        put.timeoutInterval = 600
        let delegate = UploadProgress(progress)
        let (data, resp) = try await URLSession.shared.upload(for: put, fromFile: fileURL, delegate: delegate)
        guard let code = (resp as? HTTPURLResponse)?.statusCode, (200...201).contains(code),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["id"] as? String
        else { throw Self.apiError(data, resp, "Upload failed") }
        progress(1)

        // 3. Share.
        try await makePublic(id)
        try await setAccess(fileID: id, access: access)
        return DriveFile(id: id, name: json["name"] as? String ?? fileURL.lastPathComponent)
    }

    func setAccess(fileID: String, access: ShareAccess) async throws {
        var req = try await authorized(URL(string: "\(api)/files/\(fileID)?fields=id,copyRequiresWriterPermission")!, method: "PATCH")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["copyRequiresWriterPermission": access == .view])
        try await send(req, "Couldn't change link access")
    }

    private func makePublic(_ fileID: String) async throws {
        var req = try await authorized(URL(string: "\(api)/files/\(fileID)/permissions?fields=id")!, method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["role": "reader", "type": "anyone", "allowFileDiscovery": false])
        try await send(req, "Couldn't create share link")
    }

    /// Everything goes into an "Island Drop" folder the app owns (drive.file scope).
    private func ensureFolder() async throws -> String {
        let cached = await MainActor.run { AppSettings.shared.folderID }
        if !cached.isEmpty {
            let req = try await authorized(URL(string: "\(api)/files/\(cached)?fields=id,trashed")!, method: "GET")
            if let (data, resp) = try? await URLSession.shared.data(for: req),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               json["trashed"] as? Bool != true {
                return cached
            }
        }
        var req = try await authorized(URL(string: "\(api)/files?fields=id")!, method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["name": "Island Drop", "mimeType": "application/vnd.google-apps.folder"])
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let id = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["id"] as? String
        else { throw Self.apiError(data, resp, "Couldn't create Drive folder") }
        await MainActor.run { AppSettings.shared.folderID = id }
        return id
    }

    private func authorized(_ url: URL, method: String) async throws -> URLRequest {
        let token = try await GoogleAuth.shared.accessToken()
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return r
    }

    @discardableResult
    private func send(_ req: URLRequest, _ what: String) async throws -> Data {
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let code = (resp as? HTTPURLResponse)?.statusCode, (200..<300).contains(code) else {
            if (resp as? HTTPURLResponse)?.statusCode == 401 { await GoogleAuth.shared.invalidateAccessToken() }
            throw Self.apiError(data, resp, what)
        }
        return data
    }

    private static func apiError(_ data: Data, _ resp: URLResponse, _ what: String) -> IslandError {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let msg = ((try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? [String: Any])?["message"] as? String
        if code == 403, msg?.contains("has not been used") == true || msg?.contains("disabled") == true {
            return IslandError("Enable the Google Drive API for your Cloud project.")
        }
        return IslandError("\(what) (\(code))\(msg.map { ": \($0)" } ?? "")")
    }
}

private final class UploadProgress: NSObject, URLSessionTaskDelegate {
    let handler: @Sendable (Double) -> Void
    init(_ handler: @escaping @Sendable (Double) -> Void) { self.handler = handler }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else { return }
        handler(min(0.99, Double(totalBytesSent) / Double(totalBytesExpectedToSend)))
    }
}
