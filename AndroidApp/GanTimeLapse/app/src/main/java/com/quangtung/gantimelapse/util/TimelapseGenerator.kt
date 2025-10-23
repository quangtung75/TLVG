package com.quangtung.gantimelapse.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt

class TimelapseGenerator(
    private val context: Context,
    private val generatorRunner: GeneratorRunner
) {

    data class TimelapseFrame(
        val bitmap: Bitmap,
        val timeOfDay: String,
        val hour: Int
    )

    companion object {
        private const val TARGET_SIZE = 512
    }

    suspend fun generateTimelapseFrames(
        imageUri: Uri,
        frameCount: Int,
        startHour: Int,
        endHour: Int
    ): List<TimelapseFrame> = withContext(Dispatchers.Default) {
        val guideBitmap = loadSquareBitmap(context, imageUri, TARGET_SIZE)
            ?: return@withContext emptyList()
        val frames = mutableListOf<TimelapseFrame>()

        // --- BẮT ĐẦU THAY ĐỔI LOGIC ---

        // Mô hình có 48 bước (0-47), tương ứng 2 bước mỗi giờ
        val tStartIndex = startHour * 2
        var tEndIndex = endHour * 2

        // Xử lý trường hợp vòng qua đêm (ví dụ: 22:00 -> 06:00)
        // Bằng cách cộng thêm 48 bước (1 ngày) vào chỉ số kết thúc
        if (tEndIndex <= tStartIndex) {
            tEndIndex += 48
        }

        for (i in 0 until frameCount) {

            val progress = if (frameCount > 1) i.toFloat() / (frameCount - 1) else 0.0f

            val interpolatedIndex = tStartIndex + progress * (tEndIndex - tStartIndex)

            val finalModelTValue = interpolatedIndex.roundToInt() % 48

            val lowResColorBitmap = generatorRunner.generate(guideBitmap, finalModelTValue)

            if (lowResColorBitmap != null) {
                val finalHighResBitmap = applyColorUpsampling(guideBitmap, lowResColorBitmap)

                val currentHour = finalModelTValue / 2
                val timeLabel = getTimeLabel(currentHour)
                frames.add(TimelapseFrame(finalHighResBitmap, timeLabel, currentHour))
            }
        }

        frames
    }

    private fun loadSquareBitmap(context: Context, imageUri: Uri, targetSize: Int): Bitmap? {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(imageUri)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
            options.inJustDecodeBounds = false

            inputStream = context.contentResolver.openInputStream(imageUri)
            val downscaledBitmap = BitmapFactory.decodeStream(inputStream, null, options) ?: return null

            val smallerDim = min(downscaledBitmap.width, downscaledBitmap.height)
            val x = (downscaledBitmap.width - smallerDim) / 2
            val y = (downscaledBitmap.height - smallerDim) / 2
            val croppedBitmap = Bitmap.createBitmap(downscaledBitmap, x, y, smallerDim, smallerDim)
            if (downscaledBitmap != croppedBitmap) {
                downscaledBitmap.recycle()
            }

            if (croppedBitmap.width == targetSize) {
                return croppedBitmap
            } else {
                val finalBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetSize, targetSize, true)
                if (croppedBitmap != finalBitmap) {
                    croppedBitmap.recycle()
                }
                return finalBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            inputStream?.close()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
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

    private fun applyColorTransferLab(highResGuide: Bitmap, lowResColor: Bitmap): Bitmap {
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

        val finalChannels = listOf(
            highResChannels[0], // Kênh L từ ảnh gốc (chi tiết)
            lowResChannels[1],  // Kênh a* từ ảnh GAN (màu)
            lowResChannels[2]   // Kênh b* từ ảnh GAN (màu)
        )

        val finalLabMat = Mat()
        Core.merge(finalChannels, finalLabMat)

        val finalRgbMat = Mat()
        Imgproc.cvtColor(finalLabMat, finalRgbMat, Imgproc.COLOR_Lab2RGB)

        val finalBitmap = Bitmap.createBitmap(highResGuide.width, highResGuide.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalRgbMat, finalBitmap)

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