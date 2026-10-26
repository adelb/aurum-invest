package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.theme.AurumColors

/**
 * The Wealth tab: the portfolio's management desk, as one wallet of passes
 * ([WealthWallet]) — the score, what to do next, the sector gaps, the next
 * session, the must-buys, the market and the race against SPY.
 */
@Composable
fun WealthScreen(onOpenAnalysis: (String) -> Unit, onOpenDetail: (String) -> Unit) {
    val vm: WealthViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // The passes drop in once; coming back from a stock does not replay it.
    var introDone by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Wealth",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            if (state.refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AurumColors.accent,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = vm::refresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Re-scan the market",
                        tint = AurumColors.textSub
                    )
                }
            }
        }

        AurumRefreshBox(
            refreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 120.dp)
            ) {
                WealthWallet(
                    state = state,
                    intro = !introDone,
                    onIntroDone = { introDone = true },
                    onOpenDetail = onOpenDetail,
                    onOpenAnalysis = onOpenAnalysis
                )
                Hint("Tap a pass to bring it forward. ⓘ turns the front pass over for the reasons behind it.")
                Text(
                    text = state.report?.caveat
                        ?: "Every number here is measured from live market data and the 35-technique " +
                            "board — decision support, not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
