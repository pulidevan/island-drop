import AppKit
import Network
import UniformTypeIdentifiers

/// Lets the Android app send to this Mac (the AirDrop direction).
/// Advertised over Bonjour as `_islanddrop-mac._tcp`. Same wire protocol as Mac → phone, reversed:
///   phone → Mac : JSON line {"type":"files","sender":…,"files":[{name,size,mime}],"total":N} | {"type":"link","url":…}
///   Mac → phone : "ACCEPT\n" | "DECLINE\n" (files) or "OK\n" (link)
///   phone → Mac : raw bytes            Mac → phone : "DONE\n"
@MainActor
final class MacReceiver {
    static let shared = MacReceiver()
    static let serviceType = "_islanddrop-mac._tcp"
    private var listener: NWListener?

    func start() {
        listener?.cancel()
        do {
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            let params = NWParameters(tls: nil, tcp: tcp)
            params.includePeerToPeer = true
            let l = try NWListener(using: params)
            l.service = NWListener.Service(name: PhoneLink.computerName, type: Self.serviceType)
            l.newConnectionHandler = { [weak self] conn in
                Task { @MainActor in self?.handle(conn) }
            }
            l.stateUpdateHandler = { [weak self] st in
                if case .failed = st {
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        self?.start()
                    }
                }
            }
            l.start(queue: .main)
            listener = l
        } catch {
            NSLog("Island Drop receiver failed: \(error)")
        }
    }

    private func handle(_ nw: NWConnection) {
        let c = LineConnection(connection: nw)
        Task { @MainActor in
            defer { c.close() }
            do {
                try await c.open()
                let line = try await c.readLine(timeout: 15)
                guard let h = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] else { return }
                let sender = h["sender"] as? String ?? "Phone"
                switch h["type"] as? String {
                case "link":
                    try await c.send(Data("OK\n".utf8))
                    IslandModel.shared.receivedLink(h["url"] as? String ?? "", from: sender)
                case "files":
                    try await receiveFiles(h, sender: sender, c)
                default: break
                }
            } catch {
                IslandModel.shared.receiveFailed(error)
            }
        }
    }

    private func receiveFiles(_ h: [String: Any], sender: String, _ c: LineConnection) async throws {
        let model = IslandModel.shared
        let files = (h["files"] as? [[String: Any]] ?? []).map { f in
            (name: Self.safeName(f["name"] as? String ?? "file"), size: (f["size"] as? NSNumber)?.int64Value ?? 0)
        }
        guard !files.isEmpty else { return }
        let total = max(files.reduce(0) { $0 + $1.size }, 1)
        let icon = Self.icon(for: files[0].name, count: files.count)
        let what = files.count == 1 ? files[0].name : "\(files.count) files"
        let size = ByteCountFormatter.string(fromByteCount: total, countStyle: .file)

        let accepted = await model.askIncoming(IncomingRequest(sender: sender, title: what, detail: size, icon: icon))
        guard accepted else {
            try? await c.send(Data("DECLINE\n".utf8))
            return
        }
        try await c.send(Data("ACCEPT\n".utf8))
        model.receiveStarted(icon: icon)

        var saved: [URL] = []
        var received: Int64 = 0
        var lastUI = Date.distantPast
        do {
            for f in files {
                let url = Self.uniqueURL(f.name)
                FileManager.default.createFile(atPath: url.path, contents: nil)
                let handle = try FileHandle(forWritingTo: url)
                defer { try? handle.close() }
                saved.append(url)
                var remaining = f.size
                while remaining > 0 {
                    let chunk = try await c.receiveChunk(maxLength: Int(min(remaining, 1 << 20)))
                    try handle.write(contentsOf: chunk)
                    remaining -= Int64(chunk.count)
                    received += Int64(chunk.count)
                    if Date().timeIntervalSince(lastUI) > 0.05 {
                        lastUI = Date()
                        model.receiveProgress(Double(received) / Double(total))
                    }
                }
            }
            try await c.send(Data("DONE\n".utf8))
            model.receiveFinished(saved, from: sender)
        } catch {
            saved.forEach { try? FileManager.default.removeItem(at: $0) }
            throw IslandError("Transfer from \(sender) failed")
        }
    }

    private static func safeName(_ n: String) -> String {
        let s = (n as NSString).lastPathComponent
            .replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return s.isEmpty || s.hasPrefix(".") ? "file-\(Int(Date().timeIntervalSince1970))" + s : s
    }

    private static func uniqueURL(_ name: String) -> URL {
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        var url = dir.appendingPathComponent(name)
        let base = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var i = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(i)" : "\(base) \(i).\(ext)")
            i += 1
        }
        return url
    }

    static func icon(for name: String, count: Int) -> NSImage {
        if count > 1 { return NSWorkspace.shared.icon(for: .folder) }
        let ext = (name as NSString).pathExtension
        return NSWorkspace.shared.icon(for: UTType(filenameExtension: ext) ?? .data)
    }
}
