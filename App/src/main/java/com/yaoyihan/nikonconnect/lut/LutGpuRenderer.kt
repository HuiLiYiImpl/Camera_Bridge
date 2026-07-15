package com.yaoyihan.nikonconnect.lut

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.yaoyihan.nikonconnect.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val VertexShader = """
#version 300 es
in vec2 aPosition;
out vec2 vTexCoord;
uniform vec2 uScale;
uniform float uZoom;
uniform vec2 uPan;
uniform int uRotation;
void main() {
    vec2 screenCoord = vec2((aPosition.x + 1.0) * 0.5, (1.0 - aPosition.y) * 0.5);
    if (uRotation == 1) vTexCoord = vec2(screenCoord.y, 1.0 - screenCoord.x);
    else if (uRotation == 2) vTexCoord = vec2(1.0 - screenCoord.x, 1.0 - screenCoord.y);
    else if (uRotation == 3) vTexCoord = vec2(1.0 - screenCoord.y, screenCoord.x);
    else vTexCoord = screenCoord;
    gl_Position = vec4(aPosition * uScale * uZoom + uPan, 0.0, 1.0);
}
"""

private const val FragmentShader = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
uniform sampler2D uImage;
uniform highp sampler3D uLut;
uniform float uIntensity;
uniform float uLutSize;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform bool uHasLut;
out vec4 fragmentColor;
void main() {
    vec4 original = texture(uImage, vTexCoord);
    if (!uHasLut) { fragmentColor = original; return; }
    vec3 inputColor = clamp((original.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
    float scale = (uLutSize - 1.0) / uLutSize;
    float offset = 1.0 / (2.0 * uLutSize);
    vec3 coord = inputColor * scale + offset;
    vec3 graded = texture(uLut, coord).rgb;
    fragmentColor = vec4(mix(original.rgb, graded, clamp(uIntensity, 0.0, 1.0)), original.a);
}
"""
private const val LutGpuLogTag = "LutGlView"

class LutGlView(context: Context) : GLSurfaceView(context) {
    private val lutRenderer = Renderer()

    init {
        setEGLContextClientVersion(3)
        setRenderer(lutRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setImage(bitmap: Bitmap?) { lutRenderer.image = bitmap; requestRender() }
    fun setLut(lut: CubeLut?) { lutRenderer.lut = lut; requestRender() }
    fun setIntensity(value: Float) { lutRenderer.intensity = value; requestRender() }
    fun setImageRotation(degrees: Int) { lutRenderer.rotation = ((degrees % 360) + 360) % 360 / 90; requestRender() }
    fun setTransform(zoom: Float, panX: Float, panY: Float) { lutRenderer.zoom = zoom; lutRenderer.panX = panX; lutRenderer.panY = panY; requestRender() }

    override fun onDetachedFromWindow() {
        queueEvent { lutRenderer.release() }
        super.onDetachedFromWindow()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        var image: Bitmap? = null
        var lut: CubeLut? = null
        var intensity = 1f
        var rotation = 0
        var zoom = 1f
        var panX = 0f
        var panY = 0f
        private var program = 0
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var imageTexture = 0
        private var lutTexture = 0
        private var uploadedImage: Bitmap? = null
        private var uploadedLut: CubeLut? = null
        private var position = 0
        private var imageHandle = 0
        private var lutHandle = 0
        private var intensityHandle = 0
        private var sizeHandle = 0
        private var domainMinHandle = 0
        private var domainMaxHandle = 0
        private var hasLutHandle = 0
        private var scaleHandle = 0
        private var zoomHandle = 0
        private var panHandle = 0
        private var rotationHandle = 0
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = runCatching {
                linkProgram(compile(GLES30.GL_VERTEX_SHADER, VertexShader), compile(GLES30.GL_FRAGMENT_SHADER, FragmentShader))
            }.getOrElse { error ->
                Log.e(LutGpuLogTag, "OpenGL initialization failed", error)
                return
            }
            position = GLES30.glGetAttribLocation(program, "aPosition")
            imageHandle = GLES30.glGetUniformLocation(program, "uImage")
            lutHandle = GLES30.glGetUniformLocation(program, "uLut")
            intensityHandle = GLES30.glGetUniformLocation(program, "uIntensity")
            sizeHandle = GLES30.glGetUniformLocation(program, "uLutSize")
            domainMinHandle = GLES30.glGetUniformLocation(program, "uDomainMin")
            domainMaxHandle = GLES30.glGetUniformLocation(program, "uDomainMax")
            hasLutHandle = GLES30.glGetUniformLocation(program, "uHasLut")
            scaleHandle = GLES30.glGetUniformLocation(program, "uScale")
            zoomHandle = GLES30.glGetUniformLocation(program, "uZoom")
            panHandle = GLES30.glGetUniformLocation(program, "uPan")
            rotationHandle = GLES30.glGetUniformLocation(program, "uRotation")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (program == 0) return
            val source = image ?: return
            GLES30.glUseProgram(program)
            if (uploadedImage !== source) {
                if (imageTexture == 0) imageTexture = genTexture()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                uploadedImage = source
            }
            val selected = lut
            val imageWidth = if (rotation % 2 == 0) source.width else source.height
            val imageHeight = if (rotation % 2 == 0) source.height else source.width
            val imageAspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1)
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
            if (imageAspect > surfaceAspect) GLES30.glUniform2f(scaleHandle, 1f, surfaceAspect / imageAspect)
            else GLES30.glUniform2f(scaleHandle, imageAspect / surfaceAspect, 1f)
            GLES30.glUniform1f(zoomHandle, zoom)
            GLES30.glUniform2f(panHandle, panX * 2f / surfaceWidth, panY * -2f / surfaceHeight)
            GLES30.glUniform1i(rotationHandle, rotation)
            GLES30.glUniform1i(hasLutHandle, if (selected == null) 0 else 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
            GLES30.glUniform1i(imageHandle, 0)
            if (selected != null) {
                if (uploadedLut !== selected) uploadLut(selected)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
                GLES30.glUniform1i(lutHandle, 1)
                GLES30.glUniform1f(sizeHandle, selected.size.toFloat())
                GLES30.glUniform3fv(domainMinHandle, 1, selected.domainMin, 0)
                GLES30.glUniform3fv(domainMaxHandle, 1, selected.domainMax, 0)
            }
            GLES30.glUniform1f(intensityHandle, intensity)
            GLES30.glEnableVertexAttribArray(position)
            GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, vertices)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(position)
        }

        private fun uploadLut(lut: CubeLut) {
            if (lutTexture == 0) lutTexture = genTexture3d()
            val buffer = ByteBuffer.allocateDirect(lut.values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(lut.values).position(0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            uploadedLut = lut
        }

        fun release() {
            if (imageTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(imageTexture), 0)
            if (lutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
            if (program != 0) GLES30.glDeleteProgram(program)
            imageTexture = 0; lutTexture = 0; program = 0; uploadedImage = null; uploadedLut = null
        }

        private fun genTexture() = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        private fun genTexture3d() = genTexture()
        private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader -> GLES30.glShaderSource(shader, source.trimStart()); GLES30.glCompileShader(shader); checkStatus(shader, GLES30.GL_COMPILE_STATUS, "Shader 编译失败") }
        private fun linkProgram(vertex: Int, fragment: Int): Int = GLES30.glCreateProgram().also { p -> GLES30.glAttachShader(p, vertex); GLES30.glAttachShader(p, fragment); GLES30.glLinkProgram(p); val result = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, result, 0); if (result[0] == 0) error("Shader 链接失败") }
        private fun checkStatus(handle: Int, status: Int, message: String) {
            val result = IntArray(1)
            GLES30.glGetShaderiv(handle, status, result, 0)
            if (result[0] == 0) error("$message：${GLES30.glGetShaderInfoLog(handle)}")
        }
    }
}
