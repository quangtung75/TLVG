package com.quangtung.gantimelapse.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Khởi tạo GuidedUpsampler
    private val guidedUpsampler = GuidedUpsampler(context)

    suspend fun generateTimelapseFrames(
        imageUri: Uri,
        frameCount: Int,
        startHour: Int,
        endHour: Int
    ): List<TimelapseFrame> = withContext(Dispatchers.Default) {
        val guideBitmap = loadSquareBitmap(context, imageUri, TARGET_SIZE)
            ?: return@withContext emptyList()
        val frames = mutableListOf<TimelapseFrame>()

        val tStartIndex = startHour * 2
        var tEndIndex = endHour * 2

        if (tEndIndex <= tStartIndex) {
            tEndIndex += 48
        }

        for (i in 0 until frameCount) {
            val progress = if (frameCount > 1) i.toFloat() / (frameCount - 1) else 0.0f
            val interpolatedIndex = tStartIndex + progress * (tEndIndex - tStartIndex)
            val finalModelTValue = interpolatedIndex.roundToInt() % 48

            val lowResColorBitmap = generatorRunner.generate(guideBitmap, finalModelTValue)

            if (lowResColorBitmap != null) {
                // THAY ĐỔI: Sử dụng Guided Upsampling thay vì LAB color transfer
                val finalHighResBitmap = applyGuidedUpsampling(
                    guideBitmap,
                    lowResColorBitmap
                )
//
                val currentHour = finalModelTValue / 2
                val timeLabel = getTimeLabel(currentHour)
                frames.add(TimelapseFrame(finalHighResBitmap, timeLabel, currentHour))
            }
        }

        frames
    }

    private fun applyGuidedUpsampling(
        highResGuide: Bitmap,
        lowResColor: Bitmap
    ): Bitmap {
        return guidedUpsampler.upsample(
            lowResOutput = lowResColor,
            originalInput = highResGuide,
            radius = 8,
            epsilon = 0.1f
        )
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