package com.lightmeter.app.filmpreview

data class FilmRgbVector(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    init {
        require(red.isFinite())
        require(green.isFinite())
        require(blue.isFinite())
    }

    fun average(): Double = (red + green + blue) / 3.0
}

data class FilmColorMatrix(
    val redFromRed: Double,
    val redFromGreen: Double,
    val redFromBlue: Double,
    val greenFromRed: Double,
    val greenFromGreen: Double,
    val greenFromBlue: Double,
    val blueFromRed: Double,
    val blueFromGreen: Double,
    val blueFromBlue: Double,
) {
    init {
        require(values().all(Double::isFinite))
    }

    internal fun values(): List<Double> = listOf(
        redFromRed,
        redFromGreen,
        redFromBlue,
        greenFromRed,
        greenFromGreen,
        greenFromBlue,
        blueFromRed,
        blueFromGreen,
        blueFromBlue,
    )

    companion object {
        val IDENTITY = FilmColorMatrix(
            redFromRed = 1.0,
            redFromGreen = 0.0,
            redFromBlue = 0.0,
            greenFromRed = 0.0,
            greenFromGreen = 1.0,
            greenFromBlue = 0.0,
            blueFromRed = 0.0,
            blueFromGreen = 0.0,
            blueFromBlue = 1.0,
        )
    }
}

data class NegativeFilmLookProfile(
    val toneGamma: Double,
    val responseGamma: FilmRgbVector,
    val exposureBiasEv: FilmRgbVector,
    val colorMatrix: FilmColorMatrix,
    val saturation: Double,
    val lowPassSigmaPxAt1080: Double,
    val grainAmount: Double,
    val grainRadiusPxAt1080: Double,
    val grainChromaFraction: Double,
    val grainSeed: Int,
) {
    init {
        require(toneGamma in 0.5..1.5)
        require(responseGamma.red > 0.0)
        require(responseGamma.green > 0.0)
        require(responseGamma.blue > 0.0)
        require(saturation in 0.0..2.0)
        require(lowPassSigmaPxAt1080.isFinite() && lowPassSigmaPxAt1080 >= 0.0)
        require(grainAmount in 0.0..2.0)
        require(grainRadiusPxAt1080 > 0.0)
        require(grainChromaFraction in 0.0..1.0)
    }
}

object NegativeFilmLooks {
    val NEUTRAL = NegativeFilmLookProfile(
        toneGamma = 1.0,
        responseGamma = FilmRgbVector(0.64, 0.64, 0.64),
        exposureBiasEv = FilmRgbVector(0.0, 0.0, 0.0),
        colorMatrix = FilmColorMatrix.IDENTITY,
        saturation = 1.0,
        lowPassSigmaPxAt1080 = 0.0,
        grainAmount = 0.0,
        grainRadiusPxAt1080 = 1.0,
        grainChromaFraction = 0.0,
        grainSeed = 101,
    )

    val GENERIC_COLOR_100 = NegativeFilmLookProfile(
        toneGamma = 0.98,
        responseGamma = FilmRgbVector(0.64, 0.64, 0.64),
        exposureBiasEv = FilmRgbVector(0.0, 0.0, 0.0),
        colorMatrix = FilmColorMatrix.IDENTITY,
        saturation = 1.06,
        lowPassSigmaPxAt1080 = 0.55,
        grainAmount = 0.50,
        grainRadiusPxAt1080 = 1.80,
        grainChromaFraction = 0.12,
        grainSeed = 1103,
    )

    val KODAK_GOLD_200 = NegativeFilmLookProfile(
        toneGamma = 0.94,
        responseGamma = FilmRgbVector(0.62, 0.64, 0.67),
        exposureBiasEv = FilmRgbVector(0.06, 0.0, -0.08),
        colorMatrix = FilmColorMatrix(
            redFromRed = 1.06,
            redFromGreen = -0.03,
            redFromBlue = -0.03,
            greenFromRed = -0.02,
            greenFromGreen = 1.04,
            greenFromBlue = -0.02,
            blueFromRed = -0.04,
            blueFromGreen = 0.02,
            blueFromBlue = 1.02,
        ),
        saturation = 1.14,
        lowPassSigmaPxAt1080 = 0.65,
        grainAmount = 0.70,
        grainRadiusPxAt1080 = 2.20,
        grainChromaFraction = 0.12,
        grainSeed = 2203,
    )

    val GENERIC_COLOR_200 = NegativeFilmLookProfile(
        toneGamma = 0.98,
        responseGamma = FilmRgbVector(0.64, 0.64, 0.64),
        exposureBiasEv = FilmRgbVector(0.0, 0.0, 0.0),
        colorMatrix = FilmColorMatrix.IDENTITY,
        saturation = 1.06,
        lowPassSigmaPxAt1080 = 0.65,
        grainAmount = 0.65,
        grainRadiusPxAt1080 = 2.20,
        grainChromaFraction = 0.12,
        grainSeed = 2213,
    )

    val KODAK_ULTRA_MAX_400 = NegativeFilmLookProfile(
        toneGamma = 1.03,
        responseGamma = FilmRgbVector(0.65, 0.67, 0.69),
        exposureBiasEv = FilmRgbVector(0.04, 0.0, -0.04),
        colorMatrix = FilmColorMatrix(
            redFromRed = 1.08,
            redFromGreen = -0.04,
            redFromBlue = -0.04,
            greenFromRed = -0.03,
            greenFromGreen = 1.08,
            greenFromBlue = -0.05,
            blueFromRed = -0.03,
            blueFromGreen = -0.03,
            blueFromBlue = 1.06,
        ),
        saturation = 1.15,
        lowPassSigmaPxAt1080 = 0.80,
        grainAmount = 0.90,
        grainRadiusPxAt1080 = 2.80,
        grainChromaFraction = 0.16,
        grainSeed = 4409,
    )

    val GENERIC_COLOR_400 = NegativeFilmLookProfile(
        toneGamma = 1.0,
        responseGamma = FilmRgbVector(0.64, 0.64, 0.64),
        exposureBiasEv = FilmRgbVector(0.0, 0.0, 0.0),
        colorMatrix = FilmColorMatrix.IDENTITY,
        saturation = 1.07,
        lowPassSigmaPxAt1080 = 0.80,
        grainAmount = 0.90,
        grainRadiusPxAt1080 = 2.80,
        grainChromaFraction = 0.16,
        grainSeed = 4423,
    )

    val GENERIC_COLOR_800 = NegativeFilmLookProfile(
        toneGamma = 0.95,
        responseGamma = FilmRgbVector(0.60, 0.61, 0.63),
        exposureBiasEv = FilmRgbVector(0.0, 0.0, 0.0),
        colorMatrix = FilmColorMatrix.IDENTITY,
        saturation = 1.04,
        lowPassSigmaPxAt1080 = 1.00,
        grainAmount = 1.25,
        grainRadiusPxAt1080 = 3.60,
        grainChromaFraction = 0.20,
        grainSeed = 8803,
    )

    val KODAK_DISPOSABLE_800 = NegativeFilmLookProfile(
        toneGamma = 1.0,
        responseGamma = FilmRgbVector(0.61, 0.63, 0.66),
        exposureBiasEv = FilmRgbVector(0.05, 0.0, -0.07),
        colorMatrix = FilmColorMatrix(
            redFromRed = 1.07,
            redFromGreen = -0.03,
            redFromBlue = -0.04,
            greenFromRed = -0.02,
            greenFromGreen = 1.05,
            greenFromBlue = -0.03,
            blueFromRed = -0.04,
            blueFromGreen = 0.01,
            blueFromBlue = 1.03,
        ),
        saturation = 1.14,
        lowPassSigmaPxAt1080 = 1.15,
        grainAmount = 1.30,
        grainRadiusPxAt1080 = 3.80,
        grainChromaFraction = 0.20,
        grainSeed = 8817,
    )

    val FUJI_SUPERIA_XTRA_400 = NegativeFilmLookProfile(
        toneGamma = 1.02,
        responseGamma = FilmRgbVector(0.65, 0.64, 0.66),
        exposureBiasEv = FilmRgbVector(-0.03, 0.03, 0.02),
        colorMatrix = FilmColorMatrix(
            redFromRed = 1.01,
            redFromGreen = -0.02,
            redFromBlue = 0.01,
            greenFromRed = -0.03,
            greenFromGreen = 1.08,
            greenFromBlue = -0.05,
            blueFromRed = 0.01,
            blueFromGreen = 0.02,
            blueFromBlue = 0.97,
        ),
        saturation = 1.14,
        lowPassSigmaPxAt1080 = 0.80,
        grainAmount = 0.90,
        grainRadiusPxAt1080 = 2.60,
        grainChromaFraction = 0.16,
        grainSeed = 4417,
    )

    val FUJI_C400_400 = NegativeFilmLookProfile(
        toneGamma = 1.0,
        responseGamma = FilmRgbVector(0.63, 0.64, 0.66),
        exposureBiasEv = FilmRgbVector(0.0, 0.02, 0.0),
        colorMatrix = FilmColorMatrix(
            redFromRed = 1.02,
            redFromGreen = -0.01,
            redFromBlue = -0.01,
            greenFromRed = -0.02,
            greenFromGreen = 1.05,
            greenFromBlue = -0.03,
            blueFromRed = 0.0,
            blueFromGreen = 0.02,
            blueFromBlue = 0.98,
        ),
        saturation = 1.12,
        lowPassSigmaPxAt1080 = 0.80,
        grainAmount = 0.95,
        grainRadiusPxAt1080 = 2.80,
        grainChromaFraction = 0.16,
        grainSeed = 4421,
    )

    fun genericForIso(iso: Int): NegativeFilmLookProfile {
        return when {
            iso <= 100 -> GENERIC_COLOR_100
            iso <= 200 -> GENERIC_COLOR_200
            iso <= 400 -> GENERIC_COLOR_400
            else -> GENERIC_COLOR_800
        }
    }
}

internal fun NegativeFilmLookProfile.grainSeedForFrame(timestampNs: Long): Int {
    return grainSeed xor (timestampNs xor (timestampNs ushr 32)).toInt()
}
