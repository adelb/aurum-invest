package com.aurum.invest

import android.app.Application
import com.aurum.invest.data.db.AurumDatabase
import com.aurum.invest.data.remote.YahooClient
import com.aurum.invest.data.repo.BankFeedRepository
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import com.aurum.invest.data.repo.PicksRepository
import com.aurum.invest.data.repo.PortfolioRepository
import com.aurum.invest.data.repo.SettingsRepository
import com.aurum.invest.data.repo.TargetsRepository
import com.aurum.invest.data.repo.WalletRepository
import com.aurum.invest.data.repo.WatchRepository
import com.aurum.invest.data.repo.WealthRepository
import com.aurum.invest.data.repo.AdviceLogRepository
import com.aurum.invest.data.repo.AlertsRepository
import com.aurum.invest.data.repo.CashRepository
import com.aurum.invest.data.repo.FundamentalsRepository
import com.aurum.invest.work.Schedules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AurumApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Schedules.ensure(this)
        // Retention: raw bank-notification text is sensitive; captures older
        // than the configured window are deleted on every launch.
        container.appScope.launch {
            runCatching {
                val days = container.settings.bankRetentionDays.first()
                if (days > 0) container.bankFeed.purgeOlderThan(days)
            }
        }
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
    val watch = WatchRepository(db.watchDao())
    val news = NewsRepository(db.cacheDao())
    val picks = PicksRepository(db.picksDao(), market, db.cacheDao(), news)
    val bankFeed = BankFeedRepository(db.bankEventDao(), portfolio)
    val adviceLog = AdviceLogRepository(db.adviceLogDao(), BuildConfig.VERSION_NAME)
    val cash = CashRepository(db.cashEventDao(), db.transactionDao())
    val fundamentals = FundamentalsRepository(db.cacheDao())
    // Declared before the Wealth layer: the portfolio review needs the wallet
    // to know the account's equity, not just the book's value.
    val wallet = WalletRepository(app, portfolio)
    val wealth = WealthRepository(
        db.cacheDao(), market, news, portfolio, settings, adviceLog, cash, picks, fundamentals,
        wallet
    )
    val targets = TargetsRepository(db.cacheDao())
    val alerts = AlertsRepository(db.priceAlertDao())
}
