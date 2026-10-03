// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DLNet",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLNet", targets: ["DLNet"]),
    ],
    targets: [
        .target(name: "DLNet"),
    ]
)
