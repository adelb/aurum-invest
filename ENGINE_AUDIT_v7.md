# Engine audit v7 — every engine checked, operation uniqueness, NextSession v2

Date: 2026-08-18. Scope: all analytics engines audited for correctness and
integrity of the provided information; the buy→sell→rebuy report conflict
fixed at its root; the Wealth next-session engine rebuilt as the most
advanced engine in the app. 104 unit tests pass.

---

## 1. The reported bug: buy → sell → buy again conflicted in reports

**Root cause.** Bank-import dedup keyed operations by their *shape* —
(symbol, side, shares, price) within ±36 h (`countBankDuplicates`). A genuine
rebuy of the same stock at the same size and price looked like a duplicate of
the first buy and was silently swallowed (`importEvent` marked the event
IMPORTED and wrote **no ledger row**). Every later sell then oversold, the
legacy clamp rewrote realized P/L, and reports disagreed with the broker.

**Fix — each operation is now unique, regardless of the stock:**
- Room schema **v4** (`MIGRATION_3_4`): `transactions.ref` stores the broker's
  transaction reference — the identity of the operation. `TradeParser` already
  extracted it; it was never persisted.
- `BankFeedRepository.importEvent` dedups in layers of identity strength:
  1. an event already IMPORTED never writes again (double-tap safe);
  2. with a parsed ref, only a same-(symbol, ref) BANK row is a duplicate —
     a rebuy carries a new ref and always imports;
  3. only ref-less alerts fall back to the shape heuristic.
- Auto-import (which already required a ref) now stores it, so layer 2 always
  applies to unattended writes.

**Same-root hardening (ledger integrity):**
- `ReportsViewModel.updateTrade` now runs the same `validateEdit` full-ledger
  replay as the Edit-position screen (it previously skipped validation);
  rejections surface in a "Ledger protected" dialog.
- Deleting a buy that a later sell depended on is now rejected with the
  reason (`PortfolioRepository.validateDelete`) in both Reports and
  Edit-position — deleting it would silently rewrite that sell's realized
  outcome.

Tests: `OperationUniquenessTest` locks buy→sell→rebuy report integrity,
identical-shape rebuys as distinct operations, and delete-behind-a-sell
rejection.

---

## 2. NextSession engine v2 — the Wealth "position for the next session"

Rebuilt to decide from everything the app can measure (cache key
`nextsession:v3`; alert gates unchanged: score ≥78, probUp ≥65 over ≥8
analogs, bullish board ≥60%):

| Input (fixed 100-pt scale) | Pts | Source |
|---|---|---|
| Setup strength (continuation shape) | 15 | screener fields |
| Trend quality + **rel. strength vs SPY (20d)** | 12 | daily candles + SPY baseline |
| 35-technique board | 15 | `Techniques.analyze` |
| Analog-day follow-through study | 20 | own history (unmeasured → fixed 8 + label) |
| **Volume pace + session-over-session expansion** | 10 | screener + candles |
| **Sector money flow** (shared `MoneyFlowEngine.flowFor`) | 8 | CMF/MFI/OBV/up-dollar share |
| **5-day headline tone** (FeedStatus-aware) | 8 | news feed; FAILED → midpoint + "not measured" |
| **Intraday structure: session VWAP + day-scan continuity** | 7 | intraday bars + today's entry/power scans |
| Extended-hours print | 5 | session-aware price read |

Plus per-pick chart mechanics: 20-session Donchian breakout flag, structural
stop under support (ATR-padded), target **capped at the nearest measured
resistance**, and the resulting **risk/reward** printed on the card (with a
warning below 1.0). Entries are priced session-aware — never off the morning
pre-market print. Every unmeasured input scores its fixed midpoint and is
named in the report notes; nothing is renormalized to the day's best.

Wired in `WealthRepository`: money-flow report + today's entry/power scans +
news repo flow into the engine; the Wealth card shows the new pills
(R/R, sector inflow/outflow, VWAP) and the scan/news/flow context lines.

---

## 3. Engine-suite audit — verified findings fixed

**Critical**
- `NewsSentiment`: bearish action verbs ("halts", "scraps", "cancels",
  "ends") sat in the NEGATOR list and *inverted* genuinely bearish headlines
  ("scraps drug program, shares plunge" scored +2). Moved to the negative
  lexicon. Dedup switched to Jaccard overlap (order-independent).
- `MoneyFlowEngine`: an unfetched/failed news tone minted 5 free points on a
  scale documented as measured (12 of 18 sectors every run). Tone is now
  nullable; unmeasured tone removes its 10-point band from both sides of the
  scale. Unmeasurable CMF/MFI now skip the sector instead of voting neutral.
  Missing SPY baseline in a cached payload now fails the parse instead of
  reading back as 0.0.
- `MarketPulse`: fabricated 50% breadth/advancers when the pool was
  unmeasurable — now nullable end-to-end (advisor prose included); the score
  is withheld (`score = null`, UI shows "—") when the call is INCOMPLETE;
  cache-read defaults fail closed (call→INCOMPLETE, coverage→0); coverage now
  also scales by how many screens actually served; breadth and participation
  share one denominator; zero-close index reads can no longer emit Infinity
  into the cache write.
- `EntryPicker`/`PowerPicker` (and `NextSessionEngine`, `NextWeekPlanner`):
  `ExtendedHours.livePrice` served the **morning pre-market print all through
  the regular session**, mispricing entries/targets/stops and silently
  dropping the strongest names. All now select the price by market session.
- `UPatternEngine`: the live read required 50 five-minute bars (blind before
  ~13:40 ET, i.e. its entire signal window) — live path now needs 3; the
  backtest replayed a different rule than the card signals (no stop) — the
  stop is now replayed and the card names the exact replayed rule; division
  by a cached-zero median rebound (NaN/Infinity in user text) is guarded.
- `WeeklyPicker`: the "deep read unreachable" fallback actually served the
  exact names the bearish-board gate had just rejected — removed. Fabricated
  +1% targets, fabricated ATR, and fabricated 1.0x volume claims removed.
- `LiquidityAllocationEngine`: ticket rounding could round **up past the
  user's cash and position/sector caps** (spend $1,000 of a $998 balance and
  claim "nothing held back") — clamped to room. Fabricated flow strengths
  (60/40) for trend-fallback verdicts now contribute 0 conviction points.
- `BuyPlan`: crash on tiny budgets vs very expensive shares (`minOf` on
  empty); fabricated 2% "ATR" printed as a measured ATR (now: no ATR → no
  plan); penny-stop clamp that turned the 5:1 frame into a ~6× fantasy target
  (now: indefensible structure → no plan); mislabeled "20-day average"
  trigger when the average wasn't measurable.
- `PortfolioGrade`: **ignored the investor profile entirely** — scored
  hardcoded 30/22/35/30 caps and a fixed −8% loss rule while the advisor
  acted on the profile's own caps, so one review could contradict itself.
  The profile now threads through (`evaluate(..., policy)`) via shared
  threshold functions on `PortfolioAdvisor` (one derivation, no drift), and
  the principle strings quote the user's caps. Percentage-base bugs fixed:
  step-2 rotations re-base weights after step 1; loss/winner/regime shares
  divide by the measured book, not a fictitious 100 (unverifiable holdings
  no longer count as healthy weight); rotate steps only claim an inflowing
  buy when one exists.
- `PortfolioAdvisor`: every overweight sector proposed the same single buy
  candidate (stackable past the position cap) — candidates are now a queue,
  one per move; weakest-holding tie-break could rank a 0%-confidence HOLD
  even with a 100%-confidence TRIM present — bucket spacing fixed.
- `TechniqueEvaluator.weights()`: vote weighting rode the overlapping-sample
  hit rate while the trust badge used the independent grid — the outlook now
  weights by the independent record (same bar as the badge).
- `SectorStrategy`: theme targets normalized to 100% of the book (told a
  diversified book to become a four-theme portfolio) — now scaled to a 40%
  steering budget; allocation list sorted by a value that is always zero in
  production — now by share; unmeasured volume no longer a −3 penalty; a
  failed news feed no longer indistinguishable from verified-no-news; an
  unmeasurable board no longer scores as a neutral board.
- `SectorTrends`: news tone (fetched for 6 of 18 themes) removed from the
  cross-theme *ranking* score — a ±10 term decided ranks on feed luck.
  Still measured and displayed.
- `GoldCorrelation`: unmeasurable correlation rendered as "r = 0.00" — now
  null → "r —"; day alignment moved to ET (`sameEtDay`), same for news price
  impact.
- Concurrency: sector tone maps were plain HashMaps written from parallel IO
  coroutines — replaced with `awaitAll().toMap()` in both engines.
- Assorted: true median for even member counts; closing-auction bar excluded
  from pre-market timing histograms; per-screen try/catch so one failed Yahoo
  screen can't blank a whole scan; `techTotal` cache default 0 (not 15);
  member price guards; 20-day trailing-stop label only when 20 days exist.

**Deferred (documented, deliberate):**
- `StockCatalog` "Utilities & REITs" shelf measures its live trend on XLU
  (no REITs) and the two symbol→theme maps disagree on a few names
  (GOOGL/TSLA/PLTR…) — product-level classification decisions; unify with
  care in their own pass.
- `Indicators.rsi` returns 50.0 for a perfectly flat series (undefined RSI);
  changing it to null ripples through every call site — schedule separately.
- A few picker LOWs (IntradayPicker's early-session relative-volume display,
  hit-rate time-of-day conditioning, `asOf` stamping) — display-honesty
  refinements, not correctness breaks.
- `MarketPulse` still carries dead `TomorrowPick` plumbing (always empty
  since the NextSession split) — removal is cosmetic.

---

## 4. Where things live now

- Operation identity: `transactions.ref` (schema v4), `bankRefExists`,
  `validateDelete` — `PortfolioRepository`; layered dedup —
  `BankFeedRepository.importEvent`.
- Shared flow mapping: `MoneyFlowEngine.flowFor` (advisor + next-session).
- Shared policy thresholds: `PortfolioAdvisor.positionCapPct/positionTrimPct/
  sectorOverweightPct/sectorTargetPct/cutLossPct(policy)`.
- NextSession v2: `NextSessionEngine` (+ `DayScanContext`), cache
  `nextsession:v3`, UI in `WealthScreen` NextSessionCard.
