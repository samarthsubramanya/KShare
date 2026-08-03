package com.beam.app

import android.app.Application
import com.beam.app.di.initKoin

class BeamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}
