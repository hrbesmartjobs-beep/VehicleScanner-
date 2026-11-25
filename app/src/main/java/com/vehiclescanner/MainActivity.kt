package com.vehiclescanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vehiclescanner.scan.ScanState
import com.vehiclescanner.ui.DatabaseScreen
import com.vehiclescanner.ui.HistoryScreen
import com.vehiclescanner.ui.HomeScreen
import com.vehiclescanner.ui.ImportScreen
import com.vehiclescanner.ui.PhotoScreen
import com.vehiclescanner.ui.ResultsScreen
import com.vehiclescanner.ui.ScanningScreen
import com.vehiclescanner.ui.SettingsScreen
import com.vehiclescanner.ui.theme.VehicleScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VehicleScannerApp).container
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            VehicleScannerTheme(settings.theme) { AppNav(container) }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SCANNING = "scanning"
    const val RESULTS = "results/{id}"
    const val PHOTO = "photo/{id}"
    const val DATABASE = "database"
    const val IMPORT = "import"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    fun results(id: Long) = "results/$id"
    fun photo(id: Long) = "photo/$id"
}

@Composable
fun AppNav(c: AppContainer) {
    val nav = rememberNavController()
    val scanState by c.scanner.state.collectAsStateWithLifecycle()

    // When a scan finishes, jump straight to its results.
    LaunchedEffect(scanState) {
        val s = scanState
        if (s is ScanState.Done) {
            c.scanner.acknowledge()
            nav.navigate(Routes.results(s.sessionId)) { popUpTo(Routes.HOME) }
        }
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(c,
                onScanStarted = { nav.navigate(Routes.SCANNING) },
                onOpen = { nav.navigate(it) },
                onOpenSession = { nav.navigate(Routes.results(it)) })
        }
        composable(Routes.SCANNING) { // Guarded pop: never pops a different screen if Scanning has already left the back stack.
            ScanningScreen(c, onBack = { nav.popBackStack(Routes.SCANNING, inclusive = true) }) }
        composable(Routes.RESULTS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments!!.getLong("id")
            ResultsScreen(c, id, onBack = { nav.popBackStack() }, onPhoto = { nav.navigate(Routes.photo(id)) })
        }
        composable(Routes.PHOTO, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            PhotoScreen(c, it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable(Routes.DATABASE) { DatabaseScreen(c, onBack = { nav.popBackStack() }, onImport = { nav.navigate(Routes.IMPORT) }) }
        composable(Routes.IMPORT) { ImportScreen(c, onBack = { nav.popBackStack() }) }
        composable(Routes.HISTORY) { HistoryScreen(c, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.results(it)) }) }
        composable(Routes.SETTINGS) { SettingsScreen(c, onBack = { nav.popBackStack() }) }
    }
}
