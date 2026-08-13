import Foundation

public enum MeteringMode: String, Codable, CaseIterable, Sendable {
    case spot
    case centerWeighted
    case average

    public var displayName: String {
        switch self {
        case .spot: "点测光"
        case .centerWeighted: "中央重点"
        case .average: "平均测光"
        }
    }
}

public enum FrameFormat: String, Codable, CaseIterable, Sendable {
    case film135
    case apsC
    case film645
    case film66

    public var displayName: String {
        switch self {
        case .film135: "135"
        case .apsC: "APS-C"
        case .film645: "6x4.5"
        case .film66: "6x6"
        }
    }

    public var sizeMillimeters: (width: Double, height: Double) {
        switch self {
        case .film135: (36, 24)
        case .apsC: (23.6, 15.7)
        case .film645: (56, 41.5)
        case .film66: (56, 56)
        }
    }
}

public struct NormalizedPoint: Codable, Equatable, Sendable {
    public let x: Double
    public let y: Double

    public init(x: Double, y: Double) {
        self.x = min(max(x, 0), 1)
        self.y = min(max(y, 0), 1)
    }
}

public struct NormalizedRect: Codable, Equatable, Sendable {
    public let left: Double
    public let top: Double
    public let right: Double
    public let bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        precondition(left >= 0 && top >= 0 && right <= 1 && bottom <= 1)
        precondition(left < right && top < bottom)
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public static let full = NormalizedRect(left: 0, top: 0, right: 1, bottom: 1)
    public var width: Double { right - left }
    public var height: Double { bottom - top }
    public var centerX: Double { (left + right) / 2 }
    public var centerY: Double { (top + bottom) / 2 }

    public func contains(x: Double, y: Double) -> Bool {
        x >= left && x <= right && y >= top && y <= bottom
    }
}

public struct MeteringConfiguration: Equatable, Sendable {
    public var mode: MeteringMode = .centerWeighted
    public var spotPoint: NormalizedPoint?
    public var spotAreaPercent = 5
    public var centerAreaPercent = 25
    public var centerWeightPercent = 70
    public var viewfinder = NormalizedRect.full
    public var previewAspectRatio = 9.0 / 16.0
    public var calibrationOffset = 0.0
    public var revision: UInt64 = 0

    public init() {}
}

public struct CameraExposureMetadata: Equatable, Sendable {
    public let exposureSeconds: Double
    public let sensitivityISO: Double
    public let aperture: Double

    public init(exposureSeconds: Double, sensitivityISO: Double, aperture: Double) {
        self.exposureSeconds = exposureSeconds
        self.sensitivityISO = sensitivityISO
        self.aperture = aperture
    }

    public var isValid: Bool {
        exposureSeconds.isFinite && exposureSeconds > 0
            && sensitivityISO.isFinite && sensitivityISO > 0
            && aperture.isFinite && aperture > 0
    }
}

public struct LuminancePlane: Sendable {
    public let width: Int
    public let height: Int
    public let values: [UInt8]
    public let isVideoRange: Bool

    public init(width: Int, height: Int, values: [UInt8], isVideoRange: Bool) {
        precondition(width > 0 && height > 0 && values.count == width * height)
        self.width = width
        self.height = height
        self.values = values
        self.isVideoRange = isVideoRange
    }
}

public struct ExposureMap: Sendable {
    public let width: Int
    public let height: Int
    public let pixelEV100: [Float]
    public let rawLuminance: [UInt8]
    public let clippedHighlights: [Bool]
    public let timestampNanoseconds: Int64
    public let revision: UInt64

    public init(
        width: Int,
        height: Int,
        pixelEV100: [Float],
        rawLuminance: [UInt8],
        clippedHighlights: [Bool],
        timestampNanoseconds: Int64,
        revision: UInt64
    ) {
        precondition(pixelEV100.count == width * height)
        precondition(rawLuminance.count == pixelEV100.count)
        precondition(clippedHighlights.count == pixelEV100.count)
        self.width = width
        self.height = height
        self.pixelEV100 = pixelEV100
        self.rawLuminance = rawLuminance
        self.clippedHighlights = clippedHighlights
        self.timestampNanoseconds = timestampNanoseconds
        self.revision = revision
    }
}

public struct ExposureSnapshot: Sendable {
    public let exposureMap: ExposureMap
    public let meteredEV100: Double
    public let measuredLuminance: Double
    public let metadata: CameraExposureMetadata
    public let timestampNanoseconds: Int64
    public let revision: UInt64

    public init(
        exposureMap: ExposureMap,
        meteredEV100: Double,
        measuredLuminance: Double,
        metadata: CameraExposureMetadata,
        timestampNanoseconds: Int64,
        revision: UInt64
    ) {
        self.exposureMap = exposureMap
        self.meteredEV100 = meteredEV100
        self.measuredLuminance = measuredLuminance
        self.metadata = metadata
        self.timestampNanoseconds = timestampNanoseconds
        self.revision = revision
    }
}
