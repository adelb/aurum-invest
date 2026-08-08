package com.aurum.invest.data.repo

import com.aurum.invest.data.db.WatchDao
import com.aurum.invest.data.db.WatchItemEntity
import kotlinx.coroutines.flow.Flow

class WatchRepository(private val watchDao: WatchDao) {

    fun observeAll(): Flow<List<WatchItemEntity>> = watchDao.observeAll()

    suspend fun getAll(): List<WatchItemEntity> = watchDao.getAll()

    suspend fun add(symbol: String, name: String = "") = watchDao.upsert(
        WatchItemEntity(
            symbol = symbol.trim().uppercase(),
            name = name,
            pinned = false,
            addedAt = System.currentTimeMillis()
        )
    )

    suspend fun remove(symbol: String) = watchDao.remove(symbol)

    suspend fun setPinned(symbol: String, pinned: Boolean) = watchDao.setPinned(symbol, pinned)

    suspend fun isWatched(symbol: String): Boolean = watchDao.get(symbol.uppercase()) != null
}
