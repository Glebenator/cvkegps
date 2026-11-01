// swift-tools-version: 6.2
import PackageDescription

// The measurement rules, trip storage, and trip recorder of the iOS app. They use Foundation only, so they also build and
// run their tests on Linux. Apple platforms use the system SQLite library. Linux uses the CSQLite module map.
let package = Package(
    name: "CVKEGPSCore",
    defaultLocalization: "en",
    platforms: [.iOS(.v26), .macOS(.v15)],
    products: [.library(name: "CVKEGPSCore", targets: ["CVKEGPSCore"])],
    targets: [
        .systemLibrary(name: "CSQLite", path: "Sources/CSQLite", pkgConfig: "sqlite3", providers: [.apt(["libsqlite3-dev"])]),
        .target(
            name: "CVKEGPSCore",
            dependencies: [.target(name: "CSQLite", condition: .when(platforms: [.linux]))]
        ),
        .testTarget(name: "CVKEGPSCoreTests", dependencies: ["CVKEGPSCore"]),
    ]
)
