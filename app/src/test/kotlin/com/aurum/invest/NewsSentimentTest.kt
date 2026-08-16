package com.aurum.invest

import com.aurum.invest.analytics.NewsSentiment
import com.aurum.invest.data.model.NewsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Headline-tone rules (H3): polarity, negation, dedup, and source tiers. */
class NewsSentimentTest {

    @Test
    fun `positive and negative terms score`() {
        assertTrue(NewsSentiment.score("Apple beats estimates, raises guidance") > 0)
        assertTrue(NewsSentiment.score("Tesla misses badly, cuts guidance") < 0)
        assertEquals(0, NewsSentiment.score("Apple to hold developer conference"))
    }

    @Test
    fun `negation flips the term`() {
        val positive = NewsSentiment.score("Company wins approval for new drug")
        val negated = NewsSentiment.score("Company fails to win approval for new drug")
        assertTrue(positive > 0)
        assertTrue(negated < positive)
    }

    @Test
    fun `denies flips a deal headline`() {
        val plain = NewsSentiment.score("Acme in merger talks")
        val denied = NewsSentiment.score("Acme denies merger talks")
        assertTrue(denied < plain)
    }

    @Test
    fun `duplicate headlines collapse keeping the better source`() {
        fun item(id: String, title: String, source: String) = NewsItem(
            id = id, symbol = "AAPL", title = title, source = source,
            url = "https://x/$id", publishedAt = 0L, sentiment = 0
        )
        val items = listOf(
            item("1", "Apple beats Q3 earnings estimates on iPhone strength", "SomeBlog"),
            item("2", "Apple beats Q3 earnings estimates on strong iPhone sales", "Reuters"),
            item("3", "Fed to meet next week on interest rates", "AP News")
        )
        val deduped = NewsSentiment.dedupe(items)
        assertEquals(2, deduped.size)
        // The syndicated pair collapses to the tier-1 copy.
        assertTrue(deduped.any { it.source == "Reuters" })
        assertTrue(deduped.none { it.source == "SomeBlog" })
    }

    @Test
    fun `tier one sources are recognized`() {
        assertTrue(NewsSentiment.isTier1("Reuters"))
        assertTrue(NewsSentiment.isTier1("bloomberg"))
        assertTrue(!NewsSentiment.isTier1("Random Stock Blog"))
    }
}
