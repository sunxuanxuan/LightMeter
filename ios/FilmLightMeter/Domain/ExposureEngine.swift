import Foundation

public struct ExposurePair: Equatable, Sendable {
    public let apertureLabel: String
    public let aperture: Double
    public let shutterLabel: String
    public let shutterSeconds: Double
    public let ev: Double
    public let error: Double
}

public struct ExposureRecommendation: Equatable, Sendable {
    public let targetEV: Double
    public let primary: ExposurePair
    public let equivalents: [ExposurePair]
}

public enum ExposureEngine {
    private struct Aperture {
        let label: String
        let value: Double
    }

    private struct Shutter {
        let label: String
        let seconds: Double
    }

    private static let apertures: [Aperture] = [
        ("f/1", 1), ("f/1.1", 1.1), ("f/1.2", 1.2), ("f/1.4", 1.4),
        ("f/1.6", 1.6), ("f/1.8", 1.8), ("f/2", 2), ("f/2.2", 2.2),
        ("f/2.5", 2.5), ("f/2.8", 2.8), ("f/3.2", 3.2), ("f/3.5", 3.5),
        ("f/4", 4), ("f/4.5", 4.5), ("f/5", 5), ("f/5.6", 5.6),
        ("f/6.3", 6.3), ("f/7.1", 7.1), ("f/8", 8), ("f/9", 9),
        ("f/10", 10), ("f/11", 11), ("f/13", 13), ("f/14", 14),
        ("f/16", 16), ("f/18", 18), ("f/20", 20), ("f/22", 22),
    ].map { Aperture(label: $0.0, value: $0.1) }

    private static let shutters: [Shutter] = [
        ("1/2000", 1.0 / 2000), ("1/1000", 1.0 / 1000),
        ("1/500", 1.0 / 500), ("1/250", 1.0 / 250),
        ("1/125", 1.0 / 125), ("1/60", 1.0 / 60),
        ("1/30", 1.0 / 30), ("1/15", 1.0 / 15),
        ("1/8", 1.0 / 8), ("1/4", 1.0 / 4), ("1/2", 1.0 / 2),
        ("1s", 1), ("2s", 2), ("4s", 4), ("8s", 8),
    ].map { Shutter(label: $0.0, seconds: $0.1) }

    private static let commonApertures = [5.6, 8, 4, 11, 2.8, 16]

    public static func targetEV(
        meteredEV100: Double,
        filmISO: Int,
        exposureCompensation: Double
    ) -> Double {
        meteredEV100 + log2(Double(filmISO) / 100) - exposureCompensation
    }

    public static func recommendation(
        meteredEV100: Double,
        filmISO: Int,
        exposureCompensation: Double,
        focalLengthMillimeters: Double
    ) -> ExposureRecommendation? {
        let target = targetEV(
            meteredEV100: meteredEV100,
            filmISO: filmISO,
            exposureCompensation: exposureCompensation
        )
        let pairs = exposurePairs(targetEV: target)
        guard let primary = primaryPair(
            pairs: pairs,
            safeShutter: safeShutter(focalLengthMillimeters: focalLengthMillimeters)
        ) else {
            return nil
        }
        return ExposureRecommendation(targetEV: target, primary: primary, equivalents: pairs)
    }

    public static func exposurePairs(targetEV: Double) -> [ExposurePair] {
        let nearest = apertures.map { closestPair(aperture: $0, targetEV: targetEV) }
        let withinTolerance = nearest.filter { $0.error <= 1.0 / 6.0 }
        let candidates: [ExposurePair]
        if withinTolerance.isEmpty {
            let minimum = nearest.map(\.error).min() ?? .infinity
            candidates = nearest.filter { abs($0.error - minimum) < 1e-9 }
        } else {
            candidates = withinTolerance
        }

        return Dictionary(grouping: candidates, by: \.shutterLabel)
            .compactMap { $0.value.min(by: { $0.error < $1.error }) }
            .sorted(by: { $0.shutterSeconds < $1.shutterSeconds })
    }

    private static func closestPair(aperture: Aperture, targetEV: Double) -> ExposurePair {
        shutters.map { shutter in
            let ev = log2(aperture.value * aperture.value / shutter.seconds)
            return ExposurePair(
                apertureLabel: aperture.label,
                aperture: aperture.value,
                shutterLabel: shutter.label,
                shutterSeconds: shutter.seconds,
                ev: ev,
                error: abs(ev - targetEV)
            )
        }.min(by: { $0.error < $1.error })!
    }

    private static func primaryPair(
        pairs: [ExposurePair],
        safeShutter: Double
    ) -> ExposurePair? {
        let safePairs = pairs.filter { $0.shutterSeconds <= safeShutter }
        guard !safePairs.isEmpty else {
            return pairs.min {
                ($0.error, $0.shutterSeconds) < ($1.error, $1.shutterSeconds)
            }
        }
        return safePairs.min {
            let lhsPriority = commonApertures.firstIndex(of: $0.aperture) ?? .max
            let rhsPriority = commonApertures.firstIndex(of: $1.aperture) ?? .max
            return (lhsPriority, $0.error, $0.shutterSeconds)
                < (rhsPriority, $1.error, $1.shutterSeconds)
        }
    }

    private static func safeShutter(focalLengthMillimeters: Double) -> Double {
        switch focalLengthMillimeters {
        case ...35: 1.0 / 30
        case ...50: 1.0 / 60
        case ...90: 1.0 / 125
        default: 1.0 / 250
        }
    }
}
