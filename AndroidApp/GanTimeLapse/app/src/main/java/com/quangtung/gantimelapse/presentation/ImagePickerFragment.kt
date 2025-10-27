package com.quangtung.gantimelapse.presentation

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.quangtung.gantimelapse.R
import com.quangtung.gantimelapse.databinding.FragmentImagePickerBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ImagePickerFragment : Fragment() {

    private var _binding: FragmentImagePickerBinding? = null
    private val binding get() = _binding!!
    private var croppedImageUri: Uri? = null

    private lateinit var cropImage: ActivityResultLauncher<CropImageContractOptions>
    private lateinit var pickImage: ActivityResultLauncher<String>

    private val viewModel: ImagePickerViewModel by viewModels()

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
                viewModel.startGeneration(uri)
                ProgressDialogFragment().show(childFragmentManager, ProgressDialogFragment.TAG)
            } else {
                Toast.makeText(requireContext(), "Please upload an image first!", Toast.LENGTH_SHORT).show()
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.processingState.collectLatest { state ->
                when (state) {
                    is ProcessingState.Complete -> {
                        dismissProgressDialog()
                        val bundle = bundleOf("videoPath" to state.videoPath)
                        findNavController().navigate(
                            R.id.action_imagePickerFragment_to_resultFragment,
                            bundle
                        )
                        viewModel.resetState()
                    }
                    is ProcessingState.Error -> {
                        dismissProgressDialog()
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    else -> {
                        // Trạng thái Processing được xử lý bởi DialogFragment
                        // Trạng thái Idle không làm gì cả
                    }
                }
            }
        }
    }

    private fun dismissProgressDialog() {
        (childFragmentManager.findFragmentByTag(ProgressDialogFragment.TAG) as? DialogFragment)?.dismiss()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}