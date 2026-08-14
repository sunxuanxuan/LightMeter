import Foundation

public struct ViewfinderProjection: Equatable, Sendable {
    public let baseWidthFraction: Double
    public let baseHeightFraction: Double
    public let fitZoomFactor: Double

    public func rect(actualZoomFactor: Double) -> NormalizedRect {
        let width = min(max(baseWidthFraction * actualZoomFactor, 0.001), 1)
        let height = min(max(baseHeightFraction * actualZoomFactor, 0.001), 1)
        return NormalizedRect(
            left: (1 - width) / 2,
            top: (1 - height) / 2,
            right: (1 + width) / 2,
            bottom: (1 + height) / 2
        )
    }

    public func equivalentFocalLength(
        targetFocalLengthMillimeters: Double,
        zoomFactor: Double
    ) -> Double {
        precondition(targetFocalLengthMillimeters > 0)
        precondition(zoomFactor > 0)
        return targetFocalLengthMillimeters * zoomFactor / fitZoomFactor
    }
}

public enum ViewfinderEngine {
    public static func supportedFocalLengthRange(
        previewAspectRatio: Double,
        frameFormat: FrameFormat,
        cameraHorizontalFieldOfViewDegrees: Double,
        minimumZoomFactor: Double,
        maximumZoomFactor: Double,
        allowedRange: ClosedRange<Double>
    ) -> ClosedRange<Double>? {
        precondition(minimumZoomFactor > 0)
        precondition(maximumZoomFactor >= minimumZoomFactor)
        precondition(allowedRange.lowerBound > 0)

        let reference = projection(
            previewAspectRatio: previewAspectRatio,
            frameFormat: frameFormat,
            targetFocalLengthMillimeters: 1,
            cameraHorizontalFieldOfViewDegrees: cameraHorizontalFieldOfViewDegrees
        )
        let hardwareMinimum = ceil(reference.equivalentFocalLength(
            targetFocalLengthMillimeters: 1,
            zoomFactor: minimumZoomFactor
        ))
        let hardwareMaximum = floor(reference.equivalentFocalLength(
            targetFocalLengthMillimeters: 1,
            zoomFactor: maximumZoomFactor
        ))
        let minimum = max(allowedRange.lowerBound, hardwareMinimum)
        let maximum = min(allowedRange.upperBound, hardwareMaximum)
        return minimum <= maximum ? minimum...maximum : nil
    }

    public static func projection(
        previewAspectRatio: Double,
        frameFormat: FrameFormat,
        targetFocalLengthMillimeters: Double,
        cameraHorizontalFieldOfViewDegrees: Double
    ) -> ViewfinderProjection {
        precondition(previewAspectRatio > 0)
        precondition(targetFocalLengthMillimeters > 0)
        precondition(cameraHorizontalFieldOfViewDegrees > 0)

        let filmSize = frameFormat.sizeMillimeters
        let portraitWidth = min(filmSize.width, filmSize.height)
        let portraitHeight = max(filmSize.width, filmSize.height)
        let targetHorizontalFOV = 2 * atan(
            portraitWidth / (2 * targetFocalLengthMillimeters)
        )
        let targetVerticalFOV = 2 * atan(
            portraitHeight / (2 * targetFocalLengthMillimeters)
        )

        let cameraLandscapeHorizontalFOV =
            cameraHorizontalFieldOfViewDegrees * .pi / 180
        let cameraPortraitVerticalFOV = cameraLandscapeHorizontalFOV
        let cameraPortraitHorizontalFOV = 2 * atan(
            tan(cameraPortraitVerticalFOV / 2) * previewAspectRatio
        )

        let widthFraction = tan(targetHorizontalFOV / 2)
            / tan(cameraPortraitHorizontalFOV / 2)
        let heightFraction = tan(targetVerticalFOV / 2)
            / tan(cameraPortraitVerticalFOV / 2)
        let fitZoom = 1 / max(widthFraction, heightFraction)
        return ViewfinderProjection(
            baseWidthFraction: widthFraction,
            baseHeightFraction: heightFraction,
            fitZoomFactor: fitZoom
        )
    }

    public static func constrainedZoom(
        fitZoomFactor: Double,
        minimum: Double,
        maximum: Double
    ) -> Double {
        min(max(fitZoomFactor, minimum), maximum)
    }
}
