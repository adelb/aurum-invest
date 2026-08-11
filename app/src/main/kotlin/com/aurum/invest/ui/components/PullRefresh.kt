package com.aurum.invest.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.aurum.invest.ui.theme.AurumColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drag-down-to-refresh wrapper used by every scrolling screen. Wrap the
 * screen's scrollable content; when the user pulls past the threshold,
 * [onRefresh] fires and the gold spinner stays up until [refreshing] has
 * gone true and back to false (with generous timeouts so a refresh that
 * finishes instantly, or a flag that never flips, can't strand the spinner).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurumRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    val busy by rememberUpdatedState(refreshing)
    if (state.isRefreshing) {
        LaunchedEffect(true) {
            onRefresh()
            withTimeoutOrNull(2_000L) { snapshotFlow { busy }.first { it } }
            withTimeoutOrNull(90_000L) { snapshotFlow { busy }.first { !it } }
            state.endRefresh()
        }
    }
    // clipToBounds: at rest the indicator parks just ABOVE this box; without
    // the clip it bleeds over whatever sits above (headers, tabs, search) as
    // a stale floating circle. Clipped, it only appears while actually pulled.
    Box(modifier = modifier.clipToBounds().nestedScroll(state.nestedScrollConnection)) {
        content()
        PullToRefreshContainer(
            state = state,
            containerColor = AurumColors.surfaceHigh,
            contentColor = AurumColors.gold,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
