// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLModels",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLModels", targets: ["DLModels"]),
    ],
    targets: [
        .target(name: "DLModels"),
    ]
)
