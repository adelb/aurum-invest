package com.aurum.invest.data.repo

import com.aurum.invest.data.db.AlertDirection
import com.aurum.invest.data.db.PriceAlertDao
import com.aurum.invest.data.db.PriceAlertEntity
import com.aurum.invest.data.model.Quote
import kotlinx.coroutines.flow.Flow

/**
 * User-defined price alerts (M1): watch any symbol for a cross above or below
 * a chosen level. A background worker checks them on a 15-minute cadence;
 * each alert fires once and keeps its trigger time as history.
 */
class AlertsRepository(private val dao: PriceAlertDao) {

    fun observeAll(): Flow<List<PriceAlertEntity>> = dao.observeAll()

    fun observeForSymbol(symbol: String): Flow<List<PriceAlertEntity>> =
        dao.observeForSymbol(symbol.trim().uppercase())

    suspend fun add(symbol: String, direction: String, threshold: Double, note: String = ""): Long =
        try {
            dao.insert(
                PriceAlertEntity(
                    symbol = symbol.trim().uppercase(),
                    direction = direction,
                    threshold = threshold,
                    note = note,
                    active = true,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            -1L
        }

    suspend fun delete(alert: PriceAlertEntity) {
        try {
            dao.delete(alert)
        } catch (_: Exception) {
        }
    }

    suspend fun activeAlerts(): List<PriceAlertEntity> = try {
        dao.getActive()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Checks [alerts] against [quotes]; deactivates and returns the ones that
     * fired so the caller can notify.
     */
    suspend fun markTriggered(alerts: List<PriceAlertEntity>, quotes: Map<String, Quote>): List<PriceAlertEntity> {
        val fired = ArrayList<PriceAlertEntity>()
        for (alert in alerts) {
            val price = quotes[alert.symbol]?.price ?: continue
            val hit = when (alert.direction) {
                AlertDirection.ABOVE -> price >= alert.threshold
                AlertDirection.BELOW -> price <= alert.threshold
                else -> false
            }
            if (hit) {
                val updated = alert.copy(
                    active = false,
                    triggeredAt = System.currentTimeMillis(),
                    priceAtTrigger = price
                )
                try {
                    dao.update(updated)
                    fired.add(updated)
                } catch (_: Exception) {
                }
            }
        }
        return fired
    }
}
