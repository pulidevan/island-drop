import AppKit

/// Notices files that arrive in ~/Downloads via AirDrop (quarantine agent "sharingd")
/// so the island can offer to forward them to the Android device.
@MainActor
final class AirDropWatcher {
    static let shared = AirDropWatcher()

    private let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    private var source: DispatchSourceFileSystemObject?
    private var seen = Set<String>()
    private var pending: [URL] = []
    private var debounce: DispatchWorkItem?

    func start() {
        guard source == nil else { return }
        seen = Set(entries().map(\.path))
        let fd = open(dir.path, O_EVTONLY)
        guard fd >= 0 else { return }
        let src = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: [.write, .rename, .link], queue: .main)
        src.setEventHandler { [weak self] in MainActor.assumeIsolated { self?.scan() } }
        src.setCancelHandler { close(fd) }
        src.resume()
        source = src
    }

    private func entries() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil,
                                                       options: [.skipsHiddenFiles])) ?? []
    }

    private func scan() {
        for url in entries() where !seen.contains(url.path) {
            seen.insert(url.path)
            pending.append(url)
        }
        guard !pending.isEmpty else { return }
        // AirDrop delivers a batch file by file – wait for it to settle.
        debounce?.cancel()
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                // Check the AirDrop mark only once the batch has settled.
                let files = self.pending.filter { Self.isFromAirDrop($0) }
                self.pending = []
                if !files.isEmpty { IslandModel.shared.airDropArrived(files) }
            }
        }
        debounce = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    static func isFromAirDrop(_ url: URL) -> Bool {
        var buf = [CChar](repeating: 0, count: 1024)
        let n = getxattr(url.path, "com.apple.quarantine", &buf, buf.count - 1, 0, XATTR_NOFOLLOW)
        guard n > 0 else { return false }
        buf[n] = 0
        let q = String(cString: buf).lowercased()
        return q.contains("sharingd") || q.contains("airdrop")
    }
}
