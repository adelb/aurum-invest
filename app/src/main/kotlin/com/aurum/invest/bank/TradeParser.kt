package com.aurum.invest.bank

import com.aurum.invest.data.model.ParsedTrade
import com.aurum.invest.data.model.TradeSide
import org.json.JSONObject
import kotlin.math.round

/**
 * Extracts a trade from bank/broker notification text, English or Arabic
 * (Bank al Etihad Jordan style). Pure regex — no network, never throws.
 *
 * Examples it understands:
 *   "Bought 10 shares of AAPL at $150.25"
 *   "Buy executed: AAPL x10 @ 150.25"
 *   "Sold 5 shares of TSLA for USD 1,502.50"
 *   "شراء ١٠ أسهم من AAPL بسعر ١٥٠٫٢٥ دولار"
 *   "بيع ٥ أسهم TSLA بقيمة 1,200 د.أ"
 */
object TradeParser {

    /** Number with optional thousands separators: "1,502.50" or "150.25" or "10". */
    private const val NUM = """(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)"""

    private val BUY_EN = Regex("""\b(?:buy|buys|bought|purchase[ds]?|purchasing)\b""", RegexOption.IGNORE_CASE)
    private val SELL_EN = Regex("""\b(?:sell|sells|sold|selling)\b""", RegexOption.IGNORE_CASE)

    private val SHARES_BEFORE_WORD = Regex("""$NUM\s*(?:shares?|units?|stocks?|أسهم|اسهم|سهم)""", RegexOption.IGNORE_CASE)
    private val SHARES_AFTER_X = Regex("""\bx\s*$NUM""", RegexOption.IGNORE_CASE)
    private val SHARES_BEFORE_X = Regex("""$NUM\s*x\b""", RegexOption.IGNORE_CASE)

    private val PRICE = Regex(
        """(?:@|\bat\b|بسعر)\s*:?\s*(?:USD|JOD|EUR|GBP|SAR|AED|د\.أ|\$|€|£)?\s*$NUM""",
        RegexOption.IGNORE_CASE
    )
    private val AMOUNT = Regex(
        """(?:\bfor\b|\btotal\b|بقيمة|بمبلغ)\s*:?\s*(?:USD|JOD|EUR|GBP|SAR|AED|د\.أ|\$|€|£)?\s*$NUM""",
        RegexOption.IGNORE_CASE
    )

    private val CURRENCY_CODE = Regex("""\b(USD|JOD|EUR|GBP|SAR|AED)\b""", RegexOption.IGNORE_CASE)

    /** Contextual symbol patterns tried first — much less likely to hit a stray word. */
    private val SYMBOL_CONTEXT = listOf(
        Regex("""(?i:\bof|من|في)\s+([A-Z]{1,5})\b"""),          // "10 shares of AAPL" / "أسهم من AAPL"
        Regex("""\b([A-Z]{1,5})\s*(?i:x)\s*\d"""),               // "AAPL x10"
        Regex("""(?:سهم|أسهم|اسهم)\s+([A-Z]{1,5})\b"""),        // "أسهم AAPL"
        Regex("""\b([A-Z]{1,5})\s*(?:@|(?i:\bat\b))""")          // "AAPL @ 150.25"
    )
    private val SYMBOL_ANY = Regex("""\b([A-Z]{1,5})\b""")

    /** Never treat these tokens as a ticker symbol. */
    private val EXCLUDED_TOKENS = setOf(
        // currency codes
        "USD", "JOD", "EUR", "GBP", "SAR", "AED", "JD", "ILS", "EGP", "KWD", "QAR", "BHD", "OMR", "CHF", "JPY", "CAD", "AUD",
        // generic words that show up uppercase in notifications
        "BUY", "SELL", "FOR", "THE", "AT", "SHARES", "STOCK", "X", "UNITS", "OF",
        "SHARE", "UNIT", "TOTAL", "ORDER", "TRADE", "BANK", "AL", "SOLD", "NEW", "FEE", "FEES", "NET"
    )

    /**
     * Parse a notification into a trade. Returns null when no buy/sell keyword
     * is present or the text carries no digits at all.
     */
    fun parse(title: String?, text: String): ParsedTrade? {
        val raw = listOfNotNull(title?.takeIf { it.isNotBlank() }, text).joinToString(" ")
        if (raw.isBlank()) return null
        val s = normalize(raw)

        val side = detectSide(s) ?: return null
        if (s.none { it.isDigit() }) return null

        val symbol = extractSymbol(s)
        var shares = extractShares(s)
        var price = firstNumber(PRICE, s)
        val amount = firstNumber(AMOUNT, s)
        val currency = extractCurrency(s)

        if (shares != null && shares <= 0.0) shares = null
        if (price != null && price <= 0.0) price = null
        if (price == null && amount != null && shares != null && shares > 0.0) {
            price = round(amount / shares * 100.0) / 100.0
        }

        var confidence = 40 // side is guaranteed present here
        if (symbol != null) confidence += 25
        if (shares != null) confidence += 20
        if (price != null || amount != null) confidence += 15

        return ParsedTrade(
            side = side,
            symbol = symbol,
            shares = shares,
            price = price,
            amount = amount,
            currency = currency,
            confidence = confidence
        )
    }

    fun toJson(t: ParsedTrade): String {
        val o = JSONObject()
        o.put("side", t.side.name)
        o.put("symbol", t.symbol ?: JSONObject.NULL)
        o.put("shares", t.shares ?: JSONObject.NULL)
        o.put("price", t.price ?: JSONObject.NULL)
        o.put("amount", t.amount ?: JSONObject.NULL)
        o.put("currency", t.currency ?: JSONObject.NULL)
        o.put("confidence", t.confidence)
        return o.toString()
    }

    fun fromJson(s: String): ParsedTrade? = try {
        val o = JSONObject(s)
        ParsedTrade(
            side = TradeSide.valueOf(o.getString("side")),
            symbol = if (o.isNull("symbol")) null else o.getString("symbol"),
            shares = if (o.isNull("shares")) null else o.getDouble("shares"),
            price = if (o.isNull("price")) null else o.getDouble("price"),
            amount = if (o.isNull("amount")) null else o.getDouble("amount"),
            currency = if (o.isNull("currency")) null else o.getString("currency"),
            confidence = o.optInt("confidence", 0)
        )
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- helpers

    /** Arabic-Indic digits -> ASCII, Arabic decimal/thousands marks -> "." / ",". */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660')) // ٠..٩
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0')) // ۰..۹
                '\u066B' -> sb.append('.') // arabic decimal separator ٫
                '\u066C' -> sb.append(',') // arabic thousands separator ٬
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun detectSide(s: String): TradeSide? {
        val buyIdx = listOfNotNull(
            BUY_EN.find(s)?.range?.first,
            s.indexOf("شراء").takeIf { it >= 0 }
        ).minOrNull()
        val sellIdx = listOfNotNull(
            SELL_EN.find(s)?.range?.first,
            s.indexOf("بيع").takeIf { it >= 0 }
        ).minOrNull()
        return when {
            buyIdx == null && sellIdx == null -> null
            sellIdx == null -> TradeSide.BUY
            buyIdx == null -> TradeSide.SELL
            buyIdx <= sellIdx -> TradeSide.BUY
            else -> TradeSide.SELL
        }
    }

    private fun extractSymbol(s: String): String? {
        for (rx in SYMBOL_CONTEXT) {
            for (m in rx.findAll(s)) {
                val token = m.groupValues[1]
                if (isValidSymbol(token)) return token
            }
        }
        for (m in SYMBOL_ANY.findAll(s)) {
            val token = m.groupValues[1]
            if (isValidSymbol(token)) return token
        }
        return null
    }

    private fun isValidSymbol(token: String): Boolean =
        token.isNotEmpty() && token.all { it in 'A'..'Z' } && token !in EXCLUDED_TOKENS

    private fun extractShares(s: String): Double? =
        firstNumber(SHARES_BEFORE_WORD, s)
            ?: firstNumber(SHARES_AFTER_X, s)
            ?: firstNumber(SHARES_BEFORE_X, s)

    private fun firstNumber(rx: Regex, s: String): Double? =
        rx.find(s)?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()

    private fun extractCurrency(s: String): String? {
        CURRENCY_CODE.find(s)?.let { return it.groupValues[1].uppercase() }
        return when {
            s.contains("د.أ") || s.contains("دينار") -> "JOD"
            s.contains("دولار") || s.contains('$') -> "USD"
            s.contains("ريال") -> "SAR"
            s.contains("درهم") -> "AED"
            s.contains('€') -> "EUR"
            s.contains('£') -> "GBP"
            else -> null
        }
    }
}
