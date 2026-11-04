import AppKit
import SwiftUI
import UniformTypeIdentifiers

final class IslandPanel: NSPanel {
    init() {
        super.init(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isFloatingPanel = true
        level = NSWindow.Level(rawValue: NSWindow.Level.mainMenu.rawValue + 3)
        collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        backgroundColor = .clear
        isOpaque = false
        hasShadow = false
        isMovable = false
        hidesOnDeactivate = false
        ignoresMouseEvents = true
        animationBehavior = .none
    }
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

final class FirstMouseHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

/// Root view of the panel. Handles file drops in AppKit (SwiftUI's onDrop doesn't fire reliably in a
/// non-activating overlay panel) and maps the cursor to a drop tile.
final class IslandDropView: NSView {
    var targetAt: (NSPoint) -> DropTarget? = { _ in nil }
    var hover: (DropTarget?) -> Void = { _ in }
    var drop: (DropTarget, [URL]) -> Bool = { _, _ in false }

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }
    required init?(coder: NSCoder) { fatalError() }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    private func target(_ info: NSDraggingInfo) -> DropTarget? {
        guard let w = window else { return nil }
        return targetAt(w.convertPoint(toScreen: info.draggingLocation))
    }

    override func draggingEntered(_ info: NSDraggingInfo) -> NSDragOperation { draggingUpdated(info) }

    override func draggingUpdated(_ info: NSDraggingInfo) -> NSDragOperation {
        let t = target(info)
        hover(t)
        return t == nil ? [] : .copy
    }

    override func draggingExited(_ info: NSDraggingInfo?) { hover(nil) }

    override func prepareForDragOperation(_ info: NSDraggingInfo) -> Bool { target(info) != nil }

    override func performDragOperation(_ info: NSDraggingInfo) -> Bool {
        guard let t = target(info) else { return false }
        let urls = info.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                       options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        hover(nil)
        return !urls.isEmpty && drop(t, urls)
    }
}

/// Owns the overlay window, tracks the pointer and system-wide file drags.
@MainActor
final class IslandController {
    private let model: IslandModel
    private let panel = IslandPanel()
    private var timer: Timer?
    private var monitors: [Any] = []
    private var mouseWasDown = false
    private var dragBaseline = 0
    private let windowSize = CGSize(width: 760, height: 330)

    init(model: IslandModel) {
        self.model = model
        let host = FirstMouseHostingView(rootView: IslandRootView(model: model))
        host.frame = CGRect(origin: .zero, size: windowSize)
        host.autoresizingMask = [.width, .height]
        let root = IslandDropView(frame: CGRect(origin: .zero, size: windowSize))
        root.addSubview(host)
        host.unregisterDraggedTypes()
        root.targetAt = { [weak self] p in MainActor.assumeIsolated { self?.dropTarget(at: p) } }
        root.hover = { t in MainActor.assumeIsolated { model.setHoveredTarget(t) } }
        root.drop = { t, urls in MainActor.assumeIsolated { model.acceptDrop(t, urls: urls) } }
        panel.contentView = root
        layout()
        panel.orderFrontRegardless()

        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.layout() }
        }
        let mask: NSEvent.EventTypeMask = [.mouseMoved, .leftMouseDragged, .leftMouseDown, .leftMouseUp]
        if let g = NSEvent.addGlobalMonitorForEvents(matching: mask, handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }) { monitors.append(g) }
        if let l = NSEvent.addLocalMonitorForEvents(matching: mask, handler: { [weak self] e in
            MainActor.assumeIsolated { self?.tick() }
            return e
        }) { monitors.append(l) }
        // Drag sessions swallow mouse events, so also poll.
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    private var screen: NSScreen {
        NSScreen.screens.first { $0.safeAreaInsets.top > 0 } ?? NSScreen.main ?? NSScreen.screens[0]
    }

    private func layout() {
        let s = screen
        if s.safeAreaInsets.top > 0, let l = s.auxiliaryTopLeftArea, let r = s.auxiliaryTopRightArea {
            model.hasNotch = true
            model.notchSize = CGSize(width: s.frame.width - l.width - r.width, height: s.safeAreaInsets.top)
        } else {
            model.hasNotch = false
            let bar = max(24, s.frame.maxY - s.visibleFrame.maxY)
            model.notchSize = CGSize(width: 190, height: bar)
        }
        panel.setFrame(CGRect(x: s.frame.midX - windowSize.width / 2, y: s.frame.maxY - windowSize.height,
                              width: windowSize.width, height: windowSize.height), display: true)
    }

    /// Which tile (left / middle / right third of the expanded island) is under a screen point.
    private func dropTarget(at p: NSPoint) -> DropTarget? {
        guard model.state == .dropTargets else { return nil }
        let r = islandRect(model.size(for: .dropTargets)).insetBy(dx: -12, dy: -24)
        guard r.contains(p) else { return nil }
        return p.x < r.midX ? .airdrop : .phone
    }

    private func islandRect(_ size: CGSize) -> CGRect {
        let s = screen.frame
        return CGRect(x: s.midX - size.width / 2, y: s.maxY - size.height, width: size.width, height: size.height)
    }

    private func tick() {
        let mouse = NSEvent.mouseLocation
        let down = NSEvent.pressedMouseButtons & 1 != 0
        let pb = NSPasteboard(name: .drag)

        if down && !mouseWasDown {
            dragBaseline = pb.changeCount
            model.lastMouseDown = Date()
        }
        var dragging = false
        if down && pb.changeCount != dragBaseline {
            dragging = pb.canReadObject(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])
        }
        mouseWasDown = down

        let current = islandRect(model.size).insetBy(dx: -4, dy: -4)
        let inside = current.contains(mouse) || (mouse.y >= screen.frame.maxY - 1 && abs(mouse.x - current.midX) < current.width / 2)

        // "Near": within a generous zone below the notch so the targets open before the cursor arrives.
        let zone = islandRect(model.size(for: .dropTargets)).insetBy(dx: -90, dy: -110)
        model.drag(active: dragging, near: dragging && zone.contains(mouse))

        model.pointer(inside: inside)
        // While a file drag is in flight the panel must stay a drop destination for the whole session.
        let interactive = dragging || inside
        if panel.ignoresMouseEvents == interactive { panel.ignoresMouseEvents = !interactive }
    }
}
