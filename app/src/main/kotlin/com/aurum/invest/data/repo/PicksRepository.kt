package com.aurum.invest.data.repo

import com.aurum.invest.analytics.WeeklyPicker
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.PicksDao
import com.aurum.invest.data.db.WeeklyPickEntity
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
    private val market: MarketRepository
) {

    companion object {
        /** Budget (under-$25) picks share the weekly_picks table under a suffixed week key. */
        const val BUDGET_SUFFIX = ":U25"
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
