package com.quangtung.gantimelapse.presentation

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.quangtung.gantimelapse.R
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
                val bundle = Bundle().apply {
                    putString("image_uri", uri.toString())
                }

                findNavController().navigate(
                    R.id.action_imagePickerFragment_to_processingFragment,
                    bundle
                )
            } else {
                Toast.makeText(requireContext(), "Please upload an image first!", Toast.LENGTH_SHORT).show()
            }
        }
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