package com.rconegliam.bumpmap

import android.app.Application
import org.maplibre.android.MapLibre

class BumpMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
