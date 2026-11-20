package dev.titanide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.nav.TitanRoot
import dev.titanide.core.designsystem.TitanTheme

val LocalContainer = staticCompositionLocalOf<TitanContainer> {
    error("TitanContainer was not provided")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TitanApplication).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                TitanTheme {
                    TitanRoot()
                }
            }
        }
    }
}
