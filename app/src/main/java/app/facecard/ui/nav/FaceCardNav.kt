package app.facecard.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.facecard.ui.collage.CollageScreen
import app.facecard.ui.home.HomeScreen
import app.facecard.ui.processing.ProcessingScreen
import app.facecard.ui.results.ResultsScreen

object Routes {
    const val HOME = "home"
    const val PROCESSING = "processing"
    const val RESULTS = "results"
    const val COLLAGE = "collage"
}

/**
 * Phase 1: static routes. Phase 2 adds `processing?uri={uri}` args
 * with URL-encoded SAF uri + rotation-safe processing ViewModel.
 */
@Composable
fun FaceCardNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onStartDemo = { nav.navigate(Routes.PROCESSING) })
        }
        composable(Routes.PROCESSING) {
            ProcessingScreen(
                onDone = { nav.navigate(Routes.RESULTS) },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(Routes.RESULTS) {
            ResultsScreen(
                onViewCollage = { nav.navigate(Routes.COLLAGE) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.COLLAGE) {
            CollageScreen(onBack = { nav.popBackStack() })
        }
    }
}
