package com.quangtung.gantimelapse

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.quangtung.gantimelapse.databinding.FragmentImagePickerBinding
import java.util.Locale


class ImagePickerFragment : Fragment() {

    private var _binding: FragmentImagePickerBinding? = null
    private val binding get() = _binding!!
    private var croppedImageUri: Uri? = null

    private lateinit var cropImage: ActivityResultLauncher<CropImageContractOptions>
    private lateinit var pickImage: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cropImage = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                croppedImageUri = result.uriContent
                binding.ivImagePreview.setImageURI(croppedImageUri)
                binding.ivImagePreview.background = null
                binding.btnGenerate.isEnabled = true
                Toast.makeText(requireContext(), "Upload image successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Upload image failed!", Toast.LENGTH_SHORT).show()
            }
        }
        pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { startCrop(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentImagePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnChooseImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.ivImagePreview.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnGenerate.setOnClickListener {
            val uri = croppedImageUri
            if (uri != null) {
                showGenerateOptionsDialog(uri)
            } else {
                Toast.makeText(requireContext(), "Please upload an image first!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showGenerateOptionsDialog(imageUri: Uri) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_generate_options, null)

        val sliderFrameCount = dialogView.findViewById<Slider>(R.id.sliderFrameCount)
        val sliderStartTime = dialogView.findViewById<Slider>(R.id.sliderStartTime)
        val sliderEndTime = dialogView.findViewById<Slider>(R.id.sliderEndTime)

        val tvFrameCount = dialogView.findViewById<TextView>(R.id.tvFrameCount)
        val tvStartTime = dialogView.findViewById<TextView>(R.id.tvStartTime)
        val tvEndTime = dialogView.findViewById<TextView>(R.id.tvEndTime)

        val updateTimeLabel: (TextView, Float) -> Unit = { textView, value ->
            val hour = value.toInt()
            val hourText = String.format(Locale.US, "%02d:00", hour)
            val dayPart = when (hour) {
                in 5..7 -> "Sunrise"
                in 8..11 -> "Morning"
                in 12..16 -> "Afternoon"
                in 17..19 -> "Sunset"
                else -> "Night"
            }
            textView.text = "$hourText ($dayPart)"
        }

        sliderFrameCount.addOnChangeListener { _, value, _ ->
            tvFrameCount.text = "${value.toInt()} frames"
        }
        sliderStartTime.addOnChangeListener { _, value, _ ->
            updateTimeLabel(tvStartTime, value)
        }
        sliderEndTime.addOnChangeListener { _, value, _ ->
            updateTimeLabel(tvEndTime, value)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Video Generation Options")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                // When user clicks "Start"
                val frameCount = sliderFrameCount.value.toInt()
                val startHour = sliderStartTime.value.toInt()
                val endHour = sliderEndTime.value.toInt()

                val bundle = Bundle().apply {
                    putString("image_uri", imageUri.toString())
                    putInt("frame_count", frameCount)
                    putInt("start_hour", startHour)
                    putInt("end_hour", endHour)
                }

                findNavController().navigate(
                    R.id.action_imagePickerFragment_to_processingFragment,
                    bundle
                )
            }
            .show()
    }


    private fun startCrop(uri: Uri) {
        val cropOptions = CropImageOptions(
            guidelines = CropImageView.Guidelines.ON,
            cropShape = CropImageView.CropShape.RECTANGLE,
            fixAspectRatio = true,
            aspectRatioX = 1,
            aspectRatioY = 1,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 90,
            activityTitle = "Crop image",
            activityBackgroundColor = Color.BLACK,
            toolbarBackButtonColor = Color.WHITE,
            toolbarTintColor = Color.WHITE
        )

        cropImage.launch(CropImageContractOptions(uri, cropOptions))
    }

    fun getCroppedImageUri(): Uri? = croppedImageUri
}