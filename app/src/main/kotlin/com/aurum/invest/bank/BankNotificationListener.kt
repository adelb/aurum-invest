package com.aurum.invest.bank

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.aurum.invest.AurumApp
import com.aurum.invest.core.Fmt
import com.aurum.invest.core.Notify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Listens to all posted notifications and captures the ones that look like
 * bank/broker trade alerts. A notification is captured when EITHER its package
 * contains one of the configured bank-package fragments OR its text matches
 * trade keywords (EN/AR) and contains a digit. High-confidence parses are
 * auto-imported into the ledger when the user has enabled auto-import.
 *
 * Declared in the manifest as `.bank.BankNotificationListener`.
 */
class BankNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return // never capture our own notifications

        val extras = n.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val body = if (bigText.length > text.length) bigText else text
        if (title.isBlank() && body.isBlank()) return

        val app = applicationContext as? AurumApp ?: return
        val container = app.container
        val pkg = n.packageName ?: return
        val postedAt = n.postTime

        container.appScope.launch {
            try {
                val fragments = container.settings.bankPackages.first()
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                val pkgLower = pkg.lowercase()
                val packageMatches = fragments.any { pkgLower.contains(it) }

                val combined = "$title $body"
                val keywordMatches =
                    TRADE_KEYWORDS.containsMatchIn(combined) && combined.any { it.isDigit() }

                if (!packageMatches && !keywordMatches) return@launch

                val eventId = container.bankFeed.recordNotification(pkg, title, body, postedAt)
                if (eventId <= 0L) return@launch // deduped or storage failure

                val parsed = TradeParser.parse(title, body) ?: return@launch
                val autoImportOn = container.settings.autoImport.first()
                // Unattended ledger writes demand more than a confident regex:
                // every field present, a broker transaction reference to trace
                // it by, and USD denomination (non-USD needs a human-entered
                // FX conversion — storing a JOD number as a USD price would
                // silently corrupt the ledger).
                val usd = parsed.currency == null || parsed.currency == "USD"
                val complete = parsed.symbol != null && parsed.shares != null && parsed.price != null
                if (autoImportOn && complete && usd && parsed.ref != null && parsed.confidence >= 80) {
                    val written = container.bankFeed.importEvent(
                        eventId = eventId,
                        symbol = parsed.symbol!!,
                        side = parsed.side,
                        shares = parsed.shares!!,
                        price = parsed.price!!
                    )
                    if (written) {
                        Notify.bankImported(
                            applicationContext,
                            "${parsed.side.name} ${Fmt.qty(parsed.shares)} ${parsed.symbol} " +
                                "@ ${Fmt.money(parsed.price)} · ref ${parsed.ref}"
                        )
                    }
                } else if (parsed.confidence >= 60) {
                    // Looks like a real trade but doesn't clear the unattended
                    // bar — ask the human instead of guessing.
                    val why = when {
                        !complete -> "some fields could not be read"
                        !usd -> "it is in ${parsed.currency} and needs an FX conversion"
                        parsed.ref == null -> "it carries no broker reference"
                        !autoImportOn -> "auto-import is off"
                        else -> "the parse is below the auto-import bar"
                    }
                    Notify.bankReviewNeeded(applicationContext, title.ifBlank { body.take(60) }, why)
                }
            } catch (_: Exception) {
                // capture must never crash the listener service
            }
        }
    }

    companion object {

        private val TRADE_KEYWORDS = Regex(
            """buy|bought|sell|sold|shares|stock|شراء|بيع|سهم|أسهم""",
            RegexOption.IGNORE_CASE
        )

        /** True when the user has granted Aurum notification access. */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** Opens the system notification-access settings screen. */
        fun openSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                // settings screen unavailable on this device — nothing to do
            }
        }
    }
}
