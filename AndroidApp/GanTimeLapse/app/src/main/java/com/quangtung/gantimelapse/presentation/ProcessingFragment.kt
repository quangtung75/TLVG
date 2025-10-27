// Trong file: ProcessingFragment.kt
package com.quangtung.gantimelapse.presentation

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.quangtung.gantimelapse.util.GeneratorRunner
import com.quangtung.gantimelapse.R
import com.quangtung.gantimelapse.adapter.TimelapseFrameAdapter
import com.quangtung.gantimelapse.util.TimelapseGenerator
import com.quangtung.gantimelapse.util.VideoEncoder
import com.quangtung.gantimelapse.databinding.FragmentProcessingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ProcessingFragment : Fragment() {

    private var _binding: FragmentProcessingBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TimelapseFrameAdapter
    private lateinit var generator: TimelapseGenerator
    private lateinit var runner: GeneratorRunner
    private var imageUri: Uri? = null
    private var frameCount: Int = 24 // Bắt đầu với 24 frame GỐC
    private var startHour: Int = 6
    private var endHour: Int = 23

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            imageUri = it.getString(ARG_IMAGE_URI)?.let { uriString -> Uri.parse(uriString) }
        }
        // frameCount, startHour, endHour sẽ được hardcode trong startProcessing

        val modelPath = assetFilePath("model_mobile.ptl")
        runner = GeneratorRunner(modelPath)
        generator = TimelapseGenerator(requireContext(), runner)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        binding.recyclerFrames.layoutManager = GridLayoutManager(requireContext(), 3)
        startProcessing()
    }

    private fun setupToolbar() {
        binding.toolbar.navigationIconTint
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    // *** HÀM MỚI HOÀN TOÀN ***
    private fun startProcessing() {
        imageUri?.let { uri ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                try {
                    // Cài đặt số lượng
                    val originalFrameCount = 24 // Số frame GỐC (128x128)
                    val framesToInsert = 3 // Số frame "fake" (128x128)
                    frameCount = originalFrameCount // Cập nhật biến class
                    val totalFrames = originalFrameCount + (originalFrameCount - 1) * framesToInsert

                    // --- BƯỚC 1: Tải ảnh gốc 1 lần ---
                    binding.tvStatus.text = "Loading guide image..."
                    binding.progressIndicator.progress = 0
                    val guideLoaded = withContext(Dispatchers.Default) {
                        generator.loadGuideBitmap(uri)
                    }
                    if (guideLoaded == null) throw Exception("Failed to load guide image")

                    // --- BƯỚC 2: Sinh 24 frame 128x128 ---
                    binding.tvStatus.text = "Generating $originalFrameCount low-res frames..."
                    val lowResFrames = withContext(Dispatchers.Default) {
                        generator.generateLowResFrames(originalFrameCount, startHour, endHour)
                    }

                    // --- BƯỚC 3: "Fake" 24 frame -> 47 frame 128x128 ---
                    binding.tvStatus.text = "Interpolating low-res frames..."
                    val interpolatedLowRes = withContext(Dispatchers.Default) {
                        applyFakeInterpolation(lowResFrames, framesToInsert)
                    }
                    // Dọn dẹp frame low-res gốc
                    //lowResFrames.forEach { if (!it.isRecycled) it.recycle() }

                    // --- BƯỚC 4: Upsample 47 frame 128x128 -> 47 frame 512x512 ---
                    binding.tvStatus.text = "Upsampling $totalFrames final frames..."
                    val finalHighResFrames = mutableListOf<Bitmap>()

                    interpolatedLowRes.forEachIndexed { index, lowResBmp ->
                        val highResFrame = withContext(Dispatchers.Default) {
                            generator.upsampleSingleFrame(lowResBmp)
                        }
                        finalHighResFrames.add(highResFrame.bitmap)

                        // Cập nhật UI
                        binding.progressIndicator.progress = ((index + 1) * 100 / totalFrames)
                    }
                    // Dọn dẹp frame low-res đã "fake"
                    interpolatedLowRes.forEach { if (!it.isRecycled) it.recycle() }

                    // --- BƯỚC 5: Hiển thị và cho phép tạo video ---
                    binding.tvStatus.text = "Generated $totalFrames total frames"
                    binding.progressIndicator.progress = 100

                    adapter = TimelapseFrameAdapter(finalHighResFrames)
                    binding.recyclerFrames.adapter = adapter
                    enableDragAndDrop()

                    binding.btnCreateVideo.isEnabled = true
                    binding.btnCreateVideo.setOnClickListener {
                        createVideoFromFrames(finalHighResFrames)
                    }

                } catch (e: Exception) {
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    // *** GIỮ NGUYÊN 2 HÀM "FAKE" NÀY ***
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
        // Đảm bảo bitmap có thể sửa đổi
        val resultBitmap = frameA.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(resultBitmap)
        val paint = android.graphics.Paint()

        // Vẽ frameA (75%, 50%, 25%)
        paint.alpha = ((1.0f - ratio) * 255).toInt()
        canvas.drawBitmap(frameA, 0f, 0f, paint)

        // Vẽ frameB (25%, 50%, 75%)
        paint.alpha = (ratio * 255).toInt()
        canvas.drawBitmap(frameB, 0f, 0f, paint)

        return resultBitmap
    }

    // *** CÁC HÀM CÒN LẠI GIỮ NGUYÊN ***

    private fun enableDragAndDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerFrames)
    }

    private fun createVideoFromFrames(frames: List<Bitmap>) {
        binding.tvStatus.text = "Creating video..."
        binding.btnCreateVideo.isEnabled = false
        binding.progressIndicator.isIndeterminate = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                val evenFrames = withContext(Dispatchers.Default) {
                    frames.map { makeEven(it) }
                }

                val outputFile = File(requireContext().cacheDir, "timelapse_${System.currentTimeMillis()}.mp4")

                withContext(Dispatchers.IO) {
                    VideoEncoder().encodeFramesToMp4(evenFrames, outputFile)
                }

                binding.tvStatus.text = "Video created successfully!"
                binding.progressIndicator.isIndeterminate = false
                binding.progressIndicator.progress = 100

                val bundle = bundleOf("videoPath" to outputFile.absolutePath)
                findNavController().navigate(R.id.action_processingFragment_to_resultFragment, bundle)

            } catch (e: Exception) {
                binding.tvStatus.text = "Error creating video ${e.message}"
                binding.btnCreateVideo.isEnabled = true
                binding.progressIndicator.isIndeterminate = false
            }
        }
    }

    private fun makeEven(bitmap: Bitmap): Bitmap {
        val width = if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1
        val height = if (bitmap.height % 2 == 0) bitmap.height else bitmap.height - 1
        // Kiểm tra xem bitmap đã bị recycle chưa
        if (bitmap.isRecycled) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun assetFilePath(assetName: String): String {
        val file = File(requireContext().filesDir, assetName)
        if (!file.exists()) {
            requireContext().assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file.absolutePath
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::generator.isInitialized) {
            generator.destroy()
        }
    }

    companion object {
        private const val ARG_IMAGE_URI = "image_uri"
        fun newInstance(imageUri: Uri) = ProcessingFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_IMAGE_URI, imageUri.toString())
            }
        }
    }
}