package com.aurum.invest.data.repo

import com.aurum.invest.analytics.NewsSentiment
import com.aurum.invest.core.Dates
import com.aurum.invest.data.db.CacheDao
import com.aurum.invest.data.db.CacheEntity
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.NewsFeed
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.remote.NewsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Symbol news from Google News RSS, enriched with sentiment and the same-day
 * price impact, cached as JSON under `"news:SYM"`. Never throws to callers.
 */
class NewsRepository(private val cacheDao: CacheDao) {

    private val client = NewsClient()

    /**
     * Last-5-days news for [symbol] with PROVENANCE, newest first, max 20
     * items. [candles] are the symbol's daily candles, used to compute each
     * item's same-day move. Serves fresh cache under [maxAgeMs]; on network
     * failure serves stale cache as STALE, or FAILED when nothing is cached.
     * An empty FRESH result means the feed was reached and verified empty —
     * never "we couldn't check".
     */
    suspend fun getNewsFeed(
        symbol: String,
        candles: List<Candle>,
        maxAgeMs: Long = 1_800_000L
    ): NewsFeed = withContext(Dispatchers.IO) {
        val key = "news:$symbol"
        try {
            val now = System.currentTimeMillis()
            val cached = try {
                cacheDao.get(key)
            } catch (_: Exception) {
                null
            }
            if (cached != null && now - cached.updatedAt <= maxAgeMs) {
                return@withContext NewsFeed(fromJson(cached.json), FeedStatus.FRESH, cached.updatedAt)
            }

            val raw = client.fetchNews(symbol)
            if (raw == null) {
                // Fetch FAILED: stale cache is better than nothing, but must say so.
                return@withContext if (cached != null) {
                    NewsFeed(fromJson(cached.json), FeedStatus.STALE, cached.updatedAt)
                } else {
                    NewsFeed.FAILED
                }
            }

            val cutoff = now - FIVE_DAYS_MS
            val items = raw
                .filter { it.publishedAt in (cutoff..now + ONE_DAY_MS) }
                .sortedByDescending { it.publishedAt }
                .take(MAX_ITEMS)
                .map { r ->
                    NewsItem(
                        id = stableId(r.link),
                        symbol = symbol,
                        title = r.title,
                        source = r.source,
                        url = r.link,
                        publishedAt = r.publishedAt,
                        sentiment = NewsSentiment.score(r.title),
                        priceImpactPct = priceImpact(r.publishedAt, candles)
                    )
                }
                .let { NewsSentiment.dedupe(it) }

            try {
                cacheDao.put(CacheEntity(key = key, json = toJson(items), updatedAt = now))
            } catch (_: Exception) {
                // Cache write failure is non-fatal.
            }
            NewsFeed(items, FeedStatus.FRESH, now)
        } catch (_: Exception) {
            try {
                cacheDao.get(key)?.let { NewsFeed(fromJson(it.json), FeedStatus.STALE, it.updatedAt) }
                    ?: NewsFeed.FAILED
            } catch (_: Exception) {
                NewsFeed.FAILED
            }
        }
    }

    /**
     * Items-only view of [getNewsFeed] for scoring engines. A FAILED fetch
     * looks like an empty list here, so anything user-facing must use
     * [getNewsFeed] and say which of the two it got.
     */
    suspend fun getNews(
        symbol: String,
        candles: List<Candle>,
        maxAgeMs: Long = 1_800_000L
    ): List<NewsItem> = getNewsFeed(symbol, candles, maxAgeMs).items

    /**
     * News for an arbitrary search [query] (sector themes, insider-buying
     * sweeps, institutional flow). Cached under `"topic:[cacheKey]"`. Items
     * carry sentiment but no price impact; [tag] fills NewsItem.symbol so the
     * UI can label the row. Never throws; empty list on total failure.
     */
    suspend fun getTopicNews(
        query: String,
        cacheKey: String,
        tag: String = "",
        maxAgeMs: Long = 3_600_000L,
        maxItems: Int = 12
    ): List<NewsItem> = withContext(Dispatchers.IO) {
        val key = "topic:$cacheKey"
        try {
            val now = System.currentTimeMillis()
            val cached = try {
                cacheDao.get(key)
            } catch (_: Exception) {
                null
            }
            if (cached != null && now - cached.updatedAt <= maxAgeMs) {
                return@withContext fromJson(cached.json)
            }
            val raw = client.fetchQuery(query)
            if (raw == null) {
                return@withContext cached?.let { fromJson(it.json) } ?: emptyList()
            }
            val items = raw
                .sortedByDescending { it.publishedAt }
                .take(maxItems)
                .map { r ->
                    NewsItem(
                        id = stableId(r.link),
                        symbol = tag,
                        title = r.title,
                        source = r.source,
                        url = r.link,
                        publishedAt = r.publishedAt,
                        sentiment = NewsSentiment.score(r.title),
                        priceImpactPct = null
                    )
                }
            try {
                cacheDao.put(CacheEntity(key = key, json = toJson(items), updatedAt = now))
            } catch (_: Exception) {
                // Cache write failure is non-fatal.
            }
            items
        } catch (_: Exception) {
            try {
                cacheDao.get(key)?.let { fromJson(it.json) } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Intraday move of the trading day the item was published on:
     * ((close - open) / open) * 100 for the candle whose date matches
     * (via [Dates.sameDay]); null when no candle matches (weekend/holiday).
     */
    private fun priceImpact(publishedAt: Long, candles: List<Candle>): Double? {
        val candle = candles.firstOrNull { Dates.sameDay(it.ts, publishedAt) } ?: return null
        if (candle.open == 0.0) return null
        return (candle.close - candle.open) / candle.open * 100.0
    }

    /** Stable 64-bit polynomial hash of the link, hex-encoded. */
    private fun stableId(link: String): String {
        var h = 1125899906842597L
        for (c in link) {
            h = 31 * h + c.code
        }
        return h.toULong().toString(16)
    }

    private fun toJson(items: List<NewsItem>): String {
        val arr = JSONArray()
        for (n in items) {
            val o = JSONObject()
            o.put("id", n.id)
            o.put("symbol", n.symbol)
            o.put("title", n.title)
            o.put("source", n.source)
            o.put("url", n.url)
            o.put("publishedAt", n.publishedAt)
            o.put("sentiment", n.sentiment)
            val impact = n.priceImpactPct
            if (impact != null) o.put("priceImpactPct", impact)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun fromJson(json: String): List<NewsItem> = try {
        val arr = JSONArray(json)
        val out = ArrayList<NewsItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val impact = if (o.has("priceImpactPct")) o.optDouble("priceImpactPct") else Double.NaN
            out.add(
                NewsItem(
                    id = o.optString("id"),
                    symbol = o.optString("symbol"),
                    title = o.optString("title"),
                    source = o.optString("source"),
                    url = o.optString("url"),
                    publishedAt = o.optLong("publishedAt"),
                    sentiment = o.optInt("sentiment"),
                    priceImpactPct = if (impact.isNaN()) null else impact
                )
            )
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val MAX_ITEMS = 20
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        const val FIVE_DAYS_MS = 5L * ONE_DAY_MS
    }
}
