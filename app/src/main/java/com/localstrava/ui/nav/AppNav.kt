package com.localstrava.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localstrava.ui.detail.DetailScreen
import com.localstrava.ui.history.HistoryScreen
import com.localstrava.ui.record.RecordScreen

object Routes {
    const val HISTORY = "history"
    const val RECORD = "record"
    const val DETAIL_ARG = "id"
    const val DETAIL_PATTERN = "detail/{id}"
    fun detail(id: Long) = "detail/$id"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HISTORY) {
        composable(Routes.HISTORY) {
            HistoryScreen(
                onStartRun = { nav.navigate(Routes.RECORD) },
                onOpenRun = { id -> nav.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onFinished = { id -> nav.navigate(Routes.detail(id)) { popUpTo(Routes.HISTORY) } },
                onDiscarded = { nav.popBackStack(Routes.HISTORY, inclusive = false) },
                onExitToHistory = { nav.popBackStack(Routes.HISTORY, inclusive = false) },
            )
        }
        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(navArgument(Routes.DETAIL_ARG) { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                activityId = entry.arguments?.getLong(Routes.DETAIL_ARG) ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
