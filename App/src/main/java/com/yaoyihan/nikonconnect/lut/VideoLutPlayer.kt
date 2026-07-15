package com.yaoyihan.nikonconnect.lut

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import com.yaoyihan.nikonconnect.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val VideoVertexShader = """
#version 300 es
in vec2 aPosition;
out vec2 vTexCoord;
uniform vec2 uScale;
uniform int uRotation;
void main() {
    vec2 screenCoord = (aPosition + 1.0) * 0.5;
    // SurfaceTexture uses a vertically flipped texture space, so clockwise
    // display rotation needs the inverse mapping used by regular bitmaps.
    if (uRotation == 1) vTexCoord = vec2(1.0 - screenCoord.y, screenCoord.x);
    else if (uRotation == 2) vTexCoord = vec2(1.0 - screenCoord.x, 1.0 - screenCoord.y);
    else if (uRotation == 3) vTexCoord = vec2(screenCoord.y, 1.0 - screenCoord.x);
    else vTexCoord = screenCoord;
    gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
}
"""

private const val VideoFragmentShader = """
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vTexCoord;
uniform samplerExternalOES uVideo;
uniform mat4 uTextureMatrix;
uniform highp sampler3D uLut;
uniform float uIntensity;
uniform float uLutSize;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform bool uHasLut;
out vec4 fragmentColor;
void main() {
    vec2 sourceCoord = (uTextureMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
    vec4 original = texture(uVideo, sourceCoord);
    if (!uHasLut) { fragmentColor = original; return; }
    vec3 inputColor = clamp((original.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
    float scale = (uLutSize - 1.0) / uLutSize;
    float offset = 1.0 / (2.0 * uLutSize);
    vec3 graded = texture(uLut, inputColor * scale + offset).rgb;
    fragmentColor = vec4(mix(original.rgb, graded, clamp(uIntensity, 0.0, 1.0)), original.a);
}
"""
private const val VideoLutLogTag = "VideoLutGlView"

class VideoLutGlView(context: Context) : GLSurfaceView(context) {
    var onPrepared: (Long) -> Unit = {}
    var onAudioAvailabilityChanged: (Boolean?) -> Unit = {}
    var onPlayingChanged: (Boolean) -> Unit = {}
    var onError: () -> Unit = {}

    private val lutRenderer = Renderer()
    private var source: Uri? = null
    private var outputSurface: Surface? = null
    private var player: MediaPlayer? = null
    private var resumePositionMs = 0L

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(lutRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setVideoUri(uri: Uri) {
        if (source == uri) return
        source = uri
        preparePlayer()
    }

    fun setLut(lut: CubeLut?) { lutRenderer.lut = lut; requestRender() }
    fun setIntensity(value: Float) { lutRenderer.intensity = value; requestRender() }
    fun setFillScreen(fill: Boolean) { lutRenderer.fillScreen = fill; requestRender() }
    fun setRotation(quarterTurns: Int) { lutRenderer.rotation = ((quarterTurns % 4) + 4) % 4; requestRender() }
    fun play() { player?.start(); onPlayingChanged(true) }
    fun pause() { player?.pause(); onPlayingChanged(false) }
    fun togglePlayback() { if (player?.isPlaying == true) pause() else play() }
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs.coerceIn(0, duration()).toInt()) }
    fun position(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    fun duration(): Long = runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)
    fun isPlayingNow(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun pauseForExport() {
        resumePositionMs = position()
        player?.release()
        player = null
        onPlayingChanged(false)
    }

    fun resumeAfterExport() = preparePlayer()

    fun releasePlayer() {
        player?.release()
        player = null
        outputSurface?.release()
        outputSurface = null
    }

    private fun preparePlayer() {
        val uri = source ?: return
        val surface = outputSurface ?: return
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
                setVolume(1f, 1f)
                setDataSource(context, uri)
                setSurface(surface)
                setOnVideoSizeChangedListener { _, width, height -> lutRenderer.setVideoSize(width, height) }
                setOnPreparedListener {
                    isLooping = false
                    onAudioAvailabilityChanged(runCatching {
                        trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }
                    }.getOrNull())
                    onPrepared(duration.toLong())
                    if (resumePositionMs > 0L) seekTo(resumePositionMs.coerceAtMost(duration.toLong()).toInt())
                    resumePositionMs = 0L
                    start()
                    onPlayingChanged(true)
                }
                setOnCompletionListener { onPlayingChanged(false) }
                setOnErrorListener { _, what, extra ->
                    Log.e(VideoLutLogTag, "MediaPlayer error what=$what extra=$extra")
                    onError()
                    true
                }
                prepareAsync()
            }
        }.getOrElse { error ->
            Log.e(VideoLutLogTag, "MediaPlayer prepare failed", error)
            onError()
            null
        }
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        queueEvent { lutRenderer.release() }
        super.onDetachedFromWindow()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        var lut: CubeLut? = null
        var intensity = 1f
        var fillScreen = false
        var rotation = 0
        private var videoWidth = 16
        private var videoHeight = 9
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var program = 0
        private var videoTexture = 0
        private var lutTexture = 0
        private var surfaceTexture: SurfaceTexture? = null
        @Volatile private var frameAvailable = false
        private var uploadedLut: CubeLut? = null
        private var positionHandle = 0
        private var scaleHandle = 0
        private var rotationHandle = 0
        private var videoHandle = 0
        private var matrixHandle = 0
        private var lutHandle = 0
        private var intensityHandle = 0
        private var sizeHandle = 0
        private var domainMinHandle = 0
        private var domainMaxHandle = 0
        private var hasLutHandle = 0
        private val textureMatrix = FloatArray(16)
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }

        fun setVideoSize(width: Int, height: Int) {
            videoWidth = width.coerceAtLeast(1)
            videoHeight = height.coerceAtLeast(1)
            surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
            requestRender()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = runCatching {
                linkProgram(compile(GLES30.GL_VERTEX_SHADER, VideoVertexShader), compile(GLES30.GL_FRAGMENT_SHADER, VideoFragmentShader))
            }.getOrElse { error ->
                Log.e(VideoLutLogTag, "OpenGL initialization failed", error)
                post { onError() }
                return
            }
            positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
            scaleHandle = GLES30.glGetUniformLocation(program, "uScale")
            rotationHandle = GLES30.glGetUniformLocation(program, "uRotation")
            videoHandle = GLES30.glGetUniformLocation(program, "uVideo")
            matrixHandle = GLES30.glGetUniformLocation(program, "uTextureMatrix")
            lutHandle = GLES30.glGetUniformLocation(program, "uLut")
            intensityHandle = GLES30.glGetUniformLocation(program, "uIntensity")
            sizeHandle = GLES30.glGetUniformLocation(program, "uLutSize")
            domainMinHandle = GLES30.glGetUniformLocation(program, "uDomainMin")
            domainMaxHandle = GLES30.glGetUniformLocation(program, "uDomainMax")
            hasLutHandle = GLES30.glGetUniformLocation(program, "uHasLut")
            videoTexture = genTexture()
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            surfaceTexture = SurfaceTexture(videoTexture).apply {
                setDefaultBufferSize(videoWidth, videoHeight)
                setOnFrameAvailableListener { frameAvailable = true; requestRender() }
            }
            post {
                outputSurface?.release()
                outputSurface = Surface(surfaceTexture)
                preparePlayer()
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (frameAvailable) {
                runCatching { surfaceTexture?.updateTexImage() }
                frameAvailable = false
            }
            surfaceTexture?.getTransformMatrix(textureMatrix)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(program)
            val displayWidth = if (rotation % 2 == 0) videoWidth else videoHeight
            val displayHeight = if (rotation % 2 == 0) videoHeight else videoWidth
            val videoAspect = displayWidth.toFloat() / displayHeight
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
            val scale = if (!fillScreen) {
                if (videoAspect > surfaceAspect) floatArrayOf(1f, surfaceAspect / videoAspect) else floatArrayOf(videoAspect / surfaceAspect, 1f)
            } else {
                if (videoAspect > surfaceAspect) floatArrayOf(videoAspect / surfaceAspect, 1f) else floatArrayOf(1f, surfaceAspect / videoAspect)
            }
            GLES30.glUniform2f(scaleHandle, scale[0], scale[1])
            GLES30.glUniform1i(rotationHandle, rotation)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
            GLES30.glUniform1i(videoHandle, 0)
            // samplerExternalOES and sampler3D must never point at the same texture unit,
            // even when the LUT branch is disabled in the shader.
            GLES30.glUniform1i(lutHandle, 1)
            GLES30.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
            val selected = lut
            GLES30.glUniform1i(hasLutHandle, if (selected == null) 0 else 1)
            if (selected != null) {
                if (uploadedLut !== selected) uploadLut(selected)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
                GLES30.glUniform1f(sizeHandle, selected.size.toFloat())
                GLES30.glUniform3fv(domainMinHandle, 1, selected.domainMin, 0)
                GLES30.glUniform3fv(domainMaxHandle, 1, selected.domainMax, 0)
            }
            GLES30.glUniform1f(intensityHandle, intensity)
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertices)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(positionHandle)
        }

        private fun uploadLut(value: CubeLut) {
            if (lutTexture == 0) lutTexture = genTexture()
            val buffer = ByteBuffer.allocateDirect(value.values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(value.values).position(0) }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, value.size, value.size, value.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            uploadedLut = value
        }

        fun release() {
            surfaceTexture?.release()
            surfaceTexture = null
            if (videoTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(videoTexture), 0)
            if (lutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
            if (program != 0) GLES30.glDeleteProgram(program)
            videoTexture = 0; lutTexture = 0; program = 0; uploadedLut = null
        }

        private fun genTexture() = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source.trimStart())
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) error("视频 Shader 编译失败：${GLES30.glGetShaderInfoLog(shader)}")
        }
        private fun linkProgram(vertex: Int, fragment: Int): Int = GLES30.glCreateProgram().also { value ->
            GLES30.glAttachShader(value, vertex)
            GLES30.glAttachShader(value, fragment)
            GLES30.glLinkProgram(value)
            val status = IntArray(1)
            GLES30.glGetProgramiv(value, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) error("视频 Shader 链接失败：${GLES30.glGetProgramInfoLog(value)}")
        }
    }
}
