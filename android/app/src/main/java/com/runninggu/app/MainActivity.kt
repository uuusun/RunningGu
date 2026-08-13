package com.runninggu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.runninggu.app.ui.RunningGuApp
import com.runninggu.app.ui.theme.RunningGuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunningGuTheme {
                RunningGuApp()
            }
        }
    }
}
