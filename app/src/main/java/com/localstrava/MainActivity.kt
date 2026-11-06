package com.localstrava

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.localstrava.ui.nav.AppNav
import com.localstrava.ui.theme.LocalStravaTheme
import com.localstrava.ui.theme.RunColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val ink = RunColors.Ink.toArgb()
        enableEdgeToEdge(SystemBarStyle.dark(ink), SystemBarStyle.dark(ink))
        super.onCreate(savedInstanceState)
        setContent {
            LocalStravaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = RunColors.Ink) { AppNav() }
            }
        }
    }
}
