import Foundation

public enum FilmLatitudePreset: String, Codable, CaseIterable, Sendable, Identifiable {
    case kodakGold200
    case kodakUltraMax400
    case fujifilm200
    case fujifilm400
    case fujifilmLegacy
    case luckyC200
    case luckyC400
    case kodakVision250D
    case kodakVision50D
    case kodakE100
    case kodakEktachrome100D

    public var id: String { rawValue }

    public var values: (name: String, highlight: Double, shadow: Double) {
        switch self {
        case .kodakGold200: ("柯达 Gold 200", 3, 2)
        case .kodakUltraMax400: ("柯达 UltraMax 400", 3, 2)
        case .fujifilm200: ("富士 200（现行版）", 3, 2)
        case .fujifilm400: ("富士 400（现行版）", 3, 2)
        case .fujifilmLegacy: ("富士 C200 / Superia 400（旧日版）", 3, 5.0 / 3)
        case .luckyC200: ("乐凯 C200", 8.0 / 3, 5.0 / 3)
        case .luckyC400: ("乐凯 C400", 8.0 / 3, 5.0 / 3)
        case .kodakVision250D: ("柯达 VISION3 250D 5207", 14.0 / 3, 3)
        case .kodakVision50D: ("柯达 VISION3 50D 5203", 5, 3)
        case .kodakE100: ("柯达 E100", 2.0 / 3, 5.0 / 3)
        case .kodakEktachrome100D: ("柯达 5294 / 7294 100D", 2.0 / 3, 5.0 / 3)
        }
    }
}

public struct AppSettings: Codable, Equatable, Sendable {
    public var selectedISO = 100
    public var exposureCompensation = 0.0
    public var meteringMode = MeteringMode.centerWeighted
    public var spotAreaPercent = 5
    public var centerAverageAreaPercent: Int?
    public var centerAreaPercent = 25
    public var centerWeightPercent = 70
    public var cameraMeteringPreset: CameraMeteringPreset?
    public var frameFormat = FrameFormat.film135
    public var focalLengthMillimeters = 50.0
    public var exposureRiskEnabled = true
    public var highlightLatitude = 4.0
    public var shadowLatitude = 3.0
    public var filmLatitudePreset: FilmLatitudePreset?
    public var calibrationOffset = 0.0

    public init() {}

    public mutating func normalize() {
        selectedISO = min(max(Int((Double(selectedISO) / 50).rounded()) * 50, 100), 1600)
        exposureCompensation = Self.thirdStop(exposureCompensation, range: -3...3)
        spotAreaPercent = min(max(spotAreaPercent, 1), 10)
        centerAverageAreaPercent = min(max(centerAverageAreaPercent ?? 20, 1), 20)
        centerAreaPercent = min(max(centerAreaPercent, 5), 80)
        centerWeightPercent = min(max(centerWeightPercent, 50), 95)
        if let cameraMeteringPreset {
            meteringMode = cameraMeteringPreset.meteringMode
            if let area = cameraMeteringPreset.meteringAreaPercent {
                switch cameraMeteringPreset.meteringMode {
                case .spot:
                    spotAreaPercent = area
                case .centerAverage:
                    centerAverageAreaPercent = area
                case .centerWeighted, .average:
                    break
                }
            }
            centerAreaPercent = cameraMeteringPreset.centerAreaPercent
            centerWeightPercent = cameraMeteringPreset.centerWeightPercent
        }
        focalLengthMillimeters = min(max(focalLengthMillimeters, 20), 150)
        highlightLatitude = Self.thirdStop(highlightLatitude, range: 1.0 / 3...8)
        shadowLatitude = Self.thirdStop(shadowLatitude, range: 1.0 / 3...8)
        calibrationOffset = min(max(calibrationOffset, -3), 3)
    }

    public static func thirdStop(
        _ value: Double,
        range: ClosedRange<Double>
    ) -> Double {
        min(max((value * 3).rounded() / 3, range.lowerBound), range.upperBound)
    }
}
