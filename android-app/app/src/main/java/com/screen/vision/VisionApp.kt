package com.screen.vision

import android.app.Application

class VisionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VisionApp
            private set
    }
}