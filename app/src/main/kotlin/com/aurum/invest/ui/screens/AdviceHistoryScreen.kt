package com.aurum.invest.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.AurumApp
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.AdviceLogEntity
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.theme.AurumColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One logged recommendation with the price move since it was made. */
data class AdviceOutcomeRow(
    val entry: AdviceLogEntity,
    /** Move since the advice, percent; null when unmeasurable. */
    val sincePct: Double?
)

data class AdviceHistoryState(
    val loading: Boolean = true,
    val rows: List<AdviceOutcomeRow> = emptyList()
)

class AdviceHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val _state = MutableStateFlow(AdviceHistoryState())
    val state: StateFlow<AdviceHistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.adviceLog.observeRecent().collectLatest { entries ->
                _state.update {
                    it.copy(
                        loading = false,
                        rows = entries.map { e -> AdviceOutcomeRow(e, null) }
                    )
                }
                val quotes = runCatching {
                    container.market.getQuotes(entries.map { it.symbol }.distinct())
                }.getOrDefault(emptyMap())
                _state.update { st ->
                    st.copy(
                        rows = entries.map { e ->
                            val q = quotes[e.symbol]
                            val since = if (q != null && e.priceAt > 0.0) {
                                (q.price - e.priceAt) / e.priceAt * 100.0
                            } else null
                            AdviceOutcomeRow(e, since)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The recommendation audit trail (H6/M6): every verdict the app emitted, with
 * the price at the time and the move since — successes and failures alike.
 */
@Composable
fun AdviceHistoryScreen(onBack: () -> Unit) {
    val vm: AdviceHistoryViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = AurumColors.text
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "Advice history",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AurumColors.text
                        )
                        Text(
                            text = "Every emitted verdict, with what the price did after — " +
                                "wins and misses alike",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.rows.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        title = "No recommendations logged yet",
                        message = "Verdicts from the Wealth review are recorded here as they " +
                            "are produced, so you can audit them later."
                    )
                }
            }

            items(state.rows, key = { it.entry.id }) { row ->
                AdviceRow(row)
                Spacer(Modifier.height(10.dp))
            }

            if (state.rows.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "\"Since\" is the simple price move from the advice price to the " +
                            "latest quote — not a strategy return, and not adjusted for the " +
                            "advice's own stop or target.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

@Composable
private fun AdviceRow(row: AdviceOutcomeRow) {
    val e = row.entry
    AurumCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = e.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.width(8.dp))
                    PillTag(
                        text = e.action,
                        color = when (e.action) {
                            "HOLD" -> AurumColors.textDim
                            "TAKE_PROFIT", "BUY" -> AurumColors.gain
                            else -> AurumColors.loss
                        }
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${e.engine.lowercase().replaceFirstChar { it.uppercase() }} · " +
                        "${Fmt.dateShort(e.ts)} · at ${Fmt.money(e.priceAt)} · v${e.modelVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
                if (e.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = e.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                val since = row.sincePct
                if (since != null) {
                    DeltaPct(value = since, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "since",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}
