package com.aurum.invest.data.remote

import com.aurum.invest.analytics.KaratPrice
import com.aurum.invest.analytics.SyndicateList
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the Jordan gold price list. The Jewellers' Syndicate publishes its
 * list to shops, not as an API; gold-price-today.com republishes it within
 * minutes as a page with the per-gram sell and buy prices for every karat and
 * both liras. Never throws — a failure is a null, and the caller falls back
 * to the last list it saved.
 */
class JordanGoldClient {

    suspend fun fetchList(now: Long = System.currentTimeMillis()): SyndicateList? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(SOURCE_URL)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36")
                    .header("Accept-Language", "ar,en;q=0.8")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val html = response.body?.string() ?: return@withContext null
                    JordanGoldParser.parse(html, now)
                }
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        const val SOURCE_URL = "https://gold-price-today.com/jordan/"
        const val SOURCE_LABEL = "gold-price-today.com/jordan"

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Pure parser for the republished Syndicate list. Works on the page's text
 * (tags stripped), so a styling change on the site does not break it; only a
 * change of wording would.
 */
object JordanGoldParser {

    private const val DINAR = "دينار"

    /** "عيار 21 81.400 دينار 77.600 دينار" — the sell/buy table rows. */
    private val KARAT_ROW = Regex("عيار (24|22|21|18) ([\\d,]+\\.\\d+) $DINAR ([\\d,]+\\.\\d+) $DINAR")
    private val RASHADI_ROW = Regex("الليرة الرشادي(?:ة)? ([\\d,]+\\.\\d+) $DINAR ([\\d,]+\\.\\d+) $DINAR")
    private val ENGLISH_ROW = Regex("الليرة ال[إا]نجليزي(?:ة)? ([\\d,]+\\.\\d+) $DINAR ([\\d,]+\\.\\d+) $DINAR")

    /** The prose fallback: "سعر بيع غرام الذهب عيار 24 اليوم 93.400 دينار، وسعر الشراء 89.700 دينار". */
    private val KARAT_PROSE = Regex(
        "سعر بيع غرام الذهب عيار (24|22|21|18) اليوم ([\\d,]+\\.\\d+) $DINAR[،,] وسعر الشراء ([\\d,]+\\.\\d+) $DINAR"
    )

    private val MONTHS = listOf(
        "كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
        "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"
    )

    /** "السبت 24 تشرين الأول، 10:15 صباحًا" — when the Syndicate last changed the list. */
    private val LISTED_AT = Regex(
        "(\\d{1,2}) (" + MONTHS.joinToString("|") + ")[،,] (\\d{1,2}):(\\d{2}) (صباح|مساء)"
    )

    private val AMMAN: TimeZone = TimeZone.getTimeZone("Asia/Amman")

    fun parse(html: String, now: Long): SyndicateList? {
        val text = toText(html)
        val karats = HashMap<Int, KaratPrice>()
        for (m in KARAT_ROW.findAll(text)) {
            val k = m.groupValues[1].toInt()
            if (k in karats) continue // the first table is the live list
            line(k, m.groupValues[2], m.groupValues[3])?.let { karats[k] = it }
        }
        if (karats.isEmpty()) {
            for (m in KARAT_PROSE.findAll(text)) {
                val k = m.groupValues[1].toInt()
                if (k in karats) continue
                line(k, m.groupValues[2], m.groupValues[3])?.let { karats[k] = it }
            }
        }
        val rashadi = RASHADI_ROW.find(text)?.let { line(21, it.groupValues[1], it.groupValues[2], perPiece = true) }
        val english = ENGLISH_ROW.find(text)?.let { line(22, it.groupValues[1], it.groupValues[2], perPiece = true) }
        // A list without 21K — what most of Jordan buys — is not a list.
        if (karats[21] == null) return null
        return SyndicateList(
            listedAt = listedAt(text, now),
            karats = karats,
            rashadi = rashadi,
            english = english,
            fetchedAt = now
        )
    }

    private fun line(karat: Int, sell: String, buy: String, perPiece: Boolean = false): KaratPrice? {
        val s = sell.replace(",", "").toDoubleOrNull() ?: return null
        val b = buy.replace(",", "").toDoubleOrNull() ?: return null
        val range = if (perPiece) 50.0..10_000.0 else 5.0..1_000.0
        // A shop never buys back above its own sell price; anything else is a misread.
        if (s !in range || b !in range || b > s) return null
        return KaratPrice(karat, s, b)
    }

    /** Epoch ms of the list's last change, Amman time; 0 when the page didn't say. */
    fun listedAt(text: String, now: Long): Long {
        val anchor = text.indexOf("آخر تغيير")
        val m = (if (anchor >= 0) LISTED_AT.find(text, anchor) else null)
            ?: LISTED_AT.find(text)
            ?: return 0L
        val day = m.groupValues[1].toInt()
        val month = MONTHS.indexOf(m.groupValues[2])
        var hour = m.groupValues[3].toInt() % 12
        val minute = m.groupValues[4].toInt()
        if (m.groupValues[5] == "مساء") hour += 12
        val c = Calendar.getInstance(AMMAN)
        c.timeInMillis = now
        val year = c.get(Calendar.YEAR)
        c.clear()
        c.set(year, month, day, hour, minute, 0)
        // The page gives no year; a date ahead of now belongs to last year.
        if (c.timeInMillis > now + 86_400_000L) c.set(Calendar.YEAR, year - 1)
        return c.timeInMillis
    }

    fun toText(html: String): String =
        html.replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
}
