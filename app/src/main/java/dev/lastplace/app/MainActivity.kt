package dev.lastplace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.lastplace.app.ui.nav.ParkingNavHost
import dev.lastplace.app.ui.theme.ParkingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParkingTheme {
                ParkingNavHost()
            }
        }
    }
}
