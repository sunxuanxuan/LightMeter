package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.lightmeter.app.metering.ExposureMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object GpuFilmExposureRenderer {
    fun render(
        source: Bitmap,
        exposureMap: ExposureMap,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
        filmLook: NegativeFilmLookProfile = NegativeFilmLooks.NEUTRAL,
    ): Bitmap {
        require(referenceEv100.isFinite())
        require(exposureMap.width == source.width)
        require(exposureMap.height == source.height)

        val session = EglSession(source.width, source.height)
        return try {
            session.render(
                source = source,
                exposureMap = exposureMap,
                simulationMode = FILM_RESPONSE_MODE,
                exposureCompensation = 0f,
                referenceEv100 = referenceEv100.toFloat(),
                highlightLatitudeStops = highlightLatitudeStops.toFloat(),
                shadowLatitudeStops = shadowLatitudeStops.toFloat(),
                filmLook = filmLook,
            )
        } finally {
            session.close()
        }
    }

    fun renderExposureCompensation(
        source: Bitmap,
        exposureCompensation: Double,
    ): Bitmap {
        require(exposureCompensation.isFinite())

        val session = EglSession(source.width, source.height)
        return try {
            session.render(
                source = source,
                exposureMap = null,
                simulationMode = EXPOSURE_COMPENSATION_MODE,
                exposureCompensation = exposureCompensation.toFloat(),
                referenceEv100 = 0f,
                highlightLatitudeStops = 1f,
                shadowLatitudeStops = 1f,
                filmLook = NegativeFilmLooks.NEUTRAL,
            )
        } finally {
            session.close()
        }
    }

    private class EglSession(
        private val width: Int,
        private val height: Int,
    ) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var sourceTexture = 0
        private var exposureMapTexture = 0
        private var filmResponseLutTexture = 0
        private var outputTexture = 0
        private var framebuffer = 0

        init {
            require(width > 0 && height > 0)
            try {
                initializeEgl()
                initializeGl()
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun render(
            source: Bitmap,
            exposureMap: ExposureMap?,
            simulationMode: Int,
            exposureCompensation: Float,
            referenceEv100: Float,
            highlightLatitudeStops: Float,
            shadowLatitudeStops: Float,
            filmLook: NegativeFilmLookProfile,
        ): Bitmap {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, exposureMapTexture)
            exposureMap?.let {
                encodeExposureMap(it).also { mapBitmap ->
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mapBitmap, 0)
                    mapBitmap.recycle()
                }
            }
            val responseLut = FilmResponseLut.create(
                filmLook = filmLook,
                highlightLatitudeStops = highlightLatitudeStops.toDouble(),
                shadowLatitudeStops = shadowLatitudeStops.toDouble(),
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filmResponseLutTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                FilmResponseLut.SAMPLE_COUNT,
                FilmResponseLut.TEXTURE_ROWS,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                responseLut.toRgb16TextureBuffer(),
            )

            GLES20.glUniform1i(uniform("uSource"), 0)
            GLES20.glUniform1i(uniform("uExposureMap"), 1)
            GLES20.glUniform1i(uniform("uFilmResponseLut"), 2)
            GLES20.glUniform1i(uniform("uSimulationMode"), simulationMode)
            GLES20.glUniform1f(uniform("uExposureCompensation"), exposureCompensation)
            GLES20.glUniform1f(uniform("uReferenceEv100"), referenceEv100)
            GLES20.glUniform1f(uniform("uHighlightLatitude"), highlightLatitudeStops)
            GLES20.glUniform1f(uniform("uShadowLatitude"), shadowLatitudeStops)
            val matrix = filmLook.colorMatrix
            GLES20.glUniformMatrix3fv(
                uniform("uLayerMixMatrix"),
                1,
                false,
                floatArrayOf(
                    matrix.redFromRed.toFloat(),
                    matrix.greenFromRed.toFloat(),
                    matrix.blueFromRed.toFloat(),
                    matrix.redFromGreen.toFloat(),
                    matrix.greenFromGreen.toFloat(),
                    matrix.blueFromGreen.toFloat(),
                    matrix.redFromBlue.toFloat(),
                    matrix.greenFromBlue.toFloat(),
                    matrix.blueFromBlue.toFloat(),
                ),
                0,
            )
            GLES20.glUniform1f(uniform("uSaturation"), filmLook.saturation.toFloat())
            GLES20.glUniform1f(uniform("uGrainAmount"), filmLook.grainAmount.toFloat())
            GLES20.glUniform1f(
                uniform("uGrainRadiusPxAt1080"),
                filmLook.grainRadiusPxAt1080.toFloat(),
            )
            GLES20.glUniform1f(
                uniform("uGrainChromaFraction"),
                filmLook.grainChromaFraction.toFloat(),
            )
            GLES20.glUniform1f(
                uniform("uGrainSeed"),
                filmLook.grainSeedForFrame(exposureMap?.timestampNs ?: 0L).toFloat(),
            )
            GLES20.glUniform2f(uniform("uOutputSize"), width.toFloat(), height.toFloat())

            val positionLocation = attribute("aPosition")
            val textureCoordinateLocation = attribute("aTextureCoordinate")
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                VERTICES.duplicate().apply { position(0) },
            )
            GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
            GLES20.glVertexAttribPointer(
                textureCoordinateLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                TEXTURE_COORDINATES.duplicate().apply { position(0) },
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
            checkGlError("render")

            val rgba = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgba,
            )
            checkGlError("readPixels")
            return rgbaToBitmap(rgba, width, height)
        }

        fun close() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (framebuffer != 0) {
                    GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                }
                val textures = intArrayOf(
                    sourceTexture,
                    exposureMapTexture,
                    filmResponseLutTexture,
                    outputTexture,
                )
                    .filter { it != 0 }
                if (textures.isNotEmpty()) {
                    GLES20.glDeleteTextures(
                        textures.size,
                        textures.toIntArray(),
                        0,
                    )
                }
                if (program != 0) GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }

        private fun initializeEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "Unable to acquire EGL display" }
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                "Unable to initialize EGL"
            }
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    attributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    configCount,
                    0,
                ) && configCount[0] > 0,
            ) {
                "Unable to select EGL config"
            }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(
                    EGL14.EGL_WIDTH,
                    width,
                    EGL14.EGL_HEIGHT,
                    height,
                    EGL14.EGL_NONE,
                ),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL surface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "Unable to make EGL context current"
            }
        }

        private fun initializeGl() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            sourceTexture = createTexture()
            exposureMapTexture = createTexture(filter = GLES20.GL_NEAREST)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                1,
                1,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                ByteBuffer.wrap(byteArrayOf(0, 0, 0, 0)),
            )
            filmResponseLutTexture = createTexture(filter = GLES20.GL_NEAREST)
            outputTexture = createTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null,
            )

            val framebuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            framebuffer = framebuffers[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                outputTexture,
                0,
            )
            check(
                GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                    GLES20.GL_FRAMEBUFFER_COMPLETE,
            ) {
                "Incomplete OpenGL framebuffer"
            }
            checkGlError("initialize")
        }

        private fun uniform(name: String): Int {
            return GLES20.glGetUniformLocation(program, name).also {
                check(it >= 0) { "Missing shader uniform: $name" }
            }
        }

        private fun attribute(name: String): Int {
            return GLES20.glGetAttribLocation(program, name).also {
                check(it >= 0) { "Missing shader attribute: $name" }
            }
        }
    }

    private fun createTexture(filter: Int = GLES20.GL_LINEAR): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        check(textures[0] != 0) { "Unable to create OpenGL texture" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            filter,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            filter,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    private fun encodeExposureMap(exposureMap: ExposureMap): Bitmap {
        val pixels = IntArray(exposureMap.pixelEv100.size) { index ->
            val encoded = (
                (
                    (exposureMap.pixelEv100[index]
                        .takeIf(Float::isFinite)
                        ?.toDouble()
                        ?: DEFAULT_ENCODED_EV100
                        ).coerceIn(MIN_ENCODED_EV100, MAX_ENCODED_EV100) -
                        MIN_ENCODED_EV100
                    ) / (MAX_ENCODED_EV100 - MIN_ENCODED_EV100) * MAX_ENCODED_EV_VALUE
                ).toInt().coerceIn(0, MAX_ENCODED_EV_VALUE)
            0xFF000000.toInt() or
                ((encoded ushr 8) shl 16) or
                ((encoded and 0xFF) shl 8)
        }
        return Bitmap.createBitmap(
            pixels,
            exposureMap.width,
            exposureMap.height,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return try {
            val result = GLES20.glCreateProgram()
            check(result != 0) { "Unable to create OpenGL program" }
            GLES20.glAttachShader(result, vertexShader)
            GLES20.glAttachShader(result, fragmentShader)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Unable to link OpenGL program: ${GLES20.glGetProgramInfoLog(result)}"
            }
            result
        } finally {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create OpenGL shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Unable to compile OpenGL shader: $log")
        }
        return shader
    }

    private fun checkGlError(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "OpenGL error at $stage: 0x${error.toString(16)}"
        }
    }

    private fun rgbaToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (outputY in 0 until height) {
            val sourceY = height - outputY - 1
            for (x in 0 until width) {
                val byteIndex = (sourceY * width + x) * 4
                val red = buffer.get(byteIndex).toInt() and 0xFF
                val green = buffer.get(byteIndex + 1).toInt() and 0xFF
                val blue = buffer.get(byteIndex + 2).toInt() and 0xFF
                val alpha = buffer.get(byteIndex + 3).toInt() and 0xFF
                pixels[outputY * width + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }

    private val VERTICES = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )
    private val TEXTURE_COORDINATES = floatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        ),
    )

    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTextureCoordinate;
        varying vec2 vTextureCoordinate;

        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTextureCoordinate = aTextureCoordinate;
        }
    """

    private const val FRAGMENT_SHADER = """
        precision highp float;

        uniform sampler2D uSource;
        uniform sampler2D uExposureMap;
        uniform sampler2D uFilmResponseLut;
        uniform int uSimulationMode;
        uniform float uExposureCompensation;
        uniform float uReferenceEv100;
        uniform float uHighlightLatitude;
        uniform float uShadowLatitude;
        uniform mat3 uLayerMixMatrix;
        uniform float uSaturation;
        uniform float uGrainAmount;
        uniform float uGrainRadiusPxAt1080;
        uniform float uGrainChromaFraction;
        uniform float uGrainSeed;
        uniform vec2 uOutputSize;
        varying vec2 vTextureCoordinate;

        const float TARGET_LUMINANCE = 0.18;
        const float LUMINANCE_EPSILON = 0.000001;
        const float MIN_ENCODED_EV100 = -32.0;
        const float MAX_ENCODED_EV100 = 32.0;
        const float LUT_MIN_EXPOSURE_EV = -6.0;
        const float LUT_MAX_EXPOSURE_EV = 8.0;
        const float LUT_SAMPLE_COUNT = 256.0;
        const float GRAIN_REFERENCE_WIDTH = 1080.0;
        const float MIN_GRAIN_RADIUS_PX = 0.65;
        const float GRAIN_EV_SCALE = 0.16;
        const float GRAIN_FLOOR = 0.20;
        const float GRAIN_SHADOW_BIAS = 0.10;

        vec3 srgbToLinear(vec3 value) {
            vec3 lower = value / 12.92;
            vec3 upper = pow((value + 0.055) / 1.055, vec3(2.4));
            return mix(lower, upper, step(vec3(0.04045), value));
        }

        vec3 linearToSrgb(vec3 value) {
            value = clamp(value, 0.0, 1.0);
            vec3 lower = value * 12.92;
            vec3 upper = 1.055 * pow(value, vec3(1.0 / 2.4)) - 0.055;
            return mix(lower, upper, step(vec3(0.0031308), value));
        }

        vec3 responseLutTexel(float index) {
            float x = (index + 0.5) / LUT_SAMPLE_COUNT;
            vec3 highBytes = texture2D(uFilmResponseLut, vec2(x, 0.25)).rgb;
            vec3 lowBytes = texture2D(uFilmResponseLut, vec2(x, 0.75)).rgb;
            return (highBytes * 65280.0 + lowBytes * 255.0) / 65535.0;
        }

        vec3 sampleResponseLut(vec3 exposureEv) {
            vec3 normalized = clamp(
                (exposureEv - LUT_MIN_EXPOSURE_EV) /
                    (LUT_MAX_EXPOSURE_EV - LUT_MIN_EXPOSURE_EV),
                0.0,
                1.0
            );
            vec3 position = normalized * (LUT_SAMPLE_COUNT - 1.0);
            vec3 lowerIndex = floor(position);
            vec3 fraction = position - lowerIndex;
            vec3 lower = vec3(
                responseLutTexel(lowerIndex.r).r,
                responseLutTexel(lowerIndex.g).g,
                responseLutTexel(lowerIndex.b).b
            );
            vec3 upper = vec3(
                responseLutTexel(min(lowerIndex.r + 1.0, LUT_SAMPLE_COUNT - 1.0)).r,
                responseLutTexel(min(lowerIndex.g + 1.0, LUT_SAMPLE_COUNT - 1.0)).g,
                responseLutTexel(min(lowerIndex.b + 1.0, LUT_SAMPLE_COUNT - 1.0)).b
            );
            return mix(lower, upper, fraction);
        }

        float randomValue(vec2 coordinate, float seed) {
            float value = sin(
                dot(coordinate, vec2(12.9898, 78.233)) + seed * 0.017
            ) * 43758.5453;
            return fract(value) * 2.0 - 1.0;
        }

        float valueNoise(vec2 coordinate, float seed) {
            vec2 cell = floor(coordinate);
            vec2 progress = fract(coordinate);
            progress = progress * progress * (3.0 - 2.0 * progress);
            float top = mix(
                randomValue(cell, seed),
                randomValue(cell + vec2(1.0, 0.0), seed),
                progress.x
            );
            float bottom = mix(
                randomValue(cell + vec2(0.0, 1.0), seed),
                randomValue(cell + vec2(1.0, 1.0), seed),
                progress.x
            );
            return mix(top, bottom, progress.y);
        }

        float fractalNoise(vec2 pixel, float radius, float seed) {
            float fine = valueNoise(pixel / radius, seed);
            float coarse = valueNoise(pixel / (radius * 2.0), seed + 97.0);
            return (fine + 0.5 * coarse) / 1.5;
        }

        float exposureMapEv100(vec2 coordinate) {
            vec4 encoded = texture2D(uExposureMap, coordinate);
            float highByte = floor(encoded.r * 255.0 + 0.5);
            float lowByte = floor(encoded.g * 255.0 + 0.5);
            float normalizedEv = (highByte * 256.0 + lowByte) / 65535.0;
            return mix(MIN_ENCODED_EV100, MAX_ENCODED_EV100, normalizedEv);
        }

        void main() {
            vec4 source = texture2D(uSource, vTextureCoordinate);
            vec3 linearRgb = srgbToLinear(source.rgb);
            if (uSimulationMode == 1) {
                float exposureGain = exp2(uExposureCompensation);
                gl_FragColor = vec4(
                    linearToSrgb(linearRgb * exposureGain),
                    source.a
                );
                return;
            }
            float sourceLuminance = dot(
                linearRgb,
                vec3(0.2126, 0.7152, 0.0722)
            );
            float pixelEv100 = exposureMapEv100(vTextureCoordinate);
            float deltaEv = pixelEv100 - uReferenceEv100;
            float relativeExposure = TARGET_LUMINANCE * exp2(deltaEv);
            float gain = relativeExposure / max(sourceLuminance, LUMINANCE_EPSILON);
            vec3 layerExposure = max(
                uLayerMixMatrix * (linearRgb * gain),
                vec3(LUMINANCE_EPSILON)
            );
            vec3 layerExposureEv = log2(
                layerExposure / vec3(TARGET_LUMINANCE)
            );
            vec3 styled = sampleResponseLut(layerExposureEv);

            float saturationCenter = dot(
                styled,
                vec3(0.2126, 0.7152, 0.0722)
            );
            styled = vec3(saturationCenter) +
                (styled - vec3(saturationCenter)) * uSaturation;

            if (uGrainAmount > 0.0) {
                float densityPosition = clamp(
                    (deltaEv + uShadowLatitude) /
                        (uShadowLatitude + uHighlightLatitude) +
                        GRAIN_SHADOW_BIAS,
                    0.0,
                    1.0
                );
                float densityEnvelope = GRAIN_FLOOR +
                    4.0 * densityPosition * (1.0 - densityPosition);
                float radius = max(
                    uGrainRadiusPxAt1080 * uOutputSize.x /
                        GRAIN_REFERENCE_WIDTH,
                    MIN_GRAIN_RADIUS_PX
                );
                vec2 pixel = gl_FragCoord.xy;
                float sharedNoise = fractalNoise(pixel, radius, uGrainSeed);
                vec3 independentNoise = vec3(
                    fractalNoise(pixel, radius, uGrainSeed + 17.0),
                    fractalNoise(pixel, radius, uGrainSeed + 37.0),
                    fractalNoise(pixel, radius, uGrainSeed + 67.0)
                );
                vec3 grainNoise = mix(
                    vec3(sharedNoise),
                    independentNoise,
                    uGrainChromaFraction
                );
                float amplitude =
                    GRAIN_EV_SCALE * uGrainAmount * densityEnvelope;
                styled *= exp2(grainNoise * amplitude);
            }
            gl_FragColor = vec4(
                linearToSrgb(max(styled, vec3(0.0))),
                source.a
            );
        }
    """

    private const val FILM_RESPONSE_MODE = 0
    private const val EXPOSURE_COMPENSATION_MODE = 1
    private const val MIN_ENCODED_EV100 = -32.0
    private const val MAX_ENCODED_EV100 = 32.0
    private const val DEFAULT_ENCODED_EV100 = 0.0
    private const val MAX_ENCODED_EV_VALUE = 0xFFFF
}
