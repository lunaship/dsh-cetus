// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLUI",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLUI", targets: ["DLUI"])
    ],
    targets: [
        .target(name: "DLUI")
    ]
)
