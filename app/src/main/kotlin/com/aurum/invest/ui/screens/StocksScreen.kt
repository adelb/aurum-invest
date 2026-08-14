package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.StockCatalog
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.theme.AurumColors

/**
 * The Stocks tab: one toggle between the personal Watchlist and market Search.
 * Search itself answers two ways — free tag search over the whole US symbol
 * directory, or a sector browse whose best 2-week performers wear the gold
 * border.
 */
@Composable
fun StocksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: StocksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    // 0 = Watchlist, 1 = Search.
    var mode by rememberSaveable { mutableIntStateOf(0) }
    // 0 = by tag, 1 = by sector.
    var searchMode by rememberSaveable { mutableIntStateOf(1) }

    LaunchedEffect(mode, searchMode) {
        if (mode == 1 && searchMode == 1) vm.ensureSector()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Stocks",
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.text
            )
            Spacer(modifier = Modifier.height(14.dp))
            SegmentedToggle(
                options = listOf("Watchlist", "Search"),
                selected = mode,
                onSelect = { mode = it },
                compact = true
            )
        }

        if (mode == 0) {
            WatchlistMode(vm, state, onOpenDetail, onOpenAnalysis)
        } else {
            SearchMode(
                vm = vm,
                state = state,
                searchMode = searchMode,
                onSearchMode = { searchMode = it },
                onOpenDetail = onOpenDetail,
                onOpenAnalysis = onOpenAnalysis
            )
        }
    }
}

// ------------------------------------------------------------- watchlist mode

@Composable
private fun WatchlistMode(
    vm: StocksViewModel,
    state: StocksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = stocksFieldColors(),
                placeholder = {
                    Text(
                        text = "Add ticker — try AAPL",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // The keyboard's action key adds the best match directly.
                    state.suggestions.firstOrNull()?.let { (symbol, name) ->
                        vm.addSymbol(symbol, name)
                        keyboard?.hide()
                    }
                }),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = AurumColors.textDim
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = AurumColors.textDim
                            )
                        }
                    }
                }
            )
            AnimatedVisibility(visible = state.suggestions.isNotEmpty()) {
                AurumCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    state.suggestions.forEach { (symbol, name) ->
                        SuggestionRow(
                            symbol = symbol,
                            name = name,
                            onAdd = { vm.addSymbol(symbol, name) }
                        )
                    }
                }
            }
        }

        AurumRefreshBox(
            refreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (state.loading && state.rows.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AurumColors.gold)
                        }
                    }
                } else if (state.rows.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Nothing watched yet",
                            message = "Search a ticker above to track its price, a good entry point " +
                                "and its relation to gold — or browse by sector in Search."
                        )
                    }
                } else {
                    item {
                        SectionHeader(title = "Watching")
                    }
                    items(state.rows, key = { it.symbol }) { row ->
                        WatchRowCard(
                            row = row,
                            onOpen = { onOpenDetail(row.symbol) },
                            onPin = { vm.setPinned(row.symbol, !row.pinned) },
                            onRemove = { vm.remove(row.symbol) },
                            onAnalyze = { onOpenAnalysis(row.symbol) }
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- search mode

@Composable
private fun SearchMode(
    vm: StocksViewModel,
    state: StocksState,
    searchMode: Int,
    onSearchMode: (Int) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(14.dp))
            SegmentedToggle(
                options = listOf("By tag", "By sector"),
                selected = searchMode,
                onSelect = onSearchMode,
                compact = true
            )
        }
        if (searchMode == 0) {
            TagSearch(vm, state, onOpenDetail, onOpenAnalysis)
        } else {
            SectorBrowse(vm, state, onOpenDetail, onOpenAnalysis)
        }
    }
}

@Composable
private fun TagSearch(
    vm: StocksViewModel,
    state: StocksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = state.tagQuery,
                onValueChange = vm::onTagQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = stocksFieldColors(),
                placeholder = {
                    Text(
                        text = "Ticker or company — try NVDA, lithium, bank",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = AurumColors.textDim
                    )
                },
                trailingIcon = {
                    if (state.tagQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.onTagQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = AurumColors.textDim
                            )
                        }
                    }
                }
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                state.tagSearching -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AurumColors.gold)
                    }
                }
                state.tagRows.isEmpty() && state.tagQuery.isBlank() -> item {
                    EmptyState(
                        title = "Search the whole market",
                        message = "Type any ticker or company tag. Each match shows its live " +
                            "price and its move over the last 2 weeks; the strongest " +
                            "performers wear the gold border."
                    )
                }
                state.tagRows.isEmpty() -> item {
                    EmptyState(
                        title = "No matches",
                        message = "No US-listed stock matches \"${state.tagQuery.trim()}\"."
                    )
                }
                else -> items(state.tagRows, key = { it.symbol }) { row ->
                    BrowseRowCard(
                        row = row,
                        watched = state.watchedSymbols.contains(row.symbol),
                        onOpen = { onOpenDetail(row.symbol) },
                        onAnalyze = { onOpenAnalysis(row.symbol) },
                        onToggleWatch = { vm.toggleWatch(row.symbol, row.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectorBrowse(
    vm: StocksViewModel,
    state: StocksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(14.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(StockCatalog.SECTORS, key = { it.name }) { sector ->
                val selected = sector.name == state.selectedSector
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) AurumColors.gold else AurumColors.surface)
                        .clickable { vm.selectSector(sector.name) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = sector.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) AurumColors.bg else AurumColors.textDim
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "${state.selectedSector} — sorted by the last 2 weeks' real move. " +
                        "The gold border marks the sector's best 2-week performers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            if (state.sectorLoading && state.sectorRows.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AurumColors.gold)
                    }
                }
            } else {
                items(state.sectorRows, key = { it.symbol }) { row ->
                    BrowseRowCard(
                        row = row,
                        watched = state.watchedSymbols.contains(row.symbol),
                        onOpen = { onOpenDetail(row.symbol) },
                        onAnalyze = { onOpenAnalysis(row.symbol) },
                        onToggleWatch = { vm.toggleWatch(row.symbol, row.name) }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- pieces

@Composable
private fun BrowseRowCard(
    row: BrowseRow,
    watched: Boolean,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    onToggleWatch: () -> Unit
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .let {
            if (row.top) it.border(1.5.dp, AurumColors.gold, RoundedCornerShape(16.dp)) else it
        }
    AurumCard(modifier = cardModifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (row.top) {
                        Spacer(modifier = Modifier.width(8.dp))
                        PillTag(text = "Top 2 weeks", color = AurumColors.gold)
                    }
                }
                if (row.name.isNotBlank()) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Sparkline(
                data = row.spark,
                modifier = Modifier
                    .width(90.dp)
                    .height(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                val quote = row.quote
                if (quote != null) {
                    Text(
                        text = Fmt.money(quote.price),
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val twoWeek = row.twoWeekPct
            if (twoWeek != null) {
                DeltaPct(value = twoWeek, style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "last 2 weeks",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            } else {
                Text(
                    text = "2-week read needs more history",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleWatch, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (watched) "Remove ${row.symbol} from watchlist"
                    else "Add ${row.symbol} to watchlist",
                    tint = if (watched) AurumColors.gold else AurumColors.textDim,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onAnalyze, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.QueryStats,
                    contentDescription = "Analyze ${row.symbol}",
                    tint = AurumColors.gold,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(symbol: String, name: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAdd() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            if (name.isNotBlank()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Add $symbol to watchlist",
            tint = AurumColors.gold
        )
    }
}

@Composable
private fun WatchRowCard(
    row: WatchRow,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
    onAnalyze: () -> Unit
) {
    AurumCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (row.pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (row.pinned) "Unpin ${row.symbol}" else "Pin ${row.symbol}",
                    tint = if (row.pinned) AurumColors.gold else AurumColors.textDim
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
                if (row.name.isNotBlank()) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Sparkline(
                data = row.spark,
                modifier = Modifier
                    .width(90.dp)
                    .height(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                val quote = row.quote
                if (quote != null) {
                    Text(
                        text = Fmt.money(quote.price),
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val advice = row.advice
            if (advice != null) {
                ActionBadge(action = advice.action)
                val entry = advice.suggestedBuyPrice
                if (entry != null) {
                    Text(
                        text = "Entry ≈ " + Fmt.money(entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
            } else {
                Text(
                    text = "Analyzing…",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (row.pinned) {
                when (row.goldLink) {
                    GoldLink.WITH_GOLD -> PillTag(text = "Moves with gold", color = AurumColors.gold)
                    GoldLink.INVERSE_GOLD -> PillTag(text = "Inverse to gold", color = AurumColors.loss)
                    GoldLink.NEUTRAL -> PillTag(text = "—", color = AurumColors.textDim)
                    null -> Unit
                }
            }
            IconButton(onClick = onAnalyze, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.QueryStats,
                    contentDescription = "Analyze ${row.symbol}",
                    tint = AurumColors.gold,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${row.symbol}",
                    tint = AurumColors.textDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun stocksFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedContainerColor = AurumColors.surface,
    unfocusedContainerColor = AurumColors.surface,
    cursorColor = AurumColors.gold,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    focusedPlaceholderColor = AurumColors.textDim,
    unfocusedPlaceholderColor = AurumColors.textDim
)
