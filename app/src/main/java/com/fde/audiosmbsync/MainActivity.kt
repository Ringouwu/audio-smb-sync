package com.fde.audiosmbsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fde.audiosmbsync.ui.SyncApp
import com.fde.audiosmbsync.viewmodel.SyncViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6750A4),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFE9DDFF),
                    onPrimaryContainer = Color(0xFF251047),
                    secondary = Color(0xFF496A5B),
                    secondaryContainer = Color(0xFFD1E8DA),
                    onSecondaryContainer = Color(0xFF102D21),
                    background = Color(0xFFFAF8FC),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFE9E5EC),
                    onSurface = Color(0xFF1D1B20),
                    onSurfaceVariant = Color(0xFF49454F),
                    outlineVariant = Color(0xFFCAC4D0)
                )
            ) {
                Surface { SyncApp(viewModel<SyncViewModel>()) }
            }
        }
    }
}
