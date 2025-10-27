package com.quangtung.gantimelapse.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.quangtung.gantimelapse.databinding.DialogProcessingBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProgressDialogFragment : DialogFragment() {

    private var _binding: DialogProcessingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImagePickerViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogProcessingBinding.inflate(inflater, container, false)
        dialog?.setCanceledOnTouchOutside(false)
        isCancelable = false
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.processingState.collectLatest { state ->
                if (state is ProcessingState.Processing) {
                    binding.tvStatus.text = state.status
                    if (state.progress == -1) {
                        binding.progressIndicator.isIndeterminate = true
                    } else {
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = state.progress
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ProgressDialog"
    }
}