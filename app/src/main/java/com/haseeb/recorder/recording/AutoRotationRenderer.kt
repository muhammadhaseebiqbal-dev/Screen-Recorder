package com.haseeb.recorder.recording

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Fits changing capture dimensions into a single encoder canvas without CPU pixel copies. */
internal class AutoRotationRenderer(
    encoderSurface: Surface,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val onFailure: (Exception) -> Unit
) : AutoCloseable {
    private val thread = HandlerThread("AutoRotationRenderer").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var positionAttribute = 0
    private var uvAttribute = 0
    private var transformUniform = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null
    val surface: Surface get() = checkNotNull(captureSurface)
    private var viewport = RecordingFrameLayout.fit(canvasWidth, canvasHeight, canvasWidth, canvasHeight)
    private val transform = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        val initialization = FutureTask {
            try { initialize(encoderSurface) } catch (e: Exception) { releaseResources(); throw e }
        }
        handler.post(initialization)
        try { initialization.get(10, TimeUnit.SECONDS) } catch (e: Exception) {
            close()
            throw IllegalStateException("Cannot initialize rotation rendering", e)
        }
    }

    fun resize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        val resize = FutureTask {
            if (!closed) {
                surfaceTexture?.setDefaultBufferSize(width, height)
                viewport = RecordingFrameLayout.fit(width, height, canvasWidth, canvasHeight)
            }
        }
        handler.post(resize)
        resize.get(3, TimeUnit.SECONDS)
    }

    private fun initialize(encoderSurface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, null, 0, null, 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1, EGL14.EGL_NONE) // EGL_RECORDABLE_ANDROID
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        window = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        val shaders = intArrayOf(
            shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec2 uv; uniform mat4 transform; varying vec2 tex; void main(){ gl_Position=position; tex=(transform*vec4(uv,0.0,1.0)).xy; }"),
            shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 tex; void main(){ gl_FragColor=texture2D(image,tex); }")
        )
        program = GLES20.glCreateProgram()
        shaders.forEach { GLES20.glAttachShader(program, it) }
        GLES20.glLinkProgram(program)
        shaders.forEach { GLES20.glDeleteShader(it) }
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        positionAttribute = GLES20.glGetAttribLocation(program, "position")
        uvAttribute = GLES20.glGetAttribLocation(program, "uv")
        transformUniform = GLES20.glGetUniformLocation(program, "transform")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(canvasWidth, canvasHeight)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        captureSurface = Surface(surfaceTexture)
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(message)
        }
        return shader
    }

    private fun drawFrame() {
        if (closed) return
        try {
            val source = checkNotNull(surfaceTexture)
            source.updateTexImage()
            source.getTransformMatrix(transform)
            GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(transformUniform, 1, false, transform, 0)
            vertices.position(0)
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(positionAttribute)
            GLES20.glEnableVertexAttribArray(uvAttribute)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            check(EGLExt.eglPresentationTimeANDROID(display, window, source.timestamp))
            check(EGL14.eglSwapBuffers(display, window))
        } catch (e: Exception) {
            close()
            onFailure(e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Release on the GL owner; the encoder surface itself belongs to the service.
        if (Looper.myLooper() == thread.looper) {
            releaseResources()
            thread.quitSafely()
        } else {
            val release = FutureTask { releaseResources(); thread.quitSafely() }
            handler.post(release)
            try { release.get(3, TimeUnit.SECONDS) } catch (_: Exception) { thread.quitSafely() }
        }
    }

    private fun releaseResources() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        captureSurface?.release(); captureSurface = null
        surfaceTexture?.release(); surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }
}
