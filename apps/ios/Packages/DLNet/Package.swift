// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLNet",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLNet", targets: ["DLNet"])
    ],
    dependencies: [
        .package(path: "../DLModels"),
        .package(path: "../DLCore"),
        .package(path: "../DLSecurity"),
        .package(path: "../DLRemote"),
    ],
    targets: [
        .target(
            name: "DLNet",
            dependencies: [
                .product(name: "DLModels", package: "DLModels"),
                .product(name: "DLCore", package: "DLCore"),
                .product(name: "DLSecurity", package: "DLSecurity"),
                .product(name: "DLRemote", package: "DLRemote"),
            ]
        )
    ]
)
