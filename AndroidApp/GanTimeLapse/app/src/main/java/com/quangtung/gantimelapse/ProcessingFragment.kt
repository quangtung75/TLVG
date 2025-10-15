package com.quangtung.gantimelapse

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
        arguments?.let {
            imageUri = it.getString(ARG_IMAGE_URI)?.let { uriString -> Uri.parse(uriString) }
            frameCount = it.getInt("frame_count", 48)
            startHour = it.getInt("start_hour", 6)
            endHour = it.getInt("end_hour", 18)
        }
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
                    binding.tvStatus.text = "Generating frames..."
                    binding.progressIndicator.progress = 0

                    val frames = withContext(Dispatchers.Default) {
                        generator.generateTimelapseFrames(uri, frameCount, startHour, endHour)
                    }

                    binding.tvStatus.text = "Generated ${frames.size} frames"
                    binding.progressIndicator.progress = 100

                    val bitmaps = frames.map { it.bitmap }.toMutableList()
                    adapter = TimelapseFrameAdapter(bitmaps)
                    binding.recyclerFrames.adapter = adapter
                    enableDragAndDrop()

                    binding.btnCreateVideo.isEnabled = true
                    binding.btnCreateVideo.setOnClickListener {
                        createVideoFromFrames(bitmaps)
                    }

                } catch (e: Exception) {
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            }
        }
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