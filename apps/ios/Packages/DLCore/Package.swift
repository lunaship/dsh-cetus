// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLCore",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLCore", targets: ["DLCore"])
    ],
    dependencies: [
        .package(path: "../DLModels")
    ],
    targets: [
        .target(
            name: "DLCore",
            dependencies: [
                .product(name: "DLModels", package: "DLModels")
            ]
        )
    ]
)
