import Foundation

public struct ExposureRiskMask: Sendable {
    public let width: Int
    public let height: Int
    public let bgra: [UInt8]
    public let highlightRatio: Double
    public let shadowRatio: Double
}

public struct ExposureProbeRequirements: Equatable, Sendable {
    public let highlight: Bool
    public let shadow: Bool
    public var isEmpty: Bool { !highlight && !shadow }
}

public enum ExposureRiskEngine {
    public static func referenceEV100(
        frozenMeteredEV100: Double,
        exposureCompensation: Double
    ) -> Double {
        frozenMeteredEV100 - exposureCompensation
    }

    public static func probeRequirements(
        map: ExposureMap,
        viewfinder: NormalizedRect,
        referenceEV100: Double,
        highlightLatitude: Double,
        shadowLatitude: Double
    ) -> ExposureProbeRequirements {
        var highlight = false
        var shadow = false
        for (index, value) in map.pixelEV100.enumerated() where value.isFinite {
            let x = (Double(index % map.width) + 0.5) / Double(map.width)
            let y = (Double(index / map.width) + 0.5) / Double(map.height)
            guard viewfinder.contains(x: x, y: y) else { continue }
            let delta = Double(value) - referenceEV100
            highlight = highlight || map.clippedHighlights[index]
                || delta >= highlightLatitude
            shadow = shadow || delta <= -shadowLatitude
            if highlight && shadow { break }
        }
        return ExposureProbeRequirements(highlight: highlight, shadow: shadow)
    }

    public static func calculate(
        baseline: ExposureMap,
        viewfinder: NormalizedRect,
        referenceEV100: Double,
        highlightLatitude: Double,
        shadowLatitude: Double,
        shadowProbe: ExposureMap? = nil,
        highlightProbe: ExposureMap? = nil
    ) -> ExposureRiskMask {
        let compatibleShadow = compatible(baseline, shadowProbe)
        let compatibleHighlight = compatible(baseline, highlightProbe)
        let baselineStats = compatibleShadow != nil || compatibleHighlight != nil
            ? LocalDetailStatistics(map: baseline) : nil
        let shadowStats = compatibleShadow.map(LocalDetailStatistics.init)
        let highlightStats = compatibleHighlight.map(LocalDetailStatistics.init)

        var bytes = [UInt8](repeating: 0, count: baseline.pixelEV100.count * 4)
        var highlightCount = 0
        var shadowCount = 0
        var analyzedCount = 0

        for (index, value) in baseline.pixelEV100.enumerated() where value.isFinite {
            let x = (Double(index % baseline.width) + 0.5) / Double(baseline.width)
            let y = (Double(index / baseline.width) + 0.5) / Double(baseline.height)
            guard viewfinder.contains(x: x, y: y) else { continue }
            analyzedCount += 1
            let delta = Double(value) - referenceEV100
            let highlightCandidate = baseline.clippedHighlights[index]
                || delta >= highlightLatitude

            if highlightCandidate {
                let revealed = highlightStats == nil || DetailRevealDetector.highlightRevealed(
                    baseline: baselineStats!,
                    probe: highlightStats!,
                    index: index
                )
                if revealed {
                    highlightCount += 1
                    writeColor(
                        bytes: &bytes,
                        index: index,
                        red: 255,
                        green: 45,
                        blue: 45,
                        excess: baseline.clippedHighlights[index]
                            ? 2 : delta - highlightLatitude
                    )
                }
            } else if delta <= -shadowLatitude {
                let revealed = shadowStats == nil || DetailRevealDetector.shadowRevealed(
                    baseline: baselineStats!,
                    probe: shadowStats!,
                    index: index
                )
                if revealed {
                    shadowCount += 1
                    writeColor(
                        bytes: &bytes,
                        index: index,
                        red: 0,
                        green: 210,
                        blue: 106,
                        excess: -delta - shadowLatitude
                    )
                }
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

    private static func compatible(_ baseline: ExposureMap, _ probe: ExposureMap?) -> ExposureMap? {
        guard let probe,
              probe.width == baseline.width,
              probe.height == baseline.height else {
            return nil
        }
        return probe
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

private enum DetailRevealDetector {
    static func shadowRevealed(
        baseline: LocalDetailStatistics,
        probe: LocalDetailStatistics,
        index: Int
    ) -> Bool {
        let probeMean = probe.mean(index)
        return probeMean - baseline.mean(index) >= 12
            && hasNewDetail(baseline: baseline, probe: probe, index: index)
            && probeMean <= 245
    }

    static func highlightRevealed(
        baseline: LocalDetailStatistics,
        probe: LocalDetailStatistics,
        index: Int
    ) -> Bool {
        let probeMean = probe.mean(index)
        return baseline.mean(index) - probeMean >= 12
            && hasNewDetail(baseline: baseline, probe: probe, index: index)
            && probeMean >= 10
    }

    private static func hasNewDetail(
        baseline: LocalDetailStatistics,
        probe: LocalDetailStatistics,
        index: Int
    ) -> Bool {
        let baselineGradient = baseline.gradient(index)
        let probeGradient = probe.gradient(index)
        return baselineGradient <= 4
            && probeGradient >= 7
            && probeGradient - baselineGradient >= 3
    }
}

private struct LocalDetailStatistics {
    let width: Int
    let height: Int
    let stride: Int
    let luminance: [Double]
    let luminanceCount: [Int]
    let gradient: [Double]
    let gradientCount: [Int]

    init(map: ExposureMap) {
        width = map.width
        height = map.height
        stride = width + 1
        var luminance = [Double](repeating: 0, count: (width + 1) * (height + 1))
        var luminanceCount = [Int](repeating: 0, count: luminance.count)
        var gradient = [Double](repeating: 0, count: luminance.count)
        var gradientCount = [Int](repeating: 0, count: luminance.count)

        for y in 0..<height {
            for x in 0..<width {
                let mapIndex = y * width + x
                let valid = map.pixelEV100[mapIndex].isFinite
                let value = Double(map.rawLuminance[mapIndex])
                var localGradient = 0.0
                var localCount = 0
                if valid, x + 1 < width, map.pixelEV100[mapIndex + 1].isFinite {
                    localGradient += abs(value - Double(map.rawLuminance[mapIndex + 1]))
                    localCount += 1
                }
                if valid, y + 1 < height, map.pixelEV100[mapIndex + width].isFinite {
                    localGradient += abs(value - Double(map.rawLuminance[mapIndex + width]))
                    localCount += 1
                }

                let index = (y + 1) * stride + x + 1
                let above = index - stride
                let left = index - 1
                let aboveLeft = above - 1
                luminance[index] = (valid ? value : 0)
                    + luminance[above] + luminance[left] - luminance[aboveLeft]
                luminanceCount[index] = (valid ? 1 : 0)
                    + luminanceCount[above] + luminanceCount[left]
                    - luminanceCount[aboveLeft]
                gradient[index] = localGradient
                    + gradient[above] + gradient[left] - gradient[aboveLeft]
                gradientCount[index] = localCount
                    + gradientCount[above] + gradientCount[left]
                    - gradientCount[aboveLeft]
            }
        }
        self.luminance = luminance
        self.luminanceCount = luminanceCount
        self.gradient = gradient
        self.gradientCount = gradientCount
    }

    func mean(_ index: Int) -> Double {
        let bounds = bounds(index)
        return area(luminance, bounds) / Double(max(area(luminanceCount, bounds), 1))
    }

    func gradient(_ index: Int) -> Double {
        let bounds = bounds(index)
        return area(gradient, bounds) / Double(max(area(gradientCount, bounds), 1))
    }

    private func bounds(_ index: Int) -> (Int, Int, Int, Int) {
        let x = index % width
        let y = index / width
        return (
            max(x - 2, 0),
            max(y - 2, 0),
            min(x + 3, width),
            min(y + 3, height)
        )
    }

    private func area(_ values: [Double], _ b: (Int, Int, Int, Int)) -> Double {
        values[b.3 * stride + b.2] - values[b.1 * stride + b.2]
            - values[b.3 * stride + b.0] + values[b.1 * stride + b.0]
    }

    private func area(_ values: [Int], _ b: (Int, Int, Int, Int)) -> Int {
        values[b.3 * stride + b.2] - values[b.1 * stride + b.2]
            - values[b.3 * stride + b.0] + values[b.1 * stride + b.0]
    }
}
