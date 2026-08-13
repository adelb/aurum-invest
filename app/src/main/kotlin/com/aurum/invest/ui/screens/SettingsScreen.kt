package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.BuildConfig
import com.aurum.invest.R
import com.aurum.invest.bank.BankNotificationListener
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.GoldGradientText
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenFeed: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = AurumColors.text
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = AurumColors.text
                    )
                }
            }

            item(key = "language") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = stringResource(R.string.settings_language))
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguageChip(
                            label = stringResource(R.string.settings_language_english),
                            selected = state.language == "en",
                            onClick = { vm.setLanguage("en") },
                            modifier = Modifier.weight(1f)
                        )
                        LanguageChip(
                            label = stringResource(R.string.settings_language_arabic),
                            selected = state.language == "ar",
                            onClick = { vm.setLanguage("ar") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "bank-feed-entry") {
                Spacer(Modifier.height(20.dp))
                SectionHeader(title = stringResource(R.string.settings_bank_feed))
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenFeed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_captured_alerts),
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_captured_alerts_desc),
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
                SectionHeader(title = stringResource(R.string.settings_bank_sync))
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_notification_access),
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_notification_access_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (state.listenerEnabled) {
                            PillTag(text = stringResource(R.string.settings_connected), color = AurumColors.gain)
                        } else {
                            PillTag(text = stringResource(R.string.settings_off), color = AurumColors.loss)
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
                                    text = stringResource(R.string.settings_enable_notification_access),
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
                        label = { Text(stringResource(R.string.settings_bank_packages_label)) },
                        supportingText = {
                            Text(
                                text = stringResource(R.string.settings_bank_packages_help),
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
                                text = stringResource(R.string.settings_auto_import),
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.text
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_auto_import_desc),
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

            item(key = "about") {
                Spacer(Modifier.height(28.dp))
                SectionHeader(title = stringResource(R.string.settings_about))
                Spacer(Modifier.height(14.dp))
                AurumCard(modifier = Modifier.fillMaxWidth()) {
                    GoldGradientText(
                        text = "Aurum",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_about_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AurumColors.gold else AurumColors.surfaceHigh,
            contentColor = if (selected) AurumColors.bg else AurumColors.text
        ),
        modifier = modifier
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
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
