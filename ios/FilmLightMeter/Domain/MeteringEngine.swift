import Foundation

public final class MeteringEngine: @unchecked Sendable {
    public init() {}

    public func analyze(
        plane: LuminancePlane,
        metadata: CameraExposureMetadata,
        timestampNanoseconds: Int64,
        configuration: MeteringConfiguration
    ) -> MeteringResult? {
        guard metadata.isValid, configuration.isZoomReady else { return nil }
        guard let measured = measuredLuminance(
            plane: plane,
            configuration: configuration
        ) else {
            return nil
        }

        let settingEV = Self.cameraSettingEV100(metadata)
        let rawEV = settingEV + log2(measured / Self.targetLuminance)
            + configuration.calibrationOffset
        return MeteringResult(
            meteredEV100: rawEV,
            measuredLuminance: measured,
            metadata: metadata,
            timestampNanoseconds: timestampNanoseconds,
            revision: configuration.revision
        )
    }

    public func makeSnapshot(
        plane: LuminancePlane,
        result: MeteringResult,
        configuration: MeteringConfiguration
    ) -> ExposureSnapshot? {
        guard result.revision == configuration.revision else { return nil }
        let map = exposureMap(
            plane: plane,
            settingEV: Self.cameraSettingEV100(result.metadata),
            timestampNanoseconds: result.timestampNanoseconds,
            configuration: configuration
        )
        return ExposureSnapshot(
            exposureMap: map,
            meteredEV100: result.meteredEV100,
            measuredLuminance: result.measuredLuminance,
            metadata: result.metadata,
            timestampNanoseconds: result.timestampNanoseconds,
            revision: result.revision
        )
    }

    public static func cameraSettingEV100(_ metadata: CameraExposureMetadata) -> Double {
        log2(metadata.aperture * metadata.aperture / metadata.exposureSeconds)
            - log2(metadata.sensitivityISO / 100)
    }

    public static func normalizedLuminance(_ value: UInt8, isVideoRange: Bool) -> Double {
        if isVideoRange {
            return min(max((Double(value) - 16) / 219, 0), 1)
        }
        return Double(value) / 255
    }

    private func measuredLuminance(
        plane: LuminancePlane,
        configuration: MeteringConfiguration
    ) -> Double? {
        var primary = [Int](repeating: 0, count: 256)
        var secondary = [Int](repeating: 0, count: 256)
        var primaryCount = 0
        var secondaryCount = 0

        let viewfinder = configuration.viewfinder
        let spot = configuration.spotPoint
            ?? NormalizedPoint(x: viewfinder.centerX, y: viewfinder.centerY)
        let virtualArea = viewfinder.width * configuration.previewAspectRatio
            * viewfinder.height
        let spotRadiusSquared = virtualArea
            * Double(configuration.spotAreaPercent) / 100 / .pi
        let centerScale = sqrt(Double(configuration.centerAreaPercent) / 100)
        let centerHalfWidth = viewfinder.width * centerScale / 2
        let centerHalfHeight = viewfinder.height * centerScale / 2
        let step = configuration.mode == .spot && configuration.spotAreaPercent <= 2
            ? 2 : 4

        for y in stride(from: 0, to: plane.height, by: step) {
            for x in stride(from: 0, to: plane.width, by: step) {
                guard let point = Self.bufferPointToDisplay(
                    x: x,
                    y: y,
                    width: plane.width,
                    height: plane.height,
                    previewAspectRatio: configuration.previewAspectRatio
                ) else {
                    continue
                }
                guard viewfinder.contains(x: point.x, y: point.y) else { continue }
                let value = Int(plane.values[y * plane.width + x])
                switch configuration.mode {
                case .average:
                    primary[value] += 1
                    primaryCount += 1
                case .spot:
                    let dx = (point.x - spot.x) * configuration.previewAspectRatio
                    let dy = point.y - spot.y
                    if dx * dx + dy * dy <= spotRadiusSquared {
                        primary[value] += 1
                        primaryCount += 1
                    }
                case .centerWeighted:
                    if abs(point.x - viewfinder.centerX) <= centerHalfWidth,
                       abs(point.y - viewfinder.centerY) <= centerHalfHeight {
                        primary[value] += 1
                        primaryCount += 1
                    } else {
                        secondary[value] += 1
                        secondaryCount += 1
                    }
                }
            }
        }

        guard primaryCount >= 32 else { return nil }
        let primaryMean = trimmedLinearMean(
            histogram: primary,
            count: primaryCount,
            isVideoRange: plane.isVideoRange
        )
        guard configuration.mode == .centerWeighted else { return primaryMean }
        guard secondaryCount >= 32 else { return nil }
        let secondaryMean = trimmedLinearMean(
            histogram: secondary,
            count: secondaryCount,
            isVideoRange: plane.isVideoRange
        )
        let centerWeight = Double(configuration.centerWeightPercent) / 100
        return primaryMean * centerWeight + secondaryMean * (1 - centerWeight)
    }

    private func trimmedLinearMean(
        histogram: [Int],
        count: Int,
        isVideoRange: Bool
    ) -> Double {
        var retained = histogram
        var trimLow = Int(Double(count) * 0.05)
        var trimHigh = trimLow
        for index in retained.indices where trimLow > 0 {
            let removed = min(retained[index], trimLow)
            retained[index] -= removed
            trimLow -= removed
        }
        for index in retained.indices.reversed() where trimHigh > 0 {
            let removed = min(retained[index], trimHigh)
            retained[index] -= removed
            trimHigh -= removed
        }

        var retainedCount = 0
        var sum = 0.0
        for (value, frequency) in retained.enumerated() where frequency > 0 {
            let normalized = Self.normalizedLuminance(
                UInt8(value),
                isVideoRange: isVideoRange
            )
            sum += pow(normalized, 2.2) * Double(frequency)
            retainedCount += frequency
        }
        return max(sum / Double(max(retainedCount, 1)), Self.minimumLuminance)
    }

    private func exposureMap(
        plane: LuminancePlane,
        settingEV: Double,
        timestampNanoseconds: Int64,
        configuration: MeteringConfiguration
    ) -> ExposureMap {
        let mapWidth: Int
        let mapHeight: Int
        if configuration.previewAspectRatio <= 1 {
            mapHeight = 240
            mapWidth = max(Int(Double(mapHeight) * configuration.previewAspectRatio), 1)
        } else {
            mapWidth = 240
            mapHeight = max(Int(Double(mapWidth) / configuration.previewAspectRatio), 1)
        }

        var evValues = [Float](repeating: .nan, count: mapWidth * mapHeight)
        var rawValues = [UInt8](repeating: 0, count: mapWidth * mapHeight)
        var clipped = [Bool](repeating: false, count: mapWidth * mapHeight)
        for mapY in 0..<mapHeight {
            for mapX in 0..<mapWidth {
                let displayX = (Double(mapX) + 0.5) / Double(mapWidth)
                let displayY = (Double(mapY) + 0.5) / Double(mapHeight)
                let index = mapY * mapWidth + mapX
                guard configuration.viewfinder.contains(x: displayX, y: displayY) else {
                    continue
                }
                let buffer = Self.displayPointToBuffer(
                    x: displayX,
                    y: displayY,
                    width: plane.width,
                    height: plane.height,
                    previewAspectRatio: configuration.previewAspectRatio
                )
                let raw = plane.values[buffer.y * plane.width + buffer.x]
                rawValues[index] = raw
                clipped[index] = Self.isHighlightClipped(
                    raw,
                    isVideoRange: plane.isVideoRange
                )
                let normalized = Self.normalizedLuminance(
                    raw,
                    isVideoRange: plane.isVideoRange
                )
                let linear = max(pow(normalized, 2.2), Self.minimumLuminance)
                evValues[index] = Float(
                    settingEV + log2(linear / Self.targetLuminance)
                        + configuration.calibrationOffset
                )
            }
        }
        return ExposureMap(
            width: mapWidth,
            height: mapHeight,
            pixelEV100: evValues,
            rawLuminance: rawValues,
            clippedHighlights: clipped,
            timestampNanoseconds: timestampNanoseconds,
            revision: configuration.revision
        )
    }

    public static func isHighlightClipped(_ value: UInt8, isVideoRange: Bool) -> Bool {
        value >= (isVideoRange ? 235 : 250)
    }

    // The back-camera buffer is landscape and is rotated right into the portrait preview.
    public static func bufferPointToDisplay(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        previewAspectRatio: Double
    ) -> NormalizedPoint? {
        guard width > 0, height > 0, previewAspectRatio > 0 else { return nil }
        let rawX = (Double(x) + 0.5) / Double(width)
        let rawY = (Double(y) + 0.5) / Double(height)
        let orientedX = 1 - rawY
        let orientedY = rawX
        let crop = aspectFillCrop(
            sourceAspectRatio: Double(height) / Double(width),
            previewAspectRatio: previewAspectRatio
        )
        guard orientedX >= crop.left, orientedX <= crop.right,
              orientedY >= crop.top, orientedY <= crop.bottom else {
            return nil
        }
        return NormalizedPoint(
            x: (orientedX - crop.left) / crop.width,
            y: (orientedY - crop.top) / crop.height
        )
    }

    public static func displayPointToBuffer(
        x: Double,
        y: Double,
        width: Int,
        height: Int,
        previewAspectRatio: Double
    ) -> (x: Int, y: Int) {
        precondition(width > 0 && height > 0 && previewAspectRatio > 0)
        let crop = aspectFillCrop(
            sourceAspectRatio: Double(height) / Double(width),
            previewAspectRatio: previewAspectRatio
        )
        let orientedX = crop.left + min(max(x, 0), 1) * crop.width
        let orientedY = crop.top + min(max(y, 0), 1) * crop.height
        let rawX = orientedY
        let rawY = 1 - orientedX
        return (
            min(max(Int(rawX * Double(width)), 0), width - 1),
            min(max(Int(rawY * Double(height)), 0), height - 1)
        )
    }

    private static func aspectFillCrop(
        sourceAspectRatio: Double,
        previewAspectRatio: Double
    ) -> (left: Double, top: Double, right: Double, bottom: Double,
          width: Double, height: Double) {
        if previewAspectRatio > sourceAspectRatio {
            let height = sourceAspectRatio / previewAspectRatio
            let top = (1 - height) / 2
            return (0, top, 1, top + height, 1, height)
        }
        let width = previewAspectRatio / sourceAspectRatio
        let left = (1 - width) / 2
        return (left, 0, left + width, 1, width, 1)
    }

    private static let targetLuminance = 0.18
    private static let minimumLuminance = 1e-6
}
