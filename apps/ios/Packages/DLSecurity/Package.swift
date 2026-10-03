// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DLSecurity",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLSecurity", targets: ["DLSecurity"]),
    ],
    targets: [
        .target(name: "DLSecurity"),
    ]
)
