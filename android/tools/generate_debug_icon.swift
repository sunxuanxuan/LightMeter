import AppKit
import CoreImage
import Foundation

guard CommandLine.arguments.count == 3 else {
    fputs("usage: generate_debug_icon.swift <input.png> <output.png>\n", stderr)
    exit(2)
}

let inputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outputURL = URL(fileURLWithPath: CommandLine.arguments[2])

guard let inputImage = CIImage(contentsOf: inputURL) else {
    fputs("unable to read input image\n", stderr)
    exit(1)
}

let debugImage = inputImage.applyingFilter(
    "CIColorMatrix",
    parameters: [
        "inputRVector": CIVector(x: 0.55, y: 0, z: 0, w: 0),
        "inputGVector": CIVector(x: 0, y: 0.55, z: 0, w: 0),
        "inputBVector": CIVector(x: 0, y: 0, z: 0.55, w: 0),
        "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
        "inputBiasVector": CIVector(x: 0.32, y: 0.38, z: 0.42, w: 0),
    ],
)

let context = CIContext()
let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
guard let outputImage = context.createCGImage(
    debugImage,
    from: inputImage.extent,
    format: .RGBA8,
    colorSpace: colorSpace
) else {
    fputs("unable to render debug image\n", stderr)
    exit(1)
}

let representation = NSBitmapImageRep(cgImage: outputImage)
guard let png = representation.representation(using: .png, properties: [:]) else {
    fputs("unable to encode debug image\n", stderr)
    exit(1)
}

try FileManager.default.createDirectory(
    at: outputURL.deletingLastPathComponent(),
    withIntermediateDirectories: true
)
try png.write(to: outputURL, options: .atomic)
