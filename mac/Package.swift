// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "IslandDrop",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "IslandDrop",
            path: "Sources/IslandDrop",
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("SwiftUI"),
                .linkedFramework("Network"),
                .linkedFramework("Security"),
                .linkedFramework("ServiceManagement"),
                .linkedFramework("SystemConfiguration"),
            ]
        )
    ]
)
