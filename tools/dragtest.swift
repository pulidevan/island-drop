// Test helper: a window that starts a real file drag (like Finder) when clicked, then drives the mouse to a target.
import AppKit
let file = URL(fileURLWithPath: CommandLine.arguments[1])
let target = CGPoint(x: Double(CommandLine.arguments[2])!, y: Double(CommandLine.arguments[3])!)

final class Src: NSView, NSDraggingSource {
    func draggingSession(_ s: NSDraggingSession, sourceOperationMaskFor c: NSDraggingContext) -> NSDragOperation { .copy }
    override func draw(_ r: NSRect) { NSColor.systemBlue.setFill(); r.fill() }
    override func mouseDragged(with e: NSEvent) {
        let item = NSDraggingItem(pasteboardWriter: file as NSURL)
        item.setDraggingFrame(NSRect(x: 0, y: 0, width: 40, height: 40), contents: NSWorkspace.shared.icon(forFile: file.path))
        beginDraggingSession(with: [item], event: e, source: self)
    }
}
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let w = NSWindow(contentRect: NSRect(x: 300, y: 300, width: 120, height: 120), styleMask: [.titled], backing: .buffered, defer: false)
w.contentView = Src()
w.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)
func post(_ t: CGEventType, _ p: CGPoint) {
    CGEvent(mouseEventSource: nil, mouseType: t, mouseCursorPosition: p, mouseButton: .left)?.post(tap: .cghidEventTap)
}
DispatchQueue.global().async {
    usleep(800_000)
    // window centre in CG (top-left) coords of the main screen
    let mainH = NSScreen.screens[0].frame.height
    let start = CGPoint(x: 360, y: mainH - 360)
    post(.mouseMoved, start); usleep(200_000)
    post(.leftMouseDown, start); usleep(150_000)
    let steps = 60
    for i in 1...steps {
        let t = Double(i) / Double(steps)
        post(.leftMouseDragged, CGPoint(x: start.x + (target.x - start.x) * t, y: start.y + (target.y - start.y) * t))
        usleep(25_000)
    }
    for _ in 0..<20 { post(.leftMouseDragged, target); usleep(50_000) }
    print("over target"); fflush(stdout)
    if CommandLine.arguments.count > 4 { usleep(UInt32(Double(CommandLine.arguments[4])! * 1e6)) }
    post(.leftMouseUp, target); print("dropped"); fflush(stdout)
    usleep(500_000)
    DispatchQueue.main.async { exit(0) }
}
app.run()
