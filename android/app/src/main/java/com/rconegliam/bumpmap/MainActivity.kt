package com.rconegliam.bumpmap

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import com.rconegliam.bumpmap.sync.RecordingSync
import com.rconegliam.bumpmap.ui.BumpMapRoot
import com.rconegliam.bumpmap.ui.BumpMapTheme

// AppCompatActivity is required for per-app language switching on Android 12 and older.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingSync.enqueue(this, ExistingWorkPolicy.KEEP)
        setContent {
            BumpMapTheme {
                BumpMapRoot()
            }
        }
    }
}
