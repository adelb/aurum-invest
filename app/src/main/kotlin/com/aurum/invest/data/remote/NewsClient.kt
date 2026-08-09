package com.aurum.invest.data.remote

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One raw RSS entry from the Google News feed, before enrichment. */
data class RssNewsItem(
    val title: String,
    val link: String,
    val publishedAt: Long,
    val source: String
)

/**
 * Fetches and parses the Google News RSS search feed for a stock symbol.
 * Never throws: any network or parse failure yields an empty list.
 */
class NewsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Fetch + parse the feed for [symbol]. Empty list on any failure. */
    suspend fun fetchNews(symbol: String): List<RssNewsItem> =
        fetchFeed(symbol + "+stock+when:5d")

    /**
     * Fetch + parse the feed for an arbitrary search [query] (spaces allowed;
     * they are encoded). Window defaults to the last 7 days.
     */
    suspend fun fetchQuery(query: String, windowDays: Int = 7): List<RssNewsItem> =
        fetchFeed(
            query.trim().replace(Regex("\\s+"), "+") + "+when:${windowDays}d"
        )

    private suspend fun fetchFeed(encodedQuery: String): List<RssNewsItem> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://news.google.com/rss/search" +
                    "?q=" + encodedQuery + "&hl=en-US&gl=US&ceid=US:en"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body ?: return@withContext emptyList()
                    parseRss(body.byteStream())
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** Parse an RSS 2.0 stream; returns every well-formed `<item>` seen before any error. */
    private fun parseRss(stream: InputStream): List<RssNewsItem> {
        val items = mutableListOf<RssNewsItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            var inItem = false
            var title = ""
            var link = ""
            var pubDate = ""
            var source = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "item" -> {
                            inItem = true
                            title = ""; link = ""; pubDate = ""; source = ""
                        }
                        "title" -> if (inItem) title = readText(parser)
                        "link" -> if (inItem) link = readText(parser)
                        "pubDate" -> if (inItem) pubDate = readText(parser)
                        "source" -> if (inItem) source = readText(parser)
                    }
                    XmlPullParser.END_TAG -> {
                        if (inItem && parser.name == "item") {
                            inItem = false
                            val ts = parsePubDate(pubDate)
                            if (title.isNotBlank() && link.isNotBlank() && ts != null) {
                                items.add(
                                    RssNewsItem(
                                        title = cleanTitle(title, source),
                                        link = link,
                                        publishedAt = ts,
                                        source = source
                                    )
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Keep whatever was parsed before the failure.
        }
        return items
    }

    /** Collect the text content of the current element; leaves the parser on its end tag. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event == XmlPullParser.TEXT || event == XmlPullParser.ENTITY_REF) {
            sb.append(parser.text ?: "")
            event = parser.next()
        }
        return sb.toString().trim()
    }

    /**
     * RFC-822 date parsing ("Fri, 08 Aug 2026 13:45:00 GMT"), with a fallback
     * pattern without the zone suffix. Null when unparseable.
     */
    private fun parsePubDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val parsed = SimpleDateFormat(pattern, Locale.US).parse(text)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // Try the next pattern.
            }
        }
        return null
    }

    /** Google News titles end with " - Publisher"; strip it when the `<source>` matches. */
    private fun cleanTitle(title: String, source: String): String {
        if (source.isBlank()) return title
        val suffix = " - $source"
        return if (title.endsWith(suffix, ignoreCase = true)) {
            title.dropLast(suffix.length).trim()
        } else {
            title
        }
    }
}
