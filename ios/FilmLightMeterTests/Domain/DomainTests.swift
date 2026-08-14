import XCTest
#if canImport(FilmLightMeterDomain)
@testable import FilmLightMeterDomain
#else
@testable import FilmLightMeter
#endif

final class DomainTests: XCTestCase {
    func testTargetEVAppliesISOAndCompensation() {
        let value = ExposureEngine.targetEV(
            meteredEV100: 12,
            filmISO: 400,
            exposureCompensation: 1
        )
        XCTAssertEqual(value, 13, accuracy: 0.000_001)
    }

    func testExposurePairStaysWithinOneSixthStop() throws {
        let result = try XCTUnwrap(ExposureEngine.recommendation(
            meteredEV100: 12,
            filmISO: 400,
            exposureCompensation: 1,
            focalLengthMillimeters: 50
        ))
        XCTAssertLessThanOrEqual(result.primary.error, 1.0 / 6.0)
    }

    func testSelectingApertureKeepsTargetExposure() throws {
        let pair = try XCTUnwrap(ExposureEngine.closestPair(
            apertureLabel: "f/8",
            targetEV: 12
        ))
        XCTAssertEqual(pair.apertureLabel, "f/8")
        XCTAssertLessThanOrEqual(pair.error, 1.0 / 6.0)
    }

    func testSelectingShutterKeepsTargetExposure() throws {
        let pair = try XCTUnwrap(ExposureEngine.closestPair(
            shutterLabel: "1/125",
            targetEV: 12
        ))
        XCTAssertEqual(pair.shutterLabel, "1/125")
        XCTAssertLessThanOrEqual(pair.error, 1.0 / 6.0)
    }

    func testExposureControlLabelsContainFullRanges() {
        XCTAssertEqual(ExposureEngine.apertureLabels.first, "f/1")
        XCTAssertEqual(ExposureEngine.apertureLabels.last, "f/22")
        XCTAssertEqual(ExposureEngine.shutterLabels.first, "1/2000")
        XCTAssertEqual(ExposureEngine.shutterLabels.last, "8s")
    }

    func testCameraMeteringPresetsMatchAndroidParameters() {
        XCTAssertEqual(CameraMeteringPreset.canonNewF1.centerAreaPercent, 60)
        XCTAssertEqual(CameraMeteringPreset.canonNewF1.centerWeightPercent, 70)
        XCTAssertEqual(CameraMeteringPreset.canonAL1.centerAreaPercent, 40)
        XCTAssertEqual(CameraMeteringPreset.canonAL1.centerWeightPercent, 65)
    }

    func testCameraMeteringPresetNormalizesModeAndWeights() {
        var settings = AppSettings()
        settings.meteringMode = .spot
        settings.centerAreaPercent = 25
        settings.centerWeightPercent = 95
        settings.cameraMeteringPreset = .canonAL1
        settings.normalize()

        XCTAssertEqual(settings.meteringMode, .centerWeighted)
        XCTAssertEqual(settings.centerAreaPercent, 40)
        XCTAssertEqual(settings.centerWeightPercent, 65)
    }

    func testFocalLengthNormalizesToAndroidRange() {
        var settings = AppSettings()
        settings.focalLengthMillimeters = 180
        settings.normalize()
        XCTAssertEqual(settings.focalLengthMillimeters, 150)

        settings.focalLengthMillimeters = 10
        settings.normalize()
        XCTAssertEqual(settings.focalLengthMillimeters, 20)
    }

    func testSettingsDecodeWithoutCameraPreset() throws {
        let encoded = try JSONEncoder().encode(AppSettings())
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        object.removeValue(forKey: "cameraMeteringPreset")
        let legacyData = try JSONSerialization.data(withJSONObject: object)

        let decoded = try JSONDecoder().decode(AppSettings.self, from: legacyData)
        XCTAssertNil(decoded.cameraMeteringPreset)
    }

    func testVideoRangeLuminanceUsesLegalRange() {
        XCTAssertEqual(MeteringEngine.normalizedLuminance(16, isVideoRange: true), 0)
        XCTAssertEqual(MeteringEngine.normalizedLuminance(235, isVideoRange: true), 1)
    }

    func testMeteringRejectsFramesUntilZoomIsReady() {
        var configuration = MeteringConfiguration()
        configuration.isZoomReady = false
        let result = MeteringEngine().analyze(
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
            configuration: configuration
        )

        XCTAssertNil(result)
    }

    func testHighlightClippingUsesPixelRangeSpecificThresholds() {
        XCTAssertTrue(MeteringEngine.isHighlightClipped(235, isVideoRange: true))
        XCTAssertFalse(MeteringEngine.isHighlightClipped(235, isVideoRange: false))
        XCTAssertFalse(MeteringEngine.isHighlightClipped(249, isVideoRange: false))
        XCTAssertTrue(MeteringEngine.isHighlightClipped(250, isVideoRange: false))
    }

    func testFrozenExposureMapUsesAndroidResolution() throws {
        var configuration = MeteringConfiguration()
        configuration.previewAspectRatio = 1
        let metadata = CameraExposureMetadata(
            exposureSeconds: 1.0 / 125,
            sensitivityISO: 100,
            aperture: 2.8
        )
        let result = MeteringResult(
            meteredEV100: 10,
            measuredLuminance: 0.18,
            metadata: metadata,
            timestampNanoseconds: 1,
            revision: configuration.revision
        )
        let snapshot = try XCTUnwrap(MeteringEngine().makeSnapshot(
            plane: LuminancePlane(
                width: 4,
                height: 4,
                values: [UInt8](repeating: 128, count: 16),
                isVideoRange: false
            ),
            result: result,
            configuration: configuration
        ))

        XCTAssertEqual(snapshot.exposureMap.width, 480)
        XCTAssertEqual(snapshot.exposureMap.height, 480)
    }

    func testAspectFillMappingCropsInvisibleBufferRows() throws {
        let mappedTop = MeteringEngine.displayPointToBuffer(
            x: 0.5,
            y: 0,
            width: 1920,
            height: 1080,
            previewAspectRatio: 0.75
        )
        XCTAssertEqual(mappedTop.x, 240, accuracy: 1)
        XCTAssertEqual(mappedTop.y, 540, accuracy: 1)

        let invisible = MeteringEngine.bufferPointToDisplay(
            x: 0,
            y: 540,
            width: 1920,
            height: 1080,
            previewAspectRatio: 0.75
        )
        XCTAssertNil(invisible)

        let visible = try XCTUnwrap(MeteringEngine.bufferPointToDisplay(
            x: mappedTop.x,
            y: mappedTop.y,
            width: 1920,
            height: 1080,
            previewAspectRatio: 0.75
        ))
        XCTAssertEqual(visible.x, 0.5, accuracy: 0.002)
        XCTAssertEqual(visible.y, 0, accuracy: 0.002)
    }

    func testRiskMaskExcludesOutsideViewfinder() {
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
        XCTAssertEqual(mask.highlightRatio, 0)
        XCTAssertEqual(mask.bgra[7], 0)
    }

    func testRiskMaskUsesStrictTenthStopBoundaries() {
        let map = ExposureMap(
            width: 6,
            height: 1,
            pixelEV100: [13, 13.04, 13.11, 7, 6.96, 6.89],
            rawLuminance: [128, 128, 128, 128, 128, 128],
            clippedHighlights: [false, false, false, false, false, false],
            timestampNanoseconds: 1,
            revision: 1
        )
        let mask = ExposureRiskEngine.calculate(
            baseline: map,
            viewfinder: .full,
            referenceEV100: 10,
            highlightLatitude: 3,
            shadowLatitude: 3
        )
        XCTAssertEqual(mask.highlightRatio, 1.0 / 6.0, accuracy: 0.000_001)
        XCTAssertEqual(mask.shadowRatio, 1.0 / 6.0, accuracy: 0.000_001)
        XCTAssertEqual(mask.bgra[3], 0)
        XCTAssertEqual(mask.bgra[7], 0)
        XCTAssertGreaterThan(mask.bgra[11], 0)
        XCTAssertEqual(mask.bgra[15], 0)
        XCTAssertEqual(mask.bgra[19], 0)
        XCTAssertGreaterThan(mask.bgra[23], 0)
    }

    func testClippedHighlightIsAlwaysMarked() {
        let map = ExposureMap(
            width: 1,
            height: 1,
            pixelEV100: [10],
            rawLuminance: [250],
            clippedHighlights: [true],
            timestampNanoseconds: 1,
            revision: 1
        )
        let mask = ExposureRiskEngine.calculate(
            baseline: map,
            viewfinder: .full,
            referenceEV100: 10,
            highlightLatitude: 3,
            shadowLatitude: 3
        )
        XCTAssertEqual(mask.highlightRatio, 1)
        XCTAssertEqual(mask.bgra[3], 230)
    }

    func testConstrainedZoomUsesDeviceLimits() {
        XCTAssertEqual(ViewfinderEngine.constrainedZoom(
            fitZoomFactor: 12,
            minimum: 1,
            maximum: 8
        ), 8)
    }

    func testSupportedFocalRangeRespectsHardwareZoomAndProductLimits() throws {
        let range = try XCTUnwrap(ViewfinderEngine.supportedFocalLengthRange(
            previewAspectRatio: 9.0 / 16.0,
            frameFormat: .film135,
            cameraHorizontalFieldOfViewDegrees: 60,
            minimumZoomFactor: 1,
            maximumZoomFactor: 8,
            allowedRange: 20...150
        ))

        XCTAssertGreaterThanOrEqual(range.lowerBound, 20)
        XCTAssertLessThanOrEqual(range.upperBound, 150)
        XCTAssertLessThanOrEqual(range.lowerBound, range.upperBound)
        let minimumProjection = ViewfinderEngine.projection(
            previewAspectRatio: 9.0 / 16.0,
            frameFormat: .film135,
            targetFocalLengthMillimeters: range.lowerBound,
            cameraHorizontalFieldOfViewDegrees: 60
        )
        let maximumProjection = ViewfinderEngine.projection(
            previewAspectRatio: 9.0 / 16.0,
            frameFormat: .film135,
            targetFocalLengthMillimeters: range.upperBound,
            cameraHorizontalFieldOfViewDegrees: 60
        )
        XCTAssertGreaterThanOrEqual(minimumProjection.fitZoomFactor, 1)
        XCTAssertLessThanOrEqual(maximumProjection.fitZoomFactor, 8)
    }

    func testSupportedFocalRangeReturnsNilWithoutHardwareIntersection() {
        XCTAssertNil(ViewfinderEngine.supportedFocalLengthRange(
            previewAspectRatio: 9.0 / 16.0,
            frameFormat: .film66,
            cameraHorizontalFieldOfViewDegrees: 20,
            minimumZoomFactor: 1,
            maximumZoomFactor: 1,
            allowedRange: 100...150
        ))
    }
}
