package com.rconegliam.bumpmap

import android.app.Application
import com.rconegliam.bumpmap.sync.RecordingSync
import org.maplibre.android.MapLibre

class BumpMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingSync.clearLegacyToken(this)
        MapLibre.getInstance(this)
    }
}
