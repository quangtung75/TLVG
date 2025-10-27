package com.quangtung.gantimelapse.presentation

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Processing(val status: String, val progress: Int) : ProcessingState()
    data class Complete(val videoPath: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}