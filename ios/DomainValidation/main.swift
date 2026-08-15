import FilmLightMeterDomain
import Foundation

final class CheckCounter: @unchecked Sendable {
    var value = 0
}

let checkCounter = CheckCounter()

func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    guard condition() else {
        FileHandle.standardError.write(Data("FAILED: \(message)\n".utf8))
        exit(1)
    }
    checkCounter.value += 1
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

let aperturePair = ExposureEngine.closestPair(apertureLabel: "f/8", targetEV: 12)
check(aperturePair?.apertureLabel == "f/8", "selected aperture is retained")
check((aperturePair?.error ?? 1) <= 1.0 / 6.0, "selected aperture matches target EV")
let shutterPair = ExposureEngine.closestPair(shutterLabel: "1/125", targetEV: 12)
check(shutterPair?.shutterLabel == "1/125", "selected shutter is retained")
check((shutterPair?.error ?? 1) <= 1.0 / 6.0, "selected shutter matches target EV")

var presetSettings = AppSettings()
presetSettings.meteringMode = .spot
presetSettings.cameraMeteringPreset = .canonAL1
presetSettings.normalize()
check(presetSettings.meteringMode == .centerWeighted, "camera preset selects center weighted")
check(presetSettings.centerAreaPercent == 40, "camera preset center area")
check(presetSettings.centerWeightPercent == 65, "camera preset center weight")
presetSettings.focalLengthMillimeters = 85
presetSettings.cameraMeteringPreset = .olympus35SPAverage
presetSettings.normalize()
check(presetSettings.meteringMode == .centerAverage, "35 SP average metering mode")
check(presetSettings.centerAverageAreaPercent == 20, "35 SP average metering area")
check(presetSettings.spotAreaPercent == 5, "35 SP average keeps spot area")
check(presetSettings.focalLengthMillimeters == 85, "35 SP keeps focal length")
presetSettings.cameraMeteringPreset = .olympus35SPSpot
presetSettings.normalize()
check(presetSettings.meteringMode == .spot, "35 SP spot metering mode")
check(presetSettings.spotAreaPercent == 2, "35 SP spot metering area")
check(presetSettings.focalLengthMillimeters == 85, "35 SP spot keeps focal length")
presetSettings.focalLengthMillimeters = 180
presetSettings.cameraMeteringPreset = nil
presetSettings.normalize()
check(presetSettings.focalLengthMillimeters == 150, "focal length maximum")

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

let boundaryMap = ExposureMap(
    width: 6,
    height: 1,
    pixelEV100: [13, 13.04, 13.11, 7, 6.96, 6.89],
    rawLuminance: [128, 128, 128, 128, 128, 128],
    clippedHighlights: [false, false, false, false, false, false],
    timestampNanoseconds: 1,
    revision: 1
)
let boundaryMask = ExposureRiskEngine.calculate(
    baseline: boundaryMap,
    viewfinder: .full,
    referenceEV100: 10,
    highlightLatitude: 3,
    shadowLatitude: 3
)
check(boundaryMask.bgra[3] == 0, "highlight boundary is not risk")
check(boundaryMask.bgra[7] == 0, "highlight rounds to boundary")
check(boundaryMask.bgra[11] > 0, "highlight beyond boundary is risk")
check(boundaryMask.bgra[15] == 0, "shadow boundary is not risk")
check(boundaryMask.bgra[19] == 0, "shadow rounds to boundary")
check(boundaryMask.bgra[23] > 0, "shadow beyond boundary is risk")

check(
    ViewfinderEngine.constrainedZoom(
        fitZoomFactor: 12,
        minimum: 1,
        maximum: 8
    ) == 8,
    "zoom capability limit"
)

let supportedFocalRange = ViewfinderEngine.supportedFocalLengthRange(
    previewAspectRatio: 9.0 / 16.0,
    frameFormat: .film135,
    cameraHorizontalFieldOfViewDegrees: 60,
    minimumZoomFactor: 1,
    maximumZoomFactor: 8,
    allowedRange: 20...150
)
check((supportedFocalRange?.lowerBound ?? 0) >= 20, "hardware focal minimum")
check((supportedFocalRange?.upperBound ?? 151) <= 150, "hardware focal maximum")
check(
    ViewfinderEngine.supportedFocalLengthRange(
        previewAspectRatio: 9.0 / 16.0,
        frameFormat: .film66,
        cameraHorizontalFieldOfViewDegrees: 20,
        minimumZoomFactor: 1,
        maximumZoomFactor: 1,
        allowedRange: 100...150
    ) == nil,
    "hardware focal range without intersection"
)

var zoomingConfiguration = MeteringConfiguration()
zoomingConfiguration.isZoomReady = false
let zoomingResult = MeteringEngine().analyze(
    plane: LuminancePlane(
        width: 1,
        height: 1,
        values: [128],
        isVideoRange: true
    ),
    metadata: CameraExposureMetadata(
        exposureSeconds: 1.0 / 125,
        sensitivityISO: 100,
        aperture: 2.8
    ),
    timestampNanoseconds: 1,
    configuration: zoomingConfiguration
)
check(zoomingResult == nil, "metering waits for zoom readiness")

print("Domain validation passed: \(checkCounter.value) checks")
