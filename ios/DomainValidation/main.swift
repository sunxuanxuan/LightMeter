import FilmLightMeterDomain
import Foundation

func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    guard condition() else {
        FileHandle.standardError.write(Data("FAILED: \(message)\n".utf8))
        exit(1)
    }
}

let target = ExposureEngine.targetEV(
    meteredEV100: 12,
    filmISO: 400,
    exposureCompensation: 1
)
check(abs(target - 13) < 0.000_001, "ISO/EC target EV")

let recommendation = ExposureEngine.recommendation(
    meteredEV100: 12,
    filmISO: 400,
    exposureCompensation: 1,
    focalLengthMillimeters: 50
)
check(recommendation != nil, "exposure recommendation exists")
check(recommendation!.primary.error <= 1.0 / 6.0, "exposure error tolerance")

check(
    MeteringEngine.normalizedLuminance(16, isVideoRange: true) == 0,
    "video range black"
)
check(
    MeteringEngine.normalizedLuminance(235, isVideoRange: true) == 1,
    "video range white"
)
check(
    MeteringEngine.isHighlightClipped(235, isVideoRange: true),
    "video range clipping threshold"
)
check(
    !MeteringEngine.isHighlightClipped(235, isVideoRange: false),
    "full range does not clip at video white"
)
check(
    MeteringEngine.isHighlightClipped(250, isVideoRange: false),
    "full range clipping threshold"
)

let mappedTop = MeteringEngine.displayPointToBuffer(
    x: 0.5,
    y: 0,
    width: 1920,
    height: 1080,
    previewAspectRatio: 0.75
)
check(abs(mappedTop.x - 240) <= 1, "aspect fill vertical crop")
check(abs(mappedTop.y - 540) <= 1, "aspect fill horizontal center")
check(
    MeteringEngine.bufferPointToDisplay(
        x: 0,
        y: 540,
        width: 1920,
        height: 1080,
        previewAspectRatio: 0.75
    ) == nil,
    "aspect fill excludes invisible buffer pixels"
)
let roundTrip = MeteringEngine.bufferPointToDisplay(
    x: mappedTop.x,
    y: mappedTop.y,
    width: 1920,
    height: 1080,
    previewAspectRatio: 0.75
)
check(abs((roundTrip?.x ?? -1) - 0.5) < 0.002, "aspect fill round-trip x")
check(abs(roundTrip?.y ?? -1) < 0.002, "aspect fill round-trip y")

let map = ExposureMap(
    width: 2,
    height: 1,
    pixelEV100: [10, 20],
    rawLuminance: [100, 250],
    clippedHighlights: [false, true],
    timestampNanoseconds: 1,
    revision: 1
)
let mask = ExposureRiskEngine.calculate(
    baseline: map,
    viewfinder: NormalizedRect(left: 0, top: 0, right: 0.5, bottom: 1),
    referenceEV100: 10,
    highlightLatitude: 3,
    shadowLatitude: 3
)
check(mask.highlightRatio == 0, "viewfinder risk clipping")
check(mask.bgra[7] == 0, "outside risk pixel transparency")

check(
    ViewfinderEngine.constrainedZoom(
        fitZoomFactor: 12,
        minimum: 1,
        maximum: 8
    ) == 8,
    "zoom capability limit"
)

print("Domain validation passed: 16 checks")
