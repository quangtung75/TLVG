package com.quangtung.gantimelapse.util

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.floor

/**
 * Triển khai Guided Upsampling như mô tả trong bài báo arXiv:1904.00680v1 (Mục 3.3).
 *
 * Logic cốt lõi:
 * 1. Tính toán các hệ số biến đổi tuyến tính (s, b) ở độ phân giải THẤP,
 * sử dụng (ảnh gốc downsample) làm "guide" và (ảnh output mờ) làm "source".
 * 2. Upsample các bản đồ hệ số (s_map, b_map) này lên độ phân giải ĐẦY ĐỦ.
 * 3. Áp dụng các hệ số đã upsample lên ảnh gốc độ phân giải ĐẦY ĐỦ.
 *
 * Result_full(p) = s_full(p) * Original_full(p) + b_full(p)
 *
 * Điều này bảo toàn các chi tiết tần số cao từ ảnh gốc.
 */
class GuidedUpsampler(private val context: Context) {

    // (Có thể bỏ qua context nếu không dùng RenderScript)

    /**
     * Upsample ảnh lowResOutput bằng cách sử dụng originalInput làm hướng dẫn.
     *
     * @param lowResOutput Bitmap kết quả từ mạng nơ-ron (phân giải thấp).
     * @param originalInput Bitmap gốc mà người dùng cung cấp (phân giải cao).
     * @param radius Bán kính của cửa sổ lọc (ví dụ: 8, 16).
     * @param epsilon Giá trị điều chuẩn (regularization) (ví dụ: 0.01f).
     * @return Một bitmap mới ở độ phân giải cao.
     */
    fun upsample(
        lowResOutput: Bitmap,
        originalInput: Bitmap,
        radius: Int = 8,
        epsilon: Float = 0.01f
    ): Bitmap {

        // --- Kích thước ---
        val lowW = lowResOutput.width
        val lowH = lowResOutput.height
        val fullW = originalInput.width
        val fullH = originalInput.height

        // --- Bước 1: Chuẩn bị dữ liệu ở độ phân giải THẤP ---

        // 1a. Downsample ảnh gốc (guide) về độ phân giải thấp
        val guideLowRes = Bitmap.createScaledBitmap(originalInput, lowW, lowH, true)

        // 1b. Lấy mảng pixel từ cả hai ảnh low-res
        val guideLowPixels = IntArray(lowW * lowH)
        val sourceLowPixels = IntArray(lowW * lowH) // lowResOutput
        guideLowRes.getPixels(guideLowPixels, 0, lowW, 0, 0, lowW, lowH)
        lowResOutput.getPixels(sourceLowPixels, 0, lowW, 0, 0, lowW, lowH)

        // 1c. Tách các kênh màu (và chuyển sang float 0.0-1.0)
        val guideR = FloatArray(lowW * lowH) { ((guideLowPixels[it] shr 16) and 0xFF).toFloat() }
        val guideG = FloatArray(lowW * lowH) { ((guideLowPixels[it] shr 8) and 0xFF).toFloat() }
        val guideB = FloatArray(lowW * lowH) { (guideLowPixels[it] and 0xFF).toFloat() }

        val sourceR = FloatArray(lowW * lowH) { ((sourceLowPixels[it] shr 16) and 0xFF).toFloat() }
        val sourceG = FloatArray(lowW * lowH) { ((sourceLowPixels[it] shr 8) and 0xFF).toFloat() }
        val sourceB = FloatArray(lowW * lowH) { (sourceLowPixels[it] and 0xFF).toFloat() }

        guideLowRes.recycle()

        // --- Bước 2: Tính toán các hệ số s, b ở độ phân giải THẤP ---
        // (Đây là phần "least-squares optimization" trong bài báo )

        val (sR, bR) = computeCoefficients(guideR, sourceR, lowW, lowH, radius, epsilon)
        val (sG, bG) = computeCoefficients(guideG, sourceG, lowW, lowH, radius, epsilon)
        val (sB, bB) = computeCoefficients(guideB, sourceB, lowW, lowH, radius, epsilon)

        // --- Bước 3: Upsample các bản đồ hệ số s, b ---
        // (Đây là "upsample T bilinearly" trong bài báo )

        val sR_full = upsampleBilinear(sR, lowW, lowH, fullW, fullH)
        val sG_full = upsampleBilinear(sG, lowW, lowH, fullW, fullH)
        val sB_full = upsampleBilinear(sB, lowW, lowH, fullW, fullH)
        val bR_full = upsampleBilinear(bR, lowW, lowH, fullW, fullH)
        val bG_full = upsampleBilinear(bG, lowW, lowH, fullW, fullH)
        val bB_full = upsampleBilinear(bB, lowW, lowH, fullW, fullH)

        // --- Bước 4: Áp dụng T lên ảnh gốc độ phân giải CAO ---
        // (Đây là "apply to the original image" trong bài báo )

        val fullPixels = IntArray(fullW * fullH)
        originalInput.getPixels(fullPixels, 0, fullW, 0, 0, fullW, fullH)
        val resultPixels = IntArray(fullW * fullH)

        for (p in 0 until (fullW * fullH)) {
            val origR = (fullPixels[p] shr 16) and 0xFF
            val origG = (fullPixels[p] shr 8) and 0xFF
            val origB = fullPixels[p] and 0xFF

            // I_out = s * I_in + b
            val finalR = (sR_full[p] * origR + bR_full[p]).toInt().coerceIn(0, 255)
            val finalG = (sG_full[p] * origG + bG_full[p]).toInt().coerceIn(0, 255)
            val finalB = (sB_full[p] * origB + bB_full[p]).toInt().coerceIn(0, 255)

            resultPixels[p] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        val result = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, fullW, 0, 0, fullW, fullH)

        return result
    }

    /**
     * Tính toán các hệ số s (a) và b cho Guided Filter.
     */
    private fun computeCoefficients(
        guide: FloatArray,
        source: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        eps: Float
    ): Pair<FloatArray, FloatArray> {

        // Tính các giá trị trung bình cần thiết
        val mean_I = fastBoxFilter(guide, w, h, r)
        val mean_p = fastBoxFilter(source, w, h, r)

        val Ip = FloatArray(w * h) { guide[it] * source[it] }
        val mean_Ip = fastBoxFilter(Ip, w, h, r)

        val II = FloatArray(w * h) { guide[it] * guide[it] }
        val mean_II = fastBoxFilter(II, w, h, r)

        // Tính phương sai và hiệp phương sai
        val var_I = FloatArray(w * h) { mean_II[it] - mean_I[it] * mean_I[it] }
        val cov_Ip = FloatArray(w * h) { mean_Ip[it] - mean_I[it] * mean_p[it] }

        // Tính s (a) và b
        val s = FloatArray(w * h) { cov_Ip[it] / (var_I[it] + eps) }
        val b = FloatArray(w * h) { mean_p[it] - s[it] * mean_I[it] }

        // Cần lọc s và b một lần nữa
        val s_filtered = fastBoxFilter(s, w, h, r)
        val b_filtered = fastBoxFilter(b, w, h, r)

        return Pair(s_filtered, b_filtered)
    }

    /**
     * Lấy giá trị pixel an toàn (xử lý biên)
     */
    private fun getPixel(array: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        val clampedX = x.coerceIn(0, w - 1)
        val clampedY = y.coerceIn(0, h - 1)
        return array[clampedY * w + clampedX]
    }

    /**
     * Triển khai Fast Box Filter (O(N)) thay vì (O(N*r^2))
     * Sử dụng kỹ thuật cửa sổ trượt (sliding window) qua 2 lượt (ngang và dọc).
     */
    private fun fastBoxFilter(input: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val output = FloatArray(w * h)
        val temp = FloatArray(w * h)
        val kernelSize = (2 * r + 1).toFloat()

        // --- Lượt 1: Ngang (Horizontal) ---
        for (y in 0 until h) {
            // 1. Tính tổng cho pixel đầu tiên (x=0)
            var sum = 0.0f
            for (dx in -r..r) {
                sum += getPixel(input, dx, y, w, h)
            }
            temp[y * w] = sum

            // 2. Trượt cửa sổ sang phải
            for (x in 1 until w) {
                val oldPixel = getPixel(input, x - r - 1, y, w, h)
                val newPixel = getPixel(input, x + r, y, w, h)
                sum = sum - oldPixel + newPixel
                temp[y * w + x] = sum
            }
        }

        // --- Lượt 2: Dọc (Vertical) ---
        for (x in 0 until w) {
            // 1. Tính tổng cho pixel đầu tiên (y=0)
            var sum = 0.0f
            for (dy in -r..r) {
                sum += getPixel(temp, x, dy, w, h) // Lấy từ mảng temp
            }
            // Chuẩn hóa (chia cho tổng kích thước kernel)
            output[x] = sum / (kernelSize * kernelSize)

            // 2. Trượt cửa sổ xuống dưới
            for (y in 1 until h) {
                val oldPixel = getPixel(temp, x, y - r - 1, w, h)
                val newPixel = getPixel(temp, x, y + r, w, h)
                sum = sum - oldPixel + newPixel
                output[y * w + x] = sum / (kernelSize * kernelSize)
            }
        }

        return output
    }

    /**
     * Upsample một mảng Float bằng nội suy song tuyến tính (Bilinear Interpolation).
     */
    private fun upsampleBilinear(
        input: FloatArray,
        inW: Int,
        inH: Int,
        outW: Int,
        outH: Int
    ): FloatArray {
        val output = FloatArray(outW * outH)

        // Tránh chia cho 0 nếu outW/outH = 1
        val xRatio = if (outW > 1) (inW - 1).toFloat() / (outW - 1) else 0f
        val yRatio = if (outH > 1) (inH - 1).toFloat() / (outH - 1) else 0f

        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val px = x * xRatio
                val py = y * yRatio

                val x1 = floor(px).toInt()
                val y1 = floor(py).toInt()

                // x2 và y2 có thể bằng x1, y1 nếu chúng ta ở biên
                val x2 = (x1 + 1).coerceAtMost(inW - 1)
                val y2 = (y1 + 1).coerceAtMost(inH - 1)

                val xFrac = px - x1
                val yFrac = py - y1

                val p1 = getPixel(input, x1, y1, inW, inH)
                val p2 = getPixel(input, x2, y1, inW, inH)
                val p3 = getPixel(input, x1, y2, inW, inH)
                val p4 = getPixel(input, x2, y2, inW, inH)

                val c1 = p1 * (1 - xFrac) + p2 * xFrac
                val c2 = p3 * (1 - xFrac) + p4 * xFrac

                output[y * outW + x] = c1 * (1 - yFrac) + c2 * yFrac
            }
        }
        return output
    }
}