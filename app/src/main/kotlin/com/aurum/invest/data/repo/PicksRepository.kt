package com.aurum.invest.data.repo

import com.aurum.invest.analytics.EntryPicker
import com.aurum.invest.analytics.IntradayPick
import com.aurum.invest.analytics.IntradayPicker
import com.aurum.invest.analytics.PowerPicker
import com.aurum.invest.analytics.PreMarketPick
import com.aurum.invest.analytics.PreMarketPicker
import com.aurum.invest.analytics.RelationGroup
import com.aurum.invest.analytics.RelationPicker
import com.aurum.invest.analytics.UFingerprintCache
import com.aurum.invest.analytics.UPatternEngine
import com.aurum.invest.analytics.UPick
import com.aurum.invest.analytics.WeeklyPicker
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.db.PicksDao
import com.aurum.invest.data.db.WeeklyPickEntity
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.WeeklyPick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Weekly picks persistence. The heavy scoring lives in [WeeklyPicker]; this
 * repository owns entity <-> model mapping and the "one set of picks per week"
 * storage rule (keyed by the ISO Monday date from [Dates.currentWeekStartIso]).
 *
 * Never throws to callers: failures fall back to whatever is already stored
 * (possibly an empty list).
 */
class PicksRepository(
    private val picksDao: PicksDao,
    private val market: MarketRepository,
    private val cacheDao: CacheDao,
    private val news: NewsRepository
) {

    companion object {
        /** Budget (under-$25) picks share the weekly_picks table under a suffixed week key. */
        const val BUDGET_SUFFIX = ":U25"

        /** U-pattern picks live in the JSON cache, one entry per local date. */
        private const val U_KEY_PREFIX = "upicks:"

        /** Symbols already notified as buy zones, one entry per local date. */
        private const val U_NOTIFIED_KEY_PREFIX = "unotified:"

        /** Per-symbol U fingerprints (3-day TTL enforced by the engine). */
        private const val U_FINGERPRINT_KEY_PREFIX = "ufp:"

        /** Pre-market picks: one entry per local date and profit target. */
        private const val PREMARKET_KEY_PREFIX = "premarket:"

        /** Open-session (intraday 2%) picks: one entry per local date and target. */
        private const val INTRADAY_KEY_PREFIX = "intraday:"

        /** Market-wide best-entry picks, one cached set per local date. */
        private const val ENTRY_KEY_PREFIX = "entrypicks:"

        /** Power-hour picks (buy 2:30-4:00 PM ET), one cached set per local date. */
        private const val POWER_KEY_PREFIX = "powerpicks:"

        /** First-party relation groups, one cached set per local date. */
        private const val RELATION_KEY_PREFIX = "relations:"
    }

    // ---- first-party relations (cache-backed, one set per calendar day) -----------

    private fun relationKey(): String = RELATION_KEY_PREFIX + Dates.todayIso()

    /** Today's stored relation groups (no computation). Empty on any failure. */
    suspend fun getRelations(): List<RelationGroup> = try {
        cacheDao.get(relationKey())?.let { RelationPicker.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Today's relation groups, recomputed when the stored set is older than
     * [maxAgeMs] (30 min) — the whole point is who is moving NOW.
     */
    suspend fun ensureRelations(maxAgeMs: Long = 1_800_000L): List<RelationGroup> {
        val entry = try {
            cacheDao.get(relationKey())
        } catch (_: Exception) {
            null
        }
        val stored = entry?.let { RelationPicker.fromJson(it.json) }.orEmpty()
        val fresh = entry != null &&
            System.currentTimeMillis() - entry.updatedAt <= maxAgeMs
        if (stored.isNotEmpty() && fresh) return stored
        return recomputeRelations().ifEmpty { stored }
    }

    /** Re-reads every relation group from fresh quotes and candles. */
    suspend fun recomputeRelations(): List<RelationGroup> {
        return try {
            val groups = RelationPicker(market).computeGroups()
            if (groups.isNotEmpty()) {
                cacheDao.put(
                    CacheEntity(
                        key = relationKey(),
                        json = RelationPicker.toJson(groups),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            groups
        } catch (_: Exception) {
            getRelations()
        }
    }

    // ---- power-hour picks (cache-backed, one set per calendar day) ----------------

    private fun powerKey(): String = POWER_KEY_PREFIX + Dates.todayIso()

    /** Today's stored power-hour picks (no computation). Empty on any failure. */
    suspend fun getPower(): List<PowerPick> = try {
        cacheDao.get(powerKey())?.let { PowerPicker.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Today's power-hour picks, recomputed when the stored set is older than
     * [maxAgeMs] (15 min). A closing-strength read from 3 AM is not an answer
     * to what is closing strong at 3:45 PM.
     */
    suspend fun ensurePower(maxAgeMs: Long = 900_000L): List<PowerPick> {
        val entry = try {
            cacheDao.get(powerKey())
        } catch (_: Exception) {
            null
        }
        val stored = entry?.let { PowerPicker.fromJson(it.json) }.orEmpty()
        val fresh = entry != null &&
            System.currentTimeMillis() - entry.updatedAt <= maxAgeMs
        if (stored.isNotEmpty() && fresh) return stored
        return recomputePower().ifEmpty { stored }
    }

    /** Re-runs the power-hour scan and replaces today's stored set. */
    suspend fun recomputePower(): List<PowerPick> {
        return try {
            val picks = PowerPicker(market, news).computePicks(Dates.todayIso())
            if (picks.isNotEmpty()) {
                cacheDao.put(
                    CacheEntity(
                        key = powerKey(),
                        json = PowerPicker.toJson(picks),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            picks
        } catch (_: Exception) {
            getPower()
        }
    }

    // ---- pre-market picks (cache-backed; keyed by day AND target) ----------------

    // The target is part of the key: change the goal and you are asking a
    // different question, so the stored answer must not be reused.
    private fun preMarketKey(targetPct: Double): String =
        PREMARKET_KEY_PREFIX + Dates.todayIso() + ":" + String.format(
            java.util.Locale.US, "%.2f", targetPct
        )

    /** Today's stored pre-market picks for [targetPct] (no computation). */
    suspend fun getPreMarket(targetPct: Double): List<PreMarketPick> = try {
        cacheDao.get(preMarketKey(targetPct))
            ?.let { PreMarketPicker.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Today's pre-market picks, recomputed when the stored set is older than
     * [maxAgeMs]. Unlike the other lists, this one is time-critical: prices
     * move throughout the pre-market window, so a set computed at 5 AM is not
     * an answer to what is happening at 9 AM. Two minutes by default.
     */
    suspend fun ensurePreMarket(
        targetPct: Double,
        maxAgeMs: Long = 120_000L
    ): List<PreMarketPick> {
        val entry = try {
            cacheDao.get(preMarketKey(targetPct))
        } catch (_: Exception) {
            null
        }
        val stored = entry?.let { PreMarketPicker.fromJson(it.json) }.orEmpty()
        val fresh = entry != null &&
            System.currentTimeMillis() - entry.updatedAt <= maxAgeMs
        if (stored.isNotEmpty() && fresh) return stored
        val recomputed = recomputePreMarket(targetPct)
        return recomputed.ifEmpty { stored }
    }

    /**
     * Re-runs the pre-market scan for [targetPct]. Pre-market prices move
     * continuously, so this is what the refresh button calls.
     */
    suspend fun recomputePreMarket(targetPct: Double): List<PreMarketPick> {
        return try {
            val picks = PreMarketPicker(market, news).computePicks(Dates.todayIso(), targetPct)
            if (picks.isNotEmpty()) {
                cacheDao.put(
                    CacheEntity(
                        key = preMarketKey(targetPct),
                        json = PreMarketPicker.toJson(picks),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            picks
        } catch (_: Exception) {
            getPreMarket(targetPct)
        }
    }

    // ---- open-session (intraday 2%) picks (cache-backed; keyed by day AND target) --

    private fun intradayKey(targetPct: Double): String =
        INTRADAY_KEY_PREFIX + Dates.todayIso() + ":" + String.format(
            java.util.Locale.US, "%.2f", targetPct
        )

    /** Today's stored open-session picks for [targetPct] (no computation). */
    suspend fun getIntraday(targetPct: Double): List<IntradayPick> = try {
        cacheDao.get(intradayKey(targetPct))
            ?.let { IntradayPicker.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Today's open-session picks, recomputed when the stored set is older
     * than [maxAgeMs]. Time-critical like the pre-market list: the whole
     * point is what can still move from NOW, so two minutes by default.
     * Outside the regular session the engine returns empty and whatever was
     * stored (today's last scan) is served instead.
     */
    suspend fun ensureIntraday(
        targetPct: Double,
        maxAgeMs: Long = 120_000L
    ): List<IntradayPick> {
        val entry = try {
            cacheDao.get(intradayKey(targetPct))
        } catch (_: Exception) {
            null
        }
        val stored = entry?.let { IntradayPicker.fromJson(it.json) }.orEmpty()
        val fresh = entry != null &&
            System.currentTimeMillis() - entry.updatedAt <= maxAgeMs
        if (stored.isNotEmpty() && fresh) return stored
        val recomputed = recomputeIntraday(targetPct)
        return recomputed.ifEmpty { stored }
    }

    /** Re-runs the open-session scan for [targetPct] from fresh prints. */
    suspend fun recomputeIntraday(targetPct: Double): List<IntradayPick> {
        return try {
            val picks = IntradayPicker(market, news).computePicks(Dates.todayIso(), targetPct)
            if (picks.isNotEmpty()) {
                cacheDao.put(
                    CacheEntity(
                        key = intradayKey(targetPct),
                        json = IntradayPicker.toJson(picks),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            picks
        } catch (_: Exception) {
            getIntraday(targetPct)
        }
    }

    // ---- best-entry picks (cache-backed, one set per calendar day) ----------------

    private fun entryKey(): String = ENTRY_KEY_PREFIX + Dates.todayIso()

    /** Today's stored entry picks (no computation). Empty on any failure. */
    suspend fun getEntries(): List<EntryPick> = try {
        cacheDao.get(entryKey())?.let { EntryPicker.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Today's entry picks, recomputed when the stored set is older than
     * [maxAgeMs] (30 min) — entry limits priced off the morning are wrong by
     * the afternoon.
     */
    suspend fun ensureEntries(maxAgeMs: Long = 1_800_000L): List<EntryPick> {
        val entry = try {
            cacheDao.get(entryKey())
        } catch (_: Exception) {
            null
        }
        val stored = entry?.let { EntryPicker.fromJson(it.json) }.orEmpty()
        val fresh = entry != null &&
            System.currentTimeMillis() - entry.updatedAt <= maxAgeMs
        if (stored.isNotEmpty() && fresh) return stored
        return recomputeEntries().ifEmpty { stored }
    }

    /** Re-runs the market-wide entry scan and replaces today's stored set. */
    suspend fun recomputeEntries(): List<EntryPick> {
        return try {
            val picks = EntryPicker(market, news).computePicks(Dates.todayIso())
            if (picks.isNotEmpty()) {
                cacheDao.put(
                    CacheEntity(
                        key = entryKey(),
                        json = EntryPicker.toJson(picks),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            picks
        } catch (_: Exception) {
            getEntries()
        }
    }

    // ---- U-pattern picks (cache-backed, one set per calendar day) -----------------

    private fun uKey(): String = U_KEY_PREFIX + Dates.todayIso()
    private fun uNotifiedKey(): String = U_NOTIFIED_KEY_PREFIX + Dates.todayIso()

    /** Per-symbol fingerprint persistence handed to the engine. */
    private val uFingerprints = object : UFingerprintCache {
        override suspend fun read(symbol: String): Pair<String, Long>? = try {
            cacheDao.get(U_FINGERPRINT_KEY_PREFIX + symbol)?.let { it.json to it.updatedAt }
        } catch (_: Exception) {
            null
        }

        override suspend fun write(symbol: String, json: String) {
            try {
                cacheDao.put(
                    CacheEntity(
                        key = U_FINGERPRINT_KEY_PREFIX + symbol,
                        json = json,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {
                // cache write failure is non-fatal
            }
        }
    }

    private fun uEngine(): UPatternEngine = UPatternEngine(market, uFingerprints)

    /** Today's stored U-pattern picks (no computation). Empty on any failure. */
    suspend fun getUPattern(): List<UPick> = try {
        cacheDao.get(uKey())?.let { UPatternEngine.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Today's U-pattern picks. Missing -> full scan; present but with live
     * states older than [liveMaxAgeMs] (3 min) -> the cheap live re-read.
     * The fingerprint half of a pick is stable all day; only the live half
     * goes stale in minutes.
     */
    suspend fun ensureUPattern(liveMaxAgeMs: Long = 180_000L): List<UPick> {
        val entry = try {
            cacheDao.get(uKey())
        } catch (_: Exception) {
            null
        }
        val stored = entry?.let { UPatternEngine.fromJson(it.json) }.orEmpty()
        if (stored.isEmpty()) return recomputeUPattern()
        val fresh = System.currentTimeMillis() - entry!!.updatedAt <= liveMaxAgeMs
        return if (fresh) stored else refreshULive()
    }

    /** The full market rescan: screens, fingerprints, gates, live read. */
    suspend fun recomputeUPattern(): List<UPick> {
        return try {
            val picks = uEngine().compute(Dates.todayIso())
            if (picks.isNotEmpty()) storeU(picks)
            picks
        } catch (_: Exception) {
            getUPattern()
        }
    }

    /** Re-reads only today's live state for the stored picks and saves it back. */
    suspend fun refreshULive(): List<UPick> {
        val stored = getUPattern()
        if (stored.isEmpty()) return stored
        return try {
            val updated = uEngine().refreshLive(stored)
            storeU(updated)
            updated
        } catch (_: Exception) {
            stored
        }
    }

    private suspend fun storeU(picks: List<UPick>) {
        try {
            cacheDao.put(
                CacheEntity(
                    key = uKey(),
                    json = UPatternEngine.toJson(picks),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            // cache write failure is non-fatal
        }
    }

    /** Symbols already alerted as buy zones today — never notify one twice. */
    suspend fun uNotifiedToday(): Set<String> = try {
        cacheDao.get(uNotifiedKey())?.json
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    suspend fun markUNotified(symbols: Set<String>) {
        if (symbols.isEmpty()) return
        try {
            val merged = uNotifiedToday() + symbols
            cacheDao.put(
                CacheEntity(
                    key = uNotifiedKey(),
                    json = merged.joinToString(","),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            // best-effort — worst case a duplicate alert
        }
    }

    /** Live picks for the current week, ranked 1..10. */
    fun observeCurrentWeek(): Flow<List<WeeklyPick>> =
        picksDao.observeWeek(Dates.currentWeekStartIso())
            .map { entities -> entities.map { it.toModel() } }

    /** Live under-$25 picks for the current week. */
    fun observeBudgetWeek(): Flow<List<WeeklyPick>> =
        picksDao.observeWeek(Dates.currentWeekStartIso() + BUDGET_SUFFIX)
            .map { entities -> entities.map { it.toModel() } }

    /** Stored under-$25 picks for the current week (no computation). */
    suspend fun getBudgetWeek(): List<WeeklyPick> = try {
        picksDao.getWeek(Dates.currentWeekStartIso() + BUDGET_SUFFIX).map { it.toModel() }
    } catch (_: Exception) {
        emptyList()
    }

    /** Stored under-$25 picks, computing + storing them when empty. */
    suspend fun ensureBudgetWeek(): List<WeeklyPick> {
        val existing = getBudgetWeek()
        if (existing.isNotEmpty()) return existing
        return recomputeBudget()
    }

    /** Recomputes the current week's under-$25 picks and replaces the stored set. */
    suspend fun recomputeBudget(): List<WeeklyPick> {
        val key = Dates.currentWeekStartIso() + BUDGET_SUFFIX
        return try {
            val picks = WeeklyPicker(market, news)
                .computeBudgetPicks(Dates.currentWeekStartIso())
                .map { it.copy(weekStart = key) }
            if (picks.isNotEmpty()) {
                picksDao.clearWeek(key)
                picksDao.insertAll(picks.map { it.toEntity() })
            }
            picks
        } catch (_: Exception) {
            getBudgetWeek()
        }
    }

    /** Stored picks for the current week (no computation). */
    suspend fun getCurrentWeek(): List<WeeklyPick> = try {
        picksDao.getWeek(Dates.currentWeekStartIso()).map { it.toModel() }
    } catch (_: Exception) {
        emptyList()
    }

    /** Returns stored picks for the current week, computing + storing them when empty. */
    suspend fun ensureCurrentWeek(): List<WeeklyPick> {
        val existing = getCurrentWeek()
        if (existing.isNotEmpty()) return existing
        return recompute()
    }

    /** Recomputes the current week's picks and replaces the stored set. */
    suspend fun recompute(): List<WeeklyPick> {
        val weekStart = Dates.currentWeekStartIso()
        return try {
            val picks = WeeklyPicker(market, news).computePicks(weekStart)
            if (picks.isNotEmpty()) {
                picksDao.clearWeek(weekStart)
                picksDao.insertAll(picks.map { it.toEntity() })
            }
            picks
        } catch (_: Exception) {
            // Computation or storage failed — serve whatever is already stored.
            getCurrentWeek()
        }
    }

    // ---- entity <-> model mapping -------------------------------------------------

    private fun WeeklyPickEntity.toModel(): WeeklyPick = WeeklyPick(
        weekStart = weekStart,
        rank = rank,
        symbol = symbol,
        name = name,
        score = score,
        reason = reason,
        priceAtPick = priceAtPick
    )

    private fun WeeklyPick.toEntity(): WeeklyPickEntity = WeeklyPickEntity(
        weekStart = weekStart,
        rank = rank,
        symbol = symbol,
        name = name,
        score = score,
        reason = reason,
        priceAtPick = priceAtPick
    )
}
