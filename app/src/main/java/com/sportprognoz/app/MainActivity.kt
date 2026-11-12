package com.sportprognoz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sportprognoz.app.ui.AppRoot
import com.sportprognoz.app.ui.AppTheme
import com.sportprognoz.app.ui.Palette
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = Palette.bg.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.bg.toArgb()
        setContent {
            AppTheme { AppRoot() }
        }
    }
}
