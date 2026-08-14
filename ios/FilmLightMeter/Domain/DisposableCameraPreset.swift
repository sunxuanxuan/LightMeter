import Foundation

public enum EvidenceLevel: String, Codable, Sendable {
    case official
    case measured
    case estimated

    public var displayName: String {
        switch self {
        case .official: "官方资料"
        case .measured: "实测资料"
        case .estimated: "经验近似"
        }
    }
}

public struct FilmProfile: Codable, Equatable, Sendable {
    public let name: String
    public let iso: Int
    public let highlightLatitudeStops: Double
    public let shadowLatitudeStops: Double
    public let evidence: EvidenceLevel

    public init(
        name: String,
        iso: Int,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        evidence: EvidenceLevel
    ) {
        precondition(iso > 0)
        precondition(highlightLatitudeStops > 0)
        precondition(shadowLatitudeStops > 0)
        self.name = name
        self.iso = iso
        self.highlightLatitudeStops = highlightLatitudeStops
        self.shadowLatitudeStops = shadowLatitudeStops
        self.evidence = evidence
    }
}

public struct FixedOptics: Codable, Equatable, Sendable {
    public let aperture: Double
    public let focalLengthMillimeters: Double
    public let minimumFocusMeters: Double?

    public init(
        aperture: Double,
        focalLengthMillimeters: Double,
        minimumFocusMeters: Double?
    ) {
        precondition(aperture > 0)
        precondition(focalLengthMillimeters > 0)
        precondition(minimumFocusMeters == nil || minimumFocusMeters! > 0)
        self.aperture = aperture
        self.focalLengthMillimeters = focalLengthMillimeters
        self.minimumFocusMeters = minimumFocusMeters
    }
}

public struct FlashProfile: Codable, Equatable, Sendable {
    public let effectiveDistanceMinMeters: Double
    public let effectiveDistanceMaxMeters: Double

    public init(
        effectiveDistanceMinMeters: Double,
        effectiveDistanceMaxMeters: Double
    ) {
        precondition(effectiveDistanceMinMeters > 0)
        precondition(effectiveDistanceMaxMeters >= effectiveDistanceMinMeters)
        self.effectiveDistanceMinMeters = effectiveDistanceMinMeters
        self.effectiveDistanceMaxMeters = effectiveDistanceMaxMeters
    }
}

public struct DisposableCameraPreset: Identifiable, Equatable, Sendable {
    public let id: String
    public let presetVersion: Int
    public let brand: String
    public let model: String
    public let regionOrBatch: String?
    public let film: FilmProfile
    public let optics: FixedOptics
    public let shutterSeconds: Double
    public let flash: FlashProfile?
    public let exposureEvidence: EvidenceLevel
    public let displayNameOverride: String?

    public init(
        id: String,
        presetVersion: Int,
        brand: String,
        model: String,
        regionOrBatch: String?,
        film: FilmProfile,
        optics: FixedOptics,
        shutterSeconds: Double,
        flash: FlashProfile?,
        exposureEvidence: EvidenceLevel,
        displayNameOverride: String? = nil
    ) {
        precondition(!id.isEmpty)
        precondition(presetVersion > 0)
        precondition(!brand.isEmpty)
        precondition(!model.isEmpty)
        precondition(shutterSeconds > 0)
        self.id = id
        self.presetVersion = presetVersion
        self.brand = brand
        self.model = model
        self.regionOrBatch = regionOrBatch
        self.film = film
        self.optics = optics
        self.shutterSeconds = shutterSeconds
        self.flash = flash
        self.exposureEvidence = exposureEvidence
        self.displayNameOverride = displayNameOverride
    }

    public var displayName: String {
        displayNameOverride ?? "\(brand) \(model)"
    }
}

public struct ManualCameraConfig: Codable, Equatable, Sendable {
    public static let presetID = "manual-camera"

    public var iso: Int
    public var shutterDenominator: Int
    public var aperture: Double
    public var focalLengthMillimeters: Double

    public init(
        iso: Int = 400,
        shutterDenominator: Int = 125,
        aperture: Double = 11,
        focalLengthMillimeters: Double = 32
    ) {
        self.iso = iso
        self.shutterDenominator = shutterDenominator
        self.aperture = aperture
        self.focalLengthMillimeters = focalLengthMillimeters
    }

    public var isValid: Bool {
        (25...6400).contains(iso)
            && (1...8000).contains(shutterDenominator)
            && aperture.isFinite && (1...64).contains(aperture)
            && focalLengthMillimeters.isFinite
            && (20...150).contains(focalLengthMillimeters)
    }

    public func makePreset() -> DisposableCameraPreset {
        precondition(isValid)
        return DisposableCameraPreset(
            id: Self.presetID,
            presetVersion: 1,
            brand: "手动",
            model: "配置",
            regionOrBatch: "自定义一次性胶片相机参数",
            film: FilmProfile(
                name: "通用彩色负片",
                iso: iso,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 2,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: aperture,
                focalLengthMillimeters: focalLengthMillimeters,
                minimumFocusMeters: nil
            ),
            shutterSeconds: 1 / Double(shutterDenominator),
            flash: nil,
            exposureEvidence: .estimated,
            displayNameOverride: "自定义"
        )
    }
}

public enum BuiltInDisposableCameraRepository {
    public static let presets: [DisposableCameraPreset] = [
        DisposableCameraPreset(
            id: "kodak-funsaver-800",
            presetVersion: 1,
            brand: "Kodak",
            model: "FunSaver / Fun Saver",
            regionOrBatch: "31mm · 1/100s 代表版本",
            film: FilmProfile(
                name: "Kodak ISO 800 彩色负片",
                iso: 800,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 2,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: 10,
                focalLengthMillimeters: 31,
                minimumFocusMeters: 1
            ),
            shutterSeconds: 1 / 100,
            flash: FlashProfile(
                effectiveDistanceMinMeters: 1.2,
                effectiveDistanceMaxMeters: 3.5
            ),
            exposureEvidence: .estimated
        ),
        DisposableCameraPreset(
            id: "kodak-power-flash-800",
            presetVersion: 1,
            brand: "Kodak",
            model: "Power Flash",
            regionOrBatch: "镜头与快门采用公开资料近似",
            film: FilmProfile(
                name: "Kodak ISO 800 彩色负片",
                iso: 800,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 2,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: 16,
                focalLengthMillimeters: 30,
                minimumFocusMeters: 1
            ),
            shutterSeconds: 1 / 100,
            flash: FlashProfile(
                effectiveDistanceMinMeters: 1.2,
                effectiveDistanceMaxMeters: 4.5
            ),
            exposureEvidence: .estimated
        ),
        DisposableCameraPreset(
            id: "kodak-sport-waterproof-800",
            presetVersion: 1,
            brand: "Kodak",
            model: "Sport / Waterproof",
            regionOrBatch: "镜头与快门采用同类机型保守近似",
            film: FilmProfile(
                name: "Kodak UltraMax ISO 800 彩色负片",
                iso: 800,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 2,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: 10,
                focalLengthMillimeters: 30,
                minimumFocusMeters: 1
            ),
            shutterSeconds: 1 / 125,
            flash: nil,
            exposureEvidence: .estimated
        ),
        DisposableCameraPreset(
            id: "fujifilm-quicksnap-flash-400",
            presetVersion: 2,
            brand: "Fujifilm",
            model: "QuickSnap Flash 400",
            regionOrBatch: "1/100s 版本",
            film: FilmProfile(
                name: "FUJICOLOR SUPERIA X-TRA 400",
                iso: 400,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 5 / 3,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: 10,
                focalLengthMillimeters: 32,
                minimumFocusMeters: 1
            ),
            shutterSeconds: 1 / 100,
            flash: FlashProfile(
                effectiveDistanceMinMeters: 1,
                effectiveDistanceMaxMeters: 3
            ),
            exposureEvidence: .official
        ),
        DisposableCameraPreset(
            id: "fujifilm-quicksnap-waterproof-800",
            presetVersion: 1,
            brand: "Fujifilm",
            model: "QuickSnap Waterproof / Marine",
            regionOrBatch: "ISO 800 · 10m 防水版本",
            film: FilmProfile(
                name: "Fujifilm ISO 800 彩色负片",
                iso: 800,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 2,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: 10,
                focalLengthMillimeters: 32,
                minimumFocusMeters: 1
            ),
            shutterSeconds: 1 / 125,
            flash: nil,
            exposureEvidence: .official
        ),
        DisposableCameraPreset(
            id: "fujifilm-c400-jelly",
            presetVersion: 1,
            brand: "Fujifilm",
            model: "C400 果冻胶卷相机",
            regionOrBatch: "预装 C400 · 可重复装卷",
            film: FilmProfile(
                name: "Fujifilm C400 ISO 400 彩色负片",
                iso: 400,
                highlightLatitudeStops: 3,
                shadowLatitudeStops: 2,
                evidence: .estimated
            ),
            optics: FixedOptics(
                aperture: 11,
                focalLengthMillimeters: 31,
                minimumFocusMeters: 1
            ),
            shutterSeconds: 1 / 125,
            flash: FlashProfile(
                effectiveDistanceMinMeters: 1,
                effectiveDistanceMaxMeters: 3
            ),
            exposureEvidence: .estimated
        ),
    ]

    public static func find(id: String, version: Int? = nil) -> DisposableCameraPreset? {
        presets.first {
            $0.id == id && (version == nil || $0.presetVersion == version)
        }
    }
}
