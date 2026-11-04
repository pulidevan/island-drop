import Foundation
import SwiftUI

struct IslandError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

@MainActor
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    @Published var preferredPhone: String { didSet { d.set(preferredPhone, forKey: "preferredPhone") } }
    @Published var expandOnDrag: Bool { didSet { d.set(expandOnDrag, forKey: "expandOnDrag") } }
    @Published var autoAcceptFromPhone: Bool { didSet { d.set(autoAcceptFromPhone, forKey: "autoAcceptFromPhone") } }
    @Published var autoForwardAirDrop: Bool { didSet { d.set(autoForwardAirDrop, forKey: "autoForwardAirDrop") } }
    /// Devices that were accepted once and now send without asking.
    @Published var favorites: [String] { didSet { d.set(favorites, forKey: "favorites") } }

    func isFavorite(_ name: String) -> Bool { favorites.contains(name) }
    func addFavorite(_ name: String) { if !favorites.contains(name) { favorites.append(name) } }
    func removeFavorite(_ name: String) { favorites.removeAll { $0 == name } }

    private init() {
        preferredPhone = d.string(forKey: "preferredPhone") ?? ""
        expandOnDrag = d.object(forKey: "expandOnDrag") as? Bool ?? true
        autoAcceptFromPhone = d.object(forKey: "autoAcceptFromPhone") as? Bool ?? false
        autoForwardAirDrop = d.object(forKey: "autoForwardAirDrop") as? Bool ?? false
        favorites = d.stringArray(forKey: "favorites") ?? []
        // Clean up settings left over from the removed Google Drive feature.
        for k in ["clientID", "clientSecret", "defaultAccess", "autoCopy", "sendLinkToPhone", "driveFolderID", "history"] {
            d.removeObject(forKey: k)
        }
    }
}
