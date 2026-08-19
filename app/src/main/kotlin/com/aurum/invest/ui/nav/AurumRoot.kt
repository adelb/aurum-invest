package com.aurum.invest.ui.nav

import android.app.Application
import androidx.compose.animation.animateColorAsState
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
import kotlin.math.abs
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

/** Past this much downward scroll the bar ducks away; a small nudge up recalls it. */
private const val HIDE_AFTER_PX = 48f
private const val SHOW_AFTER_PX = 16f

@Composable
fun AurumRoot() {
    val nav = rememberNavController()
    val rootVm: RootViewModel = viewModel()
    @Suppress("UNUSED_VARIABLE")
    val pending by rootVm.pendingCount.collectAsStateWithLifecycle()

    val topDests = remember {
        listOf(
            TopDest(Routes.DASHBOARD, "Portfolio"),
            TopDest(Routes.STOCKS, "Stocks"),
            TopDest(Routes.PICKS, "Picks"),
            TopDest(Routes.WEALTH, "Wealth"),
            TopDest(Routes.PREMARKET, "2%")
        )
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
                modifier = Modifier.fillMaxSize()
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
 * The text-only bottom bar: five words, a gliding gold indicator that springs
 * between them, and per-item color/scale motion. It floats OVER the content
 * (edge-to-edge) and ducks away on downward scroll.
 */
@Composable
private fun AurumNavBar(
    items: List<TopDest>,
    currentRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }
    // Off a top route the indicator keeps its last slot while the bar slides away.
    val indicatorPos by animateFloatAsState(
        targetValue = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navIndicator"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Nearly opaque: the list is felt sliding underneath without
            // ever making the labels hard to read.
            .background(AurumColors.surface.copy(alpha = 0.97f))
    ) {
        HorizontalDivider(color = AurumColors.hairline, thickness = 1.dp)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            val slotWidth = maxWidth / items.size
            val indicatorWidth = 28.dp
            Box(
                modifier = Modifier
                    .offset(x = slotWidth * indicatorPos + (slotWidth - indicatorWidth) / 2)
                    .width(indicatorWidth)
                    .height(3.dp)
                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(AurumColors.gold)
            )
            Row(modifier = Modifier.fillMaxSize()) {
                items.forEachIndexed { index, dest ->
                    val selected = index == selectedIndex
                    val color by animateColorAsState(
                        targetValue = if (selected) AurumColors.gold else AurumColors.textDim,
                        animationSpec = tween(220),
                        label = "navColor"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (selected) 1.08f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "navScale"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(dest.route) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                        )
                    }
                }
            }
        }
        // The bar's surface continues behind the gesture area — borderless.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
