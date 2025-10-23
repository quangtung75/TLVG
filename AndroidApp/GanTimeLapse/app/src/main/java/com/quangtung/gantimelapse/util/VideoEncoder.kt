package com.quangtung.gantimelapse.util

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.*
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class VideoEncoder {

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 4
        private const val I_FRAME_INTERVAL = 1
        private const val TIMEOUT_USEC = 10000L
        private const val BIT_RATE = 2_000_000
    }

    suspend fun encodeFramesToMp4(frames: List<Bitmap>, outputFile: File) {
        if (frames.isEmpty()) return

        val width = frames.first().width
        val height = frames.first().height

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val glHelper = GLHelper(inputSurface, width, height)

        try {
            frames.forEachIndexed { index, bitmap ->
                glHelper.renderBitmap(bitmap)

                val presentationTimeUs = index * 1_000_000L / FRAME_RATE
                glHelper.setPresentationTime(presentationTimeUs)

                glHelper.swapBuffers()

                drainEncoder(codec, bufferInfo, muxer, trackIndex, muxerStarted) { newTrack, started ->
                    trackIndex = newTrack
                    muxerStarted = started
                }
            }

            codec.signalEndOfInputStream()
            drainEncoder(codec, bufferInfo, muxer, trackIndex, muxerStarted, true) { _, _ -> }

        } finally {
            glHelper.release()
            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        trackIndexIn: Int,
        muxerStartedIn: Boolean,
        isEndOfStream: Boolean = false,
        onStateChange: (Int, Boolean) -> Unit
    ) {
        var trackIndex = trackIndexIn
        var muxerStarted = muxerStartedIn

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!isEndOfStream) break
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw RuntimeException("format changed twice")
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                    onStateChange(trackIndex, muxerStarted)
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex)
                        ?: throw RuntimeException("encoderOutputBuffer $outputBufferIndex was null")
                    if (bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private class GLHelper(surface: Surface, private val width: Int, private val height: Int) {
        private val eglDisplay: EGLDisplay
        private val eglContext: EGLContext
        private val eglSurface: EGLSurface

        private val vertexBuffer: FloatBuffer
        private val texBuffer: FloatBuffer
        private var program = 0
        private var textureId = 0

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            val eglConfig = configs[0]!!

            val attribContext = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribContext, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val vertexCoords = floatArrayOf(
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
            )
            val texCoords = floatArrayOf(
                0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
            )
            vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer.put(vertexCoords).position(0)
            texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            texBuffer.put(texCoords).position(0)

            val vertexShaderCode = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """
            val fragmentShaderCode = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """
            program = createProgram(vertexShaderCode, fragmentShaderCode)
            glUseProgram(program)

            // Texture
            val textures = IntArray(1)
            glGenTextures(1, textures, 0)
            textureId = textures[0]
            glBindTexture(GL_TEXTURE_2D, textureId)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }

        fun renderBitmap(bitmap: Bitmap) {
            glViewport(0, 0, width, height)
            glClear(GL_COLOR_BUFFER_BIT)

            glBindTexture(GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)

            val posHandle = glGetAttribLocation(program, "aPosition")
            val texHandle = glGetAttribLocation(program, "aTexCoord")
            val texUniform = glGetUniformLocation(program, "uTexture")

            glEnableVertexAttribArray(posHandle)
            glVertexAttribPointer(posHandle, 2, GL_FLOAT, false, 0, vertexBuffer)
            glEnableVertexAttribArray(texHandle)
            glVertexAttribPointer(texHandle, 2, GL_FLOAT, false, 0, texBuffer)
            glUniform1i(texUniform, 0)

            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

            glDisableVertexAttribArray(posHandle)
            glDisableVertexAttribArray(texHandle)
        }

        fun setPresentationTime(presentationTimeUs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }

        private fun loadShader(type: Int, code: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, code)
            glCompileShader(shader)
            return shader
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            val vs = loadShader(GL_VERTEX_SHADER, vertex)
            val fs = loadShader(GL_FRAGMENT_SHADER, fragment)
            val program = glCreateProgram()
            glAttachShader(program, vs)
            glAttachShader(program, fs)
            glLinkProgram(program)
            return program
        }
    }
}
