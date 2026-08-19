import Foundation

enum FilmGrainModel {
    static func intensity(
        baseGrainIntensity: Double,
        deltaEV: Double
    ) -> Double {
        precondition((0...maxBaseIntensity).contains(baseGrainIntensity))
        precondition(deltaEV.isFinite)
        return min(
            baseGrainIntensity + max(-deltaEV, 0) * underexposureGrainPerStop,
            maxGrainIntensity
        )
    }

    static func baseIntensity(forISO iso: Int) -> Double {
        precondition(iso > 0)
        return min(
            max(baseIntensityAtISO400 * Double(iso) / 400, minBaseIntensity),
            maxBaseIntensity
        )
    }

    static func noise(x: Int, y: Int) -> Double {
        var hash = x &* 0x1f1f1f1f &+ y &* 0x045d9f3b
        hash ^= hash >> 16
        hash &*= 0x045d9f3b
        hash ^= hash >> 16
        return Double(hash & 0x00ff_ffff) / 8_388_607.5 - 1
    }

    static let maxBaseIntensity = 0.1
    static let maxGrainIntensity = 0.065
    private static let minBaseIntensity = 0.004
    private static let baseIntensityAtISO400 = 0.012
    private static let underexposureGrainPerStop = 0.012
}
