package com.lightmeter.app.settings

import android.content.Context
import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.CameraMeteringPreset
import com.lightmeter.app.metering.FilmLatitudePreset
import com.lightmeter.app.metering.MeteringMode

data class AppSettings(
    val selectedIso: Int = 100,
    val exposureCompensation: Double = 0.0,
    val meteringPreset: MeteringMode = MeteringMode.CENTER_WEIGHTED,
    val spotAreaPercent: Int = 5,
    val centerAreaPercent: Int = 25,
    val centerWeightPercent: Int = 70,
    val cameraMeteringPreset: CameraMeteringPreset? = null,
    val frameFormat: FrameFormat = FrameFormat.FILM_135,
    val focalLengthMm: Double = 50.0,
    val exposureRiskEnabled: Boolean = true,
    val warnOnlyOutsideLatitude: Boolean = true,
    val highlightLatitudeStops: Double = 4.0,
    val shadowLatitudeStops: Double = 3.0,
    val filmLatitudePreset: FilmLatitudePreset? = null,
)

interface AppSettingsStore {
    fun load(): AppSettings

    fun save(settings: AppSettings): Boolean

    object None : AppSettingsStore {
        override fun load() = AppSettings()

        override fun save(settings: AppSettings) = true
    }
}

class SharedPreferencesAppSettingsStore(context: Context) : AppSettingsStore {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            selectedIso = preferences.getInt(KEY_ISO, defaults.selectedIso),
            exposureCompensation = preferences.getDouble(
                KEY_EXPOSURE_COMPENSATION,
                defaults.exposureCompensation,
            ),
            meteringPreset = preferences.getEnum(
                KEY_METERING_PRESET,
                defaults.meteringPreset,
            ),
            spotAreaPercent = preferences.getInt(
                KEY_SPOT_AREA_PERCENT,
                defaults.spotAreaPercent,
            ),
            centerAreaPercent = preferences.getInt(
                KEY_CENTER_AREA_PERCENT,
                defaults.centerAreaPercent,
            ),
            centerWeightPercent = preferences.getInt(
                KEY_CENTER_WEIGHT_PERCENT,
                defaults.centerWeightPercent,
            ),
            cameraMeteringPreset = preferences
                .getNullableEnum<CameraMeteringPreset>(KEY_CAMERA_METERING_PRESET),
            frameFormat = preferences.getEnum(KEY_FRAME_FORMAT, defaults.frameFormat),
            focalLengthMm = preferences.getDouble(
                KEY_FOCAL_LENGTH_MM,
                defaults.focalLengthMm,
            ),
            exposureRiskEnabled = preferences.getBoolean(
                KEY_EXPOSURE_RISK_ENABLED,
                defaults.exposureRiskEnabled,
            ),
            warnOnlyOutsideLatitude = preferences.getBoolean(
                KEY_WARN_ONLY_OUTSIDE_LATITUDE,
                defaults.warnOnlyOutsideLatitude,
            ),
            highlightLatitudeStops = preferences.getDouble(
                KEY_HIGHLIGHT_LATITUDE_STOPS,
                defaults.highlightLatitudeStops,
            ),
            shadowLatitudeStops = preferences.getDouble(
                KEY_SHADOW_LATITUDE_STOPS,
                defaults.shadowLatitudeStops,
            ),
            filmLatitudePreset = preferences
                .getNullableEnum<FilmLatitudePreset>(KEY_FILM_LATITUDE_PRESET),
        )
    }

    override fun save(settings: AppSettings): Boolean {
        return preferences.edit()
            .putInt(KEY_ISO, settings.selectedIso)
            .putLong(
                KEY_EXPOSURE_COMPENSATION,
                settings.exposureCompensation.toRawBits(),
            )
            .putString(KEY_METERING_PRESET, settings.meteringPreset.name)
            .putInt(KEY_SPOT_AREA_PERCENT, settings.spotAreaPercent)
            .putInt(KEY_CENTER_AREA_PERCENT, settings.centerAreaPercent)
            .putInt(KEY_CENTER_WEIGHT_PERCENT, settings.centerWeightPercent)
            .putNullableEnum(KEY_CAMERA_METERING_PRESET, settings.cameraMeteringPreset)
            .putString(KEY_FRAME_FORMAT, settings.frameFormat.name)
            .putLong(KEY_FOCAL_LENGTH_MM, settings.focalLengthMm.toRawBits())
            .putBoolean(KEY_EXPOSURE_RISK_ENABLED, settings.exposureRiskEnabled)
            .putBoolean(
                KEY_WARN_ONLY_OUTSIDE_LATITUDE,
                settings.warnOnlyOutsideLatitude,
            )
            .putLong(
                KEY_HIGHLIGHT_LATITUDE_STOPS,
                settings.highlightLatitudeStops.toRawBits(),
            )
            .putLong(
                KEY_SHADOW_LATITUDE_STOPS,
                settings.shadowLatitudeStops.toRawBits(),
            )
            .putNullableEnum(KEY_FILM_LATITUDE_PRESET, settings.filmLatitudePreset)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "light_meter_settings"
        const val KEY_ISO = "iso"
        const val KEY_EXPOSURE_COMPENSATION = "exposure_compensation"
        const val KEY_METERING_PRESET = "metering_preset"
        const val KEY_SPOT_AREA_PERCENT = "spot_area_percent"
        const val KEY_CENTER_AREA_PERCENT = "center_area_percent"
        const val KEY_CENTER_WEIGHT_PERCENT = "center_weight_percent"
        const val KEY_CAMERA_METERING_PRESET = "camera_metering_preset"
        const val KEY_FRAME_FORMAT = "frame_format"
        const val KEY_FOCAL_LENGTH_MM = "focal_length_mm"
        const val KEY_EXPOSURE_RISK_ENABLED = "exposure_risk_enabled"
        const val KEY_WARN_ONLY_OUTSIDE_LATITUDE = "warn_only_outside_latitude"
        const val KEY_HIGHLIGHT_LATITUDE_STOPS = "highlight_latitude_stops"
        const val KEY_SHADOW_LATITUDE_STOPS = "shadow_latitude_stops"
        const val KEY_FILM_LATITUDE_PRESET = "film_latitude_preset"
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

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.getEnum(
    key: String,
    defaultValue: T,
): T {
    return getString(key, null)?.let { saved ->
        enumValues<T>().firstOrNull { it.name == saved }
    } ?: defaultValue
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.getNullableEnum(
    key: String,
): T? {
    return getString(key, null)?.let { saved ->
        enumValues<T>().firstOrNull { it.name == saved }
    }
}

private fun android.content.SharedPreferences.Editor.putNullableEnum(
    key: String,
    value: Enum<*>?,
): android.content.SharedPreferences.Editor {
    return if (value == null) remove(key) else putString(key, value.name)
}
