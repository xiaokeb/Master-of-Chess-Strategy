package com.masterofchessstrategy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.masterofchessstrategy.engine.NativeEngineStatusProvider
import com.masterofchessstrategy.ui.MasterOfChessStrategyApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The health check is synchronous and intentionally runs only once at startup.
        val state = HomeUiState.from(NativeEngineStatusProvider.check())
        setContent {
            MasterOfChessStrategyApp(state)
        }
    }
}
