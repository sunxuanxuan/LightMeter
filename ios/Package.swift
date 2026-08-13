// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "FilmLightMeterDomain",
    platforms: [
        .macOS(.v13),
        .iOS(.v17),
    ],
    products: [
        .library(name: "FilmLightMeterDomain", targets: ["FilmLightMeterDomain"]),
        .executable(name: "DomainValidation", targets: ["DomainValidation"]),
    ],
    targets: [
        .target(
            name: "FilmLightMeterDomain",
            path: "FilmLightMeter/Domain"
        ),
        .executableTarget(
            name: "DomainValidation",
            dependencies: ["FilmLightMeterDomain"],
            path: "DomainValidation"
        ),
    ]
)
