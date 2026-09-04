package app.facecard.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.facecard.ui.about.AboutScreen
import app.facecard.ui.collage.CollageScreen
import app.facecard.ui.home.HomeScreen
import app.facecard.ui.processing.ProcessingScreen
import app.facecard.ui.results.ResultsScreen

object Routes {
    const val HOME = "home"
    const val PROCESSING = "processing?uri={uri}"
    const val RESULTS = "results"
    const val COLLAGE = "collage"
    const val ABOUT = "about"

    fun processing(uri: Uri): String = "processing?uri=${Uri.encode(uri.toString())}"
}

@Composable
fun FaceCardNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoPicked = { uri -> nav.navigate(Routes.processing(uri)) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(
            route = Routes.PROCESSING,
            arguments = listOf(
                navArgument("uri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            ProcessingScreen(
                videoUri = entry.arguments?.getString("uri"),
                onDone = {
                    // Drop Processing from the stack: Back from Results must
                    // land on Home, never re-enter (and re-run) the pipeline.
                    nav.navigate(Routes.RESULTS) {
                        popUpTo(Routes.HOME)
                    }
                },
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
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}
