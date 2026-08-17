# Engine suite v8 — three standalone elite engines for the portfolio section

Date: 2026-08-18. Scope: the three cards under **Your portfolio — the verdicts**
each get a standalone engine built to the same contract as the liquidity engine
(`LiquidityAllocationEngine`): a pure function over pre-gathered, explicitly
measured evidence, with fixed point scales and no fabricated inputs.
142 unit tests pass (104 before, +38 new).

---

## 1. Why the split

Before v8 the section had one class doing three jobs: `PortfolioAdvisor` fetched
candles, judged each holding inline through a `when` cascade, and derived the
"allocation plan" as a one-line mapping from the action it had just chosen.
Nothing was testable without a network, and the plan carried no market read at
all.

v8 separates gathering from judgement:

| Layer | What it does | Purity |
|---|---|---|
| `PortfolioAdvisor` | Fetches candles, the 35-technique board, headlines, the SPY baseline; builds one `HoldingEvidence` per holding where **every field is measured or explicitly null** | I/O |
| `PortfolioVerdictEngine` | What to do with each holding, and how to ride winners | pure |
| `AllocationPlanEngine` | What the whole book should be worth, and where freed money goes | pure |
| `PortfolioGradeEngine` | How the book scores against elite investors' published rules | pure |

A field the advisor cannot measure arrives at the engines as `null`. That is
what makes "not measured" impossible to fake.

---

## 2. `PortfolioVerdictEngine` — the verdicts, and riding winners

### The bug it was built to fix

The old rule sold winners on a percentage:

```
plPct >= takeProfitPct && (rsi >= 70 || direction != BULLISH) -> TAKE_PROFIT
```

A +16% position with RSI 71 in a textbook stage-2 advance was told to bank half.
That is the single most expensive habit in retail investing, and the app was
automating it.

**A profit percentage alone now never sells anything.** Taking profit requires a
*measured* climax or *measured* deterioration. Otherwise the position is ridden
with a raised trail, and the card says what selling would have given up.

### Two fixed 100-point scales

Conviction — what argues for staying in:

| Band | Pts |
|---|---|
| Moving-average structure (vs 50, vs 200, stacking, 50-day slope) | 22 |
| Relative strength vs the S&P 500 (20d + 60d) | 18 |
| The 35-technique board (direction × agreement) | 16 |
| Momentum quality (RSI regime + 5/20/60-day shape) | 12 |
| Volume participation (up-day volume share + volume ratio) | 10 |
| Sector money flow | 10 |
| Leadership (distance below the 52-week high) | 8 |
| Headline tone | 4 |

Risk — what argues for getting out:

| Band | Pts |
|---|---|
| Depth into the profile's own loss rule | 25 |
| Give-back from the peak, **measured in ATRs** | 20 |
| Trend break (below the 50-day, 50<200, 50 falling) | 20 |
| The board against it | 15 |
| Money leaving the sector | 10 |
| Distribution days (heavy down volume in 25 sessions) | 10 |

A band that cannot be measured is removed from **both sides** and named in
`HoldingVerdict.notMeasured`. A thin data run reports `44/62`, never a
flattering `71/100` built on defaults.

### The ratcheting trail

`trailStop` takes the highest defensible level among:

- the chandelier: `peak − k × ATR`, where `k` comes from risk tolerance **and
  horizon** (short-horizon conservative 1.5 ATR → long-horizon aggressive 3.5)
- the 50-day line less half an ATR, when price is above it
- the 20-day Donchian low less a quarter ATR
- the cost basis itself, once the open gain is worth twice the loss rule

and then **never returns below `priorStop`** — the stop the previous run
published, threaded through from the cache. A "protected" gain cannot quietly
un-protect itself.

The peak hangs from the position's real entry: `WealthRepository.entryTimestamps()`
replays the ledger to find where the *current* open run of each symbol began, so
a rebought name measures its peak from the rebuy, not from a stale high.
`HoldingEvidence.peakMeasuredFromEntry` carries the difference into the prose.

### The decision ladder

1. **CUT_LOSS** — the profile's loss rule, with the tape confirming
2. **SELL** — the published stop was broken (honouring what was promised)
3. **SELL** — the board turned decisively *and* the trend is gone
4. **TAKE_PROFIT (a third)** — a measured climax: ≥3 ATR above the 20-day, RSI ≥78, ≥2× volume
5. **TAKE_PROFIT (half)** — a winner whose risk read passed the horizon's tolerance *and* lost its trend
6. **TRIM** — the position cap, explicitly labelled *risk control, not a verdict on the stock*
7. **TRIM** — the sector is bleeding, the name lags, and it is underwater
8. **HOLD / RIDE** — with the raised trail and the runway named

### Equity awareness

`EquityContext(invested, holdingsValue, liquidity, realizedPl)` carries the money
behind the book. Each verdict reports `riskAtStop` in dollars and
`riskAtStopEquityPct` against the per-trade budget; the report totals them into
`openRiskPct`. When the wallet is not configured, `liquidity` is `null` and **no
percentage of the account is claimed** — the dollar figure still prints.

### No stop is better than a fabricated one

When neither an ATR, a support, a 50-day, a Donchian low nor a breakeven ratchet
is measurable, `stop = 0.0` meaning *no level*. Everything downstream honours it:
`riskAtStop`, `riskReward`, portfolio heat and the plan's risk sizing all go
null, the prose says "no measurable level", and the card shows "—". The obvious
shortcut — a round `price × 0.85` — would have looked measured and poisoned four
other numbers.

---

## 3. `AllocationPlanEngine` — the allocation plan

Replaces a table that mapped each action to a percentage and computed cash as
`100 − Σ suggested` on an invested-only base (which double-counted).

**Sizes by risk, not by a flat percentage.** Each position's ceiling is the
tightest of:

- the profile's position cap × the base,
- the dollar size at which *this* position losing to *its own* stop costs
  exactly the per-trade risk budget,
- (for adds only) its measured conviction.

A wide-stopped name earns a smaller slot than a tight-stopped one at equal
conviction — locked by a test.

**Never contradicts a holding card.** Each verdict publishes `keepSharePct` —
how much of today's position it leaves standing — and the plan consumes it
verbatim. The cap trim solves `(V − x)/(B − x) = cap` rather than keeping
`cap/weight`: with a 34% position and a 22% cap the naive share leaves the name
at 25% of the smaller book.

**Two ceilings, deliberately different.** The HOLD ceiling (cap ∧ risk budget)
is what an existing position may keep; conviction never forces a sale, because
the holding's own verdict is the authority on selling. The ADD ceiling
additionally respects conviction — new money has to be earned.

**Scans the market via the audited engine.** The post-plan book is handed to
`LiquidityAllocationEngine` with whatever the plan frees. New names are scored,
capped and reserved by exactly the same rules as the liquidity card. One
derivation, no drift.

**Cash is an identity, not a residual.** `targetCashValue = liquidity + freed −
deployed`, which conserves the base exactly. The regime + tolerance cash floor
is reported as the *rule*; when the plan cannot reach it, the note says so
rather than claiming cash the account has no way to hold.

**Base is named.** Equity when the wallet is tracked; the invested book when it
is not — never a silent mix.

---

## 4. `PortfolioGradeEngine` — rescaled to eight disciplines

| Discipline | v7 | v8 |
|---|---|---|
| Concentration control | 20 | 18 |
| Loss discipline | 15 | 14 |
| **Winners riding** | 15 | **16 (rewritten)** |
| Trend alignment | 15 | 14 |
| Relative strength | 15 | 14 |
| Money-flow alignment | 10 | 8 |
| Regime fit | 10 | 8 |
| **Risk budget** | — | **8 (new)** |

Still exactly 100 when everything is measurable.

### Winners riding, rewritten

v7 measured "% of the book in profit" — which rewards *owning* something green,
not *riding* it. It could not tell a winner held through a full round-trip from
one being ridden. v8 measures three things, each dropped from both sides when
unmeasurable:

- **6 pts — the win/loss size ratio**: average % gain across winners over
  average % loss across losers. This is the number that separates letting
  winners run from snipping gains and nursing losses. Needs both a winner and a
  loser to exist at all; with no losers the band leaves the scale (max 10, not a
  free 16).
- **6 pts — working weight and leadership**: how much of the measured book is in
  profit (4) and whether the three biggest slots are the winners (2).
- **4 pts — riding intact**: the share of winning weight still holding its own
  50-day line. A winner below its trend is not being ridden.

Its improvement plan scores every legal exit on the same arithmetic and takes
the best; it can never propose selling a winner, because that lowers the score.

### Risk budget (new)

Portfolio heat: every stop's dollar cost summed against
`riskPerTradePct × 3` of equity (clamped 3–12%). Unmeasured — and said to be —
without tracked equity, or when **any** holding lacks a measurable stop, so
partial coverage can never understate heat. Its plan trims the heaviest
contributors to one per-trade unit each.

---

## 5. Shared derivations (no drift)

- `AllocationMath` — sector targets, flow reads, ticket rounding and money
  formatting, now used by **both** allocation engines.
  `LiquidityAllocationEngine` was refactored onto it (its own copy, including a
  dead `sumScore` variable and a note that printed the direction word twice, is
  gone).
- `PortfolioAdvisor.positionCapPct / positionTrimPct / sectorOverweightPct /
  sectorTargetPct / cutLossPct(policy)` — unchanged, still the single place the
  profile becomes thresholds.
- `PortfolioVerdictEngine.riskBudgetPct(policy)` — read by the grade engine, so
  the heat budget on the grade card is the one the verdicts quote.

## 6. Profile awareness

The horizon rule the old take-profit threshold carried was not lost with it — it
moved to where riding actually happens: the trail's ATR multiple and the
deterioration threshold (short 38 / medium 45 / long 55). A short-horizon trader
and a long-horizon investor get measurably different trails on the identical
holding, and the riding sentence names which tolerance and horizon produced the
room.

## 7. Cache and wiring

- `portfolioreview:v6` → **`portfolioreview:v7`**. The new scales and payload
  mean a v6 blob would deserialize into a review that means something else.
- `AppContainer` now builds `wallet` before `wealth` and injects it — the review
  needs the account's equity, not just the book's value.
- `AllocationPlanJson` serializes the plan; every read fails **closed** (a
  malformed field nulls the whole plan rather than producing one with quietly
  zeroed dollars).

## 8. Where things live

- `analytics/PortfolioVerdictEngine.kt` — `HoldingVerdict`, `HoldingAction`,
  `HoldingStage`, `HoldingEvidence`, `EquityContext`, `PortfolioVerdictReport`
- `analytics/AllocationPlanEngine.kt` — `AllocationPlan`, `AllocationTarget`,
  `AllocationMove`
- `analytics/AllocationPlanJson.kt` — cache round-trip
- `analytics/AllocationMath.kt` — shared sector/ticket derivations
- `analytics/PortfolioAdvisor.kt` — gathering, `PortfolioReview`, rebalance
- `analytics/PortfolioGrade.kt` — eight disciplines
- Tests: `PortfolioVerdictEngineTest` (17), `AllocationPlanEngineTest` (12),
  `PortfolioGradeEngineTest` (9)
