import CoreGraphics
import Foundation

enum FilmExposureRenderer {
    static func render(
        image: CGImage,
        snapshot: ExposureSnapshot,
        preset: DisposableCameraPreset,
        calibrationOffset: Double
    ) -> CGImage? {
        guard calibrationOffset.isFinite else { return nil }
        let width = image.width
        let height = image.height
        let bytesPerRow = width * 4
        var pixels = [UInt8](repeating: 0, count: height * bytesPerRow)
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue
            | CGImageAlphaInfo.premultipliedLast.rawValue
        guard let context = CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo
        ) else {
            return nil
        }
        context.draw(
            image,
            in: CGRect(x: 0, y: 0, width: width, height: height)
        )

        let cameraSettingEV100 = MeteringEngine.cameraSettingEV100(
            snapshot.metadata
        )
        let referenceEV100 = FilmPreviewEngine.presetEV100(preset)
        for index in stride(from: 0, to: pixels.count, by: 4) {
            let alpha = Double(pixels[index + 3]) / 255
            guard alpha > 0 else { continue }
            let red = srgbToLinear(Double(pixels[index]) / 255 / alpha)
            let green = srgbToLinear(Double(pixels[index + 1]) / 255 / alpha)
            let blue = srgbToLinear(Double(pixels[index + 2]) / 255 / alpha)
            let sourceLuminance = red * 0.2126 + green * 0.7152 + blue * 0.0722
            let pixelEV100 = cameraSettingEV100
                + log2(max(sourceLuminance, luminanceEpsilon) / targetLuminance)
                + calibrationOffset
            let outputLuminance = FilmResponseCurve.targetLuminance(
                deltaEV: pixelEV100 - referenceEV100,
                highlightLatitudeStops: preset.film.highlightLatitudeStops,
                shadowLatitudeStops: preset.film.shadowLatitudeStops
            )
            let gain = outputLuminance / max(sourceLuminance, luminanceEpsilon)
            pixels[index] = linearToByte(red * gain * alpha)
            pixels[index + 1] = linearToByte(green * gain * alpha)
            pixels[index + 2] = linearToByte(blue * gain * alpha)
        }

        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else {
            return nil
        }
        return CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: bitmapInfo),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    private static func srgbToLinear(_ value: Double) -> Double {
        let clamped = min(max(value, 0), 1)
        return clamped <= 0.04045
            ? clamped / 12.92
            : pow((clamped + 0.055) / 1.055, 2.4)
    }

    private static func linearToByte(_ value: Double) -> UInt8 {
        let clamped = min(max(value, 0), 1)
        let srgb = clamped <= 0.0031308
            ? clamped * 12.92
            : 1.055 * pow(clamped, 1 / 2.4) - 0.055
        return UInt8((min(max(srgb, 0), 1) * 255).rounded())
    }

    private static let targetLuminance = 0.18
    private static let luminanceEpsilon = 1e-6
}
