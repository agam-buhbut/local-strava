package com.localstrava

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.localstrava.ui.nav.AppNav
import com.localstrava.ui.theme.LocalStravaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalStravaTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }
}
