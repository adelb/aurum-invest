package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.AurumApp
import com.aurum.invest.BuildConfig
import com.aurum.invest.bank.BankNotificationListener
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.GoldGradientText
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFeed: () -> Unit = {},
    onOpenDisclosures: () -> Unit = {}
) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(vm::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importFrom) }

    // Local field state, seeded once from the first DataStore emission,
    // then pushed back through the debounced saver on every keystroke.
    var packagesText by remember { mutableStateOf("") }
    var packagesSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(state.bankPackages) {
        val loaded = state.bankPackages
        if (!packagesSeeded && loaded != null) {
            packagesText = loaded
            packagesSeeded = true
        }
    }

    // Re-check listener access every time we come back from system settings.
    LifecycleResumeEffect(Unit) {
        vm.refreshListenerState()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = AurumColors.text
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AurumColors.text
                    )
                }
            }

            state.actionMessage?.let { msg ->
                item(key = "action-message") {
                    Spacer(Modifier.height(12.dp))
                    AurumCard(modifier = Modifier.fillMaxWidth(), onClick = vm::clearActionMessage) {
                        Text(
                            text = "$msg (tap to dismiss)",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.gold
                        )
                    }
                }
            }

            item(key = "investor-profile") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Investor profile")
                Spacer(Modifier.height(14.dp))
                InvestorProfileCard(profile = state.profile, onSave = vm::saveProfile)
            }

            item(key = "bank-feed-entry") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Bank feed")
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenFeed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Captured trade alerts",
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Review and import trades caught from bank notifications",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AurumColors.textDim
                        )
                    }
                }
            }

            item(key = "bank-sync") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Bank sync")
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification access",
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Lets Aurum read your bank's trade alerts",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (state.listenerEnabled) {
                            PillTag(text = "Connected", color = AurumColors.gain)
                        } else {
                            PillTag(text = "Off", color = AurumColors.loss)
                        }
                    }

                    AnimatedVisibility(
                        visible = !state.listenerEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { BankNotificationListener.openSettings(context) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AurumColors.gold,
                                    contentColor = AurumColors.bg
                                )
                            ) {
                                Text(
                                    text = "Enable notification access",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = AurumColors.hairline)
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = packagesText,
                        onValueChange = { input ->
                            packagesText = input
                            vm.saveBankPackages(input)
                        },
                        label = { Text("Bank app packages") },
                        supportingText = {
                            Text(
                                text = "Any notification whose app package contains one of these is captured",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        },
                        singleLine = true,
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-import high-confidence trades",
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Alerts parsed with high confidence are added to your ledger automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.autoImport,
                            onCheckedChange = { vm.setAutoImport(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AurumColors.bg,
                                checkedTrackColor = AurumColors.gold,
                                uncheckedThumbColor = AurumColors.textDim,
                                uncheckedTrackColor = AurumColors.surfaceHigh,
                                uncheckedBorderColor = AurumColors.hairline
                            )
                        )
                    }
                }
            }

            item(key = "trading-costs") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Trading costs")
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    var feeText by remember(state.sellFeePct) {
                        mutableStateOf(
                            if (state.sellFeePct > 0.0) {
                                com.aurum.invest.core.Fmt.trimNumber(state.sellFeePct)
                            } else ""
                        )
                    }
                    OutlinedTextField(
                        value = feeText,
                        onValueChange = { input ->
                            feeText = input
                            input.trim().toDoubleOrNull()?.let { vm.setSellFeePct(it) }
                            if (input.isBlank()) vm.setSellFeePct(0.0)
                        },
                        label = { Text("Selling cost (% of proceeds)") },
                        supportingText = {
                            Text(
                                text = "Broker fee + tax estimate. Sell targets stay labeled " +
                                    "\"before fees & tax\"; this is your own planning number.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        },
                        singleLine = true,
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item(key = "privacy") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Privacy & data")
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App lock",
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Require your device screen lock (biometric/PIN) to open Aurum",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.appLock,
                            onCheckedChange = { vm.setAppLock(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AurumColors.bg,
                                checkedTrackColor = AurumColors.gold,
                                uncheckedThumbColor = AurumColors.textDim,
                                uncheckedTrackColor = AurumColors.surfaceHigh,
                                uncheckedBorderColor = AurumColors.hairline
                            )
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = AurumColors.hairline)
                    Spacer(Modifier.height(16.dp))

                    var retentionText by remember(state.bankRetentionDays) {
                        mutableStateOf(state.bankRetentionDays.toString())
                    }
                    OutlinedTextField(
                        value = retentionText,
                        onValueChange = { input ->
                            retentionText = input.filter { it.isDigit() }.take(4)
                            retentionText.toIntOrNull()?.let { vm.setBankRetentionDays(it) }
                        },
                        label = { Text("Keep bank captures (days)") },
                        supportingText = {
                            Text(
                                text = "Raw notification text is sensitive — captures older than " +
                                    "this are deleted on every launch. 0 keeps them forever.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        },
                        singleLine = true,
                        colors = settingsFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Delete all captured bank notifications now",
                        style = MaterialTheme.typography.labelLarge,
                        color = AurumColors.loss,
                        modifier = Modifier
                            .clickable { vm.deleteBankCaptures() }
                            .padding(vertical = 6.dp)
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = AurumColors.hairline)
                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Backup & restore",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Your ledger lives only on this device. Export it as JSON to keep " +
                            "your own copy; restore merges and never duplicates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { exportLauncher.launch("aurum-backup.json") },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.gold,
                                contentColor = AurumColors.bg
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Export data", style = MaterialTheme.typography.labelLarge) }
                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.surfaceHigh,
                                contentColor = AurumColors.text
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Restore", style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }

            item(key = "disclosures-entry") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "Methodology & disclosures")
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenDisclosures) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Data sources, methods & risks",
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Where every number comes from, how fresh it is, what the " +
                                    "scores mean, and what this app is not",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AurumColors.textDim
                        )
                    }
                }
            }

            item(key = "feed-health") {
                Spacer(Modifier.height(28.dp))
                SectionHeader(title = "Market data")
                Spacer(Modifier.height(14.dp))
                FeedHealthCard()
            }

            item(key = "about") {
                Spacer(Modifier.height(28.dp))
                SectionHeader(title = "About")
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    GoldGradientText(
                        text = "Aurum",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "A personal investment wallet: live portfolio tracking, weekly picks, gold correlation and automatic trade capture from bank notifications.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Suggestions are computed from public market data and are not financial advice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

/**
 * What the app has actually spent on market data in the last hour, and what
 * the provider did with it.
 *
 * The price feed rate-limits per device, and when it starts refusing, every
 * screen looks the same: figures that stop moving. Without a count there is no
 * telling a quiet market from a runaway sweep — which is exactly the guess
 * that cost several releases. It refreshes while the screen is open.
 */
@Composable
private fun FeedHealthCard() {
    val context = LocalContext.current
    val market = remember(context) {
        (context.applicationContext as? AurumApp)?.container?.market
    }
    var requests by remember { mutableIntStateOf(0) }
    var refused by remember { mutableIntStateOf(0) }
    var pausedFor by remember { mutableLongStateOf(0L) }

    LaunchedEffect(market) {
        val repo = market ?: return@LaunchedEffect
        while (true) {
            requests = repo.feedRequestsLastHour()
            refused = repo.feedRefusalsLastHour()
            pausedFor = (repo.quotesPausedUntil() - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(2_000L)
        }
    }

    AurumCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$requests requests in the last hour",
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (refused == 0) {
                "None refused — the feed is answering normally."
            } else {
                "$refused refused as too many requests."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (refused == 0) AurumColors.textDim else AurumColors.loss
        )
        if (pausedFor > 0L) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Paused for another ${(pausedFor / 1000L).coerceAtLeast(1L)}s, " +
                    "then it tries again. Prices shown meanwhile are the last ones read.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.gold
            )
        }
    }
}

@Composable
private fun settingsFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    cursorColor = AurumColors.gold,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    focusedSupportingTextColor = AurumColors.textDim,
    unfocusedSupportingTextColor = AurumColors.textDim,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent
)

/**
 * The suitability layer (C1): who the advice is for. Position caps, sector
 * caps, loss rules, profit rules, and buy-plan sizing all derive from this —
 * and until it is saved, every recommendation is labeled as default-policy.
 */
@Composable
private fun InvestorProfileCard(
    profile: com.aurum.invest.data.repo.InvestorProfile,
    onSave: (com.aurum.invest.data.repo.InvestorProfile) -> Unit
) {
    var horizon by remember(profile) { mutableStateOf(profile.horizon) }
    var tolerance by remember(profile) { mutableStateOf(profile.riskTolerance) }
    var riskText by remember(profile) {
        mutableStateOf(com.aurum.invest.core.Fmt.trimNumber(profile.riskPerTradePct))
    }
    var maxPosText by remember(profile) {
        mutableStateOf(com.aurum.invest.core.Fmt.trimNumber(profile.maxPositionPct))
    }
    var maxSectorText by remember(profile) {
        mutableStateOf(com.aurum.invest.core.Fmt.trimNumber(profile.maxSectorPct))
    }
    val riskVal = riskText.trim().toDoubleOrNull()
    val maxPosVal = maxPosText.trim().toDoubleOrNull()
    val maxSectorVal = maxSectorText.trim().toDoubleOrNull()
    val valid = riskVal != null && riskVal in 0.25..5.0 &&
        maxPosVal != null && maxPosVal in 5.0..100.0 &&
        maxSectorVal != null && maxSectorVal in 10.0..100.0

    AurumCard(modifier = Modifier.fillMaxWidth()) {
        if (!profile.configured) {
            Text(
                text = "Not set — every recommendation currently uses labeled balanced " +
                    "defaults. Set your own policy so the advice fits YOUR money.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.gold
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = "Time horizon",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        ProfileOptionRow(
            options = listOf(
                com.aurum.invest.data.repo.InvestorProfile.HORIZON_SHORT to "Weeks",
                com.aurum.invest.data.repo.InvestorProfile.HORIZON_MEDIUM to "Months",
                com.aurum.invest.data.repo.InvestorProfile.HORIZON_LONG to "Years"
            ),
            selected = horizon,
            onSelect = { horizon = it }
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Risk tolerance",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        ProfileOptionRow(
            options = listOf(
                com.aurum.invest.data.repo.InvestorProfile.TOL_CONSERVATIVE to "Conservative",
                com.aurum.invest.data.repo.InvestorProfile.TOL_BALANCED to "Balanced",
                com.aurum.invest.data.repo.InvestorProfile.TOL_AGGRESSIVE to "Aggressive"
            ),
            selected = tolerance,
            onSelect = {
                tolerance = it
                riskText = com.aurum.invest.core.Fmt.trimNumber(
                    com.aurum.invest.data.repo.InvestorProfile.suggestedRisk(it)
                )
            }
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = riskText,
            onValueChange = { riskText = it },
            label = { Text("Risk per trade (% of account, 0.25–5)") },
            supportingText = {
                Text(
                    text = "A full stop-out on any single plan may lose at most this share of " +
                        "your account (Elder's rule, applied to YOUR number).",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            },
            singleLine = true,
            colors = settingsFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = maxPosText,
                onValueChange = { maxPosText = it },
                label = { Text("Max position %") },
                singleLine = true,
                colors = settingsFieldColors(),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = maxSectorText,
                onValueChange = { maxSectorText = it },
                label = { Text("Max sector %") },
                singleLine = true,
                colors = settingsFieldColors(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (riskVal != null && maxPosVal != null && maxSectorVal != null) {
                    onSave(
                        com.aurum.invest.data.repo.InvestorProfile(
                            configured = true,
                            horizon = horizon,
                            riskTolerance = tolerance,
                            riskPerTradePct = riskVal,
                            maxPositionPct = maxPosVal,
                            maxSectorPct = maxSectorVal
                        )
                    )
                }
            },
            enabled = valid,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AurumColors.gold,
                contentColor = AurumColors.bg,
                disabledContainerColor = AurumColors.surfaceHigh,
                disabledContentColor = AurumColors.textDim
            )
        ) { Text("Save profile", style = MaterialTheme.typography.labelLarge) }
        if (profile.configured) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = profile.label(),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun ProfileOptionRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val isSelected = key == selected
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) AurumColors.bg else AurumColors.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) AurumColors.gold else AurumColors.surfaceHigh)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
