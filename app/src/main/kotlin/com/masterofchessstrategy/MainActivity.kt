package com.masterofchessstrategy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.masterofchessstrategy.ui.MasterOfChessStrategyApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Follow the system day/night mode, including icon contrast after Activity recreation.
        enableEdgeToEdge()

        setContent {
            MasterOfChessStrategyApp()
        }
    }
}
