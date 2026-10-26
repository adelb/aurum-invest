package com.aurum.invest.ui.nav

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
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
import com.aurum.invest.ui.components.LogoFlight
import com.aurum.invest.ui.screens.AddTransactionScreen
import com.aurum.invest.ui.screens.AnalysisScreen
import com.aurum.invest.ui.screens.BankFeedScreen
import com.aurum.invest.ui.screens.DashboardScreen
import com.aurum.invest.ui.screens.EditPositionScreen
import com.aurum.invest.ui.screens.PicksScreen
import com.aurum.invest.ui.screens.PortfolioReviewScreen
import com.aurum.invest.ui.screens.PositionDetailScreen
import com.aurum.invest.ui.screens.GoldAddScreen
import com.aurum.invest.ui.screens.GoldLotScreen
import com.aurum.invest.ui.screens.GoldPricesScreen
import com.aurum.invest.ui.screens.GoldScreen
import com.aurum.invest.ui.screens.ReportsScreen
import com.aurum.invest.ui.screens.SettingsScreen
import com.aurum.invest.ui.screens.StocksScreen
import com.aurum.invest.ui.screens.WealthScreen
import com.aurum.invest.ui.screens.WealthViewModel
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

object Routes {
    const val DASHBOARD = "dashboard"; const val STOCKS = "stocks"
    const val PICKS = "picks"; const val WEALTH = "wealth"
    const val GOLD = "gold"; const val GOLD_PRICES = "gold/prices"
    const val GOLD_ADD = "gold/add?id={id}"; const val GOLD_LOT = "gold/lot/{id}"
    const val FEED = "feed"; const val SETTINGS = "settings"
    const val ADD = "add?symbol={symbol}&side={side}"; const val DETAIL = "detail/{symbol}"
    const val ANALYSIS = "analysis/{symbol}"; const val REPORTS = "reports"
    const val EDIT_POSITION = "edit/{symbol}"; const val REVIEW = "review"
    fun detail(symbol: String) = "detail/$symbol"
    fun analysis(symbol: String) = "analysis/$symbol"
    fun editPosition(symbol: String) = "edit/$symbol"
    fun goldAdd(id: Long? = null) = if (id == null) "gold/add" else "gold/add?id=$id"
    fun goldLot(id: Long) = "gold/lot/$id"
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

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

/** Top-level tabs swap with a quick cross-fade; anything pushed on top slides in. */
private fun isTop(route: String?): Boolean = route in TOP_ROUTES
private val TOP_ROUTES = setOf(Routes.DASHBOARD, Routes.STOCKS, Routes.GOLD, Routes.PICKS, Routes.WEALTH)

/** Past this much downward scroll the bar ducks away; a small nudge up recalls it. */
private const val HIDE_AFTER_PX = 48f
private const val SHOW_AFTER_PX = 16f

/** How long Home gets to itself before the Wealth engines start warming up. */
private const val WEALTH_WARMUP_MS = 4_000L

@Composable
fun AurumRoot() {
    val nav = rememberNavController()
    val rootVm: RootViewModel = viewModel()
    // App-scoped so the Wealth engines warm up behind Home and keep their results.
    val wealthVm: WealthViewModel = viewModel()
    LaunchedEffect(wealthVm) {
        delay(WEALTH_WARMUP_MS)
        wealthVm.start()
    }
    val pending by rootVm.pendingCount.collectAsStateWithLifecycle()

    val topDests = remember {
        listOf(
            TopDest(Routes.DASHBOARD, "Home", Icons.Rounded.Home),
            TopDest(Routes.STOCKS, "Stocks", Icons.AutoMirrored.Rounded.ShowChart),
            TopDest(Routes.GOLD, "Gold", Icons.Rounded.Toll),
            TopDest(Routes.PICKS, "Picks", Icons.Rounded.Bolt),
            TopDest(Routes.WEALTH, "Wealth", Icons.Rounded.DonutLarge)
        )
    }

    // Opening a stock arms the logo flight from wherever its logo sits now.
    val openDetail: (String) -> Unit = { symbol ->
        LogoFlight.arm(symbol)
        nav.navigate(Routes.detail(symbol))
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTopDest = currentRoute == null || topDests.any { it.route == currentRoute }

    // The YouTube rule: scrolling down tucks the bar away so the content owns
    // the whole screen; any upward nudge brings it back. The connection sits
    // at the root, so every screen's list drives it without knowing about it.
    var barRecalled by remember { mutableStateOf(true) }
    val barConnection = remember {
        object : NestedScrollConnection {
            private var accumulated = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (abs(dy) > 0.5f) {
                    // Direction change resets the run, so slow wobbles don't flap.
                    if ((dy < 0f && accumulated > 0f) || (dy > 0f && accumulated < 0f)) {
                        accumulated = 0f
                    }
                    accumulated += dy
                    if (accumulated < -HIDE_AFTER_PX) barRecalled = false
                    if (accumulated > SHOW_AFTER_PX) barRecalled = true
                }
                return Offset.Zero
            }
        }
    }
    // A fresh destination always starts with the bar present.
    LaunchedEffect(currentRoute) { barRecalled = true }

    val barVisible = onTopDest && barRecalled
    var barHeightPx by remember { mutableIntStateOf(0) }
    val barOffset by animateFloatAsState(
        targetValue = if (barVisible) 0f else (barHeightPx + 24).toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navBarOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
            .nestedScroll(barConnection)
    ) {
        // Content: below the status bar. On top destinations the bottom is
        // borderless — lists scroll under the floating bar and the gesture
        // area; detail-style screens keep the bottom inset since no bar
        // floats over them.
        Box(
            modifier = if (onTopDest) {
                Modifier.fillMaxSize().statusBarsPadding()
            } else {
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            }
        ) {
            NavHost(
                navController = nav,
                startDestination = Routes.DASHBOARD,
                modifier = Modifier.fillMaxSize(),
                // A stock opens the Apple Stocks way: the list steps aside and
                // fades while the detail fades in in place — it must not slide,
                // or the logo flying into its header would land on a moving target.
                enterTransition = {
                    when {
                        isTop(initialState.destination.route) && isTop(targetState.destination.route) ->
                            fadeIn(tween(220, delayMillis = 60))
                        targetState.destination.route == Routes.DETAIL ->
                            fadeIn(tween(300, delayMillis = 40))
                        else ->
                            slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 6 } +
                                fadeIn(tween(260))
                    }
                },
                exitTransition = {
                    when {
                        isTop(initialState.destination.route) && isTop(targetState.destination.route) ->
                            fadeOut(tween(120))
                        targetState.destination.route == Routes.DETAIL ->
                            slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { -it / 14 } +
                                fadeOut(tween(300))
                        else ->
                            slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 10 } +
                                fadeOut(tween(220))
                    }
                },
                popEnterTransition = {
                    if (initialState.destination.route == Routes.DETAIL) {
                        // In place, so the logo flying home lands on its row.
                        fadeIn(tween(260))
                    } else {
                        slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 10 } +
                            fadeIn(tween(240))
                    }
                },
                popExitTransition = {
                    if (initialState.destination.route == Routes.DETAIL) {
                        fadeOut(tween(220))
                    } else {
                        slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 6 } +
                            fadeOut(tween(200))
                    }
                }
            ) {
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onOpenDetail = openDetail,
                        onAdd = { nav.navigate(Routes.add()) },
                        onAddGold = { nav.navigate(Routes.goldAdd()) },
                        onOpenGold = {
                            nav.navigate(Routes.GOLD) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenLot = { nav.navigate(Routes.goldLot(it)) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onReports = { nav.navigate(Routes.REPORTS) },
                        onReview = { nav.navigate(Routes.REVIEW) },
                        onOpenFeed = { nav.navigate(Routes.FEED) },
                        pendingAlerts = pending
                    )
                }
                composable(Routes.GOLD) {
                    GoldScreen(
                        onAdd = { nav.navigate(Routes.goldAdd()) },
                        onOpenPrices = { nav.navigate(Routes.GOLD_PRICES) },
                        onOpenLot = { nav.navigate(Routes.goldLot(it)) }
                    )
                }
                composable(Routes.GOLD_PRICES) {
                    GoldPricesScreen(onBack = { nav.popBackStack() })
                }
                composable(
                    route = Routes.GOLD_ADD,
                    arguments = listOf(navArgument("id") {
                        type = NavType.LongType
                        defaultValue = -1L
                    })
                ) { entry ->
                    GoldAddScreen(
                        lotId = entry.arguments?.getLong("id") ?: -1L,
                        onDone = { nav.popBackStack() }
                    )
                }
                composable(
                    route = Routes.GOLD_LOT,
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    GoldLotScreen(
                        lotId = entry.arguments?.getLong("id") ?: -1L,
                        onBack = { nav.popBackStack() },
                        onEdit = { nav.navigate(Routes.goldAdd(it)) }
                    )
                }
                composable(Routes.REVIEW) {
                    PortfolioReviewScreen(
                        onBack = { nav.popBackStack() },
                        onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
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
                        onOpenDetail = openDetail,
                        onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
                    )
                }
                composable(Routes.PICKS) {
                    PicksScreen(
                        onOpenDetail = openDetail,
                        onOpenAnalysis = { nav.navigate(Routes.analysis(it)) }
                    )
                }
                composable(Routes.WEALTH) {
                    WealthScreen(
                        vm = wealthVm,
                        onOpenAnalysis = { nav.navigate(Routes.analysis(it)) },
                        onOpenDetail = openDetail
                    )
                }
                composable(Routes.FEED) {
                    BankFeedScreen()
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onOpenFeed = { nav.navigate(Routes.FEED) },
                        onOpenGoldPrices = { nav.navigate(Routes.GOLD_PRICES) }
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
                        onOpenAnalysis = { nav.navigate(Routes.analysis(it)) },
                        onEditPosition = { nav.navigate(Routes.editPosition(it)) }
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

        AurumNavBar(
            items = topDests,
            currentRoute = if (onTopDest) currentRoute ?: Routes.DASHBOARD else "",
            onSelect = { route ->
                nav.navigate(route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeightPx = it.height }
                .graphicsLayer { translationY = barOffset }
        )
    }
}

/**
 * The Toss tab bar: five icons with their words under them. The chosen tab
 * turns white and its icon pops (a short spring); the others sit back in
 * the quiet grey. It floats over the content and ducks away on a downward
 * scroll.
 */
@Composable
private fun AurumNavBar(
    items: List<TopDest>,
    currentRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AurumColors.surface)
    ) {
        HorizontalDivider(color = AurumColors.separator, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp)
        ) {
            items.forEachIndexed { index, dest ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    targetValue = if (selected) AurumColors.text else AurumColors.textFaint,
                    animationSpec = tween(220),
                    label = "navColor"
                )
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "navScale"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(dest.route) }
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = dest.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = color
                    )
                }
            }
        }
        // The surface continues behind the gesture area: borderless.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
