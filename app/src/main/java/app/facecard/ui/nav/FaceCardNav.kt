package app.facecard.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.facecard.ui.collage.CollageScreen
import app.facecard.ui.home.HomeScreen
import app.facecard.ui.processing.ProcessingScreen
import app.facecard.ui.results.ResultsScreen

object Routes {
    const val HOME = "home"
    const val PROCESSING = "processing?uri={uri}"
    const val RESULTS = "results"
    const val COLLAGE = "collage"

    fun processing(uri: Uri): String = "processing?uri=${Uri.encode(uri.toString())}"
}

@Composable
fun FaceCardNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onVideoPicked = { uri -> nav.navigate(Routes.processing(uri)) })
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
