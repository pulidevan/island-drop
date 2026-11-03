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
        panel.contentView = host
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

    private func islandRect(_ size: CGSize) -> CGRect {
        let s = screen.frame
        return CGRect(x: s.midX - size.width / 2, y: s.maxY - size.height, width: size.width, height: size.height)
    }

    private func tick() {
        let mouse = NSEvent.mouseLocation
        let down = NSEvent.pressedMouseButtons & 1 != 0
        let pb = NSPasteboard(name: .drag)

        if down && !mouseWasDown { dragBaseline = pb.changeCount }
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
        let interactive = model.state == .dropTargets ? zone.contains(mouse) : inside
        if panel.ignoresMouseEvents == interactive { panel.ignoresMouseEvents = !interactive }
    }
}
