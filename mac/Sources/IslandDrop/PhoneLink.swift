import Foundation
import Network
import SystemConfiguration
import UniformTypeIdentifiers

/// Finds Island Drop Android receivers on the LAN (Bonjour `_islanddrop._tcp`) and pushes files/links to them.
///
/// Wire protocol (TCP):
///   Mac → phone : one JSON line  {"v":1,"type":"files","sender":…,"files":[{name,size,mime}],"total":N}
///   phone → Mac : "ACCEPT\n" | "DECLINE\n"
///   Mac → phone : raw bytes of every file, back to back
///   phone → Mac : "DONE\n"
/// Links: {"v":1,"type":"link","url":…,"title":…,"sender":…}  →  "OK\n"
@MainActor
final class PhoneLink: ObservableObject {
    static let shared = PhoneLink()
    static let serviceType = "_islanddrop._tcp"

    struct Device: Identifiable, Hashable {
        var id: String { name }
        let name: String
        let endpoint: NWEndpoint
    }

    enum Event { case accepted, progress(Double) }

    @Published private(set) var devices: [Device] = []
    private var browser: NWBrowser?

    var preferred: Device? {
        devices.first { $0.name == AppSettings.shared.preferredPhone } ?? devices.first
    }

    func start() {
        browser?.cancel()
        let params = NWParameters()
        params.includePeerToPeer = true
        let b = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: params)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let list = results.compactMap { r -> Device? in
                if case let .service(name, _, _, _) = r.endpoint { return Device(name: name, endpoint: r.endpoint) }
                return nil
            }.sorted { $0.name < $1.name }
            Task { @MainActor in self?.devices = list }
        }
        b.stateUpdateHandler = { [weak self] st in
            if case .failed = st {
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    self?.start()
                }
            }
        }
        b.start(queue: .main)
        browser = b
    }

    static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "Mac"
    }

    nonisolated func sendFiles(_ urls: [URL], to device: Device, onEvent: @escaping @MainActor (Event) -> Void) async throws {
        let c = LineConnection(endpoint: device.endpoint)
        defer { c.close() }
        try await c.open()

        let sizes = urls.map { (try? FileManager.default.attributesOfItem(atPath: $0.path)[.size] as? NSNumber)?.int64Value ?? 0 }
        let total = sizes.reduce(0, +)
        let files: [[String: Any]] = zip(urls, sizes).map { u, s in
            ["name": u.lastPathComponent, "size": s,
             "mime": UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"]
        }
        let header: [String: Any] = ["v": 1, "type": "files", "sender": await Self.computerName, "files": files, "total": total]
        try await c.send(JSONSerialization.data(withJSONObject: header) + Data("\n".utf8))

        let reply = try await c.readLine(timeout: 120)
        guard reply == "ACCEPT" else { throw IslandError("Declined on \(device.name)") }
        await onEvent(.accepted)

        var sent: Int64 = 0
        var lastReport = Date.distantPast
        for u in urls {
            let h = try FileHandle(forReadingFrom: u)
            defer { try? h.close() }
            while let chunk = try h.read(upToCount: 512 * 1024), !chunk.isEmpty {
                try await c.send(chunk)
                sent += Int64(chunk.count)
                if Date().timeIntervalSince(lastReport) > 0.05 {
                    lastReport = Date()
                    let p = Double(sent) / Double(max(total, 1))
                    await onEvent(.progress(p))
                }
            }
        }
        await onEvent(.progress(1))
        let done = try await c.readLine(timeout: 120)
        guard done == "DONE" else { throw IslandError("\(device.name) couldn't save the file") }
    }

    nonisolated func sendLink(_ url: String, title: String, to device: Device) async throws {
        let c = LineConnection(endpoint: device.endpoint)
        defer { c.close() }
        try await c.open()
        let header: [String: Any] = ["v": 1, "type": "link", "url": url, "title": title, "sender": await Self.computerName]
        try await c.send(JSONSerialization.data(withJSONObject: header) + Data("\n".utf8))
        _ = try await c.readLine(timeout: 15)
    }
}

/// NWConnection with async send + line reads. All state lives on `queue`.
final class LineConnection {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "islanddrop.phone")
    private var buffer = Data()

    init(endpoint: NWEndpoint) {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 8
        tcp.noDelay = true
        conn = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
    }

    func open() async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            var done = false
            conn.stateUpdateHandler = { [conn] st in
                guard !done else { return }
                switch st {
                case .ready: done = true; c.resume()
                case .failed(let e): done = true; c.resume(throwing: e)
                case .waiting(let e): done = true; conn.cancel(); c.resume(throwing: e)
                case .cancelled: done = true; c.resume(throwing: IslandError("Connection cancelled"))
                default: break
                }
            }
            conn.start(queue: queue)
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { e in
                if let e { c.resume(throwing: e) } else { c.resume() }
            })
        }
    }

    func readLine(timeout: TimeInterval) async throws -> String {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<String, Error>) in
            queue.async { [self] in
                var finished = false
                func finish(_ r: Result<String, Error>) {
                    guard !finished else { return }
                    finished = true
                    c.resume(with: r)
                }
                queue.asyncAfter(deadline: .now() + timeout) { finish(.failure(IslandError("The phone didn't respond in time"))) }
                func receive() {
                    if let line = takeLine() { finish(.success(line)); return }
                    conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, complete, error in
                        if let data { self.buffer.append(data) }
                        if let line = self.takeLine() { finish(.success(line)); return }
                        if let error { finish(.failure(error)); return }
                        if complete { finish(.failure(IslandError("The phone closed the connection"))); return }
                        receive()
                    }
                }
                receive()
            }
        }
    }

    private func takeLine() -> String? {
        guard let i = buffer.firstIndex(of: 0x0A) else { return nil }
        let line = String(data: buffer[buffer.startIndex..<i], encoding: .utf8) ?? ""
        buffer.removeSubrange(buffer.startIndex...i)
        return line.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func close() { conn.cancel() }
}
