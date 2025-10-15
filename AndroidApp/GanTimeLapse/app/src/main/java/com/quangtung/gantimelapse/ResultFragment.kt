package com.quangtung.gantimelapse

import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import com.quangtung.gantimelapse.databinding.FragmentResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

class ResultFragment : Fragment() {

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!

    private var videoPath: String? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoPath = arguments?.getString("videoPath")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        initializePlayer()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbar.inflateMenu(R.menu.result_menu)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_save -> {
                    videoPath?.let { saveVideoToGallery(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun initializePlayer() {
        binding.progressIndicator.visibility = View.VISIBLE
        try {
            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                binding.playerView.player = this
                videoPath?.let { path ->
                    val videoFile = File(path)
                    val videoUri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        videoFile
                    )
                    setMediaItem(MediaItem.fromUri(videoUri))
                    prepare()
                    play()
                }

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            binding.progressIndicator.visibility = View.GONE
                        }
                    }
                })
            }
        } catch (e: Exception) {
            binding.progressIndicator.visibility = View.GONE
            Toast.makeText(requireContext(), "Error while initializing player", Toast.LENGTH_LONG).show()
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (exoPlayer == null) {
            initializePlayer()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }

    private fun saveVideoToGallery(path: String) {
        lifecycleScope.launch {
            val file = File(path)
            binding.progressIndicator.visibility = View.VISIBLE
            val saved = withContext(Dispatchers.IO) {
                try {
                    val filename = "timelapse_${System.currentTimeMillis()}.mp4"
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Timelapse")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }

                    val uri = requireContext().contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false

                    requireContext().contentResolver.openOutputStream(uri).use { out: OutputStream? ->
                        file.inputStream().copyTo(out!!)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        requireContext().contentResolver.update(uri, values, null, null)
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            binding.progressIndicator.visibility = View.GONE
            if (saved) {
                Toast.makeText(requireContext(), "Save video successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Save video failed!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}