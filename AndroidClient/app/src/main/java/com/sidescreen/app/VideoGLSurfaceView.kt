package com.sidescreen.app

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

class VideoGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    var framePool: VideoFramePool? = null

    private val pendingFrames = ArrayBlockingQueue<VideoFrame>(2)

    private var flipHorizontal = false
    private var flipVertical = false

    private val renderer = VideoRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun pushFrame(frame: VideoFrame) {
        if (!pendingFrames.offer(frame)) {
            val old = pendingFrames.poll()
            if (old != null) {
                recycleFrame(old)
            }

            if (!pendingFrames.offer(frame)) {
                recycleFrame(frame)
                return
            }
        }

        requestRender()
    }

    fun clearFrames() {
        while (true) {
            val frame = pendingFrames.poll() ?: break
            recycleFrame(frame)
        }

        queueEvent {
            renderer.clearCurrent()
        }
    }

    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        flipHorizontal = horizontal
        flipVertical = vertical
        requestRender()
    }

    override fun onPause() {
        renderer.releaseCurrent()
        clearFrames()
        super.onPause()
    }

    private fun recycleFrame(frame: VideoFrame) {
        framePool?.release(frame)
    }

    private inner class VideoRenderer : GLSurfaceView.Renderer {

        private var program = 0

        private var aPosition = 0
        private var aTexCoord = 0

        private var uY = 0
        private var uU = 0
        private var uV = 0
        private var uUV = 0
        private var uRgba = 0
        private var uHalfTexel = 0
        private var uMode = 0

        private var yTexture = 0
        private var uTexture = 0
        private var vTexture = 0
        private var uvTexture = 0
        private var rgbaTexture = 0

        private var lastFrame: VideoFrame? = null

        private var yWidth = -1
        private var yHeight = -1

        private var uWidth = -1
        private var uHeight = -1

        private var vWidth = -1
        private var vHeight = -1

        private var uvWidth = -1
        private var uvHeight = -1

        private var rgbaWidth = -1
        private var rgbaHeight = -1

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private val vertexBuffer: FloatBuffer =
            ByteBuffer
                .allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        override fun onSurfaceCreated(
            gl: GL10?,
            config: javax.microedition.khronos.egl.EGLConfig?
        ) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            EGL14.eglSwapInterval(
                EGL14.eglGetCurrentDisplay(),
                1
            )

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

            uY = GLES20.glGetUniformLocation(program, "uY")
            uU = GLES20.glGetUniformLocation(program, "uU")
            uV = GLES20.glGetUniformLocation(program, "uV")
            uUV = GLES20.glGetUniformLocation(program, "uUV")
            uRgba = GLES20.glGetUniformLocation(program, "uRgba")
            uHalfTexel = GLES20.glGetUniformLocation(program, "uHalfTexel")
            uMode = GLES20.glGetUniformLocation(program, "uMode")

            val textures = IntArray(5)
            GLES20.glGenTextures(5, textures, 0)

            yTexture = textures[0]
            uTexture = textures[1]
            vTexture = textures[2]
            uvTexture = textures[3]
            rgbaTexture = textures[4]

            GLES20.glUseProgram(program)

            GLES20.glUniform1i(uY, 0)
            GLES20.glUniform1i(uU, 1)
            GLES20.glUniform1i(uV, 2)
            GLES20.glUniform1i(uUV, 3)
            GLES20.glUniform1i(uRgba, 4)
        }

        override fun onSurfaceChanged(
            gl: GL10?,
            width: Int,
            height: Int
        ) {
            surfaceWidth = width
            surfaceHeight = height

            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
            )

            val nextFrame = pendingFrames.poll()

            if (nextFrame != null) {
                lastFrame?.let { recycleFrame(it) }
                lastFrame = nextFrame
            }

            val frame = lastFrame ?: return

            drawFrame(frame)
        }

        fun clearCurrent() {
            lastFrame?.let {
                recycleFrame(it)
                lastFrame = null
            }
        }

        fun releaseCurrent() {
            lastFrame?.let {
                recycleFrame(it)
                lastFrame = null
            }
        }

        private fun drawFrame(frame: VideoFrame) {
            val width = frame.width
            val height = frame.height

            if (width <= 0 || height <= 0) {
                return
            }

            val frameAspect = width.toFloat() / height.toFloat()
            val viewAspect =
                if (surfaceHeight == 0) {
                    frameAspect
                } else {
                    surfaceWidth.toFloat() / surfaceHeight.toFloat()
                }

            var drawWidth = 1f
            var drawHeight = 1f

            if (frameAspect > viewAspect) {
                drawHeight = viewAspect / frameAspect
            } else {
                drawWidth = frameAspect / viewAspect
            }

            val left = -drawWidth
            val right = drawWidth
            val top = drawHeight
            val bottom = -drawHeight

            val texLeft = if (flipHorizontal) 1f else 0f
            val texRight = if (flipHorizontal) 0f else 1f
            val texTop = if (flipVertical) 1f else 0f
            val texBottom = if (flipVertical) 0f else 1f

            val vertices = floatArrayOf(
                left, bottom, texLeft, texBottom,
                right, bottom, texRight, texBottom,
                left, top, texLeft, texTop,
                right, top, texRight, texTop
            )

            vertexBuffer.clear()
            vertexBuffer.put(vertices)
            vertexBuffer.position(0)

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(
                aPosition,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertexBuffer
            )

            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(
                aTexCoord,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertexBuffer
            )

            when (frame.format) {
                VideoPixelFormat.YUV420P -> {
                    uploadYuv420(frame)
                }

                VideoPixelFormat.NV12 -> {
                    uploadNv12(frame)
                }

                VideoPixelFormat.RGBA -> {
                    uploadRgba(frame)
                }
            }

            GLES20.glUniform1f(
                uHalfTexel,
                0.5f / width.toFloat()
            )

            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                0,
                4
            )

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        }

        private fun uploadYuv420(frame: VideoFrame) {
            val y = frame.y ?: return
            val u = frame.u ?: return
            val v = frame.v ?: return

            GLES20.glUniform1i(uMode, MODE_YUV420)

            uploadLuminance(
                texture = yTexture,
                unit = 0,
                data = y,
                width = frame.width,
                height = frame.height
            )

            uploadLuminance(
                texture = uTexture,
                unit = 1,
                data = u,
                width = (frame.width + 1) / 2,
                height = (frame.height + 1) / 2
            )

            uploadLuminance(
                texture = vTexture,
                unit = 2,
                data = v,
                width = (frame.width + 1) / 2,
                height = (frame.height + 1) / 2
            )
        }

        private fun uploadNv12(frame: VideoFrame) {
            val y = frame.y ?: return
            val uv = frame.uv ?: return

            GLES20.glUniform1i(uMode, MODE_NV12)

            uploadLuminance(
                texture = yTexture,
                unit = 0,
                data = y,
                width = frame.width,
                height = frame.height
            )

            uploadLuminanceAlpha(
                texture = uvTexture,
                unit = 3,
                data = uv,
                width = (frame.width + 1) / 2,
                height = (frame.height + 1) / 2
            )
        }

        private fun uploadRgba(frame: VideoFrame) {
            val rgba = frame.rgba ?: return

            GLES20.glUniform1i(uMode, MODE_RGBA)

            GLES20.glActiveTexture(
                GLES20.GL_TEXTURE4
            )

            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                rgbaTexture
            )

            configureTexture()

            if (
                rgbaWidth != frame.width ||
                rgbaHeight != frame.height
            ) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    frame.width,
                    frame.height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(rgba)
                )

                rgbaWidth = frame.width
                rgbaHeight = frame.height
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    frame.width,
                    frame.height,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(rgba)
                )
            }
        }

        private fun uploadLuminance(
            texture: Int,
            unit: Int,
            data: ByteArray,
            width: Int,
            height: Int
        ) {
            GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0 + unit
            )

            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                texture
            )

            configureTexture()

            val needsAllocation =
                when (texture) {
                    yTexture ->
                        yWidth != width || yHeight != height

                    uTexture ->
                        uWidth != width || uHeight != height

                    vTexture ->
                        vWidth != width || vHeight != height

                    else -> true
                }

            if (needsAllocation) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width,
                    height,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(data)
                )

                when (texture) {
                    yTexture -> {
                        yWidth = width
                        yHeight = height
                    }

                    uTexture -> {
                        uWidth = width
                        uHeight = height
                    }

                    vTexture -> {
                        vWidth = width
                        vHeight = height
                    }
                }
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    width,
                    height,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(data)
                )
            }
        }

        private fun uploadLuminanceAlpha(
            texture: Int,
            unit: Int,
            data: ByteArray,
            width: Int,
            height: Int
        ) {
            GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0 + unit
            )

            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                texture
            )

            configureTexture()

            val needsAllocation =
                uvWidth != width || uvHeight != height

            if (needsAllocation) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE_ALPHA,
                    width,
                    height,
                    0,
                    GLES20.GL_LUMINANCE_ALPHA,
                    GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(data)
                )

                uvWidth = width
                uvHeight = height
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    width,
                    height,
                    GLES20.GL_LUMINANCE_ALPHA,
                    GLES20.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(data)
                )
            }
        }

        private fun configureTexture() {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
        }
    }

    private companion object {
        const val MODE_YUV420 = 0
        const val MODE_NV12 = 1
        const val MODE_RGBA = 2

        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;

            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            precision mediump float;

            varying vec2 vTexCoord;

            uniform sampler2D uY;
            uniform sampler2D uU;
            uniform sampler2D uV;
            uniform sampler2D uUV;
            uniform sampler2D uRgba;

            uniform float uHalfTexel;
            uniform int uMode;

            void main() {
                if (uMode == 2) {
                    gl_FragColor = texture2D(
                        uRgba,
                        vTexCoord
                    );
                    return;
                }

                float y = texture2D(
                    uY,
                    vTexCoord
                ).r;

                float u;
                float v;

                if (uMode == 0) {
                    vec2 chromaCoord =
                        vTexCoord +
                        vec2(uHalfTexel, uHalfTexel);

                    u = texture2D(
                        uU,
                        chromaCoord
                    ).r - 0.5;

                    v = texture2D(
                        uV,
                        chromaCoord
                    ).r - 0.5;
                } else {
                    vec2 uv =
                        texture2D(
                            uUV,
                            vTexCoord +
                            vec2(uHalfTexel, uHalfTexel)
                        ).ra;

                    u = uv.x - 0.5;
                    v = uv.y - 0.5;
                }

                // BT.709 full-range YUV -> RGB.
                float r =
                    y +
                    1.402 * v;

                float g =
                    y -
                    0.344136 * u -
                    0.714136 * v;

                float b =
                    y +
                    1.772 * u;

                gl_FragColor = vec4(
                    clamp(r, 0.0, 1.0),
                    clamp(g, 0.0, 1.0),
                    clamp(b, 0.0, 1.0),
                    1.0
                );
            }
        """.trimIndent()

        fun loadShader(
            type: Int,
            source: String
        ): Int {
            val shader = GLES20.glCreateShader(type)

            GLES20.glShaderSource(
                shader,
                source
            )

            GLES20.glCompileShader(shader)

            val compiled = IntArray(1)

            GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                compiled,
                0
            )

            if (compiled[0] == 0) {
                val error =
                    GLES20.glGetShaderInfoLog(shader)

                GLES20.glDeleteShader(shader)

                throw RuntimeException(
                    "Shader compilation failed: $error"
                )
            }

            return shader
        }

        fun createProgram(
            vertexSource: String,
            fragmentSource: String
        ): Int {
            val vertexShader =
                loadShader(
                    GLES20.GL_VERTEX_SHADER,
                    vertexSource
                )

            val fragmentShader =
                loadShader(
                    GLES20.GL_FRAGMENT_SHADER,
                    fragmentSource
                )

            val program =
                GLES20.glCreateProgram()

            GLES20.glAttachShader(
                program,
                vertexShader
            )

            GLES20.glAttachShader(
                program,
                fragmentShader
            )

            GLES20.glLinkProgram(program)

            val linked = IntArray(1)

            GLES20.glGetProgramiv(
                program,
                GLES20.GL_LINK_STATUS,
                linked,
                0
            )

            if (linked[0] == 0) {
                val error =
                    GLES20.glGetProgramInfoLog(program)

                GLES20.glDeleteProgram(program)

                throw RuntimeException(
                    "Program linking failed: $error"
                )
            }

            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            return program
        }
    }
}
