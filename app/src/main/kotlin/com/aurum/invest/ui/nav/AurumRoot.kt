package com.aurum.invest.ui.nav

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aurum.invest.AurumApp
import com.aurum.invest.ui.screens.AddTransactionScreen
import com.aurum.invest.ui.screens.AnalysisScreen
import com.aurum.invest.ui.screens.BankFeedScreen
import com.aurum.invest.ui.screens.DashboardScreen
import com.aurum.invest.ui.screens.EditPositionScreen
import com.aurum.invest.ui.screens.PicksScreen
import com.aurum.invest.ui.screens.PositionDetailScreen
import com.aurum.invest.ui.screens.PreMarketScreen
import com.aurum.invest.ui.screens.ReportsScreen
import com.aurum.invest.ui.screens.SettingsScreen
import com.aurum.invest.ui.screens.StocksScreen
import com.aurum.invest.ui.screens.WealthScreen
import com.aurum.invest.ui.theme.AurumColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

object Routes {
    const val DASHBOARD = "dashboard"; const val STOCKS = "stocks"
    const val PICKS = "picks"; const val WEALTH = "wealth"
    const val PREMARKET = "premarket"
    const val FEED = "feed"; const val SETTINGS = "settings"
    const val ADD = "add?symbol={symbol}&side={side}"; const val DETAIL = "detail/{symbol}"
    const val ANALYSIS = "analysis/{symbol}"; const val REPORTS = "reports"
    const val EDIT_POSITION = "edit/{symbol}"
    fun detail(symbol: String) = "detail/$symbol"
    fun analysis(symbol: String) = "analysis/$symbol"
    fun editPosition(symbol: String) = "edit/$symbol"
    fun add(symbol: String? = null, side: String? = null): String {
        val params = mutableListOf<String>()
        if (symbol != null) params += "symbol=$symbol"
        if (side != null) params += "side=$side"
        return if (params.isEmpty()) "add" else "add?" + params.joinToString("&")
    }
}

/** Tiny root-level VM: only the bank-feed pending badge count. */
class RootViewModel(app: Application) : AndroidViewModel(app) {
    val pendingCount: StateFlow<Int> = (app as AurumApp).container.bankFeed
        .observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

private data class TopDest(val route: String, val label: String)

@Composable
fun AurumRoot() {
    val nav = rememberNavController()
    val rootVm: RootViewModel = viewModel()
    val pending by rootVm.pendingCount.collectAsStateWithLifecycle()

    val topDests = remember {
        listOf(
            TopDest(Routes.DASHBOARD, "Portfolio"),
            TopDest(Routes.STOCKS, "Stocks"),
            TopDest(Routes.PICKS, "Picks"),
            TopDest(Routes.WEALTH, "Wealth"),
            // The 2% desk (pre-market + open-session scans against the daily
            // target) replaces the bank Feed in the bar; the feed itself
            // stays reachable from Settings.
            TopDest(Routes.PREMARKET, "2%")
        )
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute == null || topDests.any { it.route == currentRoute }

    Scaffold(
        containerColor = AurumColors.bg,
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                NavigationBar(
                    containerColor = AurumColors.surface,
                    contentColor = AurumColors.textDim,
                    tonalElevation = 0.dp
                ) {
                    topDests.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            // Text-only bar: the label IS the destination —
                            // five words read faster than five glyphs decoded.
                            icon = {
                                Text(
                                    text = dest.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (currentRoute == dest.route) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AurumColors.gold,
                                selectedTextColor = AurumColors.gold,
                                unselectedIconColor = AurumColors.textDim,
                                unselectedTextColor = AurumColors.textDim,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(inner)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onAdd = { nav.navigate(Routes.add()) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onReports = { nav.navigate(Routes.REPORTS) },
                    onEditPosition = { nav.navigate(Routes.editPosition(it)) }
                )
            }
            composable(
                route = Routes.EDIT_POSITION,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType })
            ) { entry ->
                EditPositionScreen(
                    symbol = entry.arguments?.getString("symbol").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onAddTrade = { nav.navigate(Routes.add(it)) }
                )
            }
            composable(Routes.STOCKS) {
                StocksScreen(
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
                )
            }
            composable(Routes.PICKS) {
                PicksScreen(
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
                )
            }
            composable(Routes.WEALTH) {
                WealthScreen(
                    onOpenAnalysis = { nav.navigate(Routes.analysis(it)) },
                    onOpenDetail = { nav.navigate(Routes.detail(it)) }
                )
            }
            composable(Routes.PREMARKET) {
                PreMarketScreen(
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
                )
            }
            composable(Routes.FEED) {
                BankFeedScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenFeed = { nav.navigate(Routes.FEED) }
                )
            }
            composable(Routes.REPORTS) {
                ReportsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                route = Routes.ADD,
                arguments = listOf(
                    navArgument("symbol") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("side") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                AddTransactionScreen(
                    prefillSymbol = entry.arguments?.getString("symbol"),
                    prefillSide = entry.arguments?.getString("side"),
                    onDone = { nav.popBackStack() }
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType })
            ) { entry ->
                PositionDetailScreen(
                    symbol = entry.arguments?.getString("symbol").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onTrade = { sym, side -> nav.navigate(Routes.add(sym, side)) },
                    onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
                )
            }
            composable(
                route = Routes.ANALYSIS,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType })
            ) { entry ->
                AnalysisScreen(
                    symbol = entry.arguments?.getString("symbol").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
