package com.quangtung.gantimelapse

import android.app.Application
import org.opencv.android.OpenCVLoader

class GenTimelapseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OpenCVLoader.initDebug()
    }
}