# Aurum v3.5 — "Gap & Go" discipline layer for the Pre-market desk

You are working in the Aurum repo (native Android, Kotlin + Jetpack Compose). Before writing any
code, read these files and code against them exactly:

- `CONTRACTS.md` — module architecture, shared components, UI conventions. All of it applies.
- `app/src/main/kotlin/com/aurum/invest/analytics/PreMarketPicker.kt` — the existing engine this feature extends.
- `app/src/main/kotlin/com/aurum/invest/ui/screens/PreMarketScreen.kt` + `PreMarketViewModel.kt`
- `app/src/main/kotlin/com/aurum/invest/data/repo/TargetsRepository.kt` — the pattern for storing user settings in the cache table.
- `app/src/main/kotlin/com/aurum/invest/data/model/Models.kt` (ExtendedHours), `data/remote/YahooClient.kt` (fetchExtendedHours), `data/repo/NewsRepository.kt`, `ui/components/Common.kt`.

Hard constraints (non-negotiable):
- NO new libraries; do not touch any gradle file. NEVER bump the Room version — persist through the
  existing `CacheEntity` key/value table, exactly like `TargetsRepository` does.
- Design lock: flat fills only, no gradients; sentence-case labels; terse factual copy with concrete
  numbers; colors only from `AurumColors`; money/percent formatting only via `Fmt`; reuse
  `AurumCard`, `SectionHeader`, `StatTile`, `PillTag`, `DeltaPct`, `EmptyState`.
- ViewModel pattern from CONTRACTS.md (AndroidViewModel + `(app as AurumApp).container`,
  StateFlow + `collectAsStateWithLifecycle()`). Repos never throw — return null/empty on failure.

## What is being built

The Pre-market tab currently ranks movers and measures the odds of the user's daily target
(default 2.06%). This feature turns each pick into a **complete trade plan with discipline rails**,
based on the gap-and-go strategy:

1. Only trade gaps that have a **real catalyst** (news) behind them.
2. Enter on a **break above the pre-market high** — never chase, never buy a fade.
3. Every trade has a **hard stop before entry** and is only allowed when reward:risk ≥ 1.5.
4. Position size comes from a **risk budget** (default 1% of account per trade), not from feel.
5. **Daily rails**: max 2 trades a day, stop for the day at −2R. No qualifying setup → the desk
   says "stand aside" instead of pushing a weak pick.
6. A **journal** records every trade taken and computes win rate and expectancy, so the data —
   not hope — says whether the strategy is working.

Everything stays measured-not-guessed, matching the app's existing honesty (e.g. the desk already
says when the pre-market list is a fallback to yesterday's session).

---

## M1 — Pre-market high/low (EDITS: `Models.kt`, `YahooClient.kt`, `MarketRepository.kt`)

`ExtendedHours` gains two nullable fields with defaults (older cached JSON must keep parsing):

```kotlin
val preMarketHigh: Double? = null,   // highest pre-market print of the CURRENT session
val preMarketLow: Double? = null,
```

`YahooClient.fetchExtendedHours` already walks the pre-market candle window bounded by
`meta.currentTradingPeriod` — compute the max high / min low over that same window (null when the
window has no prints). `MarketRepository`'s ExtendedHours JSON (de)serialization gains both fields,
absent-tolerant.

## M2 — Catalyst scan (`analytics/CatalystScan.kt` — NEW, pure Kotlin, no Android imports)

```kotlin
enum class CatalystType { EARNINGS, GUIDANCE, FDA, MERGER, CONTRACT, ANALYST, NONE }

data class Catalyst(
    val type: CatalystType,
    val headline: String?,     // the matching headline, null when NONE
    val ageHours: Int          // hours since that headline; 0 when NONE
)

object CatalystScan {
    // Headlines no older than 48h. Priority when several match: FDA > MERGER > EARNINGS >
    // GUIDANCE > CONTRACT > ANALYST. Case-insensitive keyword lexicon on the title:
    //   EARNINGS: earnings, eps, revenue, beat, beats, miss, results, quarter, q1..q4
    //   GUIDANCE: guidance, raises, raised, outlook, forecast, hikes
    //   FDA: fda, approval, approves, clearance, trial, phase
    //   MERGER: merger, acquire, acquisition, buyout, takeover, stake, bid
    //   CONTRACT: contract, award, awarded, order, deal, partnership
    //   ANALYST: upgrade, upgraded, downgrade, price target, initiates, overweight, outperform
    fun classify(items: List<NewsItem>, nowMs: Long = System.currentTimeMillis()): Catalyst
}
```

## M3 — Plan engine (`analytics/GapGoPlanner.kt` — NEW, pure Kotlin)

```kotlin
enum class PlanVerdict { TRADE, WATCH, STAND_ASIDE }

data class GateCheck(val name: String, val passed: Boolean, val detail: String)  // detail = one terse sentence with the numbers

data class GapGoPlan(
    val symbol: String,
    val verdict: PlanVerdict,
    val catalyst: Catalyst,
    val entryTrigger: Double,      // buy only on a break ABOVE this
    val chaseCeiling: Double,      // trigger * 1.005 — past this, do not chase
    val stop: Double,
    val stopBasis: String,         // "pre-market low" | "0.6× ATR" | "capped at 2%"
    val target: Double,            // trigger * (1 + targetPct/100)
    val rr: Double,                // (target - trigger) / (trigger - stop)
    val riskPctOfEntry: Double,    // (trigger - stop) / trigger * 100
    val shares: Int,               // risk-budget sizing, 0 when budget too small
    val positionDollars: Double,
    val riskDollars: Double,
    val sizeNote: String,          // "" or why the size was capped / zero
    val gates: List<GateCheck>     // every gate, passed or not, in a fixed order
)

object GapGoPlanner {
    fun plan(
        pick: PreMarketPick,
        catalyst: Catalyst,
        preMarketHigh: Double?, preMarketLow: Double?,
        accountValue: Double, riskPct: Double
    ): GapGoPlan
}
```

Rules (exact):
- `entryTrigger = max(preMarketHigh ?: pick.price, pick.price)` — the break of the pre-market high,
  never below the live print.
- `stop`: start from `entryTrigger - 0.6 * atr` (recover atr as `pick.atrPct / 100 * pick.price`).
  If `preMarketLow` exists and sits ABOVE that level and within 3% below the trigger, use the
  pre-market low instead (basis "pre-market low"). Finally cap: if risk exceeds 2% of the trigger,
  `stop = entryTrigger * 0.98`, basis "capped at 2%".
- Gates, in this order (each becomes a `GateCheck`):
  1. **Catalyst** — passes when `type != NONE`, OR the gap is under 5% (a modest gap needs less
     justification; detail says so). A gap ≥ 5% with NONE fails.
  2. **Gap band** — passes when `preMarketPct` in 1.5..8.0. In 8.0..12.0 it fails with a fade
     warning. Above 12.0 it fails hard.
  3. **Evidence** — passes when `hitRatePct >= 55`. In 40..55 it fails as "coinflip". Below 40
     fails hard.
  4. **Gap hold** — passes when `gapHoldRatePct >= 50` (or no gap-day history; say so).
  5. **Reward:risk** — passes when `rr >= 1.5`.
- Verdict: TRADE when ALL five pass. STAND_ASIDE when any hard-fail fired (gap > 12 without a
  major catalyst — FDA/MERGER/EARNINGS keep it at WATCH; hit rate < 40; catalyst gate failed on a
  gap ≥ 8). Otherwise WATCH.
- Sizing: `riskDollars = accountValue * riskPct / 100`;
  `shares = floor(riskDollars / (entryTrigger - stop))`; `positionDollars = shares * entryTrigger`.
  When positionDollars exceeds 25% of the account, scale shares down to that cap and say so in
  `sizeNote`. When shares == 0, sizeNote = "risk budget too small at this price".

## M4 — Journal + rails (`data/repo/GapGoRepository.kt` — NEW; `analytics/Expectancy.kt` — NEW)

```kotlin
data class GapGoTrade(
    val id: Long,                  // creation timestamp millis, unique
    val dateIso: String,           // local trade date
    val symbol: String,
    val plannedEntry: Double, val plannedStop: Double, val plannedTarget: Double,
    val shares: Int,
    val actualEntry: Double?,      // set at log time (prefilled with plannedEntry, editable)
    val actualExit: Double?,       // null while open
    val resultR: Double?,          // (exit - entry) / (entry - plannedStop); null while open
    val resultPct: Double?,
    val catalystType: String,
    val verdictAtEntry: String,    // TRADE | WATCH — journal records what the desk said
    val note: String
)

class GapGoRepository(private val cacheDao: CacheDao) {
    // Journal: ONE JSON array under cache key "gapgo:journal" (org.json, same style as
    // PreMarketPicker.toJson/fromJson). Read-modify-write; never throw.
    suspend fun trades(): List<GapGoTrade>                       // newest first
    suspend fun logTrade(t: GapGoTrade)
    suspend fun closeTrade(id: Long, exitPrice: Double)          // computes resultR/resultPct
    suspend fun deleteTrade(id: Long)

    // Settings, one cache key each (TargetsRepository pattern), 0/absent = default:
    suspend fun riskPct(): Double            // "gapgo:riskpct", default 1.0
    suspend fun setRiskPct(v: Double)
    suspend fun accountOverride(): Double?   // "gapgo:account"; null -> caller uses portfolio value
    suspend fun setAccountOverride(v: Double?)
    suspend fun maxTradesPerDay(): Int       // "gapgo:maxtrades", default 2
    suspend fun setMaxTradesPerDay(v: Int)
    suspend fun dailyStopR(): Double         // "gapgo:dailystop", default 2.0 (meaning -2R)
    suspend fun setDailyStopR(v: Double)

    // Today's rails, computed from the journal:
    data class DayState(
        val tradesToday: Int, val openToday: Int, val dayR: Double,
        val allowed: Boolean, val blockedReason: String   // "" when allowed
    )
    suspend fun dayState(dateIso: String): DayState
    // allowed = tradesToday < maxTrades AND dayR > -dailyStopR
    // blockedReason: "2 trades taken — plan says done for today." / "Down 2.0R — stop for today."
}

data class ExpectancyStats(
    val closed: Int, val wins: Int, val losses: Int, val winRatePct: Double,
    val avgWinR: Double, val avgLossR: Double,       // avgLossR is negative
    val expectancyR: Double,                          // winRate*avgWinR + lossRate*avgLossR
    val profitFactor: Double,                         // gross win R / |gross loss R|; 0 when no losses yet
    val totalR: Double,
    val verdictLine: String
)

object Expectancy {
    // A closed trade with |resultR| < 0.05 counts as scratch: neither win nor loss, but
    // it does count in [closed] and totalR.
    // verdictLine thresholds: closed < 10 -> "Not enough closed trades to judge — keep logging."
    // expectancyR >= 0.2 -> "Positive expectancy — the edge is holding."
    // -0.1..0.2          -> "Expectancy is thin — trade minimum size while it proves itself."
    // < -0.1             -> "Negative expectancy — stop trading this setup and review the journal."
    fun compute(trades: List<GapGoTrade>): ExpectancyStats
}
```

Wire `GapGoRepository` into `AurumApp.container` as `gapgo` (constructor: `db.cacheDao()`).

## M5 — Desk UI (EDITS: `PreMarketScreen.kt`, `PreMarketViewModel.kt`)

The ViewModel, after computing picks, builds plans for the listed picks: fetch news
(`news.getNews(symbol, dailyCandles)` — chunk 5 concurrent, failures → empty list → catalyst NONE),
fetch extended-hours for pre-market high/low (already-cached calls are fine), read
`accountValue = gapgo.accountOverride() ?: portfolio summary total value` and `riskPct`, then
`GapGoPlanner.plan(...)` per pick. Expose alongside each pick; also expose `DayState` and the
settings values in the screen state.

Screen changes (keep every existing element; this is additive):

1. **Discipline card** at the top, under the existing target editor:
   - Row: "Risk per trade" + value ("1.0% ≈ $52") · "Account" + value · "Trades today 0 of 2" ·
     day R when nonzero.
   - State pill: "Trading allowed" (gain) / blockedReason (loss).
   - An edit affordance (same interaction pattern as the existing target editor) for risk %,
     account override (blank = use portfolio value), and max trades.
   - One dim footer line: "History-based odds, not a promise. Two rules: no catalyst, no trade;
     no stop, no entry."
2. **Per-pick plan block** inside each existing card, after the current content:
   - Verdict pill: "Trade" (gain) / "Watch" (gold) / "Stand aside" (dim).
   - Catalyst line: PillTag with the type (dim when NONE: "no catalyst") + the headline
     (bodySmall, dim, maxLines 1, ellipsis) when present.
   - Plan rows (only when verdict != STAND_ASIDE): "Buy above $X · don't chase past $Y",
     "Stop $Z (basis) · risk N.N%", "Target $T · R:R 1.8", "Size: 12 shares ≈ $618, risking $52".
   - Failed gates as dim bodySmall lines prefixed "× " (e.g. "× Gap 9.4% — gaps this size often
     fade after the open").
   - "Log trade" TextButton on TRADE and WATCH cards: opens a small confirm sheet/dialog
     (actual entry prefilled with the trigger, editable; note field optional) →
     `gapgo.logTrade(...)`. Disabled with the blockedReason when `!allowed`.
3. **Stand-aside note**: when no pick earns TRADE, a flat card above the list:
   "Nothing passes all five gates today. Standing aside is a position." (EmptyState style copy,
   not an EmptyState — the list below still renders.)
4. Header gains a journal icon (`Icons.Rounded.FactCheck`) → navigate to the journal route.

## M6 — Journal UI (`ui/screens/GapGoJournalScreen.kt` + `GapGoJournalViewModel.kt` — NEW; EDITS: `ui/nav/AurumRoot.kt`)

Route: `Routes.GAPGO_JOURNAL = "gapgo-journal"`, navigated from the Pre-market header only.

Screen ("Gap & go journal", back arrow):
1. **Expectancy card**: verdictLine (titleSmall), StatTile row: "Win rate" ("62% · 13 of 21"),
   "Expectancy" ("+0.31R", deltaColor by sign), "Profit factor". Second dim row: avg win / avg
   loss in R, total R. Hidden (EmptyState "No trades logged yet", invite copy) when journal empty.
2. **Open trades**: cards with symbol, dateShort, planned entry/stop/target, shares; an exit-price
   field + "Close" button → `closeTrade`; result preview updates as the field is typed.
3. **History**: rows — symbol bold, dateShort dim, catalyst PillTag, resultR colored by
   `deltaColor` ("+1.8R"), resultPct dim, note (when present) bodySmall dim. Long-press or a
   trailing icon deletes (confirm first).

## M7 — Docs + verify

- README.md: extend the Pre-market bullet with the gap-and-go layer (catalyst gate, break-of-high
  entry, risk-budget sizing, daily rails, expectancy journal) in the same voice as the rest.
- CONTRACTS.md: append a "Phase — Gap & Go" section recording the new/edited module signatures
  (same format as existing phases).
- Build must pass: `gradlew.bat :app:assembleDebug` (JDK 17). Fix every error and warning your
  change introduces. Do not commit unless asked.

Manual checks to walk through before declaring done:
- Evening/weekend (no pre-market prints): plans still render off the fallback data, catalyst scan
  still works, nothing crashes, copy still says the list is the last session's.
- A pick with no news → catalyst NONE → gap ≥ 5% cards show STAND_ASIDE with the catalyst gate
  failed, and modest-gap cards can still be WATCH/TRADE.
- Log → close round-trip updates DayState (trades today, day R) and Expectancy immediately.
- Old cached ExtendedHours JSON (without high/low) still parses.
