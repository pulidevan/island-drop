import AppKit
import SwiftUI
import UniformTypeIdentifiers

enum IslandState: Equatable {
    case idle, hover, dragPeek, working, dropTargets, open, result, message
}

enum DropTarget: String { case airdrop, drive, phone }

struct Activity: Equatable {
    var icon: NSImage
    var title: String
    var progress: Double?      // nil = indeterminate (waiting)
    var tint: Color
}

struct ResultInfo: Equatable {
    enum Kind: Equatable { case drive, phone }
    var kind: Kind
    var icon: NSImage
    var title: String
    var subtitle: String
    var files: [DriveFile]
    var access: ShareAccess
    var updating = false
}

struct Toast: Equatable {
    var icon: String
    var text: String
    var tint: Color
}

struct HistoryItem: Codable, Identifiable, Equatable {
    var id: String { file.id }
    var file: DriveFile
    var date: Date
    var access: ShareAccess
}

enum Motion {
    /// Dynamic Island–style: fast, slightly bouncy when growing, critically damped when shrinking.
    static let expand = Animation.spring(response: 0.42, dampingFraction: 0.72, blendDuration: 0)
    static let collapse = Animation.spring(response: 0.36, dampingFraction: 0.92, blendDuration: 0)
    static let content = Animation.spring(response: 0.38, dampingFraction: 0.86)
}

@MainActor
final class IslandModel: ObservableObject {
    static let shared = IslandModel()

    @Published private(set) var state: IslandState = .idle
    @Published var notchSize = CGSize(width: 185, height: 32)
    @Published var hasNotch = true
    @Published var activity: Activity?
    @Published var result: ResultInfo?
    @Published var toast: Toast?
    @Published var history: [HistoryItem] = []
    @Published var hoveredTarget: DropTarget?

    private(set) var isDraggingFiles = false
    private(set) var pointerInside = false
    private var leaveStart: Date?
    private var collapseTask: Task<Void, Never>?
    private var jobChain: Task<Void, Never>?
    private lazy var airDropDelegate = AirDropDelegate(model: self)

    let ear: CGFloat = 40

    private init() {
        if let data = UserDefaults.standard.data(forKey: "history"),
           let h = try? JSONDecoder().decode([HistoryItem].self, from: data) { history = h }
    }

    // MARK: Geometry

    var size: CGSize { size(for: state) }

    func size(for s: IslandState) -> CGSize {
        let n = notchSize
        switch s {
        case .idle: return n
        case .hover: return CGSize(width: n.width + 16, height: n.height + 4)
        case .dragPeek, .working: return CGSize(width: n.width + ear * 2, height: n.height)
        case .dropTargets: return CGSize(width: max(n.width + 300, 500), height: n.height + 122)
        case .open: return CGSize(width: max(n.width + 330, 530), height: n.height + 150)
        case .result: return CGSize(width: max(n.width + 300, 500), height: n.height + 106)
        case .message: return CGSize(width: max(n.width + 160, 340), height: n.height + 40)
        }
    }

    var topRadius: CGFloat {
        switch state {
        case .idle, .working, .dragPeek: return 6
        case .hover: return 8
        case .message: return 12
        default: return 18
        }
    }

    var bottomRadius: CGFloat {
        switch state {
        case .idle: return hasNotch ? 10 : n2(notchSize.height)
        case .hover: return 13
        case .working, .dragPeek: return 14
        case .message: return 22
        default: return 32
        }
    }
    private func n2(_ h: CGFloat) -> CGFloat { h / 2 }

    var isExpanded: Bool { [.open, .dropTargets, .result, .message].contains(state) }
    var restingState: IslandState { activity != nil ? .working : .idle }

    func go(_ s: IslandState) {
        guard s != state else { return }
        let a = size(for: s), b = size
        let grow = a.width * a.height > b.width * b.height
        if s != .result && s != .message { collapseTask?.cancel() }
        withAnimation(grow ? Motion.expand : Motion.collapse) { state = s }
        if grow && [.dropTargets, .result].contains(s) {
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
        }
    }

    // MARK: Pointer + drag (driven by IslandController)

    func pointer(inside: Bool) {
        pointerInside = inside
        switch state {
        case .idle:
            if inside && !isDraggingFiles { go(.hover) }
        case .hover:
            if !inside { go(.idle) }
        case .open:
            if inside { leaveStart = nil }
            else if let s = leaveStart {
                if Date().timeIntervalSince(s) > 0.35 { leaveStart = nil; go(restingState) }
            } else { leaveStart = Date() }
        default: break
        }
    }

    func drag(active: Bool, near: Bool) {
        if active != isDraggingFiles {
            isDraggingFiles = active
            if !active {
                hoveredTarget = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [self] in
                    if (state == .dropTargets || state == .dragPeek) && !isDraggingFiles { go(restingState) }
                }
                return
            }
        }
        guard active else { return }
        if near {
            if state != .dropTargets { go(.dropTargets) }
        } else if AppSettings.shared.expandOnDrag {
            if state != .dragPeek { go(.dragPeek) }
        } else if state == .dropTargets {
            go(restingState)
        }
    }

    func tapped() {
        switch state {
        case .idle, .hover, .working: go(.open)
        case .result, .message: go(restingState)
        default: break
        }
    }

    // MARK: Drops

    func handleDrop(_ providers: [NSItemProvider], target: DropTarget) -> Bool {
        if target == .phone && PhoneLink.shared.preferred == nil {
            showToast("smartphone", "No phone nearby. Open Island Drop on Android", tint: .orange)
            return false
        }
        Self.loadURLs(providers) { [self] urls in
            guard !urls.isEmpty else { return }
            switch target {
            case .airdrop: airDrop(urls)
            case .drive: enqueue { await self.uploadToDrive(urls) }
            case .phone: enqueue { await self.sendToPhone(urls) }
            }
        }
        return true
    }

    func perform(_ target: DropTarget, urls: [URL]) {
        switch target {
        case .airdrop: airDrop(urls)
        case .drive: enqueue { await self.uploadToDrive(urls) }
        case .phone: enqueue { await self.sendToPhone(urls) }
        }
    }

    private func enqueue(_ job: @escaping @MainActor () async -> Void) {
        let prev = jobChain
        jobChain = Task { @MainActor in
            await prev?.value
            await job()
        }
    }

    // MARK: AirDrop

    func airDrop(_ urls: [URL]) {
        guard let service = NSSharingService(named: .sendViaAirDrop), service.canPerform(withItems: urls) else {
            showToast("exclamationmark.triangle.fill", "AirDrop isn't available", tint: .orange)
            return
        }
        service.delegate = airDropDelegate
        NSApp.activate(ignoringOtherApps: true)
        service.perform(withItems: urls)
        go(restingState)
    }

    // MARK: Google Drive

    func uploadToDrive(_ urls: [URL]) async {
        let auth = GoogleAuth.shared
        let settings = AppSettings.shared
        do {
            if !auth.isConnected {
                guard settings.hasClientCredentials else {
                    showToast("gearshape.fill", "Set up Google Drive in Settings", tint: .orange)
                    SettingsWindow.show()
                    return
                }
                showToast("person.crop.circle.badge.checkmark", "Continue sign-in in your browser", tint: .blue, duration: 4)
                try await auth.connect()
            }
            let items = try await Self.prepare(urls)
            defer { items.forEach { $0.cleanup() } }
            let access = settings.defaultAccess
            var files: [DriveFile] = []
            for (i, item) in items.enumerated() {
                setActivity(Activity(icon: item.icon, title: item.url.lastPathComponent, progress: 0, tint: Palette.driveBlue))
                let count = Double(items.count)
                let f = try await DriveClient.shared.upload(fileURL: item.url, access: access) { p in
                    Task { @MainActor in self.activity?.progress = (Double(i) + p) / count }
                }
                files.append(f)
            }
            activity = nil
            let links = files.map { $0.link(access) }.joined(separator: "\n")
            if settings.autoCopy { Self.copy(links) }
            addHistory(files, access: access)
            result = ResultInfo(
                kind: .drive, icon: items[0].icon,
                title: files.count == 1 ? files[0].name : "\(files.count) files",
                subtitle: subtitle(for: access, copied: settings.autoCopy),
                files: files, access: access)
            go(.result)
            scheduleCollapse(after: 7)
            if settings.sendLinkToPhone, let phone = PhoneLink.shared.preferred {
                try? await PhoneLink.shared.sendLink(links, title: result?.title ?? "", to: phone)
            }
        } catch {
            activity = nil
            showError(error)
        }
    }

    private func subtitle(for access: ShareAccess, copied: Bool) -> String {
        let kind = access == .view ? "View-only link" : "Download link"
        return copied ? "\(kind) copied" : "Uploaded to Google Drive"
    }

    func changeAccess(_ access: ShareAccess) {
        guard var r = result, r.kind == .drive, r.access != access, !r.updating else { return }
        r.updating = true
        withAnimation(Motion.content) { result = r }
        collapseTask?.cancel()
        Task {
            do {
                for f in r.files { try await DriveClient.shared.setAccess(fileID: f.id, access: access) }
                Self.copy(r.files.map { $0.link(access) }.joined(separator: "\n"))
                withAnimation(Motion.content) {
                    result?.access = access
                    result?.updating = false
                    result?.subtitle = subtitle(for: access, copied: true)
                }
                AppSettings.shared.defaultAccess = access
                let ids = Set(r.files.map(\.id))
                for i in history.indices where ids.contains(history[i].id) { history[i].access = access }
                saveHistory()
            } catch {
                result?.updating = false
                showError(error)
                return
            }
            scheduleCollapse(after: 6)
        }
    }

    func copyResultLink() {
        guard let r = result else { return }
        Self.copy(r.files.map { $0.link(r.access) }.joined(separator: "\n"))
        withAnimation(Motion.content) { result?.subtitle = "Link copied" }
        scheduleCollapse(after: 4)
    }

    func openResultLink() {
        guard let f = result?.files.first, let r = result, let url = URL(string: f.viewLink) else { return }
        _ = r
        NSWorkspace.shared.open(url)
        go(restingState)
    }

    func sendResultToPhone() {
        guard let r = result, let phone = PhoneLink.shared.preferred else { return }
        let links = r.files.map { $0.link(r.access) }.joined(separator: "\n")
        Task {
            do {
                try await PhoneLink.shared.sendLink(links, title: r.title, to: phone)
                withAnimation(Motion.content) { result?.subtitle = "Link sent to \(phone.name)" }
                scheduleCollapse(after: 3)
            } catch { showError(error) }
        }
    }

    func copyHistory(_ item: HistoryItem) {
        Self.copy(item.file.link(item.access))
        showToast("link", "Link copied", tint: Palette.driveBlue, duration: 1.6)
    }

    private func addHistory(_ files: [DriveFile], access: ShareAccess) {
        history.insert(contentsOf: files.map { HistoryItem(file: $0, date: Date(), access: access) }, at: 0)
        history = Array(history.prefix(20))
        saveHistory()
    }

    private func saveHistory() {
        if let d = try? JSONEncoder().encode(history) { UserDefaults.standard.set(d, forKey: "history") }
    }

    // MARK: Phone

    func sendToPhone(_ urls: [URL]) async {
        guard let device = PhoneLink.shared.preferred else {
            showToast("smartphone", "No phone nearby", tint: .orange)
            return
        }
        do {
            let items = try await Self.prepare(urls)
            defer { items.forEach { $0.cleanup() } }
            setActivity(Activity(icon: items[0].icon, title: "Waiting for \(device.name)", progress: nil, tint: Palette.green))
            try await PhoneLink.shared.sendFiles(items.map(\.url), to: device) { [weak self] e in
                guard let self else { return }
                switch e {
                case .accepted: self.activity?.progress = 0
                case .progress(let p): self.activity?.progress = p
                }
            }
            activity = nil
            result = ResultInfo(
                kind: .phone, icon: items[0].icon,
                title: items.count == 1 ? items[0].url.lastPathComponent : "\(items.count) files",
                subtitle: "Sent to \(device.name)", files: [], access: .download)
            go(.result)
            scheduleCollapse(after: 4)
        } catch {
            activity = nil
            showError(error)
        }
    }

    // MARK: Feedback

    private func setActivity(_ a: Activity) {
        activity = a
        if [.idle, .hover, .result, .message].contains(state) { go(.working) }
    }

    func showToast(_ icon: String, _ text: String, tint: Color, duration: TimeInterval = 2.6) {
        toast = Toast(icon: icon, text: text, tint: tint)
        if state == .dropTargets && isDraggingFiles { return }
        if state == .message {
            // Re-trigger the content transition.
            withAnimation(Motion.content) { toast = Toast(icon: icon, text: text, tint: tint) }
        }
        go(.message)
        scheduleCollapse(after: duration)
    }

    func showError(_ error: Error) {
        let text = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        showToast("exclamationmark.triangle.fill", text, tint: Palette.red, duration: 4)
    }

    private func scheduleCollapse(after seconds: TimeInterval) {
        collapseTask?.cancel()
        collapseTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
            while let self, self.pointerInside, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
            guard let self, !Task.isCancelled, [.result, .message].contains(self.state) else { return }
            self.go(self.restingState)
        }
    }

    // MARK: Helpers

    static func copy(_ s: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
    }

    struct Prepared {
        let url: URL
        let icon: NSImage
        let temp: URL?
        func cleanup() { if let temp { try? FileManager.default.removeItem(at: temp) } }
    }

    /// Folders are zipped (Finder-style) before sending.
    static func prepare(_ urls: [URL]) async throws -> [Prepared] {
        var out: [Prepared] = []
        for u in urls {
            let icon = NSWorkspace.shared.icon(forFile: u.path)
            var isDir: ObjCBool = false
            FileManager.default.fileExists(atPath: u.path, isDirectory: &isDir)
            let isPackage = NSWorkspace.shared.isFilePackage(atPath: u.path)
            if isDir.boolValue || isPackage {
                let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
                let zip = tmp.appendingPathComponent(u.lastPathComponent + ".zip")
                try await run("/usr/bin/ditto", ["-c", "-k", "--sequesterRsrc", "--keepParent", u.path, zip.path])
                out.append(Prepared(url: zip, icon: icon, temp: tmp))
            } else {
                out.append(Prepared(url: u, icon: icon, temp: nil))
            }
        }
        return out
    }

    private static func run(_ tool: String, _ args: [String]) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: tool)
            p.arguments = args
            p.terminationHandler = { p in
                p.terminationStatus == 0 ? c.resume() : c.resume(throwing: IslandError("Couldn't compress folder"))
            }
            do { try p.run() } catch { c.resume(throwing: error) }
        }
    }

    static func loadURLs(_ providers: [NSItemProvider], completion: @escaping @MainActor ([URL]) -> Void) {
        let group = DispatchGroup()
        let lock = NSLock()
        var urls: [(Int, URL)] = []
        for (i, p) in providers.enumerated() where p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            group.enter()
            p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                var u: URL?
                if let d = item as? Data { u = URL(dataRepresentation: d, relativeTo: nil) }
                else if let x = item as? URL { u = x }
                if let u { lock.lock(); urls.append((i, u)); lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            let sorted = urls.sorted { $0.0 < $1.0 }.map(\.1)
            MainActor.assumeIsolated { completion(sorted) }
        }
    }
}

private final class AirDropDelegate: NSObject, NSSharingServiceDelegate {
    weak var model: IslandModel?
    init(model: IslandModel) { self.model = model }

    func sharingService(_ sharingService: NSSharingService, didShareItems items: [Any]) {
        Task { @MainActor in self.model?.showToast("checkmark.circle.fill", "Sent with AirDrop", tint: Palette.airdropBlue) }
    }

    func sharingService(_ sharingService: NSSharingService, didFailToShareItems items: [Any], error: Error) {
        let cancelled = (error as NSError).code == NSUserCancelledError
        guard !cancelled else { return }
        Task { @MainActor in self.model?.showError(error) }
    }
}

enum Palette {
    static let driveBlue = Color(red: 0.149, green: 0.518, blue: 0.988)
    static let airdropBlue = Color(red: 0.04, green: 0.52, blue: 1.0)
    static let green = Color(red: 0.188, green: 0.82, blue: 0.345)
    static let red = Color(red: 1.0, green: 0.27, blue: 0.23)
    static let secondary = Color.white.opacity(0.55)
    static let fill = Color.white.opacity(0.08)
}
