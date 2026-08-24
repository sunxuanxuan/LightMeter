package com.lightmeter.app.filmpreview

enum class EvidenceLevel(val displayName: String) {
    OFFICIAL("官方资料"),
    MEASURED("实测资料"),
    ESTIMATED("经验近似"),
}

data class FilmProfile(
    val name: String,
    val id: String = name,
    val iso: Int,
    val highlightLatitudeStops: Double,
    val shadowLatitudeStops: Double,
    val evidence: EvidenceLevel,
) {
    init {
        require(id.isNotBlank())
        require(iso > 0)
        require(highlightLatitudeStops > 0.0)
        require(shadowLatitudeStops > 0.0)
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
    val compatibleFilms: List<FilmProfile> = listOf(film),
) {
    init {
        require(id.isNotBlank())
        require(presetVersion > 0)
        require(brand.isNotBlank())
        require(model.isNotBlank())
        require(shutterSeconds > 0.0)
        require(compatibleFilms.isNotEmpty())
        require(compatibleFilms.any { it.id == film.id })
    }

    val displayName: String
        get() = displayNameOverride ?: "$brand $model"

    val isFilmSelectable: Boolean
        get() = compatibleFilms.size > 1

    fun withFilm(filmId: String): DisposableCameraPreset? {
        return compatibleFilms.firstOrNull { it.id == filmId }?.let { copy(film = it) }
    }
}

interface DisposableCameraRepository {
    fun presets(): List<DisposableCameraPreset>

    fun find(id: String, version: Int? = null): DisposableCameraPreset?
}

object BuiltInDisposableCameraRepository : DisposableCameraRepository {
    private const val KODAK_ULTRA_MAX_400_ID = "kodak-ultra-max-400"

    private val KODAK_EC35_FILMS = listOf(
        FilmProfile(
            id = "generic-color-100",
            name = "通用 ISO 100 彩色负片",
            iso = 100,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            evidence = EvidenceLevel.ESTIMATED,
        ),
        FilmProfile(
            id = "kodak-gold-200",
            name = "Kodak Gold 200",
            iso = 200,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            evidence = EvidenceLevel.ESTIMATED,
        ),
        FilmProfile(
            id = KODAK_ULTRA_MAX_400_ID,
            name = "Kodak Ultra Max 400",
            iso = 400,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            evidence = EvidenceLevel.ESTIMATED,
        ),
        FilmProfile(
            id = "generic-color-800",
            name = "通用 ISO 800 彩色负片",
            iso = 800,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            evidence = EvidenceLevel.ESTIMATED,
        ),
    )

    private val builtInPresets = listOf(
        DisposableCameraPreset(
            id = "kodak-power-flash-800",
            presetVersion = 3,
            brand = "Kodak",
            model = "Power Flash / FunSaver",
            regionOrBatch = "合并预设 · 31mm · f/10 · 1/100s · ISO 800",
            film = FilmProfile(
                name = "Kodak ISO 800 彩色负片",
                iso = 800,
                highlightLatitudeStops = 3.0,
                shadowLatitudeStops = 2.0,
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
        DisposableCameraPreset(
            id = "kodak-ec35-reusable",
            presetVersion = 1,
            brand = "Kodak",
            model = "EC35",
            regionOrBatch = "25mm · f/10 · 1/100s · 可换 135 胶卷",
            film = KODAK_EC35_FILMS.first { it.id == KODAK_ULTRA_MAX_400_ID },
            optics = FixedOptics(
                aperture = 10.0,
                focalLengthMm = 25.0,
                minimumFocusMeters = 1.0,
            ),
            shutterSeconds = 1.0 / 100.0,
            flash = null,
            exposureEvidence = EvidenceLevel.ESTIMATED,
            compatibleFilms = KODAK_EC35_FILMS,
        ),
    )

    override fun presets(): List<DisposableCameraPreset> = builtInPresets

    override fun find(id: String, version: Int?): DisposableCameraPreset? {
        return builtInPresets.firstOrNull {
            it.id == id && (version == null || it.presetVersion == version)
        }
    }

}
