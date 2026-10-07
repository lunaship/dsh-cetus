// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DLSecurity",
    platforms: [.iOS(.v26)],
    products: [
        .library(name: "DLSecurity", targets: ["DLSecurity"])
    ],
    dependencies: [
        .package(path: "../DLModels")
    ],
    targets: [
        .target(
            name: "DLSecurity",
            dependencies: [
                .product(name: "DLModels", package: "DLModels")
            ]
        ),
        .testTarget(
            name: "DLSecurityTests",
            dependencies: ["DLSecurity"],
            path: "Tests",
        ),
    ]
)
