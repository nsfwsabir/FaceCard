package app.facecard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.facecard.ui.nav.FaceCardNav
import app.facecard.ui.theme.FaceCardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FaceCardTheme {
                FaceCardNav()
            }
        }
    }
}
