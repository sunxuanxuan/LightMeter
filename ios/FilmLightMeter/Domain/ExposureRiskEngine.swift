import Foundation

public struct ExposureRiskMask: Sendable {
    public let width: Int
    public let height: Int
    public let bgra: [UInt8]
    public let highlightRatio: Double
    public let shadowRatio: Double
}

public enum ExposureRiskEngine {
    public static func referenceEV100(
        frozenMeteredEV100: Double,
        exposureCompensation: Double
    ) -> Double {
        frozenMeteredEV100 - exposureCompensation
    }

    public static func calculate(
        baseline: ExposureMap,
        viewfinder: NormalizedRect,
        referenceEV100: Double,
        highlightLatitude: Double,
        shadowLatitude: Double
    ) -> ExposureRiskMask {
        precondition(referenceEV100.isFinite)
        precondition(highlightLatitude > 0)
        precondition(shadowLatitude > 0)

        var bytes = [UInt8](repeating: 0, count: baseline.pixelEV100.count * 4)
        var highlightCount = 0
        var shadowCount = 0
        var analyzedCount = 0
        let highlightThreshold = quantizeMagnitudeToTenth(highlightLatitude)
        let shadowThreshold = quantizeMagnitudeToTenth(shadowLatitude)

        for (index, value) in baseline.pixelEV100.enumerated() where value.isFinite {
            let x = (Double(index % baseline.width) + 0.5) / Double(baseline.width)
            let y = (Double(index / baseline.width) + 0.5) / Double(baseline.height)
            guard viewfinder.contains(x: x, y: y) else { continue }
            analyzedCount += 1
            let delta = Double(value) - referenceEV100
            let deltaMagnitude = quantizeMagnitudeToTenth(delta)
            let highlightCandidate = baseline.clippedHighlights[index]
                || delta > 0 && deltaMagnitude > highlightThreshold

            if highlightCandidate {
                highlightCount += 1
                writeColor(
                    bytes: &bytes,
                    index: index,
                    red: 255,
                    green: 45,
                    blue: 45,
                    excess: baseline.clippedHighlights[index]
                        ? 2 : deltaMagnitude - highlightThreshold
                )
            } else if delta < 0 && deltaMagnitude > shadowThreshold {
                shadowCount += 1
                writeColor(
                    bytes: &bytes,
                    index: index,
                    red: 0,
                    green: 210,
                    blue: 106,
                    excess: deltaMagnitude - shadowThreshold
                )
            }
        }

        return ExposureRiskMask(
            width: baseline.width,
            height: baseline.height,
            bgra: bytes,
            highlightRatio: Double(highlightCount) / Double(max(analyzedCount, 1)),
            shadowRatio: Double(shadowCount) / Double(max(analyzedCount, 1))
        )
    }

    private static func quantizeMagnitudeToTenth(_ stops: Double) -> Double {
        (abs(stops) * 10).rounded() / 10
    }

    private static func writeColor(
        bytes: inout [UInt8],
        index: Int,
        red: UInt8,
        green: UInt8,
        blue: UInt8,
        excess: Double
    ) {
        let intensity = min(max(excess / 2, 0), 1)
        let alpha = UInt8((51 + (230 - 51) * intensity).rounded())
        let offset = index * 4
        bytes[offset] = blue
        bytes[offset + 1] = green
        bytes[offset + 2] = red
        bytes[offset + 3] = alpha
    }
}
