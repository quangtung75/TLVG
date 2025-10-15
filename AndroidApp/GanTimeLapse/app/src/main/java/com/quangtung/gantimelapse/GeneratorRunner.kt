package com.quangtung.gantimelapse

import android.graphics.Bitmap
import android.graphics.Color
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class GeneratorRunner(private val modelPath: String) {

    private val module: Module by lazy {
        Module.load(modelPath)
    }

    companion object {
        const val IMG_SIZE = 128
        const val Z_DIM = 128
    }

    fun generate(inputBitmap: Bitmap, tValue: Float): Bitmap? {
        // --- BƯỚC 1: CHUẨN BỊ CÁC INPUT TENSOR ---

        // 1.1. Chuẩn bị Tensor 'x' (ảnh đầu vào)
        // Resize ảnh về đúng kích thước 128x128
        val resizedBitmap = Bitmap.createScaledBitmap(inputBitmap, IMG_SIZE, IMG_SIZE, true)

        // Chuyển Bitmap sang Tensor và chuẩn hóa về khoảng [-1, 1]
        // mean=0.5, std=0.5 sẽ thực hiện phép biến đổi: (pixel/255 - 0.5) / 0.5 = 2*(pixel/255) - 1
        val imageTensor = TensorImageUtils.bitmapToFloat32Tensor(
            resizedBitmap,
            floatArrayOf(0.5f, 0.5f, 0.5f), // mean
            floatArrayOf(0.5f, 0.5f, 0.5f)  // std
        )

        // 1.2. Chuẩn bị Tensor 'z' (latent vector ngẫu nhiên)
        val random = Random()
        val zData = FloatArray(Z_DIM) { (random.nextGaussian()).toFloat() } // Tạo nhiễu từ phân phối chuẩn
        val zTensor = Tensor.fromBlob(zData, longArrayOf(1, Z_DIM.toLong()))

        // 1.3. Chuẩn bị Tensor 't' (giá trị điều kiện)
        val tTensor = Tensor.fromBlob(floatArrayOf(tValue), longArrayOf(1, 1))

        // --- BƯỚC 2: GỌI MODEL VÀ CHẠY SUY LUẬN ---

        // Khi model có nhiều input, chúng ta truyền vào một mảng các IValue
        val outputIValue = module.forward(
            IValue.from(imageTensor),
            IValue.from(zTensor),
            IValue.from(tTensor)
        )

        val outputTensor = outputIValue.toTensor()

        // --- BƯỚC 3: XỬ LÝ OUTPUT TENSOR VÀ CHUYỂN THÀNH BITMAP ---
        return tensorToBitmap(outputTensor)
    }

    /**
     * Chuyển đổi một Tensor output (đã được chuẩn hóa trong khoảng [-1, 1]) thành Bitmap.
     */
    private fun tensorToBitmap(tensor: Tensor): Bitmap? {
        val floatData = tensor.dataAsFloatArray
        val shape = tensor.shape() // Shape should be [1, 3, 128, 128]

        val height = shape[2].toInt()
        val width = shape[3].toInt()

        val pixels = IntArray(width * height)
        val numChannels = 3

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x

                // Lấy giá trị pixel đã được un-normalize từ [-1, 1] về [0, 255]
                // out = (in + 1) / 2 * 255
                val r = ((floatData[index] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                val g = ((floatData[index + width * height] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                val b = ((floatData[index + 2 * width * height] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)

                // Gộp thành màu ARGB
                pixels[index] = Color.rgb(r, g, b)
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun destroy() {
        module.destroy()
    }
}