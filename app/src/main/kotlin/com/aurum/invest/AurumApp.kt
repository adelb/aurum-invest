package com.aurum.invest

import android.app.Application
import com.aurum.invest.data.db.AurumDatabase
import com.aurum.invest.data.remote.YahooClient
import com.aurum.invest.data.repo.BankFeedRepository
import com.aurum.invest.data.repo.EvaluationStore
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import com.aurum.invest.data.repo.ReviewRepository
import com.aurum.invest.data.repo.PicksRepository
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.RecordRepository
import com.aurum.invest.data.repo.SettingsRepository
import com.aurum.invest.data.repo.StudyRepository
import com.aurum.invest.data.repo.TargetsRepository
import com.aurum.invest.data.repo.WalletRepository
import com.aurum.invest.data.repo.WatchRepository
import com.aurum.invest.data.repo.WealthRepository
import com.aurum.invest.work.Schedules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AurumApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Schedules.ensure(this)
    }
}

/** Manual service locator — single source for every repository. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db = AurumDatabase.build(app)
    val settings = SettingsRepository(app)
    val yahoo = YahooClient()
    val market = MarketRepository(yahoo, db.cacheDao())
    val portfolio = PortfolioRepository(db.transactionDao())
    val wallet = WalletRepository(app, portfolio)
    val watch = WatchRepository(db.watchDao())
    val news = NewsRepository(db.cacheDao())
    val picks = PicksRepository(db.picksDao(), market, db.cacheDao(), news)
    val bankFeed = BankFeedRepository(db.bankEventDao(), portfolio)
    val record = RecordRepository(db.engineCallDao(), market)
    val wealth = WealthRepository(db.cacheDao(), market, news, settings, picks, record)
    val study = StudyRepository(market, news, wealth, portfolio)
    val targets = TargetsRepository(db.cacheDao())
    val evaluations = EvaluationStore(db.cacheDao())
    val review = ReviewRepository(market, portfolio, news, evaluations)
}
