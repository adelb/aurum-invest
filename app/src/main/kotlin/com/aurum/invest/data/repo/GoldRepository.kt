package com.aurum.invest.data.repo

import com.aurum.invest.analytics.GoldKind
import com.aurum.invest.analytics.GoldLot
import com.aurum.invest.analytics.KaratPrice
import com.aurum.invest.analytics.ShopQuote
import com.aurum.invest.analytics.SyndicateList
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.db.GoldDao
import com.aurum.invest.data.db.GoldLotEntity
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.Quote
import com.aurum.invest.data.remote.JordanGoldClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** The Syndicate list with where it came from — fresh, the last saved one, or nothing. */
data class GoldListFeed(val list: SyndicateList?, val status: FeedStatus)

/**
 * The gold book: the user's purchases (Room), the Jordan Syndicate list
 * (fetched, cached in the key/value table), the user's own shop quote, and
 * world gold from COMEX for the live estimate and the market read.
 */
class GoldRepository(
    private val dao: GoldDao,
    private val cacheDao: CacheDao,
    private val market: MarketRepository,
    private val client: JordanGoldClient = JordanGoldClient()
) {
    private val listLock = Mutex()

    // ------------------------------------------------------------- the lots

    fun observeLots(): Flow<List<GoldLot>> = dao.observeAll().map { rows -> rows.map(::toLot) }

    fun observeLot(id: Long): Flow<GoldLot?> = dao.observe(id).map { it?.let(::toLot) }

    suspend fun lotsNow(): List<GoldLot> = dao.all().map(::toLot)

    suspend fun lot(id: Long): GoldLot? = dao.get(id)?.let(::toLot)

    /** Inserts a new lot (id 0) or replaces an existing one; returns its id. */
    suspend fun save(lot: GoldLot): Long =
        if (lot.id == 0L) dao.insert(toEntity(lot)) else {
            dao.update(toEntity(lot))
            lot.id
        }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Books a sale at the price the shop actually paid, per unit. */
    suspend fun markSold(id: Long, soldAt: Long, pricePerUnit: Double) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(soldAt = soldAt, soldPricePerUnit = pricePerUnit))
    }

    suspend fun reopen(id: Long) {
        val row = dao.get(id) ?: return
        dao.update(row.copy(soldAt = null, soldPricePerUnit = null))
    }

    // ------------------------------------------------------ the price list

    /**
     * The current Syndicate list. A saved list younger than [maxAgeMs] is
     * served as is; otherwise the page is read again. When the read fails the
     * saved list is served as STALE — never a made-up price.
     */
    suspend fun list(maxAgeMs: Long = LIST_MAX_AGE_MS): GoldListFeed = listLock.withLock {
        val now = System.currentTimeMillis()
        val saved = readList()
        if (saved != null && now - saved.fetchedAt <= maxAgeMs && saved.worldAtList != null) {
            return@withLock GoldListFeed(saved, FeedStatus.FRESH)
        }
        val fresh = client.fetchList(now)
        if (fresh == null) {
            return@withLock GoldListFeed(saved, if (saved != null) FeedStatus.STALE else FeedStatus.FAILED)
        }
        // Same list as before: keep the anchor measured when it was first seen.
        val sameList = saved != null && saved.listedAt == fresh.listedAt &&
            saved.karats == fresh.karats
        val anchor = if (sameList && saved?.worldAtList != null) saved.worldAtList
        else worldAt(fresh.listedAt)
        val stored = fresh.copy(worldAtList = anchor)
        writeList(stored)
        GoldListFeed(stored, FeedStatus.FRESH)
    }

    /**
     * World gold when the list was published: the hourly COMEX bar that
     * contains [listedAt], else that day's close, else the price right now
     * (which makes the live estimate start at the list itself).
     */
    private suspend fun worldAt(listedAt: Long): Double? {
        if (listedAt > 0L) {
            val hourly = runCatching { market.getRangeCandles(WORLD_SYMBOL, "5d", "60m") }
                .getOrDefault(emptyList())
            hourly.lastOrNull { it.ts <= listedAt }
                ?.takeIf { listedAt - it.ts <= 3 * HOUR_MS }
                ?.let { return it.close }
            val daily = runCatching { market.getDailyCandles(WORLD_SYMBOL, 30) }
                .getOrDefault(emptyList())
            daily.lastOrNull { it.ts <= listedAt }?.let { return it.close }
        }
        return runCatching { market.getQuote(WORLD_SYMBOL)?.price }.getOrNull()
    }

    // ------------------------------------------------------- shop quote

    suspend fun shopQuote(): ShopQuote? = try {
        cacheDao.get(KEY_SHOP)?.json?.takeIf { it.isNotBlank() }?.let { raw ->
            val o = JSONObject(raw)
            ShopQuote(o.getInt("karat"), o.getDouble("buy"), o.getLong("forListAt"))
        }
    } catch (_: Exception) {
        null
    }

    suspend fun setShopQuote(quote: ShopQuote?) {
        val json = quote?.let {
            JSONObject()
                .put("karat", it.karat)
                .put("buy", it.buyPerGram)
                .put("forListAt", it.forListAt)
                .toString()
        } ?: ""
        runCatching { cacheDao.put(CacheEntity(KEY_SHOP, json, System.currentTimeMillis())) }
    }

    // ------------------------------------------------------- the market

    suspend fun worldQuote(maxAgeMs: Long = 60_000L): Quote? =
        runCatching { market.getQuote(WORLD_SYMBOL, maxAgeMs) }.getOrNull()

    suspend fun worldDaily(days: Int = 730): List<Candle> =
        runCatching { market.getDailyCandles(WORLD_SYMBOL, days) }.getOrDefault(emptyList())

    suspend fun spyDaily(days: Int = 730): List<Candle> =
        runCatching { market.getDailyCandles("SPY", days) }.getOrDefault(emptyList())

    // ------------------------------------------------------------ storage

    private suspend fun readList(): SyndicateList? = try {
        cacheDao.get(KEY_LIST)?.json?.let(::listFromJson)
    } catch (_: Exception) {
        null
    }

    private suspend fun writeList(list: SyndicateList) {
        runCatching {
            cacheDao.put(CacheEntity(KEY_LIST, listToJson(list), System.currentTimeMillis()))
        }
    }

    companion object {
        /** COMEX gold futures — Yahoo has no XAUUSD spot symbol. */
        const val WORLD_SYMBOL = "GC=F"
        const val LIST_MAX_AGE_MS = 30L * 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val KEY_LIST = "gold:syndicate:v1"
        private const val KEY_SHOP = "gold:shopquote:v1"

        fun toLot(e: GoldLotEntity): GoldLot = GoldLot(
            id = e.id,
            kind = GoldKind.of(e.kind),
            karat = e.karat,
            qty = e.qty,
            pricePerUnit = e.pricePerUnit,
            makingPerUnit = e.makingPerUnit,
            boughtAt = e.boughtAt,
            name = e.name,
            note = e.note,
            soldAt = e.soldAt,
            soldPricePerUnit = e.soldPricePerUnit
        )

        fun toEntity(l: GoldLot): GoldLotEntity = GoldLotEntity(
            id = l.id,
            kind = l.kind.name,
            karat = l.kind.fixedKarat ?: l.karat,
            qty = l.qty,
            pricePerUnit = l.pricePerUnit,
            makingPerUnit = l.makingPerUnit,
            boughtAt = l.boughtAt,
            name = l.name,
            note = l.note,
            soldAt = l.soldAt,
            soldPricePerUnit = l.soldPricePerUnit
        )

        fun listToJson(l: SyndicateList): String {
            val o = JSONObject()
            o.put("listedAt", l.listedAt)
            o.put("fetchedAt", l.fetchedAt)
            l.worldAtList?.let { o.put("worldAtList", it) }
            val k = JSONObject()
            for ((karat, p) in l.karats) {
                k.put(karat.toString(), JSONObject().put("sell", p.sell).put("buy", p.buy))
            }
            o.put("karats", k)
            l.rashadi?.let { o.put("rashadi", JSONObject().put("sell", it.sell).put("buy", it.buy)) }
            l.english?.let { o.put("english", JSONObject().put("sell", it.sell).put("buy", it.buy)) }
            return o.toString()
        }

        fun listFromJson(raw: String): SyndicateList? = try {
            val o = JSONObject(raw)
            val k = o.getJSONObject("karats")
            val karats = HashMap<Int, KaratPrice>()
            for (key in k.keys()) {
                val p = k.getJSONObject(key)
                karats[key.toInt()] = KaratPrice(key.toInt(), p.getDouble("sell"), p.getDouble("buy"))
            }
            fun piece(name: String, karat: Int): KaratPrice? =
                o.optJSONObject(name)?.let { KaratPrice(karat, it.getDouble("sell"), it.getDouble("buy")) }
            SyndicateList(
                listedAt = o.optLong("listedAt", 0L),
                karats = karats,
                rashadi = piece("rashadi", 21),
                english = piece("english", 22),
                fetchedAt = o.optLong("fetchedAt", 0L),
                worldAtList = if (o.has("worldAtList")) o.getDouble("worldAtList") else null
            )
        } catch (_: Exception) {
            null
        }
    }
}
