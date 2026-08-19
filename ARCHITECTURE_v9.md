# Aurum v9 — Architecture

**Base:** v3.8 (`84bdd50`, versionCode 26, Room schema v2, 0 tests)
**Evaluating:** v3.9 → v8.6 (132 files, +32,619 / −4,074)
**Date:** 2026-08-19

---

## 0. Summary

The v3.9→v8.6 body of work contains the app's best asset — a ledger that tells the
truth and an honesty discipline no competitor bothers with — wrapped around a
structure that cannot scale. Thirty-four analytics files, nineteen of which open
their own network connections, eleven of which produce the same artifact in eleven
incompatible shapes.

The freeze that v8.3, v8.4, v8.5 and v8.6 each took a run at is not a client bug.
It is the architecture emitting ~500 requests for one screen open. v8.6's own
commit message says the method was wrong — "reasoning about which caller was
greedy" — and it is right. The fix is not a better gate. It is a data layer, so
that the requests are never made.

This document says what to re-add, what to cut, and what shape the rebuild takes.

---

## 1. Verdict on v3.9 → v8.6

### 1.1 KEEP — load-bearing, re-add substantially as-is

| What | Version | Why it survives |
|---|---|---|
| Ledger truth: oversell rejection, validateEdit/validateDelete replay | v6.0 | v3.8 has none of it |
| **Wallet cash identity** — `liquidity = total − invested + realizedPl`, one place | v6.4 | v3.8 derives `total − invested` in four places, so **a sell returns cost basis and the realized profit vanishes**. This is a live money bug in the proposed base. |
| CashRepository — deposits, dividends, fees, true equity | v6.0 | |
| **Broker-ref operation uniqueness** (schema v4 `transactions.ref`) | v7.0 | Fixes the rebuy-swallowed-as-duplicate bug |
| Difference-based edit guard (`unbackedBySymbol` / `worsenedGap`) + Incomplete-history card | v8.1 | One broken symbol must not lock every edit in the app |
| Currency + FX, splits as ledger rows, import duplicate guard | v6.0 | |
| `Fmt.moneyExact`, Reports nesting, net-worth card | v6.2–6.6 | |
| **Portfolio engines: Verdict / Allocation / Grade** | v8.0 | Already the correct architecture — pure functions over pre-gathered evidence, every field measured or explicitly null. These are the template for everything else. |
| Winners ridden behind a ratcheting trail, never sold on a percentage | v8.0 | Design-locked |
| **Honest-numbers layer** — fixed scales, no fabricated floors, FeedStatus, coverage, fail-closed pulse, Wilson intervals, indicator-agreement relabel | v3.9, v6.0, v7.0 | This is the product's differentiator. Non-negotiable. |
| TechniqueEvaluator + the Trusted gate (10+ independent samples, 60%+, 5-pt edge) | v4.0, v6.0 | The only thing that makes a 35-technique board defensible rather than decorative |
| Feed discipline: escalating backoff, `Retry-After`, no-retry-on-429, pacing gate, request counter | v8.4, v8.6 | Keep — but understand it as a floor, not the fix (§2) |
| Batched `fetchSparkCloses` / `getCloseSeries` | v8.5 | The right primitive. Wired into 2 of 11 eligible call sites. |
| Per-digit rolling figures (draw-phase wheels, 22 ms stagger, keyed from the right) | v8.1 | Design-locked |
| PortfolioPerformance — TWR, vol, drawdown, beta, Sharpe | v6.0 | |
| 159 tests, exported schemas, CI, `gradlew` 755 | v6.0, v6.4 | v3.8 has zero |

### 1.2 REWRITE — right idea, wrong structure

| What | The problem | v9 form |
|---|---|---|
| 11 picker engines | 11 output types, 4 score scales, 11 universes, each doing its own I/O | 6 `Strategy` plugins, 1 `Idea` type, 1 scale |
| Engine-level I/O (19 analytics files) | Nothing can see or bound total spend | Engines become pure; `MarketDataStore` owns all I/O |
| 35-technique board run by 12 callers | Recomputed per engine, per run | Computed once per (symbol, session) in a FeatureStore |
| 5 independent background workers | Each sweeps its own list, blind to the others | 1 coordinator with a request budget |
| `WealthScreen` — 2,867 lines, 11 sections | A dashboard of engines, not of decisions | 4 sections |
| Picks (5 tabs) + PreMarket (2 tabs) | 7 tabs answering one question | 1 Ideas surface, filtered by horizon |
| `PortfolioAdvisor` (833 lines) | Half I/O, half analysis, after v8.0 already mostly a fetcher | Pure FeatureStore consumer, ~250 lines |

### 1.3 CUT — not worth what it costs

| What | Lines | Why it goes |
|---|---|---|
| **NextWeekPlanner** | 655 | **132 requests per run** for the weakest claim in the app: a week-ahead pick off a 60-day cheap screen. Overlaps NextSession + SectorStrategy almost entirely. Becomes one `Horizon.WEEK` value on the unified strategy set. |
| **PowerPicker** (folded into EntryPicker) | 482 | Same 8-screen pool, same 28-name shortlist, same 365-day deep read as EntryPicker. Differs by a scoring tweak and a time-of-day label. One strategy with a session parameter. |
| **IntradayPicker** (folded into PreMarketPicker) | 509 | Identical relationship — the "2% desk" pre/open split is one strategy, two session windows. |
| **RelationPicker** | 333 | A hand-curated map of 10 giants → suppliers plus a correlation number. Pleasant; no decision hangs on it, and it costs a scan. Demote to a read-only panel on the stock page, or drop. |
| `TomorrowPick` inside MarketPulse | — | MarketPulse should measure the market, not pick a stock. Remove the type; the pick belongs to a strategy. |
| **GoldCorrelation** | 83 | Vestigial since v2. Nothing routes a decision through it. |
| `AllocationPlanJson` | 158 | Serialization for a cache that stops existing once the plan is derived on read rather than stored |
| BreakoutScout as a standalone engine | 213 | Not deleted — demoted. It is a strategy; it becomes one. |

**Net:** analytics goes **20,810 → ~11,000 lines**, 34 files → ~20, with no capability lost that a user would name.

---

## 2. Diagnosis: what "too many engines" actually is

Thirty-four files is not the problem. Three structural faults are.

### F1 — Every engine is a vertical silo: it fetches, computes, scores and caches for itself

Nineteen of thirty-four analytics files call the network directly. Eight screens
do too. There is no data layer between the engines and the HTTP client.

So no component can see, bound, or prioritize what the app spends — and there is
nowhere to *put* that logic short of the socket, which is exactly where v8.6
correctly ended up putting the gate. But a gate at the socket can only **slow**
requests. It cannot **avoid** them. That is why four releases aimed at this and
missed.

### F2 — The same data is fetched under eleven different keys

The cache key is `candles:$symbol:$rangeDays`, and callers ask for:

```
7 · 30 · 60 · 90 · 120 · 140 · 180 · 210 · 365 · 400
```

A 365-day series contains every bar a 60-day caller wants. The store does not know
that. A symbol held in the portfolio, listed on a shelf and appearing in two pick
lists is fetched **four separate times**, cached four times, expired four times.

And v8.5 built the correct primitive — batched close-series, verified at
4 symbols × 22 closes in one 2.1 KB response — then wired it into **2 of 11**
eligible call sites. `NextWeekPlanner` still issues **120 individual requests**
for a screen that reads closes only.

### F3 — Eleven engines produce the same artifact in eleven shapes

`BreakoutCall` · `IntradayPick` · `LiquidityCandidate` · `TomorrowPick` ·
`NextSessionPick` · `PreMarketPick` · `SectorPick` · `UPick` · `WeeklyPick` ·
`PowerPick` · `EntryPick`

Every one of them is: *a symbol, a reason, an entry, a stop, a target, a score.*

On **four different score scales** — `SCORE_SCALE` is 75.0 in WeeklyPicker, 80.0
in NextWeekPlanner, 90.0 in EntryPicker, 100.0 in PowerPicker. The user reads
"Score 72" on two cards and it means two different things. That is a quiet
violation of the honest-numbers policy the rest of the app enforces strictly.

Each type carries its own card renderer, its own cache key, its own freshness
rules, its own universe. Adding a twelfth strategy costs a screen.

### The cost, with the arithmetic

One cold Wealth open, computed from the engines' own constants:

| Engine | Fan-out | Requests |
|---|---|---|
| NextWeekPlanner | 120 × 60d screen + 12 × 365d deep | **132** |
| MoneyFlowEngine | SPY + 18 themes × (1 ETF + 8 members @60d) | **~160** |
| NextSessionEngine | 26 × 365d + SPY + news + intraday | **~60** |
| SectorStrategy | 4 themes × ≤14 names @180d | **56** |
| MarketPulse | 8 screens + 18 × 120d + indices | **~26** |
| PortfolioAdvisor | holdings × (365d + news) + SPY 140d | **~20** |
| | **Total, cold** | **~450–550** |

At v8.6's 160 ms pacing slot: **72–88 seconds of continuous requesting for one
screen open.**

That is not a rate limit to ride out. That is a rate limit being earned.

---

## 3. Target architecture

Six layers. Strict downward dependency. No layer skips the one below it.

```
L5  SURFACES     Dashboard · Ideas · Portfolio · Reports · Stock
                      │
L4  PRESENTERS   one Idea type · one 0-100 scale · one card
                      │
L3  STRATEGIES   pure: (SymbolFeatures, MarketContext) -> Idea?
                      │
L2  FEATURES     once per (symbol, session): board · indicators · RS · ATR · flow
                      │
L1  DATA STORE   the ONLY component that touches L0
                 superset-aware · grain-routed · batched · budgeted
                      │
L0  FEED         YahooClient: pacing gate · backoff · counter
```

### L1 — MarketDataStore (the layer that does not exist today)

One change; it closes F1 and F2 together.

- **One canonical range per symbol per grain.** Daily candles are stored once, at
  the deepest range any caller has asked for. A 60-day caller receives
  `takeLast(60)` from cache — **zero requests**. Range fragmentation disappears
  as a concept.
- **Grain is declared, not chosen at the call site.** `Grain.CLOSES` routes
  automatically to the batched spark endpoint; only `Grain.OHLCV` costs a
  per-symbol request. This is v8.5's win applied everywhere instead of twice, and
  it preserves v8.5's correctness note: closes-only bars must never be read for a
  high or a volume, and the type system enforces it rather than a comment.
- **Declare-then-resolve.** A run declares its whole symbol set up front; the
  store plans one batched resolution for all of it. Fifty engines wanting AAPL is
  one fetch, not fifty gated requests.
- **Priority classes.** `INTERACTIVE` (the screen in front of the user) >
  `BACKGROUND` (workers) > `SPECULATIVE` (prefetch). Under backoff, speculative
  work is **dropped**, not queued behind the user.
- **A budget that is spent against, not just counted.** v8.6 measures
  requests-per-hour and shows it in Settings. v9 gives the store that number as a
  ceiling and makes it refuse past it — with the refusal surfaced honestly, in the
  house style: *"scan reduced — 40 of 120 names measured this run."*

### L2 — FeatureStore

```kotlin
data class SymbolFeatures(
    val symbol: String,
    val sessionDate: LocalDate,
    val board: TechniqueBoard,        // the 35 techniques, ONCE
    val trust: TechniqueRecord,       // measured accuracy, Wilson interval
    val indicators: Indicators,       // ATR, RSI, MAs — one definition
    val relStrength20: Double?,       // vs SPY, computed once
    val flow: SectorFlow?,
    val newsTone: NewsTone?,
    val extendedHours: ExtendedHours?,
    val coverage: ScanCoverage,
    val freshness: FeedStatus
)
```

Keyed `features:$symbol:$sessionDate`. Every strategy and every portfolio engine
reads the same object.

Kills: the board being computed by twelve callers; four engines each deriving
relative strength; three deriving ATR by different formulas.

### L3 — Strategy plugins

```kotlin
interface Strategy {
    val id: String
    val horizon: Horizon                                // INTRADAY | OVERNIGHT | WEEK | SWING
    val session: SessionWindow                          // when it may fire
    fun universe(ctx: MarketContext): UniverseSpec      // DECLARATIVE — a spec, not a fetch
    fun evaluate(f: SymbolFeatures, ctx: MarketContext): Idea?   // PURE
}
```

`evaluate` is pure: no I/O, no caching, no coroutines. Every strategy is testable
against a fixture `SymbolFeatures` with no network and no clock.

The eleven pickers become **six strategies**:

| Strategy | Replaces | Horizon |
|---|---|---|
| `UPatternStrategy` | UPatternEngine | INTRADAY |
| `DeskStrategy` | PreMarketPicker + IntradayPicker | INTRADAY (2 session windows) |
| `EntryStrategy` | EntryPicker + PowerPicker | OVERNIGHT (2 session windows) |
| `NextSessionStrategy` | NextSessionEngine | OVERNIGHT |
| `WeeklyStrategy` | WeeklyPicker + NextWeekPlanner | WEEK |
| `BreakoutStrategy` | BreakoutScout + SectorStrategy picks | SWING |

### L4 — One Idea

```kotlin
data class Idea(
    val symbol: String,
    val strategyId: String,
    val horizon: Horizon,
    val score: Int,                  // ALWAYS 0-100, same meaning in every strategy
    val conviction: Conviction,
    val entry: Double?,              // null = unmeasurable, never fabricated
    val stop: Double?,
    val target: Double?,
    val riskReward: Double?,
    val why: List<Evidence>,         // each carries its measured number and its source
    val coverage: ScanCoverage,
    val freshness: FeedStatus
)
```

One card renders it. Adding a strategy costs a file, not a screen.

The single shared scale also fixes the honesty gap in F3: two ideas on screen
become genuinely comparable, which is what a score is for.

### L5 — Surfaces

Picks (5 tabs) + PreMarket (2 tabs) + Wealth (11 sections) = **18 places that
answer "what should I buy?"** → **four surfaces**:

- **Dashboard** — the book, live. Wallet identity, holdings, day move.
- **Ideas** — every strategy's output in one ranked list, filtered by a horizon
  chip. The seven tabs were never about different questions; they were about
  different engines.
- **Portfolio** — verdicts, allocation, grade, performance. v8.0's engines,
  unchanged, because they are already built this way.
- **Stock** — one symbol: chart, board, trust record, fundamentals, flow.

### L6 — One scheduler

Five workers → one `MarketSyncWorker` holding a per-run request budget. It decides
what to refresh from what is stale and what the user actually opens, and it is the
**only** background spender. Notification watchers become subscribers to its
output rather than five independent sweeps of five overlapping symbol lists.

v8.6's honest note — *"the periodic workers each sweep their own symbol lists, and
I have NOT audited what each spends"* — stops being a known unknown, because there
is one spender to audit.

---

## 4. Migration onto the v3.8 base

Five phases. Each ships. Each has a numeric gate that must hold before the next
starts.

### Phase 0 — Foundation
Room v2→v4 with exported schemas · test harness + CI + `gradlew` 755 ·
YahooClient with pacing gate, escalating backoff, counter ·
**MarketDataStore with superset ranges, grain routing, priority classes, budget.**

> **Gate:** requests for one cold Dashboard open ≤ **15**.

### Phase 1 — Money truth
Wallet identity · CashRepository · oversell guards + difference rule ·
broker-ref uniqueness · FX · splits · exact cents · Reports nesting.

> **Gate:** `WalletLiquidityTest`, `LedgerGuardTest`, `OperationUniquenessTest`,
> `CashAndReportsTest` all green. No screen reads liquidity from anywhere but
> `WalletState`.

### Phase 2 — Features
FeatureStore · 35 techniques · TechniqueEvaluator + Trusted gate · MoneyFlow ·
MarketPulse · the full honest-numbers layer (fixed scales, no fabricated floors,
FeedStatus, coverage, fail-closed).

> **Gate:** a test asserts the technique board is computed at most **once** per
> symbol per session across a full Ideas + Portfolio load.

### Phase 3 — Strategies
Six strategies on the `Strategy` interface · one `Idea` type · the Ideas surface.

> **Gate:** cold Ideas open ≤ **40** requests, down from ~450. Every strategy has
> unit tests running with **zero** network calls.

### Phase 4 — Portfolio
v8.0's Verdict / Allocation / Grade engines re-added essentially as-is — they
already satisfy L3's contract — plus Performance, with PortfolioAdvisor reduced to
a FeatureStore consumer.

> **Gate:** the 38 v8.0 engine tests pass unmodified against the new evidence
> source.

### Phase 5 — Surfaces & scheduler
Four surfaces · one `MarketSyncWorker` · rolling figures · alerts · fundamentals
(incl. the `pickEarnings` fix) · disclosure · export/restore · app lock ·
the Market-data card.

> **Gate:** sustained 8-hour session with zero 429s in the request counter.

---

## 5. What it buys

| | v8.6 | v9 |
|---|---|---|
| analytics lines | 20,810 | ~11,000 |
| pick output types | 11 | 1 |
| score scales | 4 | 1 |
| files that touch the network | 19 analytics + 8 screens | **1** |
| requests, cold Wealth / Ideas open | ~450–550 | **~40** |
| technique-board runs per symbol per session | up to 12 | **1** |
| background workers | 5 | 1 |
| surfaces answering "what should I buy?" | 18 | 1 |
| a strategy that is unit-testable without network | 0 | all |

---

## 6. Delivery status — v9.0 (2026-08-19)

The v9 branch was cut from v3.8 (`84bdd50`) and shipped as versionName 9.0
(versionCode 53). What the user confirmed from this document, delivered:

**Re-added from the KEEP list, substantially as-is:** wallet cash identity +
cash ledger + oversell/edit guards + broker-ref uniqueness + FX + splits +
exact cents · nested Reports (year→months→weeks→days) · the 35-technique
board + TechniqueEvaluator + Trusted gate · sector flow + MarketPulse + the
honest-numbers layer · the v8.0 portfolio engines (verdict / allocation /
grade) + performance · per-digit rolling figures (now also on the detail-hero
price and the Stocks rows) · feed discipline · the full test suite.

**Cut, as prescribed:** NextWeekPlanner (and the Wealth preview section),
RelationPicker (and the Relations tab), GoldCorrelation (and its cards),
TomorrowPick inside MarketPulse, DailyPicker / WealthPlanner / Watchlist
screens. Deferred fold-ins, kept as-is for now: PowerPicker → EntryPicker,
IntradayPicker → PreMarketPicker (§1.2's session-parameter rewrite).

**L1, first slice:** the superset-range candle store — one canonical daily
series per symbol under `candles:$symbol` at the deepest range any caller
asked for; shallow callers are served a slice reproducing the exact Yahoo
bucket a direct fetch returned (locked by `CandleRangeStoreTest`). Range
fragmentation (F2) is closed for the OHLCV path; grain routing and the
declared-budget resolver remain future work.

**New in v9.0:** the VIX index as a first-class Wealth figure (level,
5-session drift, regime label on the score's own bands) · the explain layer —
an ⓘ dot beside every load-bearing Wealth figure, fed by one `Meanings`
glossary · the portfolio-aware deployment answer: liquidity evaluated and
deployed sector by sector with named, counted stocks and dollar tickets,
candidates drawn from the entry boards AND the trendy sectors' shelves
(riding the strategy scan's own cache), the weekly theme split sized in real
wallet dollars, and one cached strategy build serving the card, the review,
and the plan.

Tests: 159 → 168, all green.

---

## 7. Where this is a judgment call, not a fact

Three of the cuts are product decisions and should be confirmed rather than
assumed:

- **NextWeekPlanner** is the most expensive engine in the app and the weakest
  claim, but "next week" is a section the Wealth screen has carried since v3.9 and
  may be something the user relies on.
- **RelationPicker** is genuinely novel — no competitor maps giants to their
  suppliers — and is being cut for cost, not for quality. It could survive as a
  zero-scan static panel with correlations refreshed weekly.
- **Collapsing 7 tabs into 1 Ideas surface** is the largest behavioural change
  here. It is right architecturally; whether it is right for a user who has
  learned where "Power Hour" lives is a separate question.

Everything else in §1.3 goes on the technical merits alone.
