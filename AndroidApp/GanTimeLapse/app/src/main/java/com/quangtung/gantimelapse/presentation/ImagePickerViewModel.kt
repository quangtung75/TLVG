package com.quangtung.gantimelapse.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quangtung.gantimelapse.util.GeneratorRunner
import com.quangtung.gantimelapse.util.TimelapseGenerator
import com.quangtung.gantimelapse.util.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ImagePickerViewModel(application: Application) : AndroidViewModel(application) {

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState

    private lateinit var generator: TimelapseGenerator
    private lateinit var runner: GeneratorRunner

    private val startHour: Float = 12F
    private val endHour: Float = 23F

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelPath = assetFilePath("model_mobile.ptl")
                runner = GeneratorRunner(modelPath)
                generator = TimelapseGenerator(application.applicationContext, runner)
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("Failed to load model: ${e.message}")
            }
        }
    }

    fun startGeneration(imageUri: Uri) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val originalFrameCount = 24
                val framesToInsert = 3
                val fakeNightFrameCount = 6

                _processingState.value = ProcessingState.Processing("Loading guide image...", 0)
                val guideLoaded = withContext(Dispatchers.Default) {
                    generator.loadGuideBitmap(imageUri)
                }
                if (guideLoaded == null) throw Exception("Failed to load guide image")

                _processingState.value = ProcessingState.Processing("Generating low-res frames...", -1)
                val lowResFrames = withContext(Dispatchers.Default) {
                    generator.generateLowResFrames(originalFrameCount, startHour, endHour)
                }

                _processingState.value = ProcessingState.Processing("Generating dark frames...", -1)
                val fakeNightFrames = withContext(Dispatchers.Default) {
                    val lastFrame = lowResFrames.last()
                    createFakeNightFrames(lastFrame, fakeNightFrameCount)
                }
                val allLowResFrames = lowResFrames.toMutableList().apply { addAll(fakeNightFrames) }
                val newOriginalCount = allLowResFrames.size

                val totalFrames = newOriginalCount + (newOriginalCount - 1) * framesToInsert

                _processingState.value = ProcessingState.Processing("Smoothing transitions...", -1)
                val interpolatedLowRes = withContext(Dispatchers.Default) {
                    applyFakeInterpolation(allLowResFrames, framesToInsert)
                }

                _processingState.value = ProcessingState.Processing("Upsampling frames...", 0)
                val finalHighResFrames = mutableListOf<Bitmap>()

                interpolatedLowRes.forEachIndexed { index, lowResBmp ->
                    val highResFrame = withContext(Dispatchers.Default) {
                        generator.upsampleSingleFrame(lowResBmp)
                    }
                    finalHighResFrames.add(highResFrame.bitmap)
                    val progress = ((index + 1) * 100 / totalFrames)
                    _processingState.value = ProcessingState.Processing("Upsampling frames...", progress)
                }
                interpolatedLowRes.forEach { if (!it.isRecycled) it.recycle() }


                _processingState.value = ProcessingState.Processing("Creating video...", -1)

                val videoPath = createVideoFromFrames(finalHighResFrames)

                _processingState.value = ProcessingState.Complete(videoPath)

            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("Error: ${e.message}")
            }
        }
    }


    private fun createFakeNightFrames(lastFrame: Bitmap, count: Int): List<Bitmap> {
        val nightFrames = mutableListOf<Bitmap>()
        for (i in 1..count) {

            val scale = 1.0f - (i.toFloat() / (count + 1).toFloat())
            val darkFrame = adjustBrightness(lastFrame, scale)
            if (darkFrame != null) {
                nightFrames.add(darkFrame)
            }
        }
        return nightFrames
    }


    private fun adjustBrightness(bitmap: Bitmap, scale: Float): Bitmap? {
        val resultBitmap = bitmap.config?.let {
            Bitmap.createBitmap(bitmap.width, bitmap.height,
                it
            )
        }
        val canvas = resultBitmap?.let { Canvas(it) }
        val paint = Paint()
        val matrix = ColorMatrix().apply {
            setScale(scale, scale, scale, 1.0f)
        }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        if (canvas != null) {
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
        return resultBitmap
    }

    private suspend fun createVideoFromFrames(frames: List<Bitmap>): String = withContext(Dispatchers.Default) {
        val evenFrames = frames.map { makeEven(it) }

        val outputFile = File(getApplication<Application>().cacheDir, "timelapse_${System.currentTimeMillis()}.mp4")

        withContext(Dispatchers.IO) {
            VideoEncoder().encodeFramesToMp4(evenFrames, outputFile)
        }
        return@withContext outputFile.absolutePath
    }

    private fun applyFakeInterpolation(
        originalFrames: List<Bitmap>,
        framesToInsert: Int
    ): List<Bitmap> {
        val smoothedFrames = mutableListOf<Bitmap>()
        for (i in 0 until originalFrames.size - 1) {
            val frameA = originalFrames[i]
            val frameB = originalFrames[i + 1]
            smoothedFrames.add(frameA)
            for (j in 1..framesToInsert) {
                val ratio = j.toFloat() / (framesToInsert + 1)
                val intermediateFrame = alphaBlendBitmaps(frameA, frameB, ratio)
                smoothedFrames.add(intermediateFrame)
            }
        }
        smoothedFrames.add(originalFrames.last())
        return smoothedFrames
    }

    private fun alphaBlendBitmaps(frameA: Bitmap, frameB: Bitmap, ratio: Float): Bitmap {
        val resultBitmap = frameA.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(resultBitmap)
        val paint = android.graphics.Paint()

        paint.alpha = ((1.0f - ratio) * 255).toInt()
        canvas.drawBitmap(frameA, 0f, 0f, paint)

        paint.alpha = (ratio * 255).toInt()
        canvas.drawBitmap(frameB, 0f, 0f, paint)

        return resultBitmap
    }

    private fun makeEven(bitmap: Bitmap): Bitmap {
        val width = if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1
        val height = if (bitmap.height % 2 == 0) bitmap.height else bitmap.height - 1
        if (bitmap.isRecycled) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun assetFilePath(assetName: String): String {
        val context = getApplication<Application>().applicationContext
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file.absolutePath
    }

    fun resetState() {
        _processingState.value = ProcessingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        if (::generator.isInitialized) {
            generator.destroy()
        }
    }
}