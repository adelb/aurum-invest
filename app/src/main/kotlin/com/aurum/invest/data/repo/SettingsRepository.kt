package com.aurum.invest.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aurum_settings")

/**
 * The investor's policy — who the advice is for. Every personalized
 * recommendation must be derived from (and cite) these constraints; until
 * [configured] is true the app uses balanced defaults and says so.
 */
data class InvestorProfile(
    val configured: Boolean,
    /** SHORT (weeks), MEDIUM (months), LONG (years). */
    val horizon: String,
    /** CONSERVATIVE / BALANCED / AGGRESSIVE. */
    val riskTolerance: String,
    /** Max % of account equity a single trade may lose at its stop. */
    val riskPerTradePct: Double,
    /** Max % of holdings value in one position before trimming is advised. */
    val maxPositionPct: Double,
    /** Max % of holdings value in one sector before capping is advised. */
    val maxSectorPct: Double
) {
    companion object {
        const val HORIZON_SHORT = "SHORT"
        const val HORIZON_MEDIUM = "MEDIUM"
        const val HORIZON_LONG = "LONG"
        const val TOL_CONSERVATIVE = "CONSERVATIVE"
        const val TOL_BALANCED = "BALANCED"
        const val TOL_AGGRESSIVE = "AGGRESSIVE"

        /** Balanced defaults, used (and labeled as defaults) until the user configures. */
        val DEFAULT = InvestorProfile(
            configured = false,
            horizon = HORIZON_MEDIUM,
            riskTolerance = TOL_BALANCED,
            riskPerTradePct = 2.0,
            maxPositionPct = 22.0,
            maxSectorPct = 35.0
        )

        /** Suggested risk-per-trade for a tolerance tier. */
        fun suggestedRisk(tolerance: String): Double = when (tolerance) {
            TOL_CONSERVATIVE -> 1.0
            TOL_AGGRESSIVE -> 3.0
            else -> 2.0
        }
    }

    /** One-line provenance for recommendation cards: which policy shaped this advice. */
    fun label(): String {
        val tol = riskTolerance.lowercase().replaceFirstChar { it.uppercase() }
        val hor = when (horizon) {
            HORIZON_SHORT -> "short horizon"
            HORIZON_LONG -> "long horizon"
            else -> "medium horizon"
        }
        val base = "$tol · $hor · ${riskPerTradePct}% risk/trade · " +
            "${maxPositionPct.toInt()}% max position · ${maxSectorPct.toInt()}% max sector"
        return if (configured) "Your profile: $base" else "Default policy (profile not set): $base"
    }
}

/**
 * App settings. [bankPackages] is a comma-separated list of package-name
 * fragments; a notification is captured when its package contains any of them.
 */
class SettingsRepository(private val context: Context) {

    private val keyBankPackages = stringPreferencesKey("bank_packages")
    private val keyAutoImport = booleanPreferencesKey("auto_import")
    private val keyBankRetentionDays = intPreferencesKey("bank_retention_days")
    private val keyAppLock = booleanPreferencesKey("app_lock")
    private val keySellFeePct = doublePreferencesKey("sell_fee_pct")

    // Investor profile (suitability layer)
    private val keyProfileSet = booleanPreferencesKey("profile_set")
    private val keyHorizon = stringPreferencesKey("profile_horizon")
    private val keyTolerance = stringPreferencesKey("profile_tolerance")
    private val keyRiskPerTrade = doublePreferencesKey("profile_risk_per_trade")
    private val keyMaxPosition = doublePreferencesKey("profile_max_position")
    private val keyMaxSector = doublePreferencesKey("profile_max_sector")

    companion object {
        const val DEFAULT_BANK_PACKAGES = "etihad,aletihad"
        const val DEFAULT_BANK_RETENTION_DAYS = 90
    }

    val bankPackages: Flow<String> = context.dataStore.data
        .map { it[keyBankPackages] ?: DEFAULT_BANK_PACKAGES }

    val autoImport: Flow<Boolean> = context.dataStore.data
        .map { it[keyAutoImport] ?: false }

    /** Days raw bank-notification captures are kept before deletion; 0 = keep forever. */
    val bankRetentionDays: Flow<Int> = context.dataStore.data
        .map { it[keyBankRetentionDays] ?: DEFAULT_BANK_RETENTION_DAYS }

    val appLock: Flow<Boolean> = context.dataStore.data
        .map { it[keyAppLock] ?: false }

    /** Estimated selling cost (broker fee + tax) as % of proceeds, used by sell targets. */
    val sellFeePct: Flow<Double> = context.dataStore.data
        .map { it[keySellFeePct] ?: 0.0 }

    val investorProfile: Flow<InvestorProfile> = context.dataStore.data.map { p ->
        InvestorProfile(
            configured = p[keyProfileSet] ?: false,
            horizon = p[keyHorizon] ?: InvestorProfile.DEFAULT.horizon,
            riskTolerance = p[keyTolerance] ?: InvestorProfile.DEFAULT.riskTolerance,
            riskPerTradePct = p[keyRiskPerTrade] ?: InvestorProfile.DEFAULT.riskPerTradePct,
            maxPositionPct = p[keyMaxPosition] ?: InvestorProfile.DEFAULT.maxPositionPct,
            maxSectorPct = p[keyMaxSector] ?: InvestorProfile.DEFAULT.maxSectorPct
        )
    }

    suspend fun setInvestorProfile(profile: InvestorProfile) {
        context.dataStore.edit {
            it[keyProfileSet] = true
            it[keyHorizon] = profile.horizon
            it[keyTolerance] = profile.riskTolerance
            it[keyRiskPerTrade] = profile.riskPerTradePct.coerceIn(0.25, 5.0)
            it[keyMaxPosition] = profile.maxPositionPct.coerceIn(5.0, 100.0)
            it[keyMaxSector] = profile.maxSectorPct.coerceIn(10.0, 100.0)
        }
    }

    suspend fun setBankPackages(value: String) {
        context.dataStore.edit { it[keyBankPackages] = value }
    }

    suspend fun setAutoImport(value: Boolean) {
        context.dataStore.edit { it[keyAutoImport] = value }
    }

    suspend fun setBankRetentionDays(value: Int) {
        context.dataStore.edit { it[keyBankRetentionDays] = value.coerceIn(0, 3650) }
    }

    suspend fun setAppLock(value: Boolean) {
        context.dataStore.edit { it[keyAppLock] = value }
    }

    suspend fun setSellFeePct(value: Double) {
        context.dataStore.edit { it[keySellFeePct] = value.coerceIn(0.0, 10.0) }
    }

    // ---- Wealth section inputs (0.0 = not configured yet) -------------------

    private val keyWealthBase = doublePreferencesKey("wealth_base")
    private val keyWealthTarget = doublePreferencesKey("wealth_target")

    val wealthBase: Flow<Double> = context.dataStore.data.map { it[keyWealthBase] ?: 0.0 }

    val wealthTarget: Flow<Double> = context.dataStore.data.map { it[keyWealthTarget] ?: 0.0 }

    suspend fun setWealthInputs(base: Double, target: Double) {
        context.dataStore.edit {
            it[keyWealthBase] = base
            it[keyWealthTarget] = target
        }
    }
}
