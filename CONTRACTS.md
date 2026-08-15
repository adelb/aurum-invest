# Aurum — Module Contracts

Aurum is a native Android (Kotlin + Jetpack Compose, Material 3) investment-wallet app.
Package root: `com.aurum.invest`. Source root: `app/src/main/kotlin/com/aurum/invest/`.
minSdk 26, target/compile 34, Kotlin 2.0, Compose BOM 2024.06.00, Room 2.6.1 (KSP),
WorkManager 2.9.0, OkHttp 4.12.0, DataStore. JSON via `org.json` (bundled in Android).
NO other libraries may be added. Do not edit gradle files.

## Already written (READ these before coding — code against them exactly)

- `core/Fmt.kt` — `object Fmt`: `money(v, symbol="$")`, `signedMoney`, `pct`, `signedPct`, `compact`, `qty`, `dateShort(ts)`, `dateTime(ts)`, `timeAgo(ts)`.
- `core/Dates.kt` — `object Dates`: `currentWeekStartIso(): String`, `weekStartLabel(iso): String`, `nextMondayMorningDelayMs(): Long`, `sameDay(ts1, ts2): Boolean`.
- `data/model/Models.kt` — Quote, Candle, TradeSide, Position, PositionView, PortfolioSummary, AdviceAction, Advice, GoldLink, GoldRelation, NewsItem, WeeklyPick, ParsedTrade, BankEvent (+ BankEvent.STATUS_* constants).
- `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/AurumDatabase.kt` — Room layer (TransactionEntity, WatchItemEntity, CacheEntity(key/json/updatedAt), BankEventEntity, WeeklyPickEntity + DAOs).
- `data/repo/PortfolioRepository.kt` — ledger + P/L math. Statics: `computePositions`, `toView(position, quote)`, `summarize(openViews, allPositions)`, `isOpen(p)`.
- `data/repo/SettingsRepository.kt` — `bankPackages: Flow<String>` (comma-separated fragments), `autoImport: Flow<Boolean>`, setters.
- `data/repo/WatchRepository.kt` — observeAll/getAll/add(symbol,name)/remove/setPinned/isWatched.
- `AurumApp.kt` — `AurumApp : Application` exposing `container: AppContainer` with members: `appScope: CoroutineScope`, `db`, `settings`, `yahoo`, `market`, `portfolio`, `watch`, `news`, `picks`, `bankFeed`. It calls `Schedules.ensure(this)` on create.
- `ui/theme/` — `AurumTheme {}`, `AurumColors` (bg, surface, surfaceHigh, hairline, gold, goldBright, goldDeep, text, textDim, gain, loss, gainSoft, lossSoft, goldSoft, `goldGradient(width)`, `cardWash`, `deltaColor(v)`), `AurumTypography`, `Inter`.
- `ui/components/Charts.kt` — `Sparkline(data: List<Double>, modifier, color: Color? = null, fill = true, strokeWidth = 2.dp)`, `PriceChart(closes: List<Double>, modifier, baseline: Double? = null, color: Color? = null)`.
- `ui/components/Common.kt` — `AurumCard(modifier, onClick=null, contentPadding, content: ColumnScope.() -> Unit)`, `SectionHeader(title, modifier, trailing=null)`, `StatTile(label, value, modifier, valueColor)`, `DeltaPct(value, modifier, style)`, `DeltaMoney(value, modifier, style)`, `ActionBadge(action)`, `adviceLabel(action)`, `PillTag(text, color)`, `ScoreBar(score 0..100)`, `SentimentDot(sentiment)`, `EmptyState(title, message)`, `GoldGradientText(text, style)`.

ViewModel pattern (no DI framework): `class XViewModel(app: Application) : AndroidViewModel(app)` and read `(app as AurumApp).container`. Screens obtain VMs with `viewModel()` from `androidx.lifecycle.viewmodel.compose`.

Every module below MUST compile against these exact signatures. When module A references module B's API, the signature is specified here — match it exactly.

---

## M1 — Market data (`data/remote/YahooClient.kt`, `data/repo/MarketRepository.kt`)

```kotlin
class YahooClient {                       // OkHttp + org.json; browser User-Agent header
    suspend fun fetchQuote(symbol: String): Quote?                         // v8 chart API, range=1d interval=1m: meta.regularMarketPrice, chartPreviousClose
    suspend fun fetchDailyCandles(symbol: String, rangeDays: Int): List<Candle>   // interval=1d, range chosen from rangeDays (e.g. 6mo for 120)
    suspend fun fetchIntraday(symbol: String): List<Candle>                // range=1d interval=5m
    suspend fun searchSymbols(query: String): List<Pair<String, String>>   // v1/finance/search -> (symbol, shortname), US equities only, max 8
}

class MarketRepository(private val yahoo: YahooClient, private val cacheDao: CacheDao) {
    suspend fun getQuote(symbol: String, maxAgeMs: Long = 60_000L): Quote?
    suspend fun getQuotes(symbols: List<String>, maxAgeMs: Long = 60_000L): Map<String, Quote>  // concurrent (coroutineScope + async), skip failures
    suspend fun getDailyCandles(symbol: String, rangeDays: Int = 120, maxAgeMs: Long = 21_600_000L): List<Candle>
    suspend fun getIntraday(symbol: String, maxAgeMs: Long = 300_000L): List<Candle>
    suspend fun getGoldCandles(rangeDays: Int = 120): List<Candle>          // GOLD_SYMBOL daily candles
    suspend fun search(query: String): List<Pair<String, String>>
    companion object { const val GOLD_SYMBOL = "GLD" }
}
```
Caching: read-through via `CacheDao` keys `"quote:SYM"`, `"candles:SYM:days"`, `"intraday:SYM"`; serve stale cache on network failure (never throw — return null/empty on total failure). Serialize with org.json. All network on `Dispatchers.IO`. Yahoo endpoints: `https://query1.finance.yahoo.com/v8/finance/chart/{sym}?range=..&interval=..` and `https://query1.finance.yahoo.com/v1/finance/search?q=..`. Send header `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36`.

## M2 — News (`data/repo/NewsRepository.kt` — may add private `NewsClient` in same file or `data/remote/NewsClient.kt`)

```kotlin
class NewsRepository(private val cacheDao: CacheDao) {
    // Google News RSS: https://news.google.com/rss/search?q={SYM}+stock+when:5d&hl=en-US&gl=US&ceid=US:en
    // Parse with XmlPullParser (android.util.Xml). Last 5 days only. Sentiment via NewsSentiment.score(title).
    // priceImpactPct: close-to-close % change of the trading day whose date matches the item's pub date
    // (use Dates.sameDay against [candles]); null when no candle matches (weekend/holiday).
    suspend fun getNews(symbol: String, candles: List<Candle>, maxAgeMs: Long = 1_800_000L): List<NewsItem>
}
```
Cache key `"news:SYM"` (JSON array). Sorted newest first, max 20 items. Never throw; empty list on failure. RSS `<source>` tag → NewsItem.source; `<pubDate>` RFC-822 → epoch millis.

## M3 — Analytics (`analytics/Indicators.kt`, `analytics/NewsSentiment.kt`, `analytics/AdviceEngine.kt`, `analytics/GoldCorrelation.kt`, `analytics/WeeklyPicker.kt`)

```kotlin
object Indicators {
    fun sma(values: List<Double>, period: Int): Double?          // null if not enough data
    fun rsi(closes: List<Double>, period: Int = 14): Double?     // Wilder smoothing
    fun atr(candles: List<Candle>, period: Int = 14): Double?
    fun dailyReturns(closes: List<Double>): List<Double>
    fun pearson(a: List<Double>, b: List<Double>): Double?       // null if size mismatch or < 2
    fun recentHigh(closes: List<Double>, lookback: Int): Double?
    fun recentLow(closes: List<Double>, lookback: Int): Double?
}

object NewsSentiment {
    fun score(title: String): Int    // -2..+2, keyword lexicon (beat/upgrade/surge/record/acquire... vs miss/downgrade/lawsuit/plunge/recall/probe...)
}

object AdviceEngine {
    // Sell-side advice for a held position. Uses RSI, price vs avgCost, ATR-based
    // target (avgCost * (1 + 2*atrPct)) and stop (avgCost * (1 - 1.5*atrPct)), newsScore.
    // Actions: TAKE_PROFIT (target hit or RSI>72 while in profit), CUT_LOSS (below stop),
    // SELL (strong negative composite), HOLD otherwise. headline = one human sentence,
    // reasons = 2-4 bullets naming concrete numbers.
    fun sellAdvice(position: Position, quote: Quote, candles: List<Candle>, newsScore: Int = 0): Advice

    // Buy-side advice for a watched symbol. suggestedBuyPrice ALWAYS set when candles
    // are sufficient: a good entry = max(recentLow(20), sma(20) * 0.985) rounded to cents,
    // clamped below current price when advice is WAIT. Actions: STRONG_BUY (RSI<32 &
    // uptrend & positive news), BUY (composite positive), WAIT otherwise.
    fun buyAdvice(quote: Quote, candles: List<Candle>, newsScore: Int = 0): Advice
}

object GoldCorrelation {
    // Align stock & gold daily candles by calendar day (Dates.sameDay), Pearson on daily
    // returns of >= 20 overlapping days. r >= 0.30 -> WITH_GOLD, r <= -0.30 -> INVERSE_GOLD,
    // else NEUTRAL. description: human sentence including r rounded to 2 decimals.
    fun relation(stockCandles: List<Candle>, goldCandles: List<Candle>): GoldRelation
}

class WeeklyPicker(private val market: MarketRepository) {
    // Universe: companion val UNIVERSE: List<Pair<String, String>> — ~80 liquid large/mid-cap
    // US names across tech/finance/health/energy/consumer/industrial/gold-miners.
    // Score each: momentum (5d & 20d return), RSI sweet band (40..65 best), volume surge
    // (5d avg vs 20d avg), distance from 20d high. Fetch via market.getDailyCandles(sym, 60)
    // + market.getQuotes. Chunk candle fetches (10 concurrent). Top 10 -> WeeklyPick with
    // rank 1..10, score scaled 0..100, reason = one sentence naming the concrete signals.
    suspend fun computePicks(weekStart: String): List<WeeklyPick>
}
```

## M4 — Bank capture (`bank/TradeParser.kt`, `bank/BankNotificationListener.kt`, `data/repo/BankFeedRepository.kt`)

```kotlin
object TradeParser {
    // Parse bank/broker notification text (English AND Arabic) into a trade.
    // EN: buy/bought/purchase/executed + "10 shares of AAPL at $150.25" / "AAPL x10 @ 150.25" / "for USD 1,502.50"
    // AR: شراء / بيع + سهم / أسهم, symbol as Latin ticker, amounts with USD/JOD/د.أ/$.
    // side keywords: buy/bought/purchase/شراء -> BUY ; sell/sold/بيع -> SELL.
    // symbol: 1-5 uppercase Latin letters token (exclude currency codes USD/JOD/EUR and words like SHARES).
    // shares: number adjacent to shares/سهم/x/units. price: after @/at/بسعر. amount: after for/total/بقيمة/بمبلغ.
    // If price missing but amount+shares present, price = amount/shares. confidence 0..100:
    // +40 side, +25 symbol, +20 shares, +15 price-or-amount. Return null when side absent or no numbers.
    fun parse(title: String?, text: String): ParsedTrade?
    fun toJson(t: ParsedTrade): String
    fun fromJson(s: String): ParsedTrade?
}

class BankFeedRepository(private val bankDao: BankEventDao, private val portfolio: PortfolioRepository) {
    fun observeEvents(): Flow<List<BankEvent>>            // map entity -> BankEvent (parse parsedJson via TradeParser.fromJson)
    fun observePendingCount(): Flow<Int>
    // record: dedup via bankDao.countRecentDuplicates(pkg,title,text, now-60_000) > 0 -> skip.
    // Parse, store entity (parsedJson when parsed != null). Returns event id or -1 when deduped.
    suspend fun recordNotification(pkg: String, title: String, text: String, postedAt: Long): Long
    // import: add BANK-source transaction via portfolio.addTransaction(symbol, side, shares, price, ts = event.postedAt, source = "BANK"), set status IMPORTED.
    suspend fun importEvent(eventId: Long, symbol: String, side: TradeSide, shares: Double, price: Double)
    suspend fun dismissEvent(eventId: Long)
}

class BankNotificationListener : NotificationListenerService() {
    // onNotificationPosted: ignore own package; read title/text/bigText from extras.
    // Capture when EITHER the package contains any comma-separated fragment from
    // settings.bankPackages (case-insensitive) OR text matches trade keywords
    // (buy|bought|sell|sold|shares|stock|شراء|بيع|سهم|أسهم) AND contains a digit.
    // Launch on container.appScope: bankFeed.recordNotification(...).
    // If settings.autoImport.first() && parsed != null && confidence >= 80 && symbol/shares/price all present -> bankFeed.importEvent(...) automatically.
    // companion object { fun isEnabled(context: Context): Boolean  // NotificationManagerCompat.getEnabledListenerPackages
    //                    fun openSettings(context: Context) }      // ACTION_NOTIFICATION_LISTENER_SETTINGS intent (FLAG_ACTIVITY_NEW_TASK)
}
```
Manifest entry already exists: service `.bank.BankNotificationListener` — class must be exactly there.

## M5 — Picks repo + workers (`data/repo/PicksRepository.kt`, `work/Schedules.kt`, `work/RefreshWorker.kt`, `work/WeeklyPicksWorker.kt`)

```kotlin
class PicksRepository(private val picksDao: PicksDao, private val market: MarketRepository) {
    fun observeCurrentWeek(): Flow<List<WeeklyPick>>      // picksDao.observeWeek(Dates.currentWeekStartIso()) mapped
    suspend fun getCurrentWeek(): List<WeeklyPick>
    suspend fun ensureCurrentWeek(): List<WeeklyPick>     // compute + store when the current week is empty
    suspend fun recompute(): List<WeeklyPick>             // clearWeek + WeeklyPicker(market).computePicks + insertAll
}

object Schedules {
    fun ensure(context: Context)
    // 1) "aurum-refresh": PeriodicWorkRequest<RefreshWorker> every 30 min, NetworkType.CONNECTED, KEEP.
    // 2) "aurum-weekly-picks": PeriodicWorkRequest<WeeklyPicksWorker> every 7 days,
    //    initialDelay = Dates.nextMondayMorningDelayMs(), NetworkType.CONNECTED, KEEP.
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params)
// doWork: container from (applicationContext as AurumApp); symbols = open positions + watchlist;
// market.getQuotes(symbols, maxAgeMs = 0) to warm cache; Result.success() always (retry() on total failure).

class WeeklyPicksWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params)
// doWork: container.picks.recompute(); success/retry.
```

## M6 — UI: navigation shell + Dashboard + Position detail

Files: `MainActivity.kt` (root pkg), `ui/nav/AurumRoot.kt`, `ui/screens/DashboardScreen.kt`, `ui/screens/DashboardViewModel.kt`, `ui/screens/PositionDetailScreen.kt`, `ui/screens/PositionDetailViewModel.kt`.

```kotlin
// MainActivity: installSplashScreen(); enableEdgeToEdge? NO — keep simple; setContent { AurumTheme { AurumRoot() } }
// ui/nav/AurumRoot.kt:
object Routes {
    const val DASHBOARD = "dashboard"; const val WATCHLIST = "watchlist"
    const val PICKS = "picks"; const val FEED = "feed"; const val SETTINGS = "settings"
    const val ADD = "add?symbol={symbol}"; const val DETAIL = "detail/{symbol}"
    fun detail(symbol: String) = "detail/$symbol"
    fun add(symbol: String? = null) = if (symbol == null) "add" else "add?symbol=$symbol"
}
@Composable fun AurumRoot()
// Scaffold(containerColor = AurumColors.bg) + NavigationBar (containerColor surface) with 4 items:
// Portfolio (Icons.Rounded.AccountBalanceWallet), Watchlist (Icons.Rounded.Visibility),
// Picks (Icons.Rounded.AutoAwesome), Feed (Icons.Rounded.Notifications) — Feed item shows a
// Badge with pending count when > 0 (observe bankFeed.observePendingCount from a tiny RootViewModel).
// NavHost with all routes; DETAIL takes navArgument "symbol"; ADD optional arg "symbol" (nullable, defaultValue null).
```

DashboardScreen: header row with `GoldGradientText("Aurum", displaySmall)` + settings gear (navigate SETTINGS). PortfolioSummary hero: "Total value" money(large, displayLarge), DeltaMoney(dayPl) + " today", row of StatTiles (Invested, Unrealized P/L colored, Realized P/L colored). Holdings section: each holding = AurumCard(onClick -> detail): symbol + qty shares, Sparkline (intraday closes, width ~90dp height 36dp), price + DeltaPct(day change), unrealized P/L line, ActionBadge(sellAdvice.action). FAB (gold, Icons.Rounded.Add) -> Routes.add(). Empty state when no holdings. A small refresh IconButton triggers reload. VM: combine portfolio.observePositions with quotes fetched in viewModelScope; expose StateFlow<DashboardState> (loading, summary, holdings: List<HoldingRow(view: PositionView, spark: List<Double>, advice: Advice?)>). Compute advice via AdviceEngine.sellAdvice with getDailyCandles(sym, 120) and newsScore 0 (skip news on dashboard for speed).

PositionDetailScreen(symbol): back arrow + symbol title + shortName dim. Price hero + DeltaPct today. Range chips "1D" / "3M" toggling PriceChart(intraday closes, baseline = prevClose) vs PriceChart(daily closes, baseline = avgCost when held). If held: position card (Shares, Avg cost, Market value, Unrealized P/L). Advice card: ActionBadge + headline + reason bullets + when present target/stop/suggestedBuyPrice as StatTiles ("Target", "Stop", "Good entry"). Gold card: "Gold relation" — PillTag WITH GOLD (gold color) / INVERSE (loss) / NEUTRAL (dim) + description + r value. News card list: last-5-days items (SentimentDot, title, source • timeAgo, priceImpactPct as DeltaPct when non-null); tapping opens URL (Intent.ACTION_VIEW). Buttons: "Buy" / "Sell" -> Routes.add(symbol). Watch/pin toggles: star icon (add/remove watch + pin). VM loads: quote, intraday, daily(120), advice (sell if held else buy, with newsScore from news items sum clamped -2..2), goldRelation via market.getGoldCandles(), news via news.getNews(symbol, daily). Expose single StateFlow<DetailState>; refresh() reloads.

## M7 — UI: Watchlist + Picks + Add transaction

Files: `ui/screens/WatchlistScreen.kt`, `WatchlistViewModel.kt`, `PicksScreen.kt`, `PicksViewModel.kt`, `AddTransactionScreen.kt`, `AddTransactionViewModel.kt` (all in `ui/screens/`).

WatchlistScreen: title "Watchlist". Search OutlinedTextField ("Add ticker — try AAPL"); as user types (>=2 chars, 300ms debounce) show market.search results as suggestion rows (symbol bold + name dim + Add icon) -> watch.add(symbol, name). List of watched: AurumCard(onClick -> detail) rows: pin star IconButton (filled gold when pinned -> watch.setPinned), symbol + name, Sparkline(daily closes 30d), price + DeltaPct, then a second line: buy advice — ActionBadge + "Entry ≈ $X" (suggestedBuyPrice) when present; for PINNED items also PillTag gold-relation badge (WITH GOLD/INV GOLD/—). Swipe not needed: long-press or trailing close icon removes. Empty state invite. VM: combine watch.observeAll + per-symbol data loaded in viewModelScope; StateFlow<WatchState(rows: List<WatchRow(symbol, name, pinned, quote, spark, advice, goldLink: GoldLink?)>, suggestions, query, loading)>.

PicksScreen: title "Weekly Picks" + `Dates.weekStartLabel(Dates.currentWeekStartIso())` dim + refresh IconButton (recompute). 10 cards: leading rank "01".."10" in gold Inter Bold, symbol + name, ScoreBar(score) with score number, reason bodySmall dim, trailing price-at-pick -> current DeltaPct("since pick"). Tap -> detail. Loading spinner while recompute. VM: observeCurrentWeek + ensureCurrentWeek on init; enrich with current quotes for since-pick delta; StateFlow<PicksState>.

AddTransactionScreen(prefill symbol?): title "Add trade". Segmented Buy/Sell (two FilterChips or a custom pill toggle — gold when selected). Fields: Symbol (uppercase, with search suggestions like watchlist), Shares, Price (per share $), Fees (optional, default 0). Live summary line: "Total: $X" (shares*price+fees for buy; proceeds for sell). Save Button (gold, full width): portfolio.addTransaction(..., source = "MANUAL") then navigate back (onDone callback). Validate: symbol nonempty, shares>0, price>0 — disable Save otherwise. VM holds field state, exposes save(onDone).

## M8 — UI: Bank feed + Settings

Files: `ui/screens/BankFeedScreen.kt`, `BankFeedViewModel.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`.

BankFeedScreen: title "Bank Feed". If !BankNotificationListener.isEnabled(context): gold AurumCard banner "Enable notification access so Aurum can auto-capture your Bank al Etihad trade alerts" + Button -> BankNotificationListener.openSettings(context). Pending section: cards with pkg dim, title bold, text bodySmall, timeAgo; when parsed != null show extracted pills (side ActionBadge-style, symbol, qty, price) and prefilled editable import row (symbol/shares/price TextFields + side toggle) with "Import" (gold) and "Dismiss" (text) buttons -> bankFeed.importEvent / dismissEvent. History section (IMPORTED/DISMISSED) collapsed list with status PillTags. VM: observeEvents split pending/history; import/dismiss functions; also expose listener-enabled state (refresh in onResume via LifecycleResumeEffect or simple refresh() call from screen).

SettingsScreen: title "Settings", back arrow. Cards:
1. "Bank sync" — listener status row (green "Connected" / red "Off" PillTag) + enable button; bankPackages OutlinedTextField (comma-separated, save on change via VM) with helper "Any notification whose app package contains one of these is captured"; autoImport Switch ("Auto-import high-confidence trades").
2. "About" — Aurum wordmark (GoldGradientText), version 1.0, short description, disclaimer bodySmall dim: suggestions are computed from public market data and are not financial advice.
VM wraps settings flows + setters, and listener-enabled state.

---

## Shared UI conventions (all UI modules)

- Screens are top-level `@Composable fun XScreen(...)` taking navigation lambdas, NOT NavController (except AurumRoot which wires them).
- Every screen root: `Column` inside `Modifier.fillMaxSize().background(AurumColors.bg)` with `LazyColumn` content padding 20.dp horizontal; respect Scaffold innerPadding.
- Spacing rhythm: 12.dp inside cards, 14.dp between cards, 28.dp between sections.
- All money via `Fmt`; all deltas via DeltaPct/DeltaMoney; never hardcode colors outside AurumColors.
- Imports: material3 only (no material2). Icons: `androidx.compose.material.icons.Icons.Rounded.*` / `Icons.AutoMirrored.Rounded.ArrowBack`.
- State: `StateFlow` + `collectAsStateWithLifecycle()` (androidx.lifecycle.compose).
- Never block main thread; all repo calls in viewModelScope with Dispatchers untouched (repos handle IO).

---

# Phase 2 — Feature contracts (analysis, reports, budget picks, trade entry)

Design language (MANDATORY, the user rejected the "AI look"): flat fills only — NO gradients anywhere; sentence-case labels (never ALL-CAPS with letterspacing); flat borderless AurumCard; terse factual copy (short sentences, concrete numbers, no chatty em-dash phrasing); sober icons only. Chart series colors: price = AurumColors.text at ~0.85 alpha or textDim, gold = AurumColors.gold, blue accent = AurumColors.info (new), soft fills = gainSoft/lossSoft/goldSoft/infoSoft. Soft translucent FLAT tints are fine; gradients are not.

## P1 — Five-technique engine (`analytics/Techniques.kt`, NEW file, pure Kotlin, no Android imports)

```kotlin
enum class TechniqueVerdict { BULLISH, BEARISH, NEUTRAL }

data class MaTrendData(val closes: List<Double>, val sma20: List<Double?>, val sma50: List<Double?>)   // same size, aligned by index, null until enough history
data class RsiData(val rsi: List<Double?>)                                                             // aligned to the same candles
data class MacdData(val macd: List<Double?>, val signal: List<Double?>, val histogram: List<Double?>)
data class BollingerData(val closes: List<Double>, val upper: List<Double?>, val middle: List<Double?>, val lower: List<Double?>)
data class SupportResistanceData(val closes: List<Double>, val supports: List<Double>, val resistances: List<Double>)  // 1-3 levels each

data class TechniqueResult(
    val key: String,       // "ma" | "rsi" | "macd" | "bollinger" | "sr"
    val name: String,      // "Moving averages" | "RSI momentum" | "MACD" | "Bollinger Bands" | "Support & resistance"
    val verdict: TechniqueVerdict,
    val strength: Int,     // 0..100
    val summary: String    // 1-2 terse sentences with concrete numbers
)

data class FiveDayOutlook(
    val direction: TechniqueVerdict,
    val bullishCount: Int, val bearishCount: Int, val neutralCount: Int,
    val expectedLow: Double, val expectedHigh: Double,   // 5-trading-day range: last close +/- ~1.3*ATR14*sqrt(5), center shifted toward direction
    val confidence: Int,                                  // 0..100 = winning side share of strength-weighted votes
    val headline: String,                                 // e.g. "Leaning bullish over the next 5 days."
    val summary: List<String>                             // 3-5 plain sentences: vote split, expected range, nearest support/resistance, one caveat that this is history-based, not a guarantee
)

data class TechniqueAnalysis(
    val symbol: String,
    val results: List<TechniqueResult>,   // exactly 5, order: ma, rsi, macd, bollinger, sr
    val outlook: FiveDayOutlook,
    val maData: MaTrendData, val rsiData: RsiData, val macdData: MacdData,
    val bollingerData: BollingerData, val srData: SupportResistanceData
)

object Techniques {
    fun analyze(symbol: String, candles: List<Candle>): TechniqueAnalysis?   // null when < 30 daily candles; use last ~120 candles for the series
}
```
Rules: EMA/MACD(12,26,9), Bollinger(20, 2 sigma), rolling SMA/RSI series computed internally (may reuse Indicators for scalars). Support/resistance: swing highs/lows (local extrema, window 3-5) over the last ~90 candles, cluster levels within 1.5%, keep the 1-3 most-touched per side, supports below current price, resistances above. Verdicts: MA — price>sma20>sma50 bullish, price<sma20<sma50 bearish, else neutral (20/50 cross within the last 10 bars raises strength); RSI — <30 bullish (oversold), >70 bearish, else neutral; MACD — macd>signal with rising histogram bullish, opposite bearish; Bollinger — close at/below lower band bullish (reversion), at/above upper bearish, narrow width (<4% of mid) neutral "squeeze, breakout pending"; SR — within 2% above nearest support bullish, within 2% below nearest resistance bearish, else neutral. Outlook direction needs >=55% strength-weighted share, else NEUTRAL with a "Mixed signals" headline.

## P2 — Analysis UI (`ui/components/TechniqueCharts.kt`, `ui/screens/AnalysisScreen.kt`, `ui/screens/AnalysisViewModel.kt` — all NEW)

```kotlin
@Composable fun MaTrendDiagram(data: MaTrendData, modifier: Modifier = Modifier)             // price line + SMA20 (gold) + SMA50 (info); tiny legend row of dot+label chips below
@Composable fun RsiDiagram(data: RsiData, modifier: Modifier = Modifier)                     // 0..100 pane, flat tint band between 30 and 70, dashed lines at 30/70 with labels, RSI line gold
@Composable fun MacdDiagram(data: MacdData, modifier: Modifier = Modifier)                   // centered zero line, histogram bars in gain/loss tints, MACD gold line, signal info line, legend
@Composable fun BollingerDiagram(data: BollingerData, modifier: Modifier = Modifier)         // flat infoSoft fill between upper/lower, middle dashed textDim, price line
@Composable fun SupportResistanceDiagram(data: SupportResistanceData, modifier: Modifier = Modifier)  // price line + dashed horizontal support lines (gain) and resistance lines (loss), right-edge price labels
```
All Canvas-drawn in the style of ui/components/Charts.kt (same normalize/smooth-path approach; TextMeasurer for labels). Diagrams ~170.dp tall inside their cards. No gradients.

`AnalysisScreen(symbol: String, onBack: () -> Unit)` (package com.aurum.invest.ui.screens): back arrow + symbol title + "5-technique analysis" dim subtitle. Content: (1) Outlook card — outlook.headline (titleMedium), three PillTags ("3 bullish" gain / "1 bearish" loss / "1 neutral" dim), "Next 5 days" range row: expectedLow and expectedHigh at the ends of a thin flat bar with a marker dot at the current price, then outlook.summary sentences as a bodyMedium list; (2) five cards, one per TechniqueResult in order: header row (name titleSmall + verdict PillTag colored gain/loss/dim + "Strength N" dim label), the matching diagram, summary bodySmall dim. AnalysisViewModel (AndroidViewModel pattern): loads market.getDailyCandles(symbol, 180) + market.getQuote(symbol), runs Techniques.analyze, exposes StateFlow<AnalysisState(loading, analysis: TechniqueAnalysis?, price: Double?)> + refresh(). CircularProgressIndicator while loading; EmptyState("Not enough history", ...) when analyze returns null.

## P3 — Reports (`analytics/ReportsEngine.kt`, `ui/screens/ReportsScreen.kt`, `ui/screens/ReportsViewModel.kt` — all NEW)

```kotlin
enum class ReportPeriod { WEEK, MONTH }

data class TradeLine(
    val symbol: String, val side: String, val shares: Double, val price: Double,
    val ts: Long, val realizedPl: Double?   // non-null for SELL rows only
)

data class PeriodReport(
    val periodKey: String,        // week: ISO Monday "2026-08-03"; month: "2026-08"
    val label: String,            // "Week of Aug 3" (Dates.weekStartLabel) / "August 2026"
    val startTs: Long, val endTs: Long,
    val buysCount: Int, val buysTotal: Double,
    val sellsCount: Int, val sellsTotal: Double,
    val realizedPl: Double,
    val bestTrade: TradeLine?, val worstTrade: TradeLine?,   // highest / lowest realizedPl among the period sells
    val trades: List<TradeLine>   // chronological
)

object ReportsEngine {
    // Replay the FULL ordered ledger (ts then id asc) with weighted-average cost per symbol
    // (same math as PortfolioRepository: buys fold fees into cost; sell realized = qty*(price-avg) - fees,
    // qty clamped to held). Assign each transaction to its local-time week (Monday start) and month.
    // Newest period first. Periods with no trades are omitted.
    fun build(transactions: List<TransactionEntity>, period: ReportPeriod): List<PeriodReport>
}
```
`ReportsScreen(onBack: () -> Unit)` (package com.aurum.invest.ui.screens): back arrow + "Reports" title. Flat two-option toggle Weekly | Monthly (selected = flat gold fill, like Add-trade Buy/Sell). PeriodReport cards, newest first: label + realized P/L (titleMedium, deltaColor), StatTile row ("Buys" -> "3 · $4,510", "Sells" -> "1 · $1,620"), best/worst lines ("Best: AAPL +$120.30") when present, and an expandable chronological trade list (tap header to expand; rows: side PillTag Buy=gain/Sell=loss, symbol, qty @ price, dateShort, realized P/L on sells). EmptyState when there are no trades at all. ReportsViewModel: observes portfolio.observeTransactions(), builds both lists via ReportsEngine, exposes StateFlow<ReportsState(weekly, monthly, loading)>.

## P4 — Under-$25 weekly picks (EDITS: `analytics/WeeklyPicker.kt`, `data/repo/PicksRepository.kt`, `work/WeeklyPicksWorker.kt`, `ui/screens/PicksScreen.kt`, `ui/screens/PicksViewModel.kt`)

WeeklyPicker additions:
```kotlin
companion object {
    val BUDGET_EXTRA: List<Pair<String, String>>   // ~18 liquid low-priced US names (F, T, NOK, PLUG, SOFI, NIO, RIVN, LCID, AAL, CCL, NCLH, SNAP, WBD, M, RIG, TLRY, VALE, BBD, HBAN, KEY ...)
}
suspend fun computeBudgetPicks(weekStart: String, maxPrice: Double = 25.0, count: Int = 5): List<WeeklyPick>
// Candidates = UNIVERSE + BUDGET_EXTRA (dedup by symbol). Same scoring as computePicks, but only
// symbols whose latest price (quote, else last close) < maxPrice. Top [count]. reason must lead
// with the price, e.g. "$4.12 — +6.3% in 5 days on 1.6x volume, RSI 55, 2.1% below the 20-day high".
```
PicksRepository additions (budget picks live in the SAME weekly_picks table with a suffixed week key — NO schema change, NEVER bump the Room version):
```kotlin
companion object { const val BUDGET_SUFFIX = ":U25" }
fun observeBudgetWeek(): Flow<List<WeeklyPick>>       // picksDao.observeWeek(Dates.currentWeekStartIso() + BUDGET_SUFFIX)
suspend fun ensureBudgetWeek(): List<WeeklyPick>
suspend fun recomputeBudget(): List<WeeklyPick>       // clearWeek(key) + computeBudgetPicks(Dates.currentWeekStartIso()) stored with weekStart = key
```
WeeklyPicksWorker.doWork additionally calls picks.recomputeBudget() (each call in its own try/catch).
PicksScreen NEW signature: `PicksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit)`. After the top-10 list add SectionHeader "Under $25 · weekly watch" + up to 5 budget cards: rank, symbol + name, current price bold (+ since-pick delta), ScoreBar + score, reason (bodySmall dim), and a trailing analysis affordance (icon Icons.Rounded.QueryStats or a small TextButton "Analysis") -> onOpenAnalysis(symbol); card tap -> onOpenDetail(symbol). The refresh button recomputes BOTH lists. Main top-10 cards ALSO get the same analysis affordance -> onOpenAnalysis. PicksViewModel: expose budget rows alongside main rows (same live-quote enrichment), ensureBudgetWeek on init.

## P5 — Trade entry upgrades (EDITS: `ui/screens/AddTransactionScreen.kt`, `ui/screens/AddTransactionViewModel.kt`)

NEW signature: `AddTransactionScreen(prefillSymbol: String?, prefillSide: String?, onDone: () -> Unit)` — prefillSide is "BUY" / "SELL" / null (null -> BUY). The Buy/Sell toggle initializes from prefillSide so the user does NOT pick the side again.

Amount-based entry: alongside Shares add an "Invested amount ($)" field (below the Shares/Price row). Bidirectional sync, last-edited-field wins: editing Amount with a valid Price sets shares = amount / price (round to 4 decimals; dim helper line "= 6.6543 shares" under the amount field); editing Shares sets amount = shares * price; editing Price recomputes whichever of shares/amount the user did NOT edit last. Saving uses the shares value. Validation unchanged (symbol nonempty, shares > 0, price > 0).

Position awareness: the VM loads the open position for the entered symbol (portfolio.positionsNow() + PortfolioRepository.isOpen). When held, show under the symbol field: "You hold 20 shares · avg $150.25" (bodySmall dim). When side = SELL and the symbol is held, show a "Sell all shares" toggle row (Text bodyMedium + material3 Switch, flat colors): ON -> shares set to the full held quantity, amount syncs, Shares and Amount fields disabled while on; OFF -> editable again. The toggle appears ONLY for held symbols on the sell side.

## Wiring (done by the coordinator AFTER these modules land — agents must NOT touch these files):
- AurumRoot: Routes.ANALYSIS "analysis/{symbol}" + analysis(symbol), Routes.REPORTS "reports", ADD route gains &side={side}; new composable entries; updated screen callbacks.
- PositionDetailScreen: Sell/Buy pass their side; adds a "5-day analysis" entry point; signature gains onOpenAnalysis.
- DashboardScreen: header gains a reports icon. WatchlistScreen rows gain an analysis icon.

---

# Phase 3 — 11-technique analysis (`analytics/Techniques.kt` extension)

TechniqueAnalysis gains `val timestamps: List<Long>` (epoch millis of each candle used, index-aligned with every series) and six new data fields; `results` becomes EXACTLY 11 entries in order: ma, rsi, macd, bollinger, sr, fvg, fib, ichimoku, stoch, obv, adx. The outlook aggregates all 11 (same strength-weighted rule; first summary sentence says "N of 11 techniques...").

```kotlin
data class FvgZone(val startIndex: Int, val low: Double, val high: Double, val bullish: Boolean, val filled: Boolean)
data class FvgData(val closes: List<Double>, val zones: List<FvgZone>)                    // max 12 most recent zones
data class FibonacciData(val closes: List<Double>, val swingLow: Double, val swingHigh: Double, val levels: List<Pair<String, Double>>) // "0.236".."0.786" + "0.0"/"1.0", price levels from window swing low->high
data class IchimokuData(val closes: List<Double>, val tenkan: List<Double?>, val kijun: List<Double?>, val senkouA: List<Double?>, val senkouB: List<Double?>) // senkou arrays displaced +26 and aligned to candle index (null where undefined)
data class StochasticData(val k: List<Double?>, val d: List<Double?>)                     // %K(14) smoothed 3, %D = SMA3 of K
data class ObvData(val obv: List<Double>)                                                  // cumulative on-balance volume
data class AdxData(val adx: List<Double?>, val plusDi: List<Double?>, val minusDi: List<Double?>)  // Wilder 14
```

New technique keys/names/verdicts:
- "fvg" / "Fair value gap": 3-candle gaps over the window (bullish: high[i-2] < low[i], zone = [high[i-2], low[i]]; bearish mirrored). Zone filled when a later candle trades fully through it (bullish: low <= zone.low; bearish: high >= zone.high). Verdict: nearest UNFILLED bullish zone below price within 5% -> BULLISH (gap acts as support magnet); nearest unfilled bearish zone above within 5% -> BEARISH; else NEUTRAL. Summary cites the zone bounds.
- "fib" / "Fibonacci retracement": swing low/high of the window; price within 1.5% of the 0.382/0.5/0.618 level while above the 0.618 -> BULLISH (buy-the-dip zone); price below the 0.786 -> BEARISH (retracement failed); within 1.5% of 0.236 or above -> mild BULLISH momentum; else NEUTRAL. Summary names the nearest level and its price.
- "ichimoku" / "Ichimoku Cloud": tenkan(9), kijun(26), senkouA=(tenkan+kijun)/2 displaced +26, senkouB=(52-high+low)/2 displaced +26 (needs highs/lows from candles). Price above the cloud with tenkan>kijun -> BULLISH; below the cloud -> BEARISH; inside -> NEUTRAL.
- "stoch" / "Stochastic oscillator": %K<20 with K>=D (crossing up) -> BULLISH; %K>80 with K<=D -> BEARISH; else NEUTRAL with direction noted.
- "obv" / "On-balance volume": 20-bar OBV slope up while price 20-bar change <= 0 -> BULLISH (accumulation divergence); OBV slope down while price up -> BEARISH (distribution divergence); slopes agreeing -> NEUTRAL confirming, verdict follows price direction at lower strength.
- "adx" / "ADX trend strength": ADX>25 and +DI>-DI -> BULLISH; ADX>25 and -DI>+DI -> BEARISH; ADX<20 -> NEUTRAL "no trend".

All summaries terse with concrete numbers, sentence case. Existing five techniques and their rules unchanged.

## Phase 3 — diagram viewport (TechniqueCharts.kt, coordinator-owned)

Every diagram gains `timestamps: List<Long>` + `viewport: DiagramViewport` and renders only the visible window with 3 date labels (first/mid/last visible, Fmt.dateShort) along the bottom. `rememberDiagramViewport(total)` + gestures: two-finger pinch zooms (up to ~8x, min 15 points), single-finger horizontal drag pans (vertical drags still scroll the page), double-tap steps zoom 1x -> 2x -> 4x -> reset. Six new diagram composables for the Phase-3 data classes.

---

# Phase 5 — the enterprise engine suite (v5.0)

The Wealth section was rebuilt around five standalone, portfolio-aware engines. The 4-month goal plan (`WealthPlanner`) and its setup form were REMOVED — `SettingsRepository.wealthBase/wealthTarget` remain in DataStore but nothing reads them.

## E1 — 35-technique board (`analytics/Techniques.kt`)

`Techniques.analyze` returns EXACTLY 35 results, the original 20 followed by: `stochrsi` (StochRSI 14,14,3,3), `roc` (ROC 12), `trix` (TRIX 15 + EMA9 signal), `uo` (Ultimate Oscillator 7/14/28), `vortex` (VI 14), `efi` (Force Index EMA13), `cmo` (Chande 14), `dpo` (non-centered DPO 20), `kst` (Pring KST + SMA9), `hull` (HMA 20), `supertrend` (10, 3x ATR), `chandelier` (22, 3x ATR), `vwap` (rolling 20-day VWAP), `ad` (Chaikin A/D line), `pivot` (monthly floor-trader pivots from the last COMPLETED ET month). Each has a data class on `TechniqueAnalysis`, a diagram mapping in `AnalysisScreen`, and a full write-up in `TechniqueExplain`.

## E2 — integrity engine (`analytics/TechniqueEvaluator.kt`)

`LOOKBACK_DAYS = 252` (a full year), `TOP_TECHNIQUES = 20`. A grade is produced only when all 252 sessions can be replayed after indicator warm-up and the five-session forward window. `TechniqueEvaluation.ranked()` orders the board by measured merit (trusted > graded > ungraded, then hit rate, then evidence); `rankByKey()` gives 1-based ranks. The analysis screen sorts cards by rank, shows the top 20 with a fold toggle for the rest, and prints each card's 12-month record. `AnalysisViewModel` fetches 550 daily candles (Yahoo range "2y", added in `YahooClient`) and caches evals under a versioned `techeval:v5:SYM` key (6 h).

## E3 — money-flow engine (`analytics/MoneyFlowEngine.kt`)

`MoneyFlowEngine(market, news).compute(): MoneyFlowReport?` — per sector ETF: up-day dollar-volume share (10 sessions), CMF(20), MFI(14), OBV slope normalized by average volume, relative strength vs SPY (20d), volume pace, member breadth (top 8 themes only; -1 = not measured), news tone (top 6). The report fails closed when the SPY baseline cannot be measured. Fixed 0..100 `flowScore`; `FlowVerdict.INFLOW/OUTFLOW` requires >= 3 of the 4 money signals to agree, else `NEUTRAL`. Cached under a versioned `moneyflow:v2` key (30 min) in `WealthRepository`.

## E4 — portfolio advisor (`analytics/PortfolioAdvisor.kt`)

`PortfolioAdvisor(market, news).review(views, sectors, flow, strategy, pulse, unpriced): PortfolioReview?` — per holding a `HoldingVerdict` (`HOLD/TAKE_PROFIT/TRIM/SELL/CUT_LOSS` + headline + whenText + measured whyPoints + forward target/stop from the stock's own supports and ATR + `sessionMovePct` from the live quote), an `AllocationLine` plan (current vs suggested weight; caps: 30% trim line, 22% suggested ceiling), sector notes, and `RebalanceMove`s (sector >= 35% -> sell its weakest name back to 30%, buy the board-approved lead pick of the strongest inflowing theme the book is light in; no passing candidate -> hold cash, said out loud). Live context per holding: session move, 20-day relative strength vs SPY (from the flow report's measured baseline), and the holding's own sector money flow (exact WATCH/StockCatalog membership first, else unambiguous Yahoo-sector theme mapping; mixed reads are reported as mixed, never picked from). A high-confidence sector OUTFLOW (>= 75) with a non-bullish board, negative P/L and negative relative strength escalates HOLD -> TRIM. `PortfolioReview.marketNote` carries the pulse regime line. Holdings that cannot be measured (< 30 sessions of history, no price, board failure) land in `PortfolioReview.unverified` by name with the measured reason — they never withhold the verdicts of holdings that can (null only when NOTHING is verifiable). Cached under versioned `portfolioreview:v3` with a full position/cost-basis fingerprint — any relevant trade invalidates it; the fingerprint-matching stale copy (honest computedAt shown) is served when a recompute fails. `WealthViewModel` re-runs the review every 2 minutes while the screen is collected (quotes are the only network cost — every other input is cache-served). If the ledger itself cannot be read, portfolio advice fails closed; market-wide next-session and next-week scans still run and explicitly state that their portfolio overlay is unavailable.

`PortfolioGradeEngine.evaluate(verdicts, book, flow, pulse, strategy): PortfolioGrade` (`analytics/PortfolioGrade.kt`) — the standalone grade engine: the verified book scored on FIXED scales against seven named elite-investor rules: concentration control 20 (Buffett/position+sector caps), loss discipline 15 (O'Neil 8% rule), winners riding 15 (Livermore), trend alignment 15 (Weinstein 50-day), relative strength 15 (O'Neil vs SPY), money-flow alignment 10 (institutional), regime fit 10 (Livermore/tape). Unmeasurable disciplines are excluded from BOTH sides of the score and labeled "not measured" (score shown as `score`/`maxScore`). Every discipline below the green line (80% of its points) carries `GradeAction`s (SELL/TRIM/ROTATE/BUY/REVIEW): the concrete move, the arithmetic `pointsNow -> pointsAfter` computed from today's measured numbers (never predictions), and — for any buy — an EXPLICIT ticker tag (`buySymbol`, `buyName`, `buyEntry` from the pick's measured entry). Buy candidates come only from the sector-gap strategy's board-approved picks in non-outflow themes (inflow first, by flow score then board votes), excluding held symbols — this is how the engine hears market move (pulse), volume equations (CMF/MFI/OBV via flow), techniques/charts (35-board picks), news (pick + holding tone), and trending sectors. The weakest discipline's first action becomes `suggestion`. Rendered by `PortfolioGradeCard` as expandable per-discipline dropdown rows (principle + fixes + "Buy TICKER" pill); tapping an action opens the relevant detail screen. `HoldingVerdict` carries the structured inputs: `above50: Boolean?`, `rel20Pct: Double?`, `flowVerdictName: String`. Review cache `portfolioreview:v5`; the repo always builds the strategy when flow is present so the grade engine has candidates.

## E5 — next-session engine (`analytics/NextSessionEngine.kt`)

`NextSessionEngine(market).compute(held): NextSessionReport?` — whole-market screener pool -> liquidity + continuation gates -> 26-name shortlist -> deep read (35-board, bearish disqualifies; analog-day study over ~130 completed sessions: same-direction move within ±2pp, close position within ±25pp; probUp = share of analogs that closed higher next day, -1 below `MIN_ANALOGS`=6). Fixed-scale score; 10 picks with entry/target (analog average, ATR-capped)/stop (structural). `alert` fires only when score >= 78 AND probUp >= 65 over >= 8 analogs AND bullish board >= 60%. `NextSessionWorker` (30-min cadence, acts POST 16:00–20:00 ET and PRE from 7:00 ET) refreshes the report and posts `Notify.nextSessionAlert` once per symbol per ET day (tracked under `nextsession:notified:<date>`). Delivered alerts alone are marked notified. The market report is cached under versioned `nextsession:v2` (20 min), then current holdings are applied dynamically.

## Rewired modules

- `SectorStrategy.build(trends, book, investable, flow)` — themes ranked by flow when present; OUTFLOW themes get no allocation; per-theme pool = WATCH + matching `StockCatalog` shelf (cap 14); `SectorGap` gains `flowScore`/`flowVerdict`.
- `NextWeekPlanner.build(..., flow)` — sectors chosen by flow (inflows first); `investable` = invested book at cost (0 -> percentage-only split, `allocationPct` from weight shares).
- `MarketPulse` — `nextDay` is now always empty; the next-session read belongs to E5. `bestYesterday` and the 0-100 rating unchanged.
- `WealthRepository(cacheDao, market, news, portfolio)` — dropped inputs/plan APIs; added `getMoneyFlow/recomputeMoneyFlow`, `getPortfolioReview/recomputePortfolioReview`, `getNextSession/recomputeNextSession`, `nsNotifiedToday/markNsNotified`, `recomputeWeekly` (called by `WeeklyPicksWorker`), `getStrategy(book)`.
- `WealthScreen` sections: portfolio verdicts (open), next session (open), next week, market pulse, money flow, your book, sector gaps, theme lens, movers — goal and this-week-plan sections are gone.
- `TechniqueCharts.kt` gains generic `OscillatorDiagram`, `TwoLineDiagram`, `OverlayDiagram`(+`OverlaySeries`), and `ObvDiagram` gains a trailing `label` param.
