package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.R
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun MarketsScreen(
    onOpenDetail: (String) -> Unit = {}
) {
    val vm: MarketsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    AurumRefreshBox(
        refreshing = state.refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.markets_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Text(
                    text = stringResource(R.string.markets_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }

            item(key = "sec-indices") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = stringResource(R.string.markets_indices))
                Spacer(Modifier.height(10.dp))
            }
            items(state.indices, key = { "i-" + it.symbol }) { row ->
                MarketRowCard(row = row, onClick = { onOpenDetail(row.symbol) })
                Spacer(Modifier.height(8.dp))
            }

            item(key = "sec-metals") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(title = stringResource(R.string.markets_metals))
                Spacer(Modifier.height(10.dp))
            }
            items(state.metals, key = { "m-" + it.symbol }) { row ->
                MarketRowCard(row = row, onClick = { onOpenDetail(row.symbol) })
                Spacer(Modifier.height(8.dp))
            }

            item(key = "sec-fx") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(title = stringResource(R.string.markets_fx))
                Spacer(Modifier.height(10.dp))
            }
            items(state.fx, key = { "f-" + it.symbol }) { row ->
                MarketRowCard(row = row, onClick = { onOpenDetail(row.symbol) })
                Spacer(Modifier.height(8.dp))
            }

            item(key = "footer") {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.markets_footer_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun MarketRowCard(row: MarketRow, onClick: () -> Unit) {
    val quote = row.quote
    val currency = quote?.currency?.takeIf { it.isNotBlank() } ?: ""
    AurumCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.symbol + if (currency.isNotEmpty()) "  ·  $currency" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = quote?.price?.let { formatPrice(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                if (quote != null) {
                    DeltaPct(value = quote.dayChangePct)
                } else {
                    Text(
                        text = stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

private fun formatPrice(v: Double): String {
    val abs = if (v < 0) -v else v
    return when {
        abs >= 1000 -> Fmt.money(v, symbol = "")
        abs >= 1 -> String.format(java.util.Locale.US, "%.4f", v)
        else -> String.format(java.util.Locale.US, "%.5f", v)
    }
}
