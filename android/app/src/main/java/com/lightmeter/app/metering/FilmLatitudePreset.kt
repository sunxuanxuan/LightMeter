package com.lightmeter.app.metering

enum class FilmLatitudeEvidence(val displayName: String) {
    OFFICIAL_CURVE("官方曲线"),
    OFFICIAL_DATA("官方资料"),
    CONSERVATIVE_ESTIMATE("经验近似"),
}

enum class FilmLatitudePreset(
    val displayName: String,
    val highlightStops: Double,
    val shadowStops: Double,
    val evidence: FilmLatitudeEvidence,
) {
    KODAK_GOLD_200(
        displayName = "柯达 Gold 200",
        highlightStops = 3.0,
        shadowStops = 2.0,
        evidence = FilmLatitudeEvidence.OFFICIAL_DATA,
    ),
    KODAK_ULTRAMAX_400(
        displayName = "柯达 UltraMax 400",
        highlightStops = 3.0,
        shadowStops = 2.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    FUJIFILM_200_CURRENT(
        displayName = "富士 200（现行版）",
        highlightStops = 3.0,
        shadowStops = 2.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    FUJIFILM_400_CURRENT(
        displayName = "富士 400（现行版）",
        highlightStops = 3.0,
        shadowStops = 2.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    FUJIFILM_C200_SUPERIA_400_LEGACY(
        displayName = "富士 C200 / Superia 400（旧日版）",
        highlightStops = 3.0,
        shadowStops = 5.0 / 3.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    LUCKY_C200(
        displayName = "乐凯 C200",
        highlightStops = 8.0 / 3.0,
        shadowStops = 5.0 / 3.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    LUCKY_C400(
        displayName = "乐凯 C400",
        highlightStops = 8.0 / 3.0,
        shadowStops = 5.0 / 3.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    KODAK_VISION3_250D_5207(
        displayName = "柯达 VISION3 250D 5207",
        highlightStops = 14.0 / 3.0,
        shadowStops = 3.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    KODAK_VISION3_50D_5203(
        displayName = "柯达 VISION3 50D 5203",
        highlightStops = 5.0,
        shadowStops = 3.0,
        evidence = FilmLatitudeEvidence.OFFICIAL_CURVE,
    ),
    KODAK_E100(
        displayName = "柯达 E100",
        highlightStops = 2.0 / 3.0,
        shadowStops = 5.0 / 3.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
    KODAK_EKTACHROME_100D_5294(
        displayName = "柯达 5294 / 7294 100D",
        highlightStops = 2.0 / 3.0,
        shadowStops = 5.0 / 3.0,
        evidence = FilmLatitudeEvidence.CONSERVATIVE_ESTIMATE,
    ),
}
