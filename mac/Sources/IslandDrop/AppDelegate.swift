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
        setupStatusItem()
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
        let auth = GoogleAuth.shared

        let open = NSMenuItem(title: "Open Island", action: #selector(openIsland), keyEquivalent: "")
        open.target = self
        menu.addItem(open)
        menu.addItem(.separator())

        if let email = auth.email, auth.isConnected {
            let item = NSMenuItem(title: "Google Drive: \(email)", action: nil, keyEquivalent: "")
            item.isEnabled = false
            menu.addItem(item)
            let dis = NSMenuItem(title: "Disconnect Google", action: #selector(disconnectGoogle), keyEquivalent: "")
            dis.target = self
            menu.addItem(dis)
        } else {
            let con = NSMenuItem(title: "Connect Google Drive…", action: #selector(connectGoogle), keyEquivalent: "")
            con.target = self
            menu.addItem(con)
        }

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
    @objc private func disconnectGoogle() { GoogleAuth.shared.disconnect() }
    @objc private func connectGoogle() {
        guard AppSettings.shared.hasClientCredentials else { SettingsWindow.show(); return }
        Task {
            do { try await GoogleAuth.shared.connect() }
            catch { IslandModel.shared.showError(error) }
        }
    }
}
