import SwiftUI
import AppKit
import ServiceManagement
import UniformTypeIdentifiers

@MainActor
enum SettingsWindow {
    private static var window: NSWindow?

    static func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 600),
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
    @ObservedObject private var auth = GoogleAuth.shared
    @ObservedObject private var phone = PhoneLink.shared
    @State private var error: String?
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    DriveLogo().frame(width: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Google Drive").font(.headline)
                        Text(auth.isConnected ? (auth.email ?? "Connected") : "Not connected")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if auth.isConnecting {
                        ProgressView().controlSize(.small)
                    } else if auth.isConnected {
                        Button("Disconnect") { auth.disconnect() }
                    } else {
                        Button("Connect Google") { connect() }
                            .buttonStyle(.borderedProminent)
                            .disabled(!settings.hasClientCredentials)
                    }
                }
                if !auth.isConnected {
                    Button("Import client_secret.json…") { importJSON() }
                    TextField("OAuth Client ID", text: $settings.clientID)
                        .textFieldStyle(.roundedBorder)
                    SecureField("OAuth Client Secret", text: $settings.clientSecret)
                        .textFieldStyle(.roundedBorder)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("One-time setup (2 min):").bold()
                        Text("1. console.cloud.google.com → create a project → enable **Google Drive API**")
                        Text("2. Google Auth Platform → Branding & Audience → add your Gmail as a test user")
                        Text("3. Clients → Create client → **Desktop app** → download JSON → import above")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    Link("Open Google Cloud Console", destination: URL(string: "https://console.cloud.google.com/auth/clients")!)
                        .font(.caption)
                }
            } header: { Text("Account") }

            Section {
                Picker("Default link access", selection: $settings.defaultAccess) {
                    Label("Can view (no download)", systemImage: "eye.fill").tag(ShareAccess.view)
                    Label("Can download", systemImage: "arrow.down.circle.fill").tag(ShareAccess.download)
                }
                Toggle("Copy link automatically", isOn: $settings.autoCopy)
                Toggle("Also send Drive link to my phone", isOn: $settings.sendLinkToPhone)
                Text("Files go into an “Island Drop” folder in your Drive. Anyone with the link can open them.")
                    .font(.caption).foregroundStyle(.secondary)
            } header: { Text("Sharing") }

            Section {
                if phone.devices.isEmpty {
                    Text("No phone found. Install Island Drop on Android and join the same Wi-Fi.")
                        .foregroundStyle(.secondary)
                } else {
                    Picker("Send to", selection: $settings.preferredPhone) {
                        ForEach(phone.devices) { d in Text(d.name).tag(d.name) }
                    }
                }
            } header: { Text("Phone") }

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
        .frame(width: 520, height: 600)
    }

    private func connect() {
        error = nil
        Task {
            do { try await auth.connect() } catch { self.error = error.localizedDescription }
        }
    }

    private func importJSON() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.message = "Choose the client_secret_….json you downloaded from Google Cloud"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { try settings.importClientJSON(url); error = nil }
        catch { self.error = error.localizedDescription }
    }
}
