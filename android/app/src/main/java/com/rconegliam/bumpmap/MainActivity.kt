package com.rconegliam.bumpmap

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.rconegliam.bumpmap.ui.BumpMapRoot
import com.rconegliam.bumpmap.ui.BumpMapTheme

// AppCompatActivity is required for per-app language switching on Android 12 and older.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BumpMapTheme {
                BumpMapRoot()
            }
        }
    }
}
