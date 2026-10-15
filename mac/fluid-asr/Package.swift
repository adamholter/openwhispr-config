// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "OWFluidASR",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/FluidInference/FluidAudio.git", revision: "90c1b07e7fbf9598bc29446b0482fc89dca04e68"),
    ],
    targets: [
        .executableTarget(
            name: "OWFluidASR",
            dependencies: [.product(name: "FluidAudio", package: "FluidAudio")],
            path: "Sources/OWFluidASR"
        ),
    ]
)
