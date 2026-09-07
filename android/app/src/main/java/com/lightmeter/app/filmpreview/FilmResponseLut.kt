package com.lightmeter.app.filmpreview

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

internal class FilmResponseLut private constructor(
    private val red: DoubleArray,
    private val green: DoubleArray,
    private val blue: DoubleArray,
) {
    init {
        require(red.size == SAMPLE_COUNT)
        require(green.size == SAMPLE_COUNT)
        require(blue.size == SAMPLE_COUNT)
        require(red.isValidResponse())
        require(green.isValidResponse())
        require(blue.isValidResponse())
    }

    fun sampleRed(exposureEv: Double): Double = sample(red, exposureEv)

    fun sampleGreen(exposureEv: Double): Double = sample(green, exposureEv)

    fun sampleBlue(exposureEv: Double): Double = sample(blue, exposureEv)

    fun toRgb16TextureBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(SAMPLE_COUNT * TEXTURE_ROWS * RGBA_CHANNELS)
        for (bytePart in 0 until TEXTURE_ROWS) {
            for (index in 0 until SAMPLE_COUNT) {
                val shift = if (bytePart == 0) 8 else 0
                buffer.put(encodedByte(red[index], shift))
                buffer.put(encodedByte(green[index], shift))
                buffer.put(encodedByte(blue[index], shift))
                buffer.put(0xFF.toByte())
            }
        }
        return buffer.apply { position(0) }
    }

    private fun sample(channel: DoubleArray, exposureEv: Double): Double {
        val position = (
            (exposureEv.coerceIn(MIN_EXPOSURE_EV, MAX_EXPOSURE_EV) - MIN_EXPOSURE_EV) /
                (MAX_EXPOSURE_EV - MIN_EXPOSURE_EV) * (SAMPLE_COUNT - 1)
            )
        val lowerIndex = floor(position).toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(channel.lastIndex)
        val fraction = position - lowerIndex
        return channel[lowerIndex] + (channel[upperIndex] - channel[lowerIndex]) * fraction
    }

    private fun encodedByte(value: Double, shift: Int): Byte {
        val encoded = (value.coerceIn(0.0, 1.0) * MAX_ENCODED_VALUE)
            .roundToInt()
            .coerceIn(0, MAX_ENCODED_VALUE)
        return ((encoded ushr shift) and 0xFF).toByte()
    }

    companion object {
        const val SAMPLE_COUNT = 256
        const val TEXTURE_ROWS = 2
        const val MIN_EXPOSURE_EV = -6.0
        const val MAX_EXPOSURE_EV = 8.0

        fun create(
            filmLook: NegativeFilmLookProfile,
            highlightLatitudeStops: Double,
            shadowLatitudeStops: Double,
        ): FilmResponseLut {
            fun buildChannel(gamma: Double, biasEv: Double): DoubleArray {
                val grayAnchor = FilmResponseCurve.targetLuminance(
                    deltaEv = biasEv,
                    highlightLatitudeStops = highlightLatitudeStops,
                    shadowLatitudeStops = shadowLatitudeStops,
                )
                return DoubleArray(SAMPLE_COUNT) { index ->
                    val exposureEv = MIN_EXPOSURE_EV +
                        index.toDouble() / (SAMPLE_COUNT - 1) *
                        (MAX_EXPOSURE_EV - MIN_EXPOSURE_EV)
                    val response = FilmResponseCurve.targetLuminance(
                        deltaEv = exposureEv * filmLook.toneGamma * gamma + biasEv,
                        highlightLatitudeStops = highlightLatitudeStops,
                        shadowLatitudeStops = shadowLatitudeStops,
                    )
                    (response * MIDDLE_GRAY_LUMINANCE / grayAnchor).coerceIn(0.0, 1.0)
                }
            }

            return FilmResponseLut(
                red = buildChannel(
                    gamma = filmLook.responseGamma.red,
                    biasEv = filmLook.exposureBiasEv.red,
                ),
                green = buildChannel(
                    gamma = filmLook.responseGamma.green,
                    biasEv = filmLook.exposureBiasEv.green,
                ),
                blue = buildChannel(
                    gamma = filmLook.responseGamma.blue,
                    biasEv = filmLook.exposureBiasEv.blue,
                ),
            )
        }

        private fun DoubleArray.isValidResponse(): Boolean {
            return isNotEmpty() &&
                all { it.isFinite() && it in 0.0..1.0 } &&
                (1 until size).all { index -> this[index] >= this[index - 1] }
        }

        private const val MIDDLE_GRAY_LUMINANCE = 0.18
        private const val MAX_ENCODED_VALUE = 0xFFFF
        private const val RGBA_CHANNELS = 4
    }
}
