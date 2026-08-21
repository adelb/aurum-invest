package com.aurum.invest.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.PortfolioBoardReview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewScreenState(
    /** True until the first (ungraded) pass of the whole book lands. */
    val loading: Boolean = true,
    val review: PortfolioBoardReview? = null,
    val total: Int = 0,
    /** Holdings whose measured 1-year grading has finished. */
    val graded: Int = 0,
    /** The symbol being graded right now; null once grading is done. */
    val grading: String? = null,
    val failed: Boolean = false
)

class PortfolioReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AurumApp).container

    private val _state = MutableStateFlow(ReviewScreenState())
    val state: StateFlow<ReviewScreenState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            container.review.reviewFlow()
                .catch {
                    _state.update { s ->
                        s.copy(loading = false, failed = s.review == null, grading = null)
                    }
                }
                .collect { p ->
                    _state.value = ReviewScreenState(
                        loading = false,
                        review = p.review,
                        total = p.total,
                        graded = p.graded,
                        grading = p.grading?.takeIf { p.graded < p.total }
                    )
                }
        }
    }
}
