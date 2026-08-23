package com.kinex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.kinex.pose.Exercise
import com.kinex.ui.CameraScreen
import com.kinex.ui.CoachScreen
import com.kinex.ui.HistoryScreen
import com.kinex.ui.HomeScreen
import com.kinex.ui.RecoveryPhraseScreen
import com.kinex.ui.SessionDetailScreen
import com.kinex.ui.SetSummaryScreen
import com.kinex.ui.SettingsScreen
import com.kinex.sync.DeviceIdentity
import com.kinex.sync.SessionSyncWorker
import com.kinex.ui.nav.Coach
import com.kinex.ui.nav.History
import com.kinex.ui.nav.Home
import com.kinex.ui.nav.RecoveryPhrase
import com.kinex.ui.nav.SessionDetail
import com.kinex.ui.nav.SetSummary
import com.kinex.ui.nav.Settings
import com.kinex.ui.nav.Workout
import com.kinex.ui.theme.KineXTheme
import kotlin.reflect.KClass

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cheap, idempotent, and the safety net for every path that does not end in a saved
        // set: a sync cancelled by a process death, a request WorkManager's KEEP policy
        // dropped, or a backlog left by a spell offline. WorkManager owns the waiting — this
        // does not touch the network and does not care whether there is anything to send.
        SessionSyncWorker.enqueue(this)
        // Both bars forced dark, because the theme is. The default reads the system setting
        // and would put dark icons on this app's near-black chrome on a light-themed phone.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.value.toInt()),
        )
        setContent {
            KineXTheme { KineXApp() }
        }
    }
}

/**
 * The four destinations the bottom bar switches between. Order is the bar's order.
 *
 * Coach sits second because it is a thing you do, next to the other one; History and Settings
 * are both places you go to look something up. `Icons.Filled.Face` rather than a speech bubble
 * for a duller reason — the chat glyphs live in `material-icons-extended`, and a second icon
 * artifact is not worth one tab.
 */
private enum class Tab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val routeClass: KClass<*>,
) {
    HOME("Home", Icons.Filled.Home, Home, Home::class),
    COACH("Coach", Icons.Filled.Face, Coach, Coach::class),
    HISTORY("History", Icons.Filled.DateRange, History, History::class),
    SETTINGS("Settings", Icons.Filled.Settings, Settings, Settings::class),
}

@Composable
private fun KineXApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val context = LocalContext.current

    /**
     * The one-time recovery-phrase presentation.
     *
     * Home is still the start destination and this navigates on top of it, rather than
     * branching the start destination on a value that has to be read off disk — which would
     * mean either blocking the first frame on the Keystore or flashing Home first anyway.
     *
     * Reading the flag does not create the account; the phrase screen does, on its first
     * call. So the account exists exactly when it is first shown to somebody.
     */
    LaunchedEffect(Unit) {
        val acknowledged = runCatching {
            DeviceIdentity.get(context).isPhraseAcknowledged()
        }.getOrDefault(true) // A broken identity store is not a reason to open on an error
        // screen at launch; the sync will fail and Settings will say so when asked.
        if (!acknowledged) {
            navController.navigate(RecoveryPhrase(firstRun = true)) { launchSingleTop = true }
        }
    }

    // Hidden on the workout screen and on the recovery phrase, and nowhere else. The workout
    // screen is a full-bleed camera preview with its own control row along the bottom edge,
    // and a navigation bar under it would both cover the preview and sit directly beneath
    // buttons it is not part of. The phrase is a screen you finish and leave, and tabbing away
    // from it half-read is not a thing to make easy.
    val showBottomBar = !current.isOn(Workout::class) && !current.isOn(RecoveryPhrase::class)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current.isOn(tab.routeClass),
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        NavHost(navController = navController, startDestination = Home) {
            composable<Home> {
                HomeScreen(
                    onStart = { navController.navigate(Workout(it.id)) },
                    onOpenSession = { navController.navigate(SessionDetail(it)) },
                    onOpenHistory = { navController.switchTab(History) },
                    modifier = content,
                )
            }

            composable<Coach> {
                // No callbacks. The coach reads what has synced and writes nothing, so there
                // is nowhere for it to navigate to — a reply naming a session cannot link to
                // one, because what comes back is prose rather than a row id.
                CoachScreen(modifier = content)
            }

            composable<History> {
                HistoryScreen(
                    onOpenSession = { navController.navigate(SessionDetail(it)) },
                    modifier = content,
                )
            }

            composable<Settings> {
                SettingsScreen(
                    onShowRecoveryPhrase = { navController.navigate(RecoveryPhrase(firstRun = false)) },
                    modifier = content,
                )
            }

            composable<RecoveryPhrase> { entry ->
                RecoveryPhraseScreen(
                    firstRun = entry.toRoute<RecoveryPhrase>().firstRun,
                    // Pops rather than navigating anywhere, so both ways in leave the way they
                    // came: the first-run presentation returns to Home, and Settings returns
                    // to Settings.
                    onDone = { navController.popBackStack() },
                    modifier = content,
                )
            }

            composable<Workout> { entry ->
                val route = entry.toRoute<Workout>()
                CameraScreen(
                    modifier = content,
                    // An id no build knows would be counted as a squat by the engine's
                    // documented fallback. Falling back here instead keeps that from being
                    // silent: the picker shows what is actually running.
                    initialExercise = Exercise.from(route.exerciseId) ?: Exercise.SQUAT,
                    onOpenHistory = { navController.switchTab(History) },
                    onSetSaved = { navController.showSummaryFor(it) },
                )
            }

            composable<SetSummary> { entry ->
                val route = entry.toRoute<SetSummary>()
                SetSummaryScreen(
                    sessionId = route.sessionId,
                    onDone = { navController.switchTab(Home) },
                    onRepeat = { exercise ->
                        // Replaces the summary rather than stacking a workout on top of it,
                        // for the same reason the summary replaced the workout: back should
                        // not walk through finished sets.
                        navController.navigate(Workout(exercise.id)) {
                            popUpTo<SetSummary> { inclusive = true }
                        }
                    },
                    modifier = content,
                )
            }

            composable<SessionDetail> { entry ->
                val route = entry.toRoute<SessionDetail>()
                SessionDetailScreen(
                    sessionId = route.sessionId,
                    onBack = { navController.popBackStack() },
                    modifier = content,
                )
            }
        }
    }
}

/**
 * Show the summary for a set that has just been written, **replacing** the workout screen.
 *
 * `popUpTo<Workout> { inclusive = true }` is the whole point of this function. Without it,
 * back from the summary returns to the camera — and that would not be resuming the set the
 * summary describes, because the set is over: it has been written to history, the engine has
 * been reset and the count is back to zero. The athlete would land in a live camera session
 * that looks like the one they just finished and is actually a new, empty one, with the old
 * count gone. Popping the workout makes back mean what it looks like it means, which is "out
 * of this set", and lands on Home.
 *
 * `launchSingleTop` guards the case where two sets somehow resolve in quick succession; there
 * is no reading of the app in which two summaries should be stacked.
 */
private fun NavHostController.showSummaryFor(sessionId: Long) {
    navigate(SetSummary(sessionId)) {
        popUpTo<Workout> { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Switch to a bottom-nav tab: standard Compose tab behaviour.
 *
 * `popUpTo(start) { saveState = true }` with `restoreState` keeps each tab's scroll position
 * across switches without letting the back stack grow one entry per tap, and
 * `launchSingleTop` stops re-tapping the current tab from stacking copies of it.
 */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Whether [this] destination, or anything it nests under, is [routeClass]. */
private fun NavDestination?.isOn(routeClass: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(routeClass) } == true
