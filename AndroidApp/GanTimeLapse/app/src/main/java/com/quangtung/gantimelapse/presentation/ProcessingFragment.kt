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
    private var frameCount: Int = 48
    private var startHour: Int = 6
    private var endHour: Int = 18

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Chỉ lấy imageUri từ arguments
        arguments?.let {
            imageUri = it.getString(ARG_IMAGE_URI)?.let { uriString -> Uri.parse(uriString) }
        }

        frameCount = 12
        startHour = 6
        endHour = 23

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

    private fun startProcessing() {
        imageUri?.let { uri ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                try {
                    binding.tvStatus.text = "Generating 12 original frames..."
                    binding.progressIndicator.progress = 0

                    val frames = withContext(Dispatchers.Default) {
                        generator.generateTimelapseFrames(uri, frameCount, startHour, endHour)
                    }

                    binding.tvStatus.text = "Generated 12 frames. Interpolating..."

                    val originalBitmaps = frames.map { it.bitmap }
                    val smoothedBitmaps = withContext(Dispatchers.Default) {
                        applyFakeInterpolation(originalBitmaps, 7)
                    }


                    binding.tvStatus.text = "Generated ${smoothedBitmaps.size} total frames"
                    binding.progressIndicator.progress = 100

                    val mutableSmoothedBitmaps = smoothedBitmaps.toMutableList()

                    adapter = TimelapseFrameAdapter(mutableSmoothedBitmaps)
                    binding.recyclerFrames.adapter = adapter
                    enableDragAndDrop()

                    binding.btnCreateVideo.isEnabled = true
                    binding.btnCreateVideo.setOnClickListener {
                        createVideoFromFrames(mutableSmoothedBitmaps)
                    }

                } catch (e: Exception) {
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            }
        }
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
        val resultBitmap = Bitmap.createBitmap(frameA.width, frameA.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)
        val paint = android.graphics.Paint()

        paint.alpha = ((1.0f - ratio) * 255).toInt()
        canvas.drawBitmap(frameA, 0f, 0f, paint)

        paint.alpha = (ratio * 255).toInt()
        canvas.drawBitmap(frameB, 0f, 0f, paint)

        return resultBitmap
    }

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