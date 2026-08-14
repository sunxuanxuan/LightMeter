package com.lightmeter.app.settings

import android.content.Context
import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.CameraMeteringPreset
import com.lightmeter.app.metering.FilmLatitudePreset
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.ui.theme.AppThemeStyle

data class AppSettings(
    val themeStyle: AppThemeStyle = AppThemeStyle.DARK,
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
    val highlightLatitudeStops: Double = 4.0,
    val shadowLatitudeStops: Double = 3.0,
    val filmLatitudePreset: FilmLatitudePreset? = null,
    val calibrationOffset: Double = 0.0,
    val grayCardCalibrationCompleted: Boolean = false,
    val grayCardCalibrationPromptSeen: Boolean = false,
    val grayCardCalibrationSignature: String? = null,
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
            themeStyle = preferences.getEnum(KEY_THEME_STYLE, defaults.themeStyle),
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
            calibrationOffset = preferences.getDouble(
                KEY_CALIBRATION_OFFSET,
                defaults.calibrationOffset,
            ),
            grayCardCalibrationCompleted = preferences.getBoolean(
                KEY_GRAY_CARD_CALIBRATION_COMPLETED,
                defaults.grayCardCalibrationCompleted,
            ),
            grayCardCalibrationPromptSeen = preferences.getBoolean(
                KEY_GRAY_CARD_CALIBRATION_PROMPT_SEEN,
                defaults.grayCardCalibrationPromptSeen,
            ),
            grayCardCalibrationSignature = preferences.getString(
                KEY_GRAY_CARD_CALIBRATION_SIGNATURE,
                defaults.grayCardCalibrationSignature,
            ),
        )
    }

    override fun save(settings: AppSettings): Boolean {
        return preferences.edit()
            .putString(KEY_THEME_STYLE, settings.themeStyle.name)
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
            .putLong(
                KEY_HIGHLIGHT_LATITUDE_STOPS,
                settings.highlightLatitudeStops.toRawBits(),
            )
            .putLong(
                KEY_SHADOW_LATITUDE_STOPS,
                settings.shadowLatitudeStops.toRawBits(),
            )
            .putNullableEnum(KEY_FILM_LATITUDE_PRESET, settings.filmLatitudePreset)
            .putLong(KEY_CALIBRATION_OFFSET, settings.calibrationOffset.toRawBits())
            .putBoolean(
                KEY_GRAY_CARD_CALIBRATION_COMPLETED,
                settings.grayCardCalibrationCompleted,
            )
            .putBoolean(
                KEY_GRAY_CARD_CALIBRATION_PROMPT_SEEN,
                settings.grayCardCalibrationPromptSeen,
            )
            .putString(
                KEY_GRAY_CARD_CALIBRATION_SIGNATURE,
                settings.grayCardCalibrationSignature,
            )
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "light_meter_settings"
        const val KEY_THEME_STYLE = "theme_style"
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
        const val KEY_HIGHLIGHT_LATITUDE_STOPS = "highlight_latitude_stops"
        const val KEY_SHADOW_LATITUDE_STOPS = "shadow_latitude_stops"
        const val KEY_FILM_LATITUDE_PRESET = "film_latitude_preset"
        const val KEY_CALIBRATION_OFFSET = "calibration_offset"
        const val KEY_GRAY_CARD_CALIBRATION_COMPLETED =
            "gray_card_calibration_completed"
        const val KEY_GRAY_CARD_CALIBRATION_PROMPT_SEEN =
            "gray_card_calibration_prompt_seen"
        const val KEY_GRAY_CARD_CALIBRATION_SIGNATURE =
            "gray_card_calibration_signature"
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
