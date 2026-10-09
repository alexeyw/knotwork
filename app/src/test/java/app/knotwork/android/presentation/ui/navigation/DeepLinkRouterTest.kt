package app.knotwork.android.presentation.ui.navigation

import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.get
import androidx.navigation.navArgument
import app.knotwork.android.presentation.state.ChatEntryRequestRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * JVM tests for the `knotwork://triggers/{id}` branch of [navigateToDeepLink] — the
 * overdue-trigger notification's tap. The graph is a stand-in holding only the
 * destinations the branch touches, under the real route constants; the tab switch
 * is the production [navigateToTab], so `restoreState` brings back whatever the
 * More tab last held, as on a device.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkRouterTest {

    private val host = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        host.registry.currentState = Lifecycle.State.RESUMED
        navController = NavHostController(RuntimeEnvironment.getApplication()).apply {
            setLifecycleOwner(host)
            setViewModelStore(ViewModelStore())
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = NavRoutes.CHAT_TAB) {
                composable(NavRoutes.CHAT_TAB) {}
                composable(NavRoutes.MORE) {}
                composable(
                    route = NavRoutes.TRIGGERS_ROUTE,
                    arguments = listOf(
                        navArgument(NavRoutes.TRIGGERS_OPEN_ARG) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {}
            }
        }
    }

    @Test
    fun `given the chat is open when an overdue notice is tapped then the trigger opens above More`() {
        assertTrue(dispatch("knotwork://triggers/trig-9"))

        assertEquals(listOf(NavRoutes.CHAT_TAB, NavRoutes.MORE, NavRoutes.TRIGGERS_ROUTE), stackRoutes())
        assertEquals("trig-9", openArgument())
        navController.popBackStack()
        assertEquals("Back from the trigger returns to More", NavRoutes.MORE, currentRoute())
    }

    @Test
    fun `given More last held the triggers list when an overdue notice is tapped then it is not stacked twice`() {
        navController.navigateToTab(NavRoutes.MORE)
        navController.navigate(NavRoutes.TRIGGERS)
        navController.navigateToTab(NavRoutes.CHAT_TAB)

        dispatch("knotwork://triggers/trig-9")

        assertEquals(listOf(NavRoutes.CHAT_TAB, NavRoutes.MORE, NavRoutes.TRIGGERS_ROUTE), stackRoutes())
        assertEquals("trig-9", openArgument())
    }

    @Test
    fun `given a triggers link without an id when dispatched then the list opens with nothing open`() {
        dispatch("knotwork://triggers")

        assertEquals(listOf(NavRoutes.CHAT_TAB, NavRoutes.MORE, NavRoutes.TRIGGERS_ROUTE), stackRoutes())
        assertEquals("", openArgument())
    }

    /** Runs [uri] through the production deep-link router, as `MainActivity` does. */
    private fun dispatch(uri: String): Boolean =
        navController.navigateToDeepLink(Intent(Intent.ACTION_VIEW, uri.toUri()), ChatEntryRequestRelay())

    /** The composable destinations on the stack, bottom first — what the shell reads. */
    private fun stackRoutes(): List<String> =
        navController.navigatorProvider[ComposeNavigator::class].backStack.value.mapNotNull { it.destination.route }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun openArgument(): String? =
        navController.currentBackStackEntry?.arguments?.getString(NavRoutes.TRIGGERS_OPEN_ARG)
}
