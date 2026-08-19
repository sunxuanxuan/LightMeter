import Foundation

public enum PreviewSceneRating: Sendable {
    case good
    case caution
    case poor
    case unavailable
}

public enum PreviewAdviceCode: Sendable {
    case suitable
    case ambientTooDark
    case ambientTooBright
    case useFlash
    case unavailable
}

public struct FilmPreviewEvaluation: Sendable {
    public let presetEV100: Double
    public let sceneDeltaEV: Double?
    public let rating: PreviewSceneRating
    public let adviceCode: PreviewAdviceCode
}

public enum FilmPreviewEngine {
    public static func presetEV100(_ preset: DisposableCameraPreset) -> Double {
        log2(
            preset.optics.aperture * preset.optics.aperture
                / preset.shutterSeconds
        ) - log2(Double(preset.film.iso) / 100)
    }

    public static func pixelDeltaEV(
        pixelEV100: Double,
        preset: DisposableCameraPreset
    ) -> Double {
        precondition(pixelEV100.isFinite)
        return pixelEV100 - presetEV100(preset)
    }

    public static func evaluate(
        meteredEV100: Double?,
        preset: DisposableCameraPreset
    ) -> FilmPreviewEvaluation {
        let reference = presetEV100(preset)
        guard let meteredEV100, meteredEV100.isFinite else {
            return FilmPreviewEvaluation(
                presetEV100: reference,
                sceneDeltaEV: nil,
                rating: .unavailable,
                adviceCode: .unavailable
            )
        }

        let delta = meteredEV100 - reference
        let shadowLimit = -preset.film.shadowLatitudeStops
        let highlightLimit = preset.film.highlightLatitudeStops
        let rating: PreviewSceneRating
        if delta <= shadowLimit - 1 + epsilon
            || delta >= highlightLimit + 1 - epsilon {
            rating = .poor
        } else if delta <= shadowLimit + 1 + epsilon
            || delta >= highlightLimit - 1 - epsilon {
            rating = .caution
        } else {
            rating = .good
        }

        let advice: PreviewAdviceCode
        if delta <= shadowLimit + epsilon {
            advice = preset.flash == nil ? .ambientTooDark : .useFlash
        } else if delta >= highlightLimit - epsilon {
            advice = .ambientTooBright
        } else {
            advice = .suitable
        }
        return FilmPreviewEvaluation(
            presetEV100: reference,
            sceneDeltaEV: delta,
            rating: rating,
            adviceCode: advice
        )
    }

    private static let epsilon = 1e-9
}

public enum FilmResponseCurve {
    public static func targetLuminance(
        deltaEV: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double
    ) -> Double {
        precondition(deltaEV.isFinite)
        precondition(highlightLatitudeStops > 0)
        precondition(shadowLatitudeStops > 0)

        let result: Double
        if deltaEV < -shadowLatitudeStops {
            let progress = clamp(
                (-deltaEV - shadowLatitudeStops) / outsideExtensionStops
            )
            result = interpolate(
                from: displayLuminance(-shadowLatitudeStops),
                to: 0,
                progress: progress
            )
        } else if deltaEV > highlightLatitudeStops {
            let progress = clamp(
                (deltaEV - highlightLatitudeStops) / outsideExtensionStops
            )
            result = interpolate(
                from: displayLuminance(highlightLatitudeStops),
                to: 1,
                progress: progress
            )
        } else {
            result = displayLuminance(deltaEV)
        }
        return abs(result) < 1e-9 ? 0 : clamp(result)
    }

    private static func interpolate(
        from: Double,
        to: Double,
        progress: Double
    ) -> Double {
        from + (to - from) * smoothstep(progress)
    }

    private static func displayLuminance(_ deltaEV: Double) -> Double {
        middleGrayLuminance * pow(2, deltaEV * displayExposureScale)
    }

    private static func smoothstep(_ value: Double) -> Double {
        let normalized = clamp(value)
        return normalized * normalized * (3 - 2 * normalized)
    }

    private static func clamp(_ value: Double) -> Double {
        min(max(value, 0), 1)
    }

    private static let middleGrayLuminance = 0.18
    private static let displayExposureScale = 0.5
    private static let outsideExtensionStops = 2.0
}
