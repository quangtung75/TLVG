package com.quangtung.gantimelapse.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class TimelapseGenerator(
    private val context: Context,
    private val generatorRunner: GeneratorRunner
) {

    data class TimelapseFrame(
        val bitmap: Bitmap,
        val timeOfDay: String,
        val hour: Int
    )

    suspend fun generateTimelapseFrames(
        imageUri: Uri,
        frameCount: Int,
        startHour: Int,
        endHour: Int
    ): List<TimelapseFrame> = withContext(Dispatchers.Default) {
        val highResGuideBitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
        val frames = mutableListOf<TimelapseFrame>()

        val tStart = startHour / 24.0f
        var tEnd = endHour / 24.0f

        if (tEnd <= tStart) {
            tEnd += 1.0f
        }

        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1)
            val interpolatedTValue = tStart + progress * (tEnd - tStart)
            val finalModelTValue = interpolatedTValue % 1.0f

            // 1. Tạo ảnh màu 128x128 từ model
            val lowResColorBitmap = generatorRunner.generate(highResGuideBitmap, finalModelTValue)

            if (lowResColorBitmap != null) {
                val finalHighResBitmap = applyColorUpsampling(highResGuideBitmap, lowResColorBitmap)

                val currentHour = (interpolatedTValue * 24).toInt() % 24
                val timeLabel = getTimeLabel(currentHour)
                frames.add(TimelapseFrame(finalHighResBitmap, timeLabel, currentHour))
            }
        }
        frames
    }



    private fun applyColorUpsampling(highResGuide: Bitmap, lowResColor: Bitmap): Bitmap {
        val highResMat = Mat()
        Utils.bitmapToMat(highResGuide, highResMat)
        val lowResMat = Mat()
        Utils.bitmapToMat(lowResColor, lowResMat)

        Imgproc.cvtColor(highResMat, highResMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(lowResMat, lowResMat, Imgproc.COLOR_RGBA2RGB)

        val upscaledLowResMat = Mat()
        Imgproc.resize(lowResMat, upscaledLowResMat, highResMat.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)

        Imgproc.cvtColor(highResMat, highResMat, Imgproc.COLOR_RGB2Lab)
        Imgproc.cvtColor(upscaledLowResMat, upscaledLowResMat, Imgproc.COLOR_RGB2Lab)

        val highResChannels = ArrayList<Mat>()
        Core.split(highResMat, highResChannels)
        val lowResChannels = ArrayList<Mat>()
        Core.split(upscaledLowResMat, lowResChannels)

        val alpha = 0.5
        val blendedL = Mat()
        Core.addWeighted(highResChannels[0], alpha, lowResChannels[0], 1 - alpha, 0.0, blendedL)
        val finalChannels = listOf(blendedL, lowResChannels[1], lowResChannels[2])

        val finalLabMat = Mat()
        Core.merge(finalChannels, finalLabMat)

        val finalRgbMat = Mat()
        Imgproc.cvtColor(finalLabMat, finalRgbMat, Imgproc.COLOR_Lab2RGB)

        val finalBitmap = Bitmap.createBitmap(highResGuide.width, highResGuide.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalRgbMat, finalBitmap)

        // Giải phóng bộ nhớ cho các đối tượng Mat
        highResMat.release()
        lowResMat.release()
        upscaledLowResMat.release()
        highResChannels.forEach { it.release() }
        lowResChannels.forEach { it.release() }
        finalLabMat.release()
        finalRgbMat.release()

        return finalBitmap
    }

    fun destroy() {
        generatorRunner.destroy()
    }

    private fun getTimeLabel(hour: Int): String = when {
        hour in 5..7 -> "Bình minh ($hour:00)"
        hour in 8..11 -> "Buổi sáng ($hour:00)"
        hour in 12..16 -> "Buổi trưa ($hour:00)"
        hour in 17..19 -> "Hoàng hôn ($hour:00)"
        else -> "Ban đêm ($hour:00)"
    }
}