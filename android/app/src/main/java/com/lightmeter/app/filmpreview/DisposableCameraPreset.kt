package com.lightmeter.app.filmpreview

enum class EvidenceLevel(val displayName: String) {
    OFFICIAL("官方资料"),
    MEASURED("实测资料"),
    ESTIMATED("经验近似"),
}

data class FilmProfile(
    val name: String,
    val iso: Int,
    val highlightLatitudeStops: Double,
    val shadowLatitudeStops: Double,
    val baseGrainIntensity: Double,
    val evidence: EvidenceLevel,
) {
    init {
        require(iso > 0)
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
        require(baseGrainIntensity in 0.0..0.1)
    }
}

data class FixedOptics(
    val aperture: Double,
    val focalLengthMm: Double,
    val minimumFocusMeters: Double?,
) {
    init {
        require(aperture > 0.0)
        require(focalLengthMm > 0.0)
        require(minimumFocusMeters == null || minimumFocusMeters > 0.0)
    }
}

data class FlashProfile(
    val effectiveDistanceMinMeters: Double,
    val effectiveDistanceMaxMeters: Double,
) {
    init {
        require(effectiveDistanceMinMeters > 0.0)
        require(effectiveDistanceMaxMeters >= effectiveDistanceMinMeters)
    }
}

data class DisposableCameraPreset(
    val id: String,
    val presetVersion: Int,
    val brand: String,
    val model: String,
    val regionOrBatch: String?,
    val film: FilmProfile,
    val optics: FixedOptics,
    val shutterSeconds: Double,
    val flash: FlashProfile?,
    val exposureEvidence: EvidenceLevel,
    val displayNameOverride: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(presetVersion > 0)
        require(brand.isNotBlank())
        require(model.isNotBlank())
        require(shutterSeconds > 0.0)
    }

    val displayName: String
        get() = displayNameOverride ?: "$brand $model"
}

interface DisposableCameraRepository {
    fun presets(): List<DisposableCameraPreset>

    fun find(id: String, version: Int? = null): DisposableCameraPreset?
}

object BuiltInDisposableCameraRepository : DisposableCameraRepository {
    private val builtInPresets = listOf(
        DisposableCameraPreset(
            id = "kodak-funsaver-800",
            presetVersion = 1,
            brand = "Kodak",
            model = "FunSaver",
            regionOrBatch = "31mm · 1/100s 代表版本",
            film = FilmProfile(
                name = "Kodak ISO 800 彩色负片",
                iso = 800,
                highlightLatitudeStops = 3.0,
                shadowLatitudeStops = 2.0,
                baseGrainIntensity = 0.018,
                evidence = EvidenceLevel.ESTIMATED,
            ),
            optics = FixedOptics(
                aperture = 10.0,
                focalLengthMm = 31.0,
                minimumFocusMeters = 1.0,
            ),
            shutterSeconds = 1.0 / 100.0,
            flash = FlashProfile(
                effectiveDistanceMinMeters = 1.2,
                effectiveDistanceMaxMeters = 3.5,
            ),
            exposureEvidence = EvidenceLevel.ESTIMATED,
        ),
        DisposableCameraPreset(
            id = "kodak-power-flash-800",
            presetVersion = 2,
            brand = "Kodak",
            model = "Power Flash",
            regionOrBatch = "30mm · f/10 · 1/125s · ISO 800",
            film = FilmProfile(
                name = "Kodak ISO 800 彩色负片",
                iso = 800,
                highlightLatitudeStops = 3.0,
                shadowLatitudeStops = 2.0,
                baseGrainIntensity = 0.018,
                evidence = EvidenceLevel.ESTIMATED,
            ),
            optics = FixedOptics(
                aperture = 10.0,
                focalLengthMm = 30.0,
                minimumFocusMeters = 1.0,
            ),
            shutterSeconds = 1.0 / 125.0,
            flash = FlashProfile(
                effectiveDistanceMinMeters = 1.2,
                effectiveDistanceMaxMeters = 4.5,
            ),
            exposureEvidence = EvidenceLevel.ESTIMATED,
        ),
        DisposableCameraPreset(
            id = "fujifilm-quicksnap-flash-400",
            presetVersion = 3,
            brand = "Fujifilm",
            model = "QuickSnap Flash 400",
            regionOrBatch = "32mm · f/10 · 1/140s · ISO 400",
            film = FilmProfile(
                name = "FUJICOLOR SUPERIA X-TRA 400",
                iso = 400,
                highlightLatitudeStops = 3.0,
                shadowLatitudeStops = 5.0 / 3.0,
                baseGrainIntensity = 0.012,
                evidence = EvidenceLevel.ESTIMATED,
            ),
            optics = FixedOptics(
                aperture = 10.0,
                focalLengthMm = 32.0,
                minimumFocusMeters = 1.0,
            ),
            shutterSeconds = 1.0 / 140.0,
            flash = FlashProfile(
                effectiveDistanceMinMeters = 1.0,
                effectiveDistanceMaxMeters = 3.0,
            ),
            exposureEvidence = EvidenceLevel.OFFICIAL,
        ),
        DisposableCameraPreset(
            id = "fujifilm-c400-jelly",
            presetVersion = 2,
            brand = "Fujifilm",
            model = "C400 果冻胶卷相机",
            regionOrBatch = "32mm · f/11 · 1/125s · ISO 400 · 单次 36 张",
            film = FilmProfile(
                name = "Fujifilm C400 ISO 400 彩色负片",
                iso = 400,
                highlightLatitudeStops = 3.0,
                shadowLatitudeStops = 2.0,
                baseGrainIntensity = 0.011,
                evidence = EvidenceLevel.ESTIMATED,
            ),
            optics = FixedOptics(
                aperture = 11.0,
                focalLengthMm = 32.0,
                minimumFocusMeters = 1.0,
            ),
            shutterSeconds = 1.0 / 125.0,
            flash = FlashProfile(
                effectiveDistanceMinMeters = 1.0,
                effectiveDistanceMaxMeters = 3.0,
            ),
            exposureEvidence = EvidenceLevel.ESTIMATED,
        ),
    )

    override fun presets(): List<DisposableCameraPreset> = builtInPresets

    override fun find(id: String, version: Int?): DisposableCameraPreset? {
        return builtInPresets.firstOrNull {
            it.id == id && (version == null || it.presetVersion == version)
        }
    }
}
