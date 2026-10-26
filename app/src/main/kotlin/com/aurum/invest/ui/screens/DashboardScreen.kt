package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.repo.TargetsRepository
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.ButtonTone
import com.aurum.invest.ui.components.CompanyLogo
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ExtHoursChips
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.components.IconTap
import com.aurum.invest.ui.components.Lead
import com.aurum.invest.ui.components.Meter
import com.aurum.invest.ui.components.MoreLink
import com.aurum.invest.ui.components.SectionBand
import com.aurum.invest.ui.components.SectionTitle
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.TossButton
import com.aurum.invest.ui.components.TossChip
import com.aurum.invest.ui.components.companyName
import com.aurum.invest.ui.components.pressScale
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun DashboardScreen(
    onOpenDetail: (String) -> Unit,
    onAdd: () -> Unit,
    onAddGold: () -> Unit,
    onOpenGold: () -> Unit,
    onOpenLot: (Long) -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit,
    onReview: () -> Unit,
    onOpenFeed: () -> Unit,
    pendingAlerts: Int
) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var targetFor by remember { mutableStateOf<HoldingRow?>(null) }
    var showWalletDialog by remember { mutableStateOf(false) }
    var holdingsTab by rememberSaveable { mutableStateOf(0) }
    var allocTab by rememberSaveable { mutableStateOf(0) }
    // Home's entrance — the figures counting in, the wallet's cards dropping
    // and the gauge's needle swinging up — plays on the first visit only, not
    // on every return from a stock.
    var homeIntroDone by rememberSaveable { mutableStateOf(false) }
    var gaugeSwung by rememberSaveable { mutableStateOf(false) }

    if (showWalletDialog) {
        WalletSetupDialog(
            current = state.wallet.total,
            onDismiss = { showWalletDialog = false },
            onSave = { amount ->
                vm.setWalletTotal(amount)
                showWalletDialog = false
            }
        )
    }

    targetFor?.let { row ->
        SellTargetDialog(
            row = row,
            onDismiss = { targetFor = null },
            onSave = { pct ->
                vm.setTarget(row.view.position.symbol, pct)
                targetFor = null
            }
        )
    }

    AurumRefreshBox(
        refreshing = state.loading,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize().background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp)
        ) {
            item {
                HomeHeader(
                    pendingAlerts = pendingAlerts,
                    onOpenFeed = onOpenFeed,
                    onSettings = onSettings
                )
            }

            item {
                NetWorthHero(
                    state = state,
                    rollIn = !homeIntroDone,
                    swingIn = !gaugeSwung,
                    onSwung = { gaugeSwung = true }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                ShortcutChips(
                    onAdd = onAdd,
                    onAddGold = onAddGold,
                    onReports = onReports,
                    onReview = onReview
                )
            }

            item {
                SectionBand()
                WalletSection(
                    state = state,
                    intro = !homeIntroDone,
                    onIntroDone = { homeIntroDone = true },
                    onEdit = { showWalletDialog = true },
                    onAddGold = onAddGold,
                    onOpenGold = onOpenGold
                )
            }

            if (state.holdings.isNotEmpty() || !state.goldBook.isEmpty) {
                item {
                    SectionBand()
                    AllocationSection(state = state, tab = allocTab, onTab = { allocTab = it })
                }
            }

            item {
                SectionBand()
                HoldingsSection(
                    state = state,
                    tab = holdingsTab,
                    onTab = { holdingsTab = it },
                    onOpenDetail = onOpenDetail,
                    onSetTarget = { targetFor = it },
                    onAdd = onAdd,
                    onAddGold = onAddGold,
                    onOpenGold = onOpenGold,
                    onOpenLot = onOpenLot
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(pendingAlerts: Int, onOpenFeed: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                append("Aurum")
                withStyle(SpanStyle(color = AurumColors.gold)) { append(".") }
            },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = AurumColors.text,
            modifier = Modifier.weight(1f)
        )
        IconTap(Icons.Rounded.Notifications, "Bank feed", onOpenFeed, badge = pendingAlerts)
        IconTap(Icons.Rounded.Settings, "Settings", onSettings)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutChips(
    onAdd: () -> Unit,
    onAddGold: () -> Unit,
    onReports: () -> Unit,
    onReview: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TossChip("Add trade", onAdd, icon = Icons.Rounded.Add)
        TossChip("Add gold", onAddGold, icon = Icons.Rounded.Toll)
        TossChip("Reports", onReports, icon = Icons.Rounded.Description)
        TossChip("Review", onReview, icon = Icons.Rounded.Insights)
    }
}

@Composable
private fun WalletSection(
    state: DashboardState,
    intro: Boolean,
    onIntroDone: () -> Unit,
    onEdit: () -> Unit,
    onAddGold: () -> Unit,
    onOpenGold: () -> Unit
) {
    SectionTitle(
        title = "Your wallet",
        subtitle = "Cash, stocks and gold, all you own",
        trailing = { MoreLink(if (state.wallet.configured) "Edit" else "Set up", onEdit) }
    )
    Spacer(Modifier.height(18.dp))
    WalletStack(
        state = state,
        intro = intro,
        onIntroDone = onIntroDone,
        onEditWallet = onEdit,
        onAddGold = onAddGold,
        onOpenGold = onOpenGold
    )
}

private data class Slice(val label: String, val sub: String, val value: Double)

@Composable
private fun AllocationSection(state: DashboardState, tab: Int, onTab: (Int) -> Unit) {
    val stocksUsd = state.holdings.sumOf { it.view.marketValue }
    val goldUsd = state.goldBook.valueUsd
    val cash = if (state.wallet.configured) state.wallet.liquidity.coerceAtLeast(0.0) else 0.0
    val slices: List<Slice> = when (tab) {
        0 -> listOfNotNull(
            Slice("Stocks", "${state.holdings.size} holdings", stocksUsd).takeIf { stocksUsd > 0.0 },
            Slice("Gold", "${Fmt.qty(Math.round(state.goldBook.grams * 10) / 10.0)} g", goldUsd).takeIf { goldUsd > 0.0 },
            Slice("Cash", "free to invest", cash).takeIf { cash > 0.0 }
        )
        1 -> state.holdings.map {
            Slice(it.view.position.symbol, Fmt.money(it.view.marketValue), it.view.marketValue)
        }
        else -> state.book.slices.map { slice ->
            Slice(
                slice.sector,
                slice.symbols.take(3).joinToString(", ") + if (slice.symbols.size > 3) " +${slice.symbols.size - 3}" else "",
                slice.weightPct
            )
        }
    }
    val total = slices.sumOf { it.value }
    SectionTitle(title = "Where your money sits")
    Spacer(Modifier.height(14.dp))
    SegmentedToggle(options = listOf("Assets", "Stocks", "Sectors"), selected = tab, onSelect = onTab)
    Spacer(Modifier.height(16.dp))
    if (slices.isEmpty() || total <= 0.0) {
        Lead(if (tab == 2) "Sector data is still loading — pull to refresh." else "Nothing to split yet.")
        return
    }
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(320)) { it / 6 * dir } + fadeIn(tween(320))) togetherWith
                (slideOutHorizontally(tween(220)) { -it / 6 * dir } + fadeOut(tween(220)))
        },
        label = "allocation"
    ) { _ ->
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                slices.forEachIndexed { i, slice ->
                    val weight = (slice.value / total).toFloat()
                    if (weight > 0.002f) {
                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(sliceColor(tab, i, slice.label))
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            slices.forEachIndexed { i, slice ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(sliceColor(tab, i, slice.label))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(slice.label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = AurumColors.text)
                    Text(
                        "  " + slice.sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Fmt.pct(slice.value / total * 100.0),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        color = AurumColors.text
                    )
                }
            }
        }
    }
    Hint(
        when (tab) {
            0 -> if (goldUsd > 0.0) "Gold is converted at the 0.709 dinar peg and valued at the Syndicate buy price." else
                "Stocks at live prices; cash is your liquidity."
            1 -> "Each holding at its live price."
            else -> "Your stocks compounded into their sectors — the same split Picks and Wealth use."
        }
    )
}

private fun sliceColor(tab: Int, i: Int, label: String): Color = when {
    tab == 0 && label == "Gold" -> AurumColors.gold
    tab == 0 && label == "Cash" -> AurumColors.textFaint
    tab == 0 -> AurumColors.accent
    else -> AurumColors.allocation.filter { it != AurumColors.gold }.let { it[i % it.size] }
}

@Composable
private fun HoldingsSection(
    state: DashboardState,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenDetail: (String) -> Unit,
    onSetTarget: (HoldingRow) -> Unit,
    onAdd: () -> Unit,
    onAddGold: () -> Unit,
    onOpenGold: () -> Unit,
    onOpenLot: (Long) -> Unit
) {
    val gold = state.goldBook
    SectionTitle(
        title = "Holdings",
        subtitle = if (tab == 0) {
            if (state.holdings.isEmpty()) "No stocks yet"
            else "${state.holdings.size} stocks · ${Fmt.moneyExact(state.holdings.sumOf { it.view.marketValue })}"
        } else {
            val n = state.goldLots.count { !it.sold }
            if (n == 0) "No gold yet" else "$n purchase${if (n == 1) "" else "s"} · ${Fmt.qty(Math.round(gold.grams * 10) / 10.0)} g"
        }
    )
    Spacer(Modifier.height(14.dp))
    SegmentedToggle(options = listOf("Stocks", "Gold"), selected = tab, onSelect = onTab)
    Spacer(Modifier.height(8.dp))
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(340)) { it / 5 * dir } + fadeIn(tween(340))) togetherWith
                (slideOutHorizontally(tween(220)) { -it / 5 * dir } + fadeOut(tween(220)))
        },
        label = "holdings"
    ) { which ->
        if (which == 0) {
            StockHoldings(state, onOpenDetail, onSetTarget, onAdd)
        } else {
            GoldHoldings(state, onAddGold, onOpenGold, onOpenLot)
        }
    }
}

@Composable
private fun StockHoldings(
    state: DashboardState,
    onOpenDetail: (String) -> Unit,
    onSetTarget: (HoldingRow) -> Unit,
    onAdd: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            state.loading && state.holdings.isEmpty() && state.summary == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = AurumColors.accent) }
            state.holdings.isEmpty() -> EmptyState(
                title = "No holdings yet",
                message = "Record your first buy and Aurum starts tracking its price, profit and advice.",
                actionLabel = "Add a trade",
                onAction = onAdd
            )
            else -> state.holdings.forEach { row ->
                StockRow(
                    row = row,
                    onClick = { onOpenDetail(row.view.position.symbol) },
                    onSetTarget = { onSetTarget(row) }
                )
            }
        }
    }
}

@Composable
private fun StockRow(row: HoldingRow, onClick: () -> Unit, onSetTarget: () -> Unit) {
    val view = row.view
    val symbol = view.position.symbol
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .pressScale(pressedScale = 0.98f, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompanyLogo(symbol = symbol)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = companyName(view.quote?.shortName, symbol),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${Fmt.signedMoney(view.unrealizedPl)} (${Fmt.signedPct(view.unrealizedPlPct)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.deltaColor(view.unrealizedPl),
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.moneyExact(view.marketValue),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                row.advice?.let { ActionBadge(action = it.action) }
                    ?: Text(
                        "${Fmt.qty(view.position.shares)} sh",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
            }
        }
        TargetLine(row = row, onClick = onSetTarget)
        if (row.ext?.preMarketPct != null || row.ext?.postMarketPct != null) {
            ExtHoursChips(ext = row.ext, modifier = Modifier.padding(start = 58.dp, top = 6.dp))
        }
    }
}

/**
 * The sell flag, as one line under the holding: the price the user's profit
 * target implies and a meter of how far along the way the price already is.
 */
@Composable
private fun TargetLine(row: HoldingRow, onClick: () -> Unit) {
    val target = row.targetPrice
    val style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .pressScale(pressedScale = 0.98f, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (target == null) {
            Text("No profit target yet — tap to set one", style = style, color = AurumColors.textDim)
            return@Row
        }
        val reached = row.targetReached
        val avg = row.view.position.avgCost
        val price = row.view.quote?.price ?: avg
        val progress = if (target > avg) ((price - avg) / (target - avg)).toFloat().coerceIn(0f, 1f) else 1f
        Text(
            text = buildAnnotatedString {
                append(if (reached) "Target reached — sell at " else "Sell at ")
                withStyle(SpanStyle(color = if (reached) AurumColors.gain else AurumColors.text)) {
                    append(Fmt.money(target))
                }
            },
            style = style,
            color = AurumColors.textDim
        )
        Spacer(Modifier.width(10.dp))
        Meter(
            fraction = if (reached) 1f else progress,
            color = if (reached) AurumColors.gain else AurumColors.gold,
            modifier = Modifier.weight(1f)
        )
        val distance = row.distanceToTargetPct
        if (!reached && distance != null) {
            Spacer(Modifier.width(10.dp))
            Text("${Fmt.pct(distance)} to go", style = style, color = AurumColors.textDim)
        }
    }
}

@Composable
private fun GoldHoldings(
    state: DashboardState,
    onAddGold: () -> Unit,
    onOpenGold: () -> Unit,
    onOpenLot: (Long) -> Unit
) {
    val book = state.goldBook
    val goldState = GoldState(
        loading = false,
        lots = state.goldLots,
        list = state.goldList,
        shop = state.goldShop
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        val open = state.goldLots.filter { !it.sold }
        if (open.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Lead("Record the gold you bought and Aurum values it at what a Jordan shop would pay you today.")
            Spacer(Modifier.height(12.dp))
            TossButton("Add gold I bought", onClick = onAddGold, tone = ButtonTone.SOFT, small = true)
            return@Column
        }
        if (book.lots.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AurumColors.goldGround)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${Fmt.qty(Math.round(book.grams * 10) / 10.0)} g · ${Fmt.jod(book.value)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = Color(0xFFE7DCC0),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${Fmt.moneyExact(book.valueUsd)} · ${Fmt.signedPct(book.plPct)}",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                    color = AurumColors.gold
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        open.forEach { lot ->
            GoldLotRow(lot = lot, state = goldState, onClick = { onOpenLot(lot.id) }, compact = true)
        }
        TossButton("Open Gold", onClick = onOpenGold, tone = ButtonTone.SOFT, small = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Hint(
            if (state.goldList != null) "Valued at the Syndicate buy price of ${listLabel(state.goldList)}, " +
                "converted at the 0.709 JOD peg."
            else "Today’s Syndicate list hasn’t loaded yet, so the gold isn’t valued."
        )
    }
}

/** Enter any profit percentage; the sell price is computed live as you type. */
@Composable
private fun SellTargetDialog(
    row: HoldingRow,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    var text by remember {
        mutableStateOf(row.targetPct?.let { Fmt.trimNumber(it) } ?: "")
    }
    val pct = text.replace(",", "").trim().toDoubleOrNull()
    val valid = pct != null && pct > 0.0
    val avgCost = row.view.position.avgCost
    val shares = row.view.position.shares
    val price = row.view.quote?.price

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Profit target for ${row.view.position.symbol}") },
        text = {
            Column {
                Text(
                    text = "Enter the profit you want. Aurum computes the price to sell at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Target profit (%)") },
                    placeholder = { Text("2.06") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AurumColors.text,
                        unfocusedTextColor = AurumColors.text,
                        focusedBorderColor = AurumColors.accent,
                        unfocusedBorderColor = AurumColors.hairline,
                        focusedLabelColor = AurumColors.accent,
                        unfocusedLabelColor = AurumColors.textDim,
                        cursorColor = AurumColors.accent
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid) {
                    val target = TargetsRepository.sellPriceFor(avgCost, pct!!)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Sell at ${Fmt.money(target)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.gold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("on ")
                            append(Fmt.money(avgCost))
                            append(" average cost · ")
                            append(Fmt.signedMoney(shares * avgCost * pct / 100.0))
                            append(" on ")
                            append(Fmt.qty(shares))
                            append(" shares")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                    if (price != null && price > 0.0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (price >= target) {
                                "The price is already there — ${Fmt.money(price)} today."
                            } else {
                                "${Fmt.pct((target - price) / price * 100.0)} above today's " +
                                    "${Fmt.money(price)}."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (valid) onSave(pct) },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.accent,
                    contentColor = AurumColors.text,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                )
            ) { Text("Set flag") }
        },
        dismissButton = {
            Row {
                if (row.targetPct != null) {
                    TextButton(onClick = { onSave(null) }) {
                        Text("Clear", color = AurumColors.loss)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AurumColors.textDim)
                }
            }
        }
    )
}

@Composable
private fun WalletSetupDialog(
    current: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var amountText by remember {
        mutableStateOf(if (current > 0.0) Fmt.money(current).filter { it.isDigit() || it == '.' } else "")
    }
    val amount = amountText.replace(",", "").trim().toDoubleOrNull()
    val valid = amount != null && amount > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Set your total wallet") },
        text = {
            Column {
                Text(
                    text = "Enter the money you put in for investing — the capital itself, " +
                        "before any profit. Aurum reads what's invested from your ledger, " +
                        "and every sell returns its cost plus the P/L it booked to your " +
                        "liquidity. Only change this when you actually add or withdraw cash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Total wallet ($)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (amount != null) onSave(amount) },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.accent,
                    contentColor = AurumColors.text,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                )
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AurumColors.textDim) }
        }
    )
}
