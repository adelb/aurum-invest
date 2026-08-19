package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.RocketLaunch
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
import androidx.compose.runtime.remember
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
import com.aurum.invest.analytics.RotationState
import com.aurum.invest.analytics.StockCatalog
import com.aurum.invest.core.Fmt
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
import java.util.Locale

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
                            message = "Search a ticker above to track its price and a good entry " +
                                "point — or browse by sector in Search."
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
    // Rotation applied to the shelf order itself: once the trend scan lands,
    // the chips run hot -> cold, so the trendiest sectors sit up front.
    val orderedSectors = remember(state.pulses) {
        if (state.pulses.isEmpty()) StockCatalog.SECTORS
        else StockCatalog.SECTORS.sortedBy { state.pulses[it.name]?.rank ?: Int.MAX_VALUE }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(14.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(orderedSectors, key = { it.name }) { sector ->
                val selected = sector.name == state.selectedSector
                val hot = state.pulses[sector.name]?.hot == true
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) AurumColors.gold else AurumColors.surface)
                        .clickable { vm.selectSector(sector.name) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hot) {
                        Icon(
                            imageVector = Icons.Rounded.LocalFireDepartment,
                            contentDescription = "Trending sector",
                            tint = if (selected) AurumColors.bg else AurumColors.gold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = sector.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) AurumColors.bg else AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                SectorPulseCard(state)
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

/**
 * The selected shelf's live read: rotation state and weekly rank from the
 * shared sector scan, what the user already holds from this shelf, and the
 * next-week breakout watch — every figure measured, nothing invented.
 */
@Composable
private fun SectorPulseCard(state: StocksState) {
    val pulse = state.pulses[state.selectedSector]
    AurumCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.selectedSector,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            if (pulse?.hot == true) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.LocalFireDepartment,
                    contentDescription = "Trending sector",
                    tint = AurumColors.gold,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (pulse != null) {
                when (pulse.state) {
                    RotationState.INFLOW ->
                        PillTag(text = "Money flowing in", color = AurumColors.gain)
                    RotationState.OUTFLOW ->
                        PillTag(text = "Money rotating out", color = AurumColors.loss)
                    RotationState.STEADY ->
                        PillTag(text = "Steady", color = AurumColors.textDim)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (pulse != null) {
            val stats = buildString {
                append(
                    String.format(
                        Locale.US,
                        "#%d of %d this week · %+.1f%% in 5 days · %+.1f%% in 20",
                        pulse.rank, pulse.ofTotal, pulse.r5Pct, pulse.r20Pct
                    )
                )
                if (pulse.volumeRatio > 0.0) {
                    append(String.format(Locale.US, " · %.1fx volume", pulse.volumeRatio))
                }
                if (pulse.newsTone != 0) {
                    append(String.format(Locale.US, " · news tone %+d", pulse.newsTone))
                }
                append(" (${pulse.etf})")
            }
            Text(
                text = stats,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        } else {
            Text(
                text = "Sorted by the last 2 weeks' real move; the gold border marks " +
                    "the shelf's best performers.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        // What the book already holds from this shelf — exact membership only.
        if (!state.book.isEmpty) {
            Spacer(modifier = Modifier.height(6.dp))
            val held = state.sectorRows.filter { it.heldPct != null }
            if (held.isNotEmpty()) {
                val total = held.sumOf { it.heldPct ?: 0.0 }
                Text(
                    text = "In your book from this shelf: " +
                        held.joinToString(", ") {
                            String.format(Locale.US, "%s %.0f%%", it.symbol, it.heldPct)
                        } +
                        String.format(Locale.US, " — %.0f%% of the book", total),
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
            } else if (state.sectorRows.isNotEmpty()) {
                Text(
                    text = "Nothing from this shelf in your portfolio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
        }
        // The forward look: names pressing their highs on real volume.
        Spacer(modifier = Modifier.height(6.dp))
        when {
            state.breakoutScanning -> Text(
                text = "Scanning for next-week setups…",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            state.breakouts.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.RocketLaunch,
                    contentDescription = null,
                    tint = AurumColors.gain,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Next-week watch: " +
                        state.breakouts.joinToString(", ") { it.symbol } +
                        " — pressing highs on volume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            else -> Text(
                text = "No name here passes the next-week breakout bar right now.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

// -------------------------------------------------------------------- pieces

@OptIn(ExperimentalLayoutApi::class)
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
                Text(
                    text = row.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                if (row.top || row.breakout != null || row.heldPct != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (row.top) {
                            PillTag(text = "Top 2 weeks", color = AurumColors.gold)
                        }
                        if (row.breakout != null) {
                            PillTag(text = "Next week", color = AurumColors.gain)
                        }
                        row.heldPct?.let { held ->
                            PillTag(
                                text = String.format(Locale.US, "Held · %.0f%%", held),
                                color = AurumColors.gold
                            )
                        }
                    }
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
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 110.dp)
                    )
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.textDim,
                        maxLines = 1
                    )
                }
            }
        }
        val breakout = row.breakout
        if (breakout != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.RocketLaunch,
                    contentDescription = "Next-week breakout watch",
                    tint = AurumColors.gain,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = breakout.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (breakout.newsNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "“${breakout.newsNote}”",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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

@OptIn(ExperimentalLayoutApi::class)
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
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 110.dp)
                    )
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.textDim,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            val advice = row.advice
            if (advice != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Watchlist advice is technical-only (no news read) — the
                    // detail screen's full view includes headline tone.
                    Text(
                        text = "tech read",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ActionBadge(action = advice.action)
                    advice.suggestedBuyPrice?.let { entry ->
                        Text(
                            text = "Entry ≈ " + Fmt.money(entry),
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.gold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    text = "Analyzing…",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
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
