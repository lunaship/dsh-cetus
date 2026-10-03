// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLRemote",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLRemote", targets: ["DLRemote"])
    ],
    targets: [
        .target(name: "DLRemote")
    ]
)
