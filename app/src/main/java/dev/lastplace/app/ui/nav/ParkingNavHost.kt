package dev.lastplace.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.ui.addstreet.AddStreetScreen
import dev.lastplace.app.ui.map.MapPickerResult
import dev.lastplace.app.ui.map.MapPickerScreen
import dev.lastplace.app.ui.settings.SettingsScreen
import dev.lastplace.app.ui.settings.SettingsSection
import dev.lastplace.app.ui.streets.ReparkingOptionsScreen
import dev.lastplace.app.ui.streets.StreetListScreen

object Routes {
    const val STREETS = "streets"
    const val SETTINGS = "settings"
    const val REPARKING_OPTIONS = "reparking_options"
    const val ADD_STREET = "add_street"
    const val MAP_PICKER = "map_picker"
    const val ARG_STREET_ID = "streetId"

    fun editStreet(streetId: Long) = "$ADD_STREET?$ARG_STREET_ID=$streetId"
    fun settings(section: SettingsSection) = "$SETTINGS?section=${section.name}"
}

@Composable
fun ParkingNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.STREETS) {
        composable(Routes.STREETS) { entry ->
            val editorResult by entry.savedStateHandle.getStateFlow<String?>("street_editor_result", null).collectAsState()
            StreetListScreen(
                onAddStreet = { navController.navigate(Routes.editStreet(-1L)) },
                onEditStreet = { id -> navController.navigate(Routes.editStreet(id)) },
                onOpenSettings = { section -> navController.navigate(Routes.settings(section)) },
                editorResult = editorResult,
                onEditorResultHandled = { entry.savedStateHandle["street_editor_result"] = null },
                onOpenReparkingOptions = { navController.navigate(Routes.REPARKING_OPTIONS) },
            )
        }
        composable(
            route = "${Routes.ADD_STREET}?${Routes.ARG_STREET_ID}={${Routes.ARG_STREET_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_STREET_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { backStackEntry ->
            val streetId = backStackEntry.arguments?.getLong(Routes.ARG_STREET_ID) ?: -1L
            AddStreetScreen(
                streetId = streetId,
                navController = navController,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "${Routes.MAP_PICKER}?start={start}",
            arguments = listOf(
                navArgument("start") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val initial = backStackEntry.arguments?.getString("start")?.let { parseLatLng(it) }
            MapPickerScreen(
                initial = initial,
                onPicked = { point ->
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        set(MapPickerResult.LAT, point.lat)
                        set(MapPickerResult.LNG, point.lng)
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.REPARKING_OPTIONS) {
            ReparkingOptionsScreen(
                onBack = { navController.popBackStack() },
                onOpenMoveReminderSettings = { navController.navigate(Routes.settings(SettingsSection.PLANNED_MOVES)) },
            )
        }
        composable(
            route = "${Routes.SETTINGS}?section={section}",
            arguments = listOf(navArgument("section") { defaultValue = SettingsSection.DELIVERY.name }),
        ) { entry ->
            val section = SettingsSection.entries.firstOrNull { it.name == entry.arguments?.getString("section") }
                ?: SettingsSection.DELIVERY
            SettingsScreen(onBack = { navController.popBackStack() }, initialSection = section)
        }
    }
}

private fun parseLatLng(value: String): LatLng? {
    val parts = value.split(",")
    val lat = parts.getOrNull(0)?.toDoubleOrNull()
    val lng = parts.getOrNull(1)?.toDoubleOrNull()
    return if (lat != null && lng != null) LatLng(lat, lng) else null
}
