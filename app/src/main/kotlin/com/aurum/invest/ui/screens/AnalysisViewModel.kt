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
    /** Measured 1-year track record per technique; null while it computes. */
    val evaluation: TechniqueEvaluation? = null,
    val evaluationLoading: Boolean = false
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
        private const val EVAL_KEY_PREFIX = "techeval:v5:"

        /** The back-test replays daily closes; a run stays valid for a session. */
        private const val EVAL_MAX_AGE_MS = 6L * 3_600_000L

        /**
         * Two years of dailies: the last 252 sessions feed the 1-year
         * integrity replay, and the sessions before them give every replayed
         * day a real indicator warm-up.
         */
        private const val CANDLE_DAYS = 550
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
            _state.update {
                it.copy(loading = true, evaluation = null, evaluationLoading = false)
            }
            // Two years of dailies: the 200-day average, plus a full-year
            // integrity replay with honest warm-up for every replayed day.
            val candles = try {
                market.getDailyCandles(sym, CANDLE_DAYS)
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
                    plan = plan,
                    evaluationLoading =
                        analysis != null &&
                            candles.size >= TechniqueEvaluator.MIN_CANDLES_FOR_FULL_REPLAY
                )
            }

            // The integrity engine: replay the last year of sessions and grade
            // every technique against the real 5-day moves. Once graded, the
            // outlook is re-voted with each technique weighted by its own
            // measured hit rate on this stock, and the board reorders so the
            // top-ranked techniques lead.
            if (
                analysis == null ||
                candles.size < TechniqueEvaluator.MIN_CANDLES_FOR_FULL_REPLAY
            ) {
                return@launch
            }
            val evaluation = loadEvaluation(sym, candles)
            if (evaluation == null) {
                _state.update { st ->
                    if (st.symbol != sym) st else st.copy(evaluationLoading = false)
                }
                return@launch
            }
            val weighted = withContext(Dispatchers.Default) {
                runCatching { Techniques.analyze(sym, candles, evaluation.weights()) }.getOrNull()
            }
            _state.update { st ->
                // Ignore a result that raced a newer symbol load.
                if (st.symbol != sym) st
                else st.copy(
                    evaluation = evaluation,
                    analysis = weighted ?: st.analysis,
                    evaluationLoading = false
                )
            }
        }
    }

    /** Cached 12-month evaluation, recomputed off the main thread when stale. */
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
            TechniqueEvaluator.fromJson(cached.json)
                ?.takeIf { TechniqueEvaluator.isComplete(it, sym) }
                ?.let { return it }
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
        // Never present an expired grade as if it measured the current board.
        return null
    }
}
