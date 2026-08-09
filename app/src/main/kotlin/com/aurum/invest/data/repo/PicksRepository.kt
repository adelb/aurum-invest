package com.aurum.invest.data.repo

import com.aurum.invest.analytics.DailyPicker
import com.aurum.invest.analytics.WeeklyPicker
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.db.PicksDao
import com.aurum.invest.data.db.WeeklyPickEntity
import com.aurum.invest.data.model.DailyPick
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

        /** Daily picks live in the JSON cache, one entry per local date. */
        private const val DAILY_KEY_PREFIX = "dailypicks:"
    }

    // ---- daily picks (cache-backed, one set per calendar day) ---------------------

    private fun dailyKey(): String = DAILY_KEY_PREFIX + Dates.todayIso()

    /** Today's stored daily picks (no computation). Empty on any failure. */
    suspend fun getDaily(): List<DailyPick> = try {
        cacheDao.get(dailyKey())?.let { DailyPicker.fromJson(it.json) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Today's daily picks, computing + storing them when none exist yet. */
    suspend fun ensureDaily(): List<DailyPick> {
        val existing = getDaily()
        if (existing.isNotEmpty()) return existing
        return recomputeDaily()
    }

    /** Recomputes today's daily picks from fresh market data and replaces the stored set. */
    suspend fun recomputeDaily(): List<DailyPick> {
        return try {
            val picks = DailyPicker(market, news).computePicks(Dates.todayIso())
            if (picks.isNotEmpty()) {
                cacheDao.put(
                    CacheEntity(
                        key = dailyKey(),
                        json = DailyPicker.toJson(picks),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            picks
        } catch (_: Exception) {
            getDaily()
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
            val picks = WeeklyPicker(market)
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
            val picks = WeeklyPicker(market).computePicks(weekStart)
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
