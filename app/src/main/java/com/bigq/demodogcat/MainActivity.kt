package com.bigq.demodogcat

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bigq.demodogcat.ui.theme.DemoDogCatTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_CODE = 1001
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Timber.plant(Timber.DebugTree())
        enableEdgeToEdge()
        setContent {
            DemoDogCatTheme {
               CameraScreen()
            }
        }
    }
}