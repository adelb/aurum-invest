package com.aurum.invest

import com.aurum.invest.data.remote.JordanGoldParser
import com.aurum.invest.data.repo.GoldRepository
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The republished Syndicate list, as the page actually marks it up (trimmed
 * from the live page of Sun 25 Oct 2026). The history table below the live
 * list carries older sell prices and must never be read as today's list.
 */
class JordanGoldParserTest {

    private val page = """
        <html><head><style>.x{color:red}</style><script>var p = "عيار 21 1.000 دينار 1.000 دينار";</script></head><body>
        <p>تم تحديث أسعار الذهب في الأردن، الأحد، 25 تشرين الأول، 3:58 مساءً أسعار البيع والشراء حسب تسعيرة
        نقابة أصحاب محلات الحلي والمجوهرات، وآخر تغيير فيها السبت 24 تشرين الأول، 10:15 صباحًا بتوقيت عمّان.</p>
        <table><thead><tr><th>العيار</th><th>سعر البيع</th><th>سعر الشراء</th></tr></thead><tbody>
        <tr><td><span class="font-medium">عيار 24</span></td>
            <td><span class="text-lg">93.400 <span class="text-[11px]">دينار</span></span></td>
            <td><span class="text-lg">89.700 <span class="text-[11px]">دينار</span></span></td></tr>
        <tr><td><span>عيار 22</span></td><td><span>86.200 <span>دينار</span></span></td><td><span>81.600 <span>دينار</span></span></td></tr>
        <tr><td><span>عيار 21</span></td><td><span>81.400 <span>دينار</span></span></td><td><span>77.600 <span>دينار</span></span></td></tr>
        <tr><td><span>عيار 18</span></td><td><span>72.000 <span>دينار</span></span></td><td><span>66.300 <span>دينار</span></span></td></tr>
        <tr><td><span class="font-bold">الليرة الرشادي</span></td><td><span>586.100 <span>دينار</span></span></td><td><span>558.700 <span>دينار</span></span></td></tr>
        <tr><td><span class="font-bold">الليرة الإنجليزي</span></td><td><span>689.600 <span>دينار</span></span></td><td><span>652.800 <span>دينار</span></span></td></tr>
        </tbody></table>
        <table class="show-tbl"><tbody>
        <tr><td><span>الخميس</span> 15 تشرين الأول</td><td>97.200</td><td>89.700</td><td>84.700</td></tr>
        </tbody></table>
        <p>عيار 21 84.700 دينار 80.000 دينار</p>
        </body></html>
    """.trimIndent()

    private val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Amman")).apply {
        clear()
        set(2026, Calendar.OCTOBER, 25, 15, 58, 0)
    }.timeInMillis

    @Test
    fun readsEveryKaratAndBothLiras() {
        val list = JordanGoldParser.parse(page, now)!!
        assertEquals(93.4, list.karats[24]!!.sell, 1e-9)
        assertEquals(89.7, list.karats[24]!!.buy, 1e-9)
        assertEquals(81.6, list.karats[22]!!.buy, 1e-9)
        assertEquals(81.4, list.karats[21]!!.sell, 1e-9)
        assertEquals(77.6, list.karats[21]!!.buy, 1e-9)
        assertEquals(66.3, list.karats[18]!!.buy, 1e-9)
        assertEquals(558.7, list.rashadi!!.buy, 1e-9)
        assertEquals(689.6, list.english!!.sell, 1e-9)
    }

    @Test
    fun theFirstTableIsTheLiveListNotTheHistory() {
        val list = JordanGoldParser.parse(page, now)!!
        assertEquals(81.4, list.karats[21]!!.sell, 1e-9)
    }

    @Test
    fun readsWhenTheSyndicateLastChangedTheList() {
        val list = JordanGoldParser.parse(page, now)!!
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Amman"))
        c.timeInMillis = list.listedAt
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH))
        assertEquals(24, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, c.get(Calendar.MINUTE))
    }

    @Test
    fun anEveningListIsReadAsPm() {
        val text = "وآخر تغيير فيها السبت 24 تشرين الأول، 6:40 مساءً"
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Amman"))
        c.timeInMillis = JordanGoldParser.listedAt(text, now)
        assertEquals(18, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun aDecemberListSeenInJanuaryBelongsToLastYear() {
        val jan = Calendar.getInstance(TimeZone.getTimeZone("Asia/Amman")).apply {
            clear()
            set(2027, Calendar.JANUARY, 2, 9, 0, 0)
        }.timeInMillis
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Amman"))
        c.timeInMillis = JordanGoldParser.listedAt("آخر تغيير فيها الخميس 31 كانون الأول، 10:15 صباحًا", jan)
        assertEquals(2026, c.get(Calendar.YEAR))
    }

    @Test
    fun aPageWithout21KIsNotAList() {
        assertNull(JordanGoldParser.parse("<p>عيار 24 93.400 دينار 89.700 دينار</p>", now))
    }

    @Test
    fun aBuyAboveTheSellPriceIsAMisread() {
        val bad = "<p>عيار 21 81.400 دينار 91.600 دينار</p>"
        assertNull(JordanGoldParser.parse(bad, now))
    }

    @Test
    fun theSavedListSurvivesARoundTrip() {
        val list = JordanGoldParser.parse(page, now)!!.copy(worldAtList = 4007.9)
        val back = GoldRepository.listFromJson(GoldRepository.listToJson(list))
        assertNotNull(back)
        assertEquals(list, back)
    }
}
