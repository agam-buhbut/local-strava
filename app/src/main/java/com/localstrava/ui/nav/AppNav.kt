package com.localstrava.ui.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localstrava.tracking.TrackingStateHolder
import com.localstrava.ui.detail.DetailScreen
import com.localstrava.ui.history.HistoryScreen
import com.localstrava.ui.log.LogRunScreen
import com.localstrava.ui.record.RecordScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object Routes {
    const val HISTORY = "history"
    const val RECORD = "record"
    const val LOG = "log"
    const val DETAIL_ARG = "id"
    const val DETAIL_PATTERN = "detail/{id}"
    fun detail(id: Long) = "detail/$id"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val activity = LocalContext.current.findActivity()

    // Lock-screen view (spec §4.5): show over the keyguard only while a run is active AND the
    // Record screen is current. Owned here, not by a screen effect: this collector isn't
    // lifecycle-gated, so a run stopped with the screen off clears it at once, and Back clears it
    // on navigation instead of after the exit fade.
    LaunchedEffect(nav) {
        combine(
            nav.currentBackStackEntryFlow.map { it.destination.route == Routes.RECORD },
            TrackingStateHolder.state.map { it.isActive },
        ) { onRecord, active -> onRecord && active }
            .distinctUntilChanged()
            .collect { activity?.setShowOverLock(it) }
    }

    NavHost(navController = nav, startDestination = Routes.HISTORY) {
        composable(Routes.HISTORY) {
            HistoryScreen(
                onStartRun = { nav.navigate(Routes.RECORD) },
                onLogRun = { nav.navigate(Routes.LOG) },
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
        composable(Routes.LOG) { LogRunScreen(onDone = { nav.popBackStack() }) }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Never setTurnScreenOn / requestDismissKeyguard: leaving the app must still hit the lock screen.
private fun Activity.setShowOverLock(show: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(show)
    } else {
        @Suppress("DEPRECATION") // the only way on API 26; setShowWhenLocked is API 27+
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }
}
