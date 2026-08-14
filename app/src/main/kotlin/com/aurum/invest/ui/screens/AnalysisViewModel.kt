package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.BuyPlan
import com.aurum.invest.analytics.BuyPlanEngine
import com.aurum.invest.analytics.TechniqueAnalysis
import com.aurum.invest.analytics.TechniqueEvaluation
import com.aurum.invest.analytics.TechniqueEvaluator
import com.aurum.invest.analytics.Techniques
import com.aurum.invest.data.db.CacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnalysisState(
    val symbol: String = "",
    val loading: Boolean = true,
    val analysis: TechniqueAnalysis? = null,
    val price: Double? = null,
    val plan: BuyPlan? = null,
    /** Measured 3-month track record per technique; null while it computes. */
    val evaluation: TechniqueEvaluation? = null
)

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container
    private val market = container.market
    private val cacheDao = container.db.cacheDao()

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private var job: Job? = null

    companion object {
        /** Versioned: a grade stored for an older board must never score this one. */
        private const val EVAL_KEY_PREFIX = "techeval:v2:"

        /** The back-test replays daily closes; a run stays valid for a session. */
        private const val EVAL_MAX_AGE_MS = 6L * 3_600_000L
    }

    fun start(symbol: String) {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) return
        if (_state.value.symbol == sym && _state.value.analysis != null) return
        _state.value = AnalysisState(symbol = sym)
        load(sym)
    }

    fun refresh() {
        val sym = _state.value.symbol
        if (sym.isNotEmpty()) load(sym)
    }

    private fun load(sym: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // A full year of dailies so the plan's 200-day average exists.
            val candles = try {
                market.getDailyCandles(sym, 365)
            } catch (_: Exception) {
                emptyList()
            }
            val quote = try {
                market.getQuote(sym)
            } catch (_: Exception) {
                null
            }
            val analysis = Techniques.analyze(sym, candles)
            val price = quote?.price ?: candles.lastOrNull()?.close
            val plan =
                if (analysis != null && price != null && price > 0.0) {
                    runCatching {
                        BuyPlanEngine.build(sym, candles, analysis, price)
                    }.getOrNull()
                } else null
            _state.update {
                it.copy(
                    loading = false,
                    analysis = analysis,
                    price = price,
                    plan = plan
                )
            }

            // The standalone accuracy engine: replay the last ~3 months and
            // grade every technique against the real 5-day moves. Once graded,
            // the outlook is re-voted with each technique weighted by its own
            // measured hit rate on this stock.
            if (analysis == null || candles.size < 40) return@launch
            val evaluation = loadEvaluation(sym, candles) ?: return@launch
            val weighted = withContext(Dispatchers.Default) {
                runCatching { Techniques.analyze(sym, candles, evaluation.weights()) }.getOrNull()
            }
            _state.update { st ->
                // Ignore a result that raced a newer symbol load.
                if (st.symbol != sym) st
                else st.copy(evaluation = evaluation, analysis = weighted ?: st.analysis)
            }
        }
    }

    /** Cached 3-month evaluation, recomputed off the main thread when stale. */
    private suspend fun loadEvaluation(
        sym: String,
        candles: List<com.aurum.invest.data.model.Candle>
    ): TechniqueEvaluation? {
        val key = EVAL_KEY_PREFIX + sym
        val cached = try {
            cacheDao.get(key)
        } catch (_: Exception) {
            null
        }
        if (cached != null && System.currentTimeMillis() - cached.updatedAt <= EVAL_MAX_AGE_MS) {
            TechniqueEvaluator.fromJson(cached.json)?.let { return it }
        }
        val fresh = withContext(Dispatchers.Default) {
            runCatching { TechniqueEvaluator.evaluate(sym, candles) }.getOrNull()
        }
        if (fresh != null) {
            try {
                cacheDao.put(
                    CacheEntity(
                        key = key,
                        json = TechniqueEvaluator.toJson(fresh),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {
                // Cache write failure is non-fatal.
            }
            return fresh
        }
        // Fresh run failed — a stale stored grade beats nothing.
        return cached?.let { TechniqueEvaluator.fromJson(it.json) }
    }
}
