import AppKit
import SwiftUI
import Combine

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var island: IslandController!
    private var statusItem: NSStatusItem!
    private var bag = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        island = IslandController(model: .shared)
        PhoneLink.shared.start()
        MacReceiver.shared.start()
        AirDropWatcher.shared.start()
        setupStatusItem()
        if let i = CommandLine.arguments.firstIndex(of: "--demo") {
            let only = CommandLine.arguments.indices.contains(i + 1) ? CommandLine.arguments[i + 1] : nil
            IslandModel.shared.runDemo(only: only)
        }
        // `--drop phone|airdrop <file>`: same path as a real drop (used for testing).
        if let i = CommandLine.arguments.firstIndex(of: "--drop"), CommandLine.arguments.count > i + 2,
           let target = DropTarget(rawValue: CommandLine.arguments[i + 1]) {
            let url = URL(fileURLWithPath: CommandLine.arguments[i + 2])
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { IslandModel.shared.perform(target, urls: [url]) }
        }
    }

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            let img = NSImage(systemSymbolName: "capsule.fill", accessibilityDescription: "Island Drop")
            img?.isTemplate = true
            button.image = img?.withSymbolConfiguration(.init(pointSize: 11, weight: .bold))
        }
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()

        let open = NSMenuItem(title: "Open Island", action: #selector(openIsland), keyEquivalent: "")
        open.target = self
        menu.addItem(open)
        menu.addItem(.separator())

        let phones = PhoneLink.shared.devices
        let phoneTitle = phones.isEmpty ? "No phone nearby" : "Phone: " + phones.map(\.name).joined(separator: ", ")
        let p = NSMenuItem(title: phoneTitle, action: nil, keyEquivalent: "")
        p.isEnabled = false
        menu.addItem(p)

        menu.addItem(.separator())
        let settings = NSMenuItem(title: "Settings…", action: #selector(openSettings), keyEquivalent: ",")
        settings.target = self
        menu.addItem(settings)
        menu.addItem(NSMenuItem(title: "Quit Island Drop", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    @objc private func openIsland() { IslandModel.shared.go(.open) }
    @objc private func openSettings() { SettingsWindow.show() }

}
