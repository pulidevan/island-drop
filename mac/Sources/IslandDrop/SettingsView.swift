import SwiftUI
import AppKit
import ServiceManagement
import UniformTypeIdentifiers

@MainActor
enum SettingsWindow {
    private static var window: NSWindow?

    static func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 460),
                             styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
            w.title = "Island Drop"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: SettingsView())
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

struct SettingsView: View {
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var phone = PhoneLink.shared
    @State private var error: String?
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        Form {
            Section {
                if phone.devices.isEmpty {
                    Text("No phone found. Install Island Drop on Android and join the same Wi-Fi.")
                        .foregroundStyle(.secondary)
                } else {
                    Picker("Send to", selection: $settings.preferredPhone) {
                        ForEach(phone.devices) { d in Text(d.name).tag(d.name) }
                    }
                }
                Toggle("Accept files from any device without asking", isOn: $settings.autoAcceptFromPhone)
                Toggle("Forward iPhone AirDrops to my phone automatically", isOn: $settings.autoForwardAirDrop)
                Text("On Android, tap Share → Send to Mac in any app to send files to this Mac.")
                    .font(.caption).foregroundStyle(.secondary)
            } header: { Text("Phone") }

            Section {
                if settings.favorites.isEmpty {
                    Text("Accept a file once and that device is remembered here. Its files then arrive without asking.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(settings.favorites, id: \.self) { name in
                    HStack {
                        Image(systemName: "star.fill").foregroundStyle(.yellow)
                        Text(name)
                        Spacer()
                        Button("Remove") { settings.removeFavorite(name) }
                    }
                }
            } header: { Text("Favorites") }

            Section {
                Toggle("Show island while dragging files", isOn: $settings.expandOnDrag)
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, on in
                        do { on ? try SMAppService.mainApp.register() : try SMAppService.mainApp.unregister() }
                        catch { self.error = error.localizedDescription }
                    }
            } header: { Text("General") }

            if let error {
                Text(error).foregroundStyle(.red).font(.caption)
            }
        }
        .formStyle(.grouped)
        .frame(width: 460, height: 460)
    }
}
