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

    func testVideoRangeLuminanceUsesLegalRange() {
        XCTAssertEqual(MeteringEngine.normalizedLuminance(16, isVideoRange: true), 0)
        XCTAssertEqual(MeteringEngine.normalizedLuminance(235, isVideoRange: true), 1)
    }

    func testHighlightClippingUsesPixelRangeSpecificThresholds() {
        XCTAssertTrue(MeteringEngine.isHighlightClipped(235, isVideoRange: true))
        XCTAssertFalse(MeteringEngine.isHighlightClipped(235, isVideoRange: false))
        XCTAssertFalse(MeteringEngine.isHighlightClipped(249, isVideoRange: false))
        XCTAssertTrue(MeteringEngine.isHighlightClipped(250, isVideoRange: false))
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

    func testConstrainedZoomUsesDeviceLimits() {
        XCTAssertEqual(ViewfinderEngine.constrainedZoom(
            fitZoomFactor: 12,
            minimum: 1,
            maximum: 8
        ), 8)
    }
}
