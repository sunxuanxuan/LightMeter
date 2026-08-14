package com.lightmeter.app.filmpreview

import android.content.Context

data class ManualCameraConfig(
    val iso: Int = 400,
    val shutterDenominator: Int = 125,
    val aperture: Double = 11.0,
    val focalLengthMm: Double = 32.0,
) {
    fun isValid(): Boolean {
        return iso in 25..6400 &&
            shutterDenominator in 1..8000 &&
            aperture.isFinite() &&
            aperture in 1.0..64.0 &&
            focalLengthMm.isFinite() &&
            focalLengthMm in 20.0..150.0
    }

    fun toPreset() = DisposableCameraPreset(
        id = MANUAL_PRESET_ID,
        presetVersion = 1,
        brand = "手动",
        model = "配置",
        regionOrBatch = "自定义一次性胶片相机参数",
        film = FilmProfile(
            name = "通用彩色负片",
            iso = iso,
            highlightLatitudeStops = 3.0,
            shadowLatitudeStops = 2.0,
            evidence = EvidenceLevel.ESTIMATED,
        ),
        optics = FixedOptics(
            aperture = aperture,
            focalLengthMm = focalLengthMm,
            minimumFocusMeters = null,
        ),
        shutterSeconds = 1.0 / shutterDenominator,
        flash = null,
        exposureEvidence = EvidenceLevel.ESTIMATED,
        displayNameOverride = "手动配置",
    )

    companion object {
        const val MANUAL_PRESET_ID = "manual-camera"
    }
}

data class FilmPreviewSettings(
    val selectedPresetId: String? = null,
    val manualConfig: ManualCameraConfig = ManualCameraConfig(),
)

interface FilmPreviewSettingsStore {
    fun load(): FilmPreviewSettings

    fun save(settings: FilmPreviewSettings): Boolean

    object None : FilmPreviewSettingsStore {
        override fun load() = FilmPreviewSettings()

        override fun save(settings: FilmPreviewSettings) = true
    }
}

class SharedPreferencesFilmPreviewSettingsStore(context: Context) :
    FilmPreviewSettingsStore {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): FilmPreviewSettings {
        val defaults = FilmPreviewSettings()
        val config = ManualCameraConfig(
            iso = preferences.getInt(KEY_MANUAL_ISO, defaults.manualConfig.iso),
            shutterDenominator = preferences.getInt(
                KEY_MANUAL_SHUTTER_DENOMINATOR,
                defaults.manualConfig.shutterDenominator,
            ),
            aperture = preferences.getDouble(
                KEY_MANUAL_APERTURE,
                defaults.manualConfig.aperture,
            ),
            focalLengthMm = preferences.getDouble(
                KEY_MANUAL_FOCAL_LENGTH,
                defaults.manualConfig.focalLengthMm,
            ),
        ).takeIf(ManualCameraConfig::isValid) ?: defaults.manualConfig
        return FilmPreviewSettings(
            selectedPresetId = preferences.getString(KEY_SELECTED_PRESET_ID, null),
            manualConfig = config,
        )
    }

    override fun save(settings: FilmPreviewSettings): Boolean {
        require(settings.manualConfig.isValid())
        return preferences.edit()
            .putString(KEY_SELECTED_PRESET_ID, settings.selectedPresetId)
            .putInt(KEY_MANUAL_ISO, settings.manualConfig.iso)
            .putInt(
                KEY_MANUAL_SHUTTER_DENOMINATOR,
                settings.manualConfig.shutterDenominator,
            )
            .putLong(
                KEY_MANUAL_APERTURE,
                settings.manualConfig.aperture.toRawBits(),
            )
            .putLong(
                KEY_MANUAL_FOCAL_LENGTH,
                settings.manualConfig.focalLengthMm.toRawBits(),
            )
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "film_preview_settings"
        const val KEY_SELECTED_PRESET_ID = "selected_preset_id"
        const val KEY_MANUAL_ISO = "manual_iso"
        const val KEY_MANUAL_SHUTTER_DENOMINATOR = "manual_shutter_denominator"
        const val KEY_MANUAL_APERTURE = "manual_aperture"
        const val KEY_MANUAL_FOCAL_LENGTH = "manual_focal_length"
    }
}

private fun android.content.SharedPreferences.getDouble(
    key: String,
    defaultValue: Double,
): Double {
    return if (contains(key)) {
        Double.fromBits(getLong(key, defaultValue.toRawBits()))
    } else {
        defaultValue
    }
}
