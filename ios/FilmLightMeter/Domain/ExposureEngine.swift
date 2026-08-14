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
        Aperture(label: "f/1", value: 1),
        Aperture(label: "f/1.1", value: 1.1),
        Aperture(label: "f/1.2", value: 1.2),
        Aperture(label: "f/1.4", value: 1.4),
        Aperture(label: "f/1.6", value: 1.6),
        Aperture(label: "f/1.8", value: 1.8),
        Aperture(label: "f/2", value: 2),
        Aperture(label: "f/2.2", value: 2.2),
        Aperture(label: "f/2.5", value: 2.5),
        Aperture(label: "f/2.8", value: 2.8),
        Aperture(label: "f/3.2", value: 3.2),
        Aperture(label: "f/3.5", value: 3.5),
        Aperture(label: "f/4", value: 4),
        Aperture(label: "f/4.5", value: 4.5),
        Aperture(label: "f/5", value: 5),
        Aperture(label: "f/5.6", value: 5.6),
        Aperture(label: "f/6.3", value: 6.3),
        Aperture(label: "f/7.1", value: 7.1),
        Aperture(label: "f/8", value: 8),
        Aperture(label: "f/9", value: 9),
        Aperture(label: "f/10", value: 10),
        Aperture(label: "f/11", value: 11),
        Aperture(label: "f/13", value: 13),
        Aperture(label: "f/14", value: 14),
        Aperture(label: "f/16", value: 16),
        Aperture(label: "f/18", value: 18),
        Aperture(label: "f/20", value: 20),
        Aperture(label: "f/22", value: 22),
    ]

    private static let shutters: [Shutter] = [
        Shutter(label: "1/2000", seconds: 1.0 / 2000),
        Shutter(label: "1/1000", seconds: 1.0 / 1000),
        Shutter(label: "1/500", seconds: 1.0 / 500),
        Shutter(label: "1/250", seconds: 1.0 / 250),
        Shutter(label: "1/125", seconds: 1.0 / 125),
        Shutter(label: "1/60", seconds: 1.0 / 60),
        Shutter(label: "1/30", seconds: 1.0 / 30),
        Shutter(label: "1/15", seconds: 1.0 / 15),
        Shutter(label: "1/8", seconds: 1.0 / 8),
        Shutter(label: "1/4", seconds: 1.0 / 4),
        Shutter(label: "1/2", seconds: 1.0 / 2),
        Shutter(label: "1s", seconds: 1),
        Shutter(label: "2s", seconds: 2),
        Shutter(label: "4s", seconds: 4),
        Shutter(label: "8s", seconds: 8),
    ]

    private static let commonApertures = [5.6, 8, 4, 11, 2.8, 16]

    public static var apertureLabels: [String] {
        apertures.map(\.label)
    }

    public static var shutterLabels: [String] {
        shutters.map(\.label)
    }

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

    public static func closestPair(
        apertureLabel: String,
        targetEV: Double
    ) -> ExposurePair? {
        guard let aperture = apertures.first(where: { $0.label == apertureLabel }) else {
            return nil
        }
        return closestPair(aperture: aperture, targetEV: targetEV)
    }

    public static func closestPair(
        shutterLabel: String,
        targetEV: Double
    ) -> ExposurePair? {
        guard let shutter = shutters.first(where: { $0.label == shutterLabel }) else {
            return nil
        }
        return closestPair(shutter: shutter, targetEV: targetEV)
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

    private static func closestPair(shutter: Shutter, targetEV: Double) -> ExposurePair {
        apertures.map { aperture in
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
