package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.GoldBook
import com.aurum.invest.analytics.GoldEngine
import com.aurum.invest.analytics.GoldLot
import com.aurum.invest.analytics.GoldTrend
import com.aurum.invest.analytics.MonthBand
import com.aurum.invest.analytics.ShopQuote
import com.aurum.invest.analytics.SyndicateList
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.Quote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which price the book is valued at. */
enum class GoldBasis { LIST, LIVE }

data class GoldState(
    val loading: Boolean = true,
    val lots: List<GoldLot> = emptyList(),
    val list: SyndicateList? = null,
    val listStatus: FeedStatus = FeedStatus.FAILED,
    val shop: ShopQuote? = null,
    val world: Quote? = null,
    val basis: GoldBasis = GoldBasis.LIST,
    /** World gold's daily closes, oldest first — the chart and the market read. */
    val worldDaily: List<Candle> = emptyList(),
    val spyDaily: List<Candle> = emptyList(),
    val trend: GoldTrend? = null,
    val band: MonthBand? = null,
    val correlation: Double? = null
) {
    /** worldNow / worldAtList — 1.0 when either is unknown. */
    val factor: Double get() = GoldEngine.liveFactor(list, world?.price)

    val activeFactor: Double get() = if (basis == GoldBasis.LIVE) factor else 1.0

    /** The world price the active valuation corresponds to. */
    val worldForValue: Double?
        get() = if (basis == GoldBasis.LIVE) world?.price ?: list?.worldAtList else list?.worldAtList

    val book: GoldBook
        get() = GoldEngine.book(lots, list, activeFactor, shop, worldForValue)

    /** Always at the list — what Home shows and what a shop would pay right now. */
    val listBook: GoldBook get() = GoldEngine.book(lots, list, 1.0, shop, list?.worldAtList)

    val openLots: List<GoldLot> get() = lots.filter { !it.sold }
    val soldLots: List<GoldLot> get() = lots.filter { it.sold }
    val realized: Double get() = GoldEngine.realized(lots)

    /** The open book, same money, same days, in SPY. Null when it can't be compared fairly. */
    val spyShadow: Double? get() = GoldEngine.shadowValue(openLots, spyDaily)

    /** World gold's move since the list was published, in %. */
    val driftPct: Double get() = (factor - 1.0) * 100.0
}

class GoldViewModel(app: Application) : AndroidViewModel(app) {

    private val gold = (app as AurumApp).container.gold

    private val _state = MutableStateFlow(GoldState())
    val state: StateFlow<GoldState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            gold.observeLots().collect { lots -> _state.update { it.copy(lots = lots) } }
        }
        refresh()
    }

    fun setBasis(basis: GoldBasis) = _state.update { it.copy(basis = basis) }

    /** Re-reads the list (if due), the shop quote, world gold and the history. */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                coroutineScope {
                    val feed = async { gold.list(if (force) 0L else com.aurum.invest.data.repo.GoldRepository.LIST_MAX_AGE_MS) }
                    val world = async { gold.worldQuote(if (force) 0L else 60_000L) }
                    val worldDaily = async { gold.worldDaily() }
                    val spyDaily = async { gold.spyDaily() }
                    val shop = async { gold.shopQuote() }
                    val f = feed.await()
                    val w = world.await()
                    val wd = worldDaily.await()
                    val sd = spyDaily.await()
                    // The market read is a few thousand multiplications — off the main thread.
                    val (trend, band, corr) = withContext(Dispatchers.Default) {
                        val closes = wd.map { it.close }
                        Triple(
                            GoldEngine.trend(closes),
                            GoldEngine.monthBand(closes),
                            GoldEngine.correlation(wd, sd)
                        )
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            list = f.list,
                            listStatus = f.status,
                            shop = shop.await(),
                            world = w ?: it.world,
                            worldDaily = wd,
                            spyDaily = sd,
                            trend = trend,
                            band = band,
                            correlation = corr
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Re-prices world gold only — the live estimate's heartbeat. */
    fun tick() {
        viewModelScope.launch {
            val w = gold.worldQuote(30_000L) ?: return@launch
            _state.update { it.copy(world = w) }
        }
    }

    fun setShopQuote(karat: Int, buyPerGram: Double?) {
        viewModelScope.launch {
            val list = _state.value.list
            val quote = if (buyPerGram == null || buyPerGram <= 0.0 || list == null) null
            else ShopQuote(karat, buyPerGram, list.listedAt)
            gold.setShopQuote(quote)
            _state.update { it.copy(shop = quote) }
        }
    }

    fun markSold(id: Long, pricePerUnit: Double) {
        viewModelScope.launch { gold.markSold(id, System.currentTimeMillis(), pricePerUnit) }
    }

    fun reopen(id: Long) {
        viewModelScope.launch { gold.reopen(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { gold.delete(id) }
    }
}
