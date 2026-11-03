import Foundation
import SwiftUI

enum ShareAccess: String, CaseIterable, Codable {
    case view, download

    var label: String { self == .view ? "Can view" : "Can download" }
    var symbol: String { self == .view ? "eye.fill" : "arrow.down.circle.fill" }
}

struct IslandError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

@MainActor
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    @Published var clientID: String { didSet { d.set(clientID, forKey: "clientID") } }
    @Published var clientSecret: String { didSet { d.set(clientSecret, forKey: "clientSecret") } }
    @Published var defaultAccess: ShareAccess { didSet { d.set(defaultAccess.rawValue, forKey: "defaultAccess") } }
    @Published var autoCopy: Bool { didSet { d.set(autoCopy, forKey: "autoCopy") } }
    @Published var sendLinkToPhone: Bool { didSet { d.set(sendLinkToPhone, forKey: "sendLinkToPhone") } }
    @Published var preferredPhone: String { didSet { d.set(preferredPhone, forKey: "preferredPhone") } }
    @Published var expandOnDrag: Bool { didSet { d.set(expandOnDrag, forKey: "expandOnDrag") } }
    var folderID: String {
        get { d.string(forKey: "driveFolderID") ?? "" }
        set { d.set(newValue, forKey: "driveFolderID") }
    }

    var hasClientCredentials: Bool {
        !clientID.trimmingCharacters(in: .whitespaces).isEmpty && !clientSecret.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private init() {
        let info = Bundle.main.infoDictionary ?? [:]
        clientID = d.string(forKey: "clientID") ?? (info["GoogleClientID"] as? String ?? "")
        clientSecret = d.string(forKey: "clientSecret") ?? (info["GoogleClientSecret"] as? String ?? "")
        defaultAccess = ShareAccess(rawValue: d.string(forKey: "defaultAccess") ?? "") ?? .view
        autoCopy = d.object(forKey: "autoCopy") as? Bool ?? true
        sendLinkToPhone = d.object(forKey: "sendLinkToPhone") as? Bool ?? false
        preferredPhone = d.string(forKey: "preferredPhone") ?? ""
        expandOnDrag = d.object(forKey: "expandOnDrag") as? Bool ?? true
    }

    /// Imports the `client_secret_XXXX.json` file downloaded from Google Cloud Console.
    func importClientJSON(_ url: URL) throws {
        let data = try Data(contentsOf: url)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let section = (obj["installed"] ?? obj["web"]) as? [String: Any],
              let id = section["client_id"] as? String,
              let secret = section["client_secret"] as? String
        else { throw IslandError("That file isn't a Google OAuth client JSON.") }
        clientID = id
        clientSecret = secret
    }
}

enum Keychain {
    private static let service = "com.puli.islanddrop"

    static func set(_ data: Data, for account: String) {
        delete(account)
        let q: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlock,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func get(_ account: String) -> Data? {
        let q: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    static func delete(_ account: String) {
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        SecItemDelete(q as CFDictionary)
    }
}
