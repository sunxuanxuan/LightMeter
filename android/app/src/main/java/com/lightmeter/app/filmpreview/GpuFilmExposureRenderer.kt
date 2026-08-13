package com.lightmeter.app.filmpreview

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object GpuFilmExposureRenderer {
    fun render(
        source: Bitmap,
        cameraSettingEv100: Double,
        calibrationOffset: Double,
        referenceEv100: Double,
        highlightLatitudeStops: Double,
        shadowLatitudeStops: Double,
    ): Bitmap {
        require(cameraSettingEv100.isFinite())
        require(calibrationOffset.isFinite())
        require(referenceEv100.isFinite())

        val session = EglSession(source.width, source.height)
        return try {
            session.render(
                source = source,
                cameraSettingEv100 = cameraSettingEv100.toFloat(),
                calibrationOffset = calibrationOffset.toFloat(),
                referenceEv100 = referenceEv100.toFloat(),
                highlightLatitudeStops = highlightLatitudeStops.toFloat(),
                shadowLatitudeStops = shadowLatitudeStops.toFloat(),
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
            cameraSettingEv100: Float,
            calibrationOffset: Float,
            referenceEv100: Float,
            highlightLatitudeStops: Float,
            shadowLatitudeStops: Float,
        ): Bitmap {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)

            GLES20.glUniform1i(uniform("uSource"), 0)
            GLES20.glUniform1f(uniform("uCameraSettingEv100"), cameraSettingEv100)
            GLES20.glUniform1f(uniform("uCalibrationOffset"), calibrationOffset)
            GLES20.glUniform1f(uniform("uReferenceEv100"), referenceEv100)
            GLES20.glUniform1f(uniform("uHighlightLatitude"), highlightLatitudeStops)
            GLES20.glUniform1f(uniform("uShadowLatitude"), shadowLatitudeStops)

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
                val textures = intArrayOf(sourceTexture, outputTexture).filter { it != 0 }
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

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        check(textures[0] != 0) { "Unable to create OpenGL texture" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
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
        uniform float uCameraSettingEv100;
        uniform float uCalibrationOffset;
        uniform float uReferenceEv100;
        uniform float uHighlightLatitude;
        uniform float uShadowLatitude;
        varying vec2 vTextureCoordinate;

        const float TARGET_LUMINANCE = 0.18;
        const float SHADOW_EDGE_LUMINANCE = 0.02;
        const float MIDDLE_GRAY_LUMINANCE = 0.18;
        const float HIGHLIGHT_EDGE_LUMINANCE = 0.98;
        const float OUTSIDE_EXTENSION_STOPS = 2.0;
        const float LUMINANCE_EPSILON = 0.000001;

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

        float smoothUnit(float value) {
            value = clamp(value, 0.0, 1.0);
            return value * value * (3.0 - 2.0 * value);
        }

        float interpolateLuminance(float from, float to, float progress) {
            return from + (to - from) * smoothUnit(progress);
        }

        float filmLuminance(float deltaEv) {
            if (deltaEv < -uShadowLatitude) {
                float progress =
                    (-deltaEv - uShadowLatitude) / OUTSIDE_EXTENSION_STOPS;
                return SHADOW_EDGE_LUMINANCE * (1.0 - smoothUnit(progress));
            }
            if (deltaEv < 0.0) {
                float progress = (deltaEv + uShadowLatitude) / uShadowLatitude;
                return interpolateLuminance(
                    SHADOW_EDGE_LUMINANCE,
                    MIDDLE_GRAY_LUMINANCE,
                    progress
                );
            }
            if (deltaEv <= uHighlightLatitude) {
                return interpolateLuminance(
                    MIDDLE_GRAY_LUMINANCE,
                    HIGHLIGHT_EDGE_LUMINANCE,
                    deltaEv / uHighlightLatitude
                );
            }
            float progress =
                (deltaEv - uHighlightLatitude) / OUTSIDE_EXTENSION_STOPS;
            return interpolateLuminance(
                HIGHLIGHT_EDGE_LUMINANCE,
                1.0,
                progress
            );
        }

        void main() {
            vec4 source = texture2D(uSource, vTextureCoordinate);
            vec3 linearRgb = srgbToLinear(source.rgb);
            float sourceLuminance = dot(
                linearRgb,
                vec3(0.2126, 0.7152, 0.0722)
            );
            float pixelEv100 =
                uCameraSettingEv100 +
                log2(max(sourceLuminance, LUMINANCE_EPSILON) / TARGET_LUMINANCE) +
                uCalibrationOffset;
            float targetLuminance = filmLuminance(pixelEv100 - uReferenceEv100);
            float gain = targetLuminance / max(sourceLuminance, LUMINANCE_EPSILON);
            gl_FragColor = vec4(linearToSrgb(linearRgb * gain), source.a);
        }
    """
}
