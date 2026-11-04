import AppKit
import SwiftUI
import UniformTypeIdentifiers

enum IslandState: Equatable {
    case idle, hover, dragPeek, working, dropTargets, open, result, message, incoming, forward
}

enum DropTarget: String { case airdrop, phone }

struct Activity: Equatable {
    var icon: NSImage
    var title: String
    var progress: Double?      // nil = indeterminate (waiting)
    var tint: Color
}

struct ResultInfo: Equatable {
    enum Kind: Equatable { case phone, received }
    var kind: Kind
    var icon: NSImage
    var title: String
    var subtitle: String
    var localURLs: [URL] = []
}

struct IncomingRequest: Equatable {
    var sender: String
    var title: String
    var detail: String
    var icon: NSImage
}

struct Toast: Equatable {
    var icon: String
    var text: String
    var tint: Color
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
    @Published var hoveredTarget: DropTarget?
    @Published var incoming: IncomingRequest?
    @Published var forwardOffer: IncomingRequest?
    private var forwardURLs: [URL] = []
    private var incomingReply: CheckedContinuation<Bool, Never>?

    private(set) var isDraggingFiles = false
    private var resultShownAt = Date.distantPast
    /// Ignore clicks that land on a result button the instant it appears under the cursor.
    var lastMouseDown = Date.distantPast
    private var resultSettled: Bool { lastMouseDown > resultShownAt.addingTimeInterval(0.15) }
    private(set) var pointerInside = false
    private var leaveStart: Date?
    private var collapseTask: Task<Void, Never>?
    private var jobChain: Task<Void, Never>?
    private lazy var airDropDelegate = AirDropDelegate(model: self)

    let ear: CGFloat = 40

    private init() {}

    // MARK: Geometry

    var size: CGSize { size(for: state) }

    func size(for s: IslandState) -> CGSize {
        let n = notchSize
        switch s {
        case .idle: return n
        case .hover: return CGSize(width: n.width + 16, height: n.height + 4)
        case .dragPeek, .working: return CGSize(width: n.width + ear * 2, height: n.height)
        case .dropTargets: return CGSize(width: max(n.width + 190, 380), height: n.height + 122)
        case .open: return CGSize(width: max(n.width + 270, 440), height: n.height + 84)
        case .result: return CGSize(width: max(n.width + 280, 470), height: n.height + (result?.kind == .phone ? 62 : 106))
        case .message: return CGSize(width: max(n.width + 160, 340), height: n.height + 46)
        case .incoming, .forward: return CGSize(width: max(n.width + 280, 470), height: n.height + 100)
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

    var isExpanded: Bool { [.open, .dropTargets, .result, .message, .incoming, .forward].contains(state) }
    var restingState: IslandState { activity != nil ? .working : .idle }

    func go(_ s: IslandState) {
        guard s != state else { return }
        let a = size(for: s), b = size
        let grow = a.width * a.height > b.width * b.height
        if s != .result && s != .message { collapseTask?.cancel() }
        if s == .result { resultShownAt = Date() }
        withAnimation(grow ? Motion.expand : Motion.collapse) { state = s }
        if grow && [.dropTargets, .result].contains(s) {
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
        }
    }

    // MARK: Pointer + drag (driven by IslandController)

    private var demo = false

    func pointer(inside: Bool) {
        pointerInside = inside
        guard !demo else { return }
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
        guard !demo else { return }
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

    func setHoveredTarget(_ t: DropTarget?) {
        guard t != hoveredTarget else { return }
        hoveredTarget = t
        if t != nil { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
    }

    func acceptDrop(_ target: DropTarget, urls: [URL]) -> Bool {
        if target == .phone && PhoneLink.shared.preferred == nil {
            showToast("smartphone", "No phone nearby. Open Island Drop on Android", tint: .orange)
            return false
        }
        isDraggingFiles = false
        hoveredTarget = nil
        perform(target, urls: urls)
        return true
    }

    func perform(_ target: DropTarget, urls: [URL]) {
        switch target {
        case .airdrop: airDrop(urls)
        case .phone: enqueue { await self.sendToPhone(urls) }
        }
    }

    func enqueueJob(_ job: @escaping @MainActor () async -> Void) { enqueue(job) }

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
                subtitle: "Sent to \(device.name)")
            go(.result)
            scheduleCollapse(after: 4)
        } catch {
            activity = nil
            showError(error)
        }
    }

    // MARK: Receiving from the phone

    /// Shows the AirDrop-style Accept / Decline prompt and waits for the answer.
    func askIncoming(_ r: IncomingRequest) async -> Bool {
        let settings = AppSettings.shared
        if settings.autoAcceptFromPhone || settings.isFavorite(r.sender) { return true }
        respondIncoming(false) // only one prompt at a time
        incoming = r
        go(.incoming)
        NSSound(named: "Glass")?.play()
        NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
        return await withCheckedContinuation { c in
            incomingReply = c
            DispatchQueue.main.asyncAfter(deadline: .now() + 100) { [weak self] in
                if self?.incoming == r { self?.respondIncoming(false) }
            }
        }
    }

    func respondIncoming(_ accept: Bool) {
        guard let c = incomingReply else { return }
        incomingReply = nil
        // Accepting once makes the device a favorite: future transfers skip the prompt.
        if accept, let sender = incoming?.sender { AppSettings.shared.addFavorite(sender) }
        c.resume(returning: accept)
        if !accept, state == .incoming { go(restingState) }
        incoming = nil
    }

    func receiveStarted(icon: NSImage) {
        setActivity(Activity(icon: icon, title: "Receiving", progress: 0, tint: Palette.green))
        if state == .incoming { go(.working) }
    }

    func receiveProgress(_ p: Double) { activity?.progress = p }

    func receiveFinished(_ urls: [URL], from sender: String) {
        activity = nil
        guard let first = urls.first else { return }
        result = ResultInfo(
            kind: .received, icon: NSWorkspace.shared.icon(forFile: first.path),
            title: urls.count == 1 ? first.lastPathComponent : "\(urls.count) files",
            subtitle: "From \(sender) · Saved to Downloads", localURLs: urls)
        go(.result)
        scheduleCollapse(after: 8)
    }

    func receiveFailed(_ error: Error) {
        activity = nil
        showError(error)
    }

    func openReceived() {
        guard resultSettled else { return }
        guard let urls = result?.localURLs, let first = urls.first else { return }
        if urls.count == 1 { NSWorkspace.shared.open(first) } else { NSWorkspace.shared.activateFileViewerSelecting(urls) }
        go(restingState)
    }

    func revealReceived() {
        guard resultSettled else { return }
        guard let urls = result?.localURLs, !urls.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting(urls)
        go(restingState)
    }

    func receivedLink(_ text: String, from sender: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: trimmed), let scheme = url.scheme, ["http", "https"].contains(scheme.lowercased()) {
            NSWorkspace.shared.open(url)
            showToast("safari.fill", "Opened link from \(sender)", tint: Palette.airdropBlue)
        } else {
            Self.copy(trimmed)
            showToast("doc.on.clipboard.fill", "Text from \(sender) copied", tint: Palette.green)
        }
    }

    // MARK: Relay (iPhone ⇄ Mac ⇄ Android)

    /// Files just arrived by AirDrop (from an iPhone): offer to pass them on to the Android device.
    func airDropArrived(_ urls: [URL]) {
        guard let phone = PhoneLink.shared.preferred, !urls.isEmpty else { return }
        if AppSettings.shared.autoForwardAirDrop {
            enqueue { await self.sendToPhone(urls) }
            return
        }
        guard ![.dropTargets, .incoming].contains(state) else { return }
        forwardURLs = urls
        let size = urls.reduce(Int64(0)) { $0 + ((try? FileManager.default.attributesOfItem(atPath: $1.path)[.size] as? NSNumber)?.int64Value ?? 0) }
        forwardOffer = IncomingRequest(
            sender: "Received with AirDrop",
            title: urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files",
            detail: "Send to \(phone.name)? · " + ByteCountFormatter.string(fromByteCount: size, countStyle: .file),
            icon: NSWorkspace.shared.icon(forFile: urls[0].path))
        go(.forward)
        scheduleCollapse(after: 12)
    }

    func respondForward(_ send: Bool) {
        let urls = forwardURLs
        forwardURLs = []
        forwardOffer = nil
        if send && !urls.isEmpty { enqueue { await self.sendToPhone(urls) } } else { go(restingState) }
    }

    /// Pass files that came from the Android device on to an iPhone via AirDrop.
    func airDropReceived() {
        guard resultSettled, let urls = result?.localURLs, !urls.isEmpty else { return }
        airDrop(urls)
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
            guard let self, !Task.isCancelled, [.result, .message, .forward].contains(self.state) else { return }
            if self.state == .forward { self.forwardURLs = []; self.forwardOffer = nil }
            self.go(self.restingState)
        }
    }

    // MARK: Demo (`--demo [state]`) – walks through every state for previews

    func runDemo(only: String?) {
        demo = true
        let icon = NSWorkspace.shared.icon(for: .pdf)
        let name = "Quarterly Report.pdf"
        let steps: [(String, () -> Void)] = [
            ("hover", { self.go(.hover) }),
            ("peek", { self.go(.dragPeek) }),
            ("drop", { self.go(.dropTargets) }),
            ("working", {
                self.activity = Activity(icon: icon, title: name, progress: 0.62, tint: Palette.green)
                self.go(.working)
            }),
            ("result", {
                self.activity = nil
                self.result = ResultInfo(kind: .received, icon: icon, title: name,
                                         subtitle: "From Lenovo Legion Tab · Saved to Downloads")
                self.go(.result)
            }),
            ("open", { self.go(.open) }),
            ("toast", { self.showToast("checkmark.circle.fill", "Sent with AirDrop", tint: Palette.airdropBlue, duration: 60) }),
        ]
        let chosen = only.map { o in steps.filter { $0.0 == o } } ?? steps
        for (i, step) in chosen.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1 + Double(i) * 2.5) {
                self.collapseTask?.cancel()
                step.1()
                self.collapseTask?.cancel()
            }
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
    static let airdropBlue = Color(red: 0.04, green: 0.52, blue: 1.0)
    static let green = Color(red: 0.188, green: 0.82, blue: 0.345)
    static let red = Color(red: 1.0, green: 0.27, blue: 0.23)
    static let secondary = Color.white.opacity(0.55)
    static let fill = Color.white.opacity(0.08)
}
