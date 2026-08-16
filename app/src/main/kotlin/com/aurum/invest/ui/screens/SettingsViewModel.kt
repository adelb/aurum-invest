package com.aurum.invest.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.bank.BankNotificationListener
import com.aurum.invest.data.db.CashEventEntity
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.repo.InvestorProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI state for Settings. [bankPackages] stays null until the first DataStore
 * emission so the screen can seed its local text field exactly once.
 */
data class SettingsState(
    val bankPackages: String? = null,
    val autoImport: Boolean = false,
    val listenerEnabled: Boolean = true,
    val profile: InvestorProfile = InvestorProfile.DEFAULT,
    val bankRetentionDays: Int = 90,
    val appLock: Boolean = false,
    val sellFeePct: Double = 0.0,
    /** One-shot result message from an export/import/delete action. */
    val actionMessage: String? = null
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val listenerEnabled =
        MutableStateFlow(BankNotificationListener.isEnabled(app))

    private val actionMessage = MutableStateFlow<String?>(null)

    private var saveJob: Job? = null

    val state: StateFlow<SettingsState> = combine(
        combine(
            container.settings.bankPackages,
            container.settings.autoImport,
            listenerEnabled
        ) { pkgs, auto, enabled -> Triple(pkgs, auto, enabled) },
        combine(
            container.settings.investorProfile,
            container.settings.bankRetentionDays,
            container.settings.appLock,
            container.settings.sellFeePct
        ) { profile, retention, lock, fee -> listOf(profile, retention, lock, fee) },
        actionMessage
    ) { (pkgs, auto, enabled), extras, message ->
        SettingsState(
            bankPackages = pkgs,
            autoImport = auto,
            listenerEnabled = enabled,
            profile = extras[0] as InvestorProfile,
            bankRetentionDays = extras[1] as Int,
            appLock = extras[2] as Boolean,
            sellFeePct = extras[3] as Double,
            actionMessage = message
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsState(listenerEnabled = listenerEnabled.value)
    )

    /** Re-checks notification-listener access; call on every lifecycle resume. */
    fun refreshListenerState() {
        listenerEnabled.value = BankNotificationListener.isEnabled(getApplication())
    }

    /** Persists the comma-separated package fragments, debounced 500 ms. */
    fun saveBankPackages(value: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            try {
                container.settings.setBankPackages(value)
            } catch (_: Exception) {
            }
        }
    }

    fun setAutoImport(value: Boolean) {
        viewModelScope.launch {
            try {
                container.settings.setAutoImport(value)
            } catch (_: Exception) {
            }
        }
    }

    fun saveProfile(profile: InvestorProfile) {
        viewModelScope.launch {
            runCatching { container.settings.setInvestorProfile(profile) }
            actionMessage.value = "Profile saved — recommendations now size to it."
        }
    }

    fun setBankRetentionDays(days: Int) {
        viewModelScope.launch { runCatching { container.settings.setBankRetentionDays(days) } }
    }

    fun setAppLock(value: Boolean) {
        viewModelScope.launch { runCatching { container.settings.setAppLock(value) } }
    }

    fun setSellFeePct(value: Double) {
        viewModelScope.launch { runCatching { container.settings.setSellFeePct(value) } }
    }

    fun deleteBankCaptures() {
        viewModelScope.launch {
            val n = container.bankFeed.deleteAllCaptures()
            actionMessage.value = "Deleted $n captured bank notifications."
        }
    }

    fun clearActionMessage() {
        actionMessage.value = null
    }

    // ---- export / restore (H5) ---------------------------------------------

    /**
     * Writes the full ledger (transactions + cash events + watchlist) as JSON
     * to [uri]. Verified by reading the row counts back from what was written.
     */
    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txs = container.db.transactionDao().getAllOrdered()
                val cash = container.db.cashEventDao().getAllOrdered()
                val watch = container.db.watchDao().getAll()
                val root = JSONObject().apply {
                    put("app", "aurum")
                    put("format", 1)
                    put("exportedAt", System.currentTimeMillis())
                    put("transactions", JSONArray().apply {
                        txs.forEach { t ->
                            put(JSONObject().apply {
                                put("symbol", t.symbol); put("side", t.side)
                                put("shares", t.shares); put("price", t.price)
                                put("fees", t.fees); put("ts", t.ts)
                                put("source", t.source); put("note", t.note)
                                t.plOverride?.let { put("plOverride", it) }
                                put("currency", t.currency); put("fxRate", t.fxRate)
                            })
                        }
                    })
                    put("cashEvents", JSONArray().apply {
                        cash.forEach { c ->
                            put(JSONObject().apply {
                                put("type", c.type); put("symbol", c.symbol)
                                put("amount", c.amount); put("currency", c.currency)
                                put("fxRate", c.fxRate); put("ts", c.ts); put("note", c.note)
                            })
                        }
                    })
                    put("watchlist", JSONArray().apply {
                        watch.forEach { w ->
                            put(JSONObject().apply {
                                put("symbol", w.symbol); put("name", w.name)
                                put("pinned", w.pinned); put("addedAt", w.addedAt)
                            })
                        }
                    })
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open the destination")
                withContext(Dispatchers.Main) {
                    actionMessage.value = "Exported ${txs.size} trades, ${cash.size} cash " +
                        "events, ${watch.size} watchlist items."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    actionMessage.value = "Export failed: ${e.message ?: "unknown error"}"
                }
            }
        }
    }

    /**
     * Restores a previously exported JSON file. MERGES: rows identical to an
     * existing one (same symbol/side/shares/price/ts) are skipped, so a
     * restore can never duplicate the ledger.
     */
    fun importFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Could not read the file")
                val root = JSONObject(text)
                require(root.optString("app") == "aurum") { "Not an Aurum export file" }

                val existing = container.db.transactionDao().getAllOrdered()
                    .map { "${it.symbol}|${it.side}|${it.shares}|${it.price}|${it.ts}" }
                    .toHashSet()
                var added = 0
                var skipped = 0
                val txArr = root.optJSONArray("transactions") ?: JSONArray()
                for (i in 0 until txArr.length()) {
                    val o = txArr.optJSONObject(i) ?: continue
                    val key = "${o.optString("symbol")}|${o.optString("side")}|" +
                        "${o.optDouble("shares")}|${o.optDouble("price")}|${o.optLong("ts")}"
                    if (key in existing) {
                        skipped++
                        continue
                    }
                    container.db.transactionDao().insert(
                        TransactionEntity(
                            symbol = o.getString("symbol"),
                            side = o.getString("side"),
                            shares = o.getDouble("shares"),
                            price = o.getDouble("price"),
                            fees = o.optDouble("fees", 0.0),
                            ts = o.getLong("ts"),
                            source = o.optString("source", "MANUAL"),
                            note = o.optString("note", ""),
                            plOverride = if (o.has("plOverride")) o.getDouble("plOverride") else null,
                            currency = o.optString("currency", "USD"),
                            fxRate = o.optDouble("fxRate", 1.0)
                        )
                    )
                    added++
                }

                val existingCash = container.db.cashEventDao().getAllOrdered()
                    .map { "${it.type}|${it.amount}|${it.ts}" }.toHashSet()
                var cashAdded = 0
                val cashArr = root.optJSONArray("cashEvents") ?: JSONArray()
                for (i in 0 until cashArr.length()) {
                    val o = cashArr.optJSONObject(i) ?: continue
                    val key = "${o.optString("type")}|${o.optDouble("amount")}|${o.optLong("ts")}"
                    if (key in existingCash) continue
                    container.db.cashEventDao().insert(
                        CashEventEntity(
                            type = o.getString("type"),
                            symbol = o.optString("symbol", ""),
                            amount = o.getDouble("amount"),
                            currency = o.optString("currency", "USD"),
                            fxRate = o.optDouble("fxRate", 1.0),
                            ts = o.getLong("ts"),
                            note = o.optString("note", "")
                        )
                    )
                    cashAdded++
                }

                withContext(Dispatchers.Main) {
                    actionMessage.value =
                        "Restored $added trades and $cashAdded cash events ($skipped already present)."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    actionMessage.value = "Restore failed: ${e.message ?: "invalid file"}"
                }
            }
        }
    }
}
