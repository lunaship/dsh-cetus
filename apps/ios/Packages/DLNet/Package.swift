// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLNet",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLNet", targets: ["DLNet"])
    ],
    dependencies: [
        .package(path: "../DLSecurity")
    ],
    targets: [
        .target(
            name: "DLNet",
            dependencies: [
                .product(name: "DLSecurity", package: "DLSecurity")
            ]
        )
    ]
)
