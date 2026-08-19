# Aurum Advisory-Grade Information Gap Audit and Remediation Re-Audit

**Original audit date:** 2026-08-16

**Re-audit date:** 2026-08-16

**Baseline:** v5.3.1 (`89f0604`)

**Current version:** v6.0 (`fd394bb`)

**Scope:** Android application code, user-facing screens, analytics engines, data sources, persistence, tests, and product documentation.

## Remediation re-audit result

Eight independent agents re-audited the current working tree by domain, and an eighth adversarial agent classified all 21 original findings. Status is measured against the **original acceptance gates**, not whether some implementation work landed:

- **Fixed:** the original gate is met or the misleading claim was removed.
- **Partial:** meaningful remediation shipped, but a material part of the original gate remains open.
- **Open:** no meaningful remediation was verified.
- **Regressed:** current behavior is worse than the v5.3.1 baseline.

### Executive update

The remediation is substantial and directionally strong, but `AUDIT_FIXES_v6.md` overstates closure. The verified result is:

| Severity | Fixed | Partial | Open | Regressed |
|---|---:|---:|---:|---:|
| Critical (C1-C7) | 0 | 7 | 0 | 0 |
| High (H1-H6) | 1 | 5 | 0 | 0 |
| Medium (M1-M8) | 2 | 6 | 0 | 0 |
| **Total** | **3** | **18** | **0** | **0** |

The three closed gates are:

1. **H4:** user-facing “whole market” claims were replaced with the actual Yahoo eight-screen sample, and scan coverage is surfaced.
2. **M7:** technical-only advice variants are now explicitly labeled, while detail advice remains news-aware.
3. **M8:** sell targets are consistently labeled “before fees & tax.”

No Critical gate is fully closed. Aurum is materially safer and more transparent than v5.3.1, but portfolio mutation integrity, stale-data action gating, suitability completeness, statistical validation, privacy/restore safety, and release-quality controls still block an advisory-grade claim.

## Current finding status

| ID | Status | Verified remediation | Remaining gate / defect |
|---|---|---|---|
| C1 | **Partial** | A persisted `InvestorProfile` now drives several position, sector, loss, profit, and sizing thresholds; Wealth cache keys include the policy (`SettingsRepository.kt:20-56,115-133`; `PortfolioAdvisor.kt:335-352`; `WealthRepository.kt:262-288`). | `PortfolioGradeEngine` still uses hardcoded 22/30/35% thresholds, and the profile lacks objective, liquidity, experience, tax, restrictions, consent, and versioning (`PortfolioGrade.kt:343-401`). No test exercises `InvestorProfile` through advisor/grade behavior. |
| C2 | **Partial** | Primary add/edit flows reject oversells; cash entries, splits, fees, currency/FX fields, clamped report replay, and “Holdings value” landed (`AddTransactionViewModel.kt:63-81,246-258`; `Entities.kt:6-66`; `ReportsEngine.kt:84-121`; `DashboardScreen.kt:491-517`). | Bank import, Reports edit, and deleting earlier buys can still create oversells (`BankFeedRepository.kt:73-87`; `ReportsViewModel.kt:74-86`; `EditPositionViewModel.kt:99-100`). Broker references are parsed but not persisted or uniquely constrained. Accounts, taxes, transfers, atomic reconciliation, and the 12-statement gate remain open. |
| C3 | **Partial** | News, candle history, scan coverage, unpriced holdings, and market-pulse incomplete states are now distinguished (`Models.kt:100-160`; `NewsRepository.kt:44-92`; `AnalysisScreen.kt:162-173`; `MarketPulse.kt:289-301`). | Quote/candle caches can still be returned with unbounded age, per-symbol advice does not enforce an SLA or become `INCOMPLETE`, and `Advice`/`BuyPlan` lack source/as-of/coverage contracts (`MarketRepository.kt:38-45,96-101,138-146`; `DashboardViewModel.kt:198-206`; `PositionDetailViewModel.kt:180-188`). |
| C4 | **Partial** | Indicator agreement and ATR limitations are disclosed; evaluator replay adds stride-five samples, stock base rates, and Wilson intervals (`AnalysisScreen.kt:335,354-356,706-707`; `TechniqueEvaluator.kt:179-287`). | Current weighting/ranking still uses overlapping hit-rate samples, trust can start at 10 independent calls, and next-session/next-week surfaces retain probability/confidence language. There is no walk-forward holdout, cost model, regime validation, or multiple-testing control (`TechniqueEvaluator.kt:90-143`; `NextSessionEngine.kt:168-174,269-289,434-440`). |
| C5 | **Partial** | The engine sizes from account risk, position cap, and cash when inputs are known, and labels the fallback as not the account 2% rule (`BuyPlan.kt:97-145,227-233,297-306`). | A cash-only account and any portfolio with one unpriced holding discard known cash/equity and fall back to $3,000; unknown equity still emits a plan instead of blocking (`AnalysisViewModel.kt:111-124`). Production wiring for these cases is untested. |
| C6 | **Partial** | Fundamentals client/model/cache/UI now show profile, health, valuation, analyst data, scenarios, and dated events with explicit unavailable/stale/failed states (`Fundamentals.kt:8-60`; `FundamentalsRepository.kt:20-113`; `PositionDetailScreen.kt:954-1290`). | `enterpriseValue` can be mislabeled as market cap, dividend yield may be multiplied by 100 incorrectly, and debt/equity units are ambiguous (`FundamentalsClient.kt:141,148,157`). Statements/history, period metadata, estimates, ownership, peers, editable assumptions, and the 90% coverage gate remain absent. |
| C7 | **Partial** | 50 unit tests across seven suites now pass; CI runs test/lint/debug build; Room exports schema 3 and registers migration 2→3 (`app\build.gradle.kts`; `.github\workflows\android.yml`; `AurumDatabase.kt:21-22,36,46,93`). | There are no Room migration tests, UI tests, randomized/property suites, coverage gate, release-artifact workflow, or required branch protection. Critical cache, bank-import, restore, suitability, and recommendation paths remain untested. |
| H1 | **Partial** | Stock detail shows next earnings, ex-dividend, and payment dates with explicit unavailable states and a seven-day warning (`FundamentalsClient.kt:122-127`; `PositionDetailScreen.kt:1241-1284`). | Estimated/past earnings dates are not handled safely; event risk does not gate advice. Filings, results/guidance history, primary-source links, and macro/regulatory calendars remain absent. |
| H2 | **Partial** | Portfolio performance now includes an equity curve, TWR, SPY comparison, volatility, drawdown, beta, Sharpe, and correlation (`PortfolioPerformance.kt:121-145,285-304`; `WealthScreen.kt:2301-2338`). | Missing-price holdings can be omitted while metrics still render; SPY is price-only rather than total return. VaR/CVaR, attribution, factor exposure, and stress scenarios remain open. |
| H3 | **Partial** | Headline sentiment gained negation handling, source tiers, duplicate clustering, and explicit feed states (`NewsSentiment.kt:1-232`; `NewsRepository.kt:44-92`; `NewsSentimentTest.kt`). | Ambiguous tickers are not entity-validated, article content/event typing/confidence are absent, and the displayed delta is still same-day open/close movement rather than timestamp-aware abnormal return (`NewsClient.kt:36-38`; `NewsRepository.kt:146-153`; `PositionDetailScreen.kt:738-741`). |
| H4 | **Fixed** | User-facing copy identifies Yahoo’s eight predefined screens as a broad liquid sample, not every US stock; scan screen/row/status/as-of coverage is shown (`README.md:20-26`; `Models.kt:145-159`; `PicksScreen.kt:291,393,664,1064,1483-1492`). | A complete security master is still future work, but the app no longer claims complete-market coverage. |
| H5 | **Partial** | Ledger export/restore, device authentication, bank-event retention, delete-all, and privacy disclosure were added (`SettingsViewModel.kt:141-278`; `MainActivity.kt:39-88`; `BankFeedRepository.kt:104-112`). | Restore exports but does not restore the watchlist, is not transactional, and uses incomplete dedup keys. App lock does not re-lock after backgrounding and unlocks on authentication exceptions. Room is unencrypted and Android backups remain enabled. |
| H6 | **Partial** | Trade notes and a reachable advice-history screen now exist; Wealth verdicts record action, price, reason, and release version (`AdviceLogRepository.kt:19-42`; `WealthRepository.kt:326-333`; `AurumRoot.kt:228-234`). | Only Wealth is logged; detail/Picks actions, input snapshots, durable unique event IDs, thesis/invalidation, decisions, peers, and audit-log export remain absent. |
| M1 | **Partial** | Durable above/below price alerts and a 15-minute worker are wired (`AlertsRepository.kt:18-76`; `AlertsWorker.kt:25-43`; `Schedules.kt:74-80`). | Volume, indicator, filing, earnings, and portfolio-risk alerts are absent. Arbitrarily stale quotes can fire alerts, and an alert is consumed before notification delivery is known to succeed. |
| M2 | **Partial** | 1Y, 5Y, and Max ranges were added and lazy-loaded (`PositionDetailViewModel.kt:104-128`; `PositionDetailScreen.kt:217-238`). | 3Y, adjusted/total-return labeling, benchmark overlays, and drawdown charts remain absent. |
| M3 | **Partial** | Disclosures state US-listed equities/ETFs in USD and bank review asks for FX on detected non-USD trades (`DisclosureScreen.kt:118-127`; `BankFeedScreen.kt:261-270,385-395`). | Manual transactions accept arbitrary symbols/currency assumptions, search excludes ETFs, and missing parsed currency can be treated as USD (`AddTransactionViewModel.kt:235-269`; `BankNotificationListener.kt:68-70`). Multi-account support remains absent. |
| M4 | **Partial** | A substantive, versioned methodology/privacy/risk disclosure center is reachable from Settings (`DisclosureScreen.kt:70-151`; `SettingsScreen.kt:430-434`). | Recommendation, target, and alert action cards do not link to it. |
| M5 | **Partial** | Major “whole market,” technique-count, and account-risk wording was corrected; score definitions are centralized. | Residual “whole market” wording remains in Stocks/engine copy, and $3,000 language remains visible on Analysis/Picks fallback paths (`StocksScreen.kt:77,340`; `AnalysisScreen.kt:134,184`; `PicksScreen.kt:820,1024`). |
| M6 | **Partial** | Advice history records a release version and shows price movement since a Wealth verdict (`AdviceHistoryScreen.kt:127-193`). | It is not a consolidated action-aware outcome system: SELL/HOLD/TRIM direction, horizon, benchmark, costs, adverse excursion, drawdown, calibration, and drift are not measured. |
| M7 | **Fixed** | Dashboard and Watchlist explicitly label their no-news result as a technical read, while detail retains headline tone (`DashboardScreen.kt:672-685`; `StocksScreen.kt:822-831`; `PositionDetailViewModel.kt:170-188`). | A single canonical snapshot remains preferable, but the original labeling alternative is met. |
| M8 | **Fixed** | Target rows and dialogs consistently say “before fees & tax” (`DashboardScreen.kt:763-782,849-865`). | The selling-cost preference is informational and is not part of target math, which is acceptable while the gross label remains explicit. |

## Newly verified release blockers

These defects were introduced or exposed by tracing the remediation end to end:

1. **Ledger mutation bypasses:** bank import, Reports edit, and deletion paths can still produce an invalid oversold ledger.
2. **No durable broker identity:** a notification reference is required for some auto-imports but is discarded before persistence; deduplication is heuristic and non-atomic.
3. **Cash-only sizing bug:** a fully known cash balance is discarded when there are no open positions, causing a $3,000 fallback.
4. **Policy inconsistency:** holding verdicts use the investor profile while Portfolio Grade still uses hardcoded concentration thresholds.
5. **Stale actions remain possible:** cached quotes/candles have no maximum age for advice or alerts.
6. **Unsafe restore:** watchlist data is not restored, malformed imports can partially write, and duplicate detection can collapse distinct trades or duplicate rows within one file.
7. **Fail-open app lock:** background/resume is not re-gated, and authentication exceptions reveal the app.
8. **Fundamental unit errors:** market cap, dividend yield, and debt/equity can be mislabeled or mis-scaled.
9. **Alert loss:** alerts are deactivated before notification delivery/permission success.
10. **Audit trail incompleteness:** only Wealth verdicts are logged, same-day events can collapse, and outcomes are raw price drift rather than recommendation performance.

## What to fix next

### P0 — release-blocking integrity

1. Route **every** transaction insert/update/delete/import/restore through one atomic ledger validator; reject non-finite values, oversells, invalid splits/FX, and cash invariant breaks.
2. Persist an immutable broker execution ID with a unique database constraint; make notification import idempotent in one transaction.
3. Fix cash-only/partially priced account equity and make incomplete account/profile inputs produce `INCOMPLETE`, not a default-sized personalized plan.
4. Carry source, feed status, as-of time, stale age, and coverage into every quote, candle set, alert, advice, and plan; enforce explicit maximum ages.
5. Make restore transactional and complete, re-lock on lifecycle backgrounding, fail closed on auth errors, and disable backup or encrypt sensitive storage.
6. Correct fundamentals units with golden Yahoo payload fixtures before using those fields in decisions.

### P1 — advisory evidence

1. Make Portfolio Grade consume the same versioned investor policy as holding verdicts; add missing suitability, constraint, consent, and policy-version fields.
2. Remove residual probability/confidence claims until walk-forward, regime-aware, benchmarked, net-cost calibration is demonstrated.
3. Add primary filings, statement history, period/source metadata, estimate revisions, peer anchors, editable valuation assumptions, and event-risk advice gates.
4. Log every emitted recommendation with immutable ID, model/policy/input snapshot, and fixed-horizon action-aware outcomes.
5. Correct portfolio performance for unpriced holdings and total-return benchmarks; then add attribution and stress testing.

### P2 — quality and workflow depth

1. Add migration, UI, restore, bank-import, stale-cache, app-lock, alert-delivery, suitability, and recommendation-rule tests.
2. Add property/invariant testing, coverage thresholds, required branch protection, and reproducible release artifacts.
3. Complete alert types, 3Y/benchmark/total-return charting, asset enforcement, action-card disclosure links, thesis/journal, and exportable advice history.

## Re-audit validation

The current working tree passed:

- `gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain` — **50 tests across 7 suites; 0 failures, 0 errors, 0 skipped**.
- `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain` — **BUILD SUCCESSFUL**.
- `gradlew.bat :app:assembleRelease --no-daemon --console=plain` — **BUILD SUCCESSFUL**, including R8 and release lint.

Passing builds do not close C7: there are still no migration tests, instrumentation tests, coverage gate, release publishing/verification workflow, or protected required checks.

## Eight-agent method and resolution

The successful independent passes covered:

1. Suitability and account-aware sizing.
2. Ledger, cash, FX, bank import, and reporting.
3. Freshness, failure states, scan coverage, and advice consistency.
4. Statistical calibration and recommendation monitoring.
5. Fundamentals, catalysts, news, and research workflow.
6. Tests, CI, migrations, schema, and release gates.
7. UX, privacy, export/restore, alerts, disclosures, and scope.
8. Adversarial classification of all 21 findings and new regressions.

Two initial agent launches rejected unsupported model settings before executing; they were replaced, leaving eight completed evidence-producing audits. Where agents disagreed, the report used the original baseline and acceptance gate: C3 is **Partial**, not regressed from v5.3.1; H4 is **Fixed** because complete-market claims were removed; M3 remains **Partial** because documented scope is not consistently enforced.

---

## Original v5.3.1 baseline

The sections below preserve the pre-remediation findings and acceptance gates for traceability. Their original `Status` labels describe v5.3.1; the current classifications above supersede them.

## Executive finding

Aurum is already a strong **technical-trading and market-scanning product**. Its 35-technique board, portfolio-aware picks, sector flow, next-session scans, trade ledger, and explicit handling of some unverified inputs are meaningful strengths.

It is **not yet safe to position as a complete professional advisory or equity-research product**. The largest gaps are not more indicators or scanners; they are portfolio truth, suitability, fundamental research, data provenance, statistically valid model claims, and quality controls. Aurum should close the Critical findings below before adding more signal engines.

Severity in this report is measured against the stated goal of an advisory-grade product:

- **Critical:** blocks that claim or can materially mislead a capital-allocation decision.
- **High:** materially limits research completeness, portfolio management, or trust.
- **Medium:** professional workflow, coverage, or credibility gap.

## What is already strong

| Area | Verified strength |
|---|---|
| Technical analysis | Thirty-five techniques, detailed chart explanations, support/resistance, ATR-based levels, and a 5-day outlook (`app\src\main\kotlin\com\aurum\invest\analytics\Techniques.kt:255-385`). |
| Historical measurement | Per-stock 252-session technique replay exists and the UI discloses sample counts (`TechniqueEvaluator.kt:87-181`, `AnalysisScreen.kt:566-675`). |
| Portfolio-aware outputs | Picks, sector exposure, money flow, allocation, and holding verdicts share portfolio context (`PortfolioLens.kt`, `PortfolioAdvisor.kt`). |
| Failure honesty in Wealth | Holdings that cannot be priced or analyzed can be named as unverified instead of silently receiving a verdict (`WealthRepository.kt:251-292`, `WealthScreen.kt:942-982`). |
| Local-first operation | Holdings and bank events stay in an on-device Room database; no account or backend is required (`AurumDatabase.kt`, `AurumApp.kt:33-47`). |
| Decision-oriented presentation | Entries, stops, targets, reasons, timestamps on several Wealth outputs, and explicit cash outcomes are more actionable than raw indicator dashboards. |

## Critical advisory blockers

### C1. No investor profile, mandate, or suitability layer

**Status:** Missing.

Settings contain bank-package and auto-import controls, but no investment objective, time horizon, loss capacity, liquidity need, experience, tax status, restricted securities, or risk tolerance (`SettingsViewModel.kt:17-23`, `SettingsRepository.kt:18-50`). Despite that, the app gives personalized instructions such as sell at the next open, trim to 22%, cap sectors at 35%, and buy replacement names (`PortfolioAdvisor.kt:298-310,589-634,759-835`).

**Why it matters:** The same position and concentration rules are applied to every user. A short-horizon trader, a long-term retirement investor, and an income investor can receive the same action even when their appropriate decisions differ.

**Acceptance gate:**

- Add a versioned investor-policy profile with objectives, horizon, risk/loss capacity, liquidity, experience, constraints, and consent.
- Every personalized recommendation must record which profile fields and constraints affected it.
- Automated tests must demonstrate that materially different profiles produce appropriately different sizing or a clear “insufficient suitability information” result.

### C2. Portfolio “truth” is incomplete and can be internally inconsistent

**Status:** Present but unsafe for advisory use.

The ledger supports only `BUY` and `SELL`; each transaction assumes a USD share price and has no account, cash flow, dividend, interest, tax, split, transfer, or FX fields (`Models.kt:39`, `Entities.kt:7-24`). Portfolio “Total value” is only the sum of open-position market values, not brokerage cash or total account equity (`PortfolioRepository.kt:218-235`, `DashboardScreen.kt:457-503`).

The manual trade form does not reject a sale larger than the held quantity (`AddTransactionViewModel.kt:25-56,204-225`). The position engine silently clamps an excessive sale to shares held, while the report still records the entered quantity and full proceeds (`PortfolioRepository.kt:117-128`, `ReportsEngine.kt:89-134`). This can make position, realized P/L, and activity totals disagree.

The bank parser preserves a detected currency, including non-USD currencies, but auto-import discards it and stores the number as a USD price (`TradeParser.kt:80-104`, `BankNotificationListener.kt:60-72`, `BankFeedRepository.kt:51-63`, `Entities.kt:12`).

Bank notifications can also mutate the ledger automatically at parser confidence 80 without a broker trade ID or mandatory human confirmation (`BankNotificationListener.kt:60-72`). A confident regex false positive therefore becomes portfolio truth and changes every downstream recommendation.

**Why it matters:** Allocation, returns, buying power, and advice cannot be correct unless the account ledger reconciles to the broker.

**Acceptance gate:**

- Reject oversells before persistence and show the available quantity.
- Add accounts, cash/deposit/withdrawal entries, dividends, fees/taxes, splits, transfers, and transaction currency plus FX rate.
- Require review or a unique broker transaction reference before a notification can mutate the ledger; duplicate imports must be impossible.
- Reconcile at least 12 representative broker statements, including partial sales, splits, dividends, transfers, and non-USD alerts, to within one cent and the broker’s share quantity.
- Rename the current dashboard value to “Holdings value” until cash is modeled.

### C3. New advice can be produced from stale, partial, or ambiguously failed data

**Status:** Present but incomplete.

Market data comes from public Yahoo chart/spark/screener/search endpoints and news from Google News RSS (`YahooClient.kt:23-418`, `NewsClient.kt:45-65`). Repositories serve stale cache entries after network failure with no maximum stale age in the returned model (`MarketRepository.kt:31-45,108-126,151-166`; `NewsRepository.kt:37-52`). `Quote.fetchedAt` exists, but quote age and source are not shown on Dashboard or stock detail.

Network and parse failures commonly become `null` or an empty list. A failed news request can therefore render as “No recent headlines,” which is indistinguishable from a verified no-news result (`NewsClient.kt:31-51`, `NewsRepository.kt:46-50`, `PositionDetailScreen.kt:481-489`).

The same ambiguity affects stock scans. Picker engines return an empty list both when no setup qualifies and when Yahoo screen requests fail, while Picks copy can explicitly tell the user that an empty list means the edge is absent rather than the scan failing (`UPatternEngine.kt:311-323`, `EntryPicker.kt:132-170`, `PicksScreen.kt:252-260`). Analysis similarly converts a failed candle load to an empty list and then says the symbol lacks enough history (`AnalysisViewModel.kt:90-101`, `AnalysisScreen.kt:158-164`).

Dashboard valuation also falls back to average cost when a quote is absent, producing a plausible-looking price and zero unrealized movement without a warning (`PortfolioRepository.kt:142-150`, `DashboardViewModel.kt:143-148`, `DashboardScreen.kt:542-569`).

The market-pulse engine substitutes neutral points for missing breadth, advancers, benchmarks, and VIX, then can still return an `INVEST`/`SELECTIVE` call (`MarketPulse.kt:248-281,335-360`).

**Why it matters:** A professional decision card must communicate source, timestamp, quality, and coverage. “No data,” “stale data,” and “neutral evidence” are not equivalent.

**Acceptance gate:**

- Every quote, metric, score, and recommendation carries source, as-of time, stale age, and input-coverage metadata.
- A new action cannot be emitted when a required input is stale beyond its defined SLA or unavailable; the result must become `INCOMPLETE`.
- News must distinguish `NO_ARTICLES` from `FETCH_FAILED`.
- Every scanner must distinguish `NO_QUALIFYING_SETUP` from `PARTIAL_SCAN` and `FETCH_FAILED`, including fetched/failed counts.
- Analysis must distinguish short listing history from a data-fetch failure.
- Portfolio value must show unpriced holdings separately rather than mark them at cost.
- Market regime calls must expose measured-weight coverage and fail closed below a defined threshold.

### C4. “Confidence,” “Trusted,” and probability language is not statistically calibrated

**Status:** Present but incomplete.

The 5-day “confidence” is the share of strength-weighted votes among 35 correlated technical indicators, not an observed forecast probability (`Techniques.kt:2707-2797`). The analysis screen more accurately calls it “% agree,” but portfolio verdicts relabel the same value as “confidence” (`AnalysisScreen.kt:317-326`, `PortfolioAdvisor.kt:603-606,649-654`).

A technique earns `Trusted` with as few as eight directional signals and a 60% hit rate. Five signals are enough for its historical hit rate to influence the current outlook (`TechniqueEvaluator.kt:102-110,114-181`). Outcomes overlap across rolling 5-day windows, the indicators are not independent, and there is no significance interval, multiple-testing control, regime holdout, walk-forward split, benchmark strategy, or transaction-cost/slippage model.

The fixed 0.5% hit threshold also does not remove the normal bullish drift or volatility of the stock, so an always-bullish rule can look skillful during a rising regime without beating a base-rate or benchmark forecast (`TechniqueEvaluator.kt:95-108,134-162`).

Several displayed “expected” ranges are deterministic ATR formulas rather than empirically calibrated prediction intervals (`Techniques.kt:2744-2759`, `NextSessionEngine.kt:395-417`).

**Why it matters:** Agreement, heuristic score, hit rate, and calibrated probability answer different questions. Conflating them can make a weak sample look authoritative.

**Acceptance gate:**

- Rename current values to “indicator agreement” or “heuristic score” until calibration is proven.
- Use non-overlapping or dependence-aware outcomes, walk-forward out-of-sample evaluation, market/regime baselines, net-of-cost returns, confidence intervals, and multiple-testing controls.
- Require a materially larger effective sample for trust; show date range, independent sample count, confidence interval, benchmark edge, drawdown, and model version.
- Permit “probability” only after reliability plots show acceptable calibration on untouched data.

### C5. The fixed $3,000 plan misstates the 2% risk rule

**Status:** Incorrect implementation/claim.

The plan defaults to a fixed `$3,000` budget and reports stop risk as a percentage of that position budget (`BuyPlan.kt:75-85,153-155`). It describes this as Alexander Elder’s 2% rule, but it neither knows total account equity nor caps loss at 2% of account equity (`BuyPlan.kt:131-155,217-219`). The stop logic can allow substantially more than 2% of the planned amount at risk.

**Why it matters:** Position risk should be derived from account equity and the chosen stop, not from an arbitrary order budget. The current wording can give false comfort about loss capacity.

**Acceptance gate:**

- Replace the fixed budget with available buying power/account equity plus a user risk-per-trade policy.
- Compute `shares = allowed account risk / (entry - stop)` and cap the order by cash and concentration constraints.
- Block the plan when account equity, stop validity, or buying power is unknown.
- Unit-test gap-through-stop, fractional-share, high-volatility, and concentrated-account cases.

### C6. Professional company research and valuation are absent

**Status:** Missing.

The core quote model contains price, prior close, ranges, volume, name, and freshness only (`Models.kt:4-26`). Stock detail “Key stats” shows day range, 52-week range, volume, prior close, and day move (`PositionDetailScreen.kt:293-300,526-573`). There are no financial statements, earnings history, estimates, profitability, debt/liquidity, cash flow, capital allocation, dividends, valuation multiples, intrinsic-value scenarios, company description, industry peers, or competitive-risk information.

The only analyst field is a 1–5 average rating on screener rows, without target price, analyst count, dispersion, date, or revisions (`Models.kt:139-153`, `EntryPicker.kt:408-430`).

**Why it matters:** Technical momentum alone cannot establish business quality, balance-sheet safety, valuation, or long-term expected return.

**Acceptance gate:**

- Add normalized company profile, annual/quarterly statements, key ratios, estimates, valuation, dividend, ownership, and analyst-consensus models with period/source metadata.
- Add bull/base/bear valuation scenarios and editable assumptions; do not present a single fair value without sensitivity.
- For a sampled S&P 500 set, at least 90% must show auditable revenue, EPS, FCF, debt/cash, margins, valuation multiples, and next-earnings date or explicit unavailable states.

### C7. There is no automated test or release-quality gate for financial logic

**Status:** Missing.

The repository has 89 production Kotlin files and zero Kotlin files under `app\src\test` or `app\src\androidTest`. `:app:testDebugUnitTest` completes with `NO-SOURCE`; `app\build.gradle.kts` defines no test dependencies. There is also no CI workflow, coverage gate, static-analysis configuration, or exported Room schema.

**Why it matters:** Changes to cost basis, indicators, backtests, scores, parsers, and recommendations can reach users without a deterministic regression check.

**Acceptance gate:**

- Add golden-vector tests for ledger/accounting, indicators, every recommendation rule, date/session logic, cache staleness, news states, and bank parsing.
- Add property tests for invariants such as no negative shares, allocation sums, finite metrics, and no future-data leakage.
- Add migration tests with exported Room schemas, UI tests for critical decisions, and CI that runs build, test, lint/static analysis, and coverage.
- Require branch protection and reproducible release artifacts before advisory positioning.

## High-priority information gaps

| ID | Gap and evidence | Required outcome |
|---|---|---|
| H1 | **No structured catalyst calendar or primary-source research.** News is a five-day Google RSS headline feed; there are no earnings dates/results, SEC filings, guidance history, conference calls, FDA/regulatory events, ex-dividend dates, or macro calendar (`NewsRepository.kt:16-66`, `Models.kt`). | Upcoming-event timeline on every holding; primary-source links; earnings/filing change summaries; explicit event-risk warnings before a recommendation. |
| H2 | **Portfolio risk and performance are incomplete.** `PortfolioSummary` has value and P/L only; Wealth adds concentration and 20-day SPY-relative context but no equity curve, time/money-weighted return, total-return benchmark, volatility, beta, drawdown, Sharpe/Sortino, VaR/CVaR, holding correlation, factor exposure, or stress tests (`Models.kt:60-67`, `PortfolioAdvisor.kt:558-674`). | Broker-reconciled performance, benchmark attribution, contribution by holding/sector, risk metrics with window definitions, and scenario/stress analysis. |
| H3 | **News evidence is too weak for recommendation input.** Sentiment is substring matching on titles only, without article content, negation, entity disambiguation, deduplication, source quality, uncertainty, or catalyst type (`NewsSentiment.kt:1-157`). “Price impact” assigns the entire same-day stock move to a headline by calendar-date matching (`NewsRepository.kt:146-153`). | Structured event classification, source quality, duplicate clustering, timestamp-aware abnormal-return analysis, and a visible confidence/unknown state. |
| H4 | **“Whole market” coverage is overstated.** Whole-market engines merge eight Yahoo predefined screens, each returning at most 100 rows, then shortlist 26–28 names (`EntryPicker.kt:16-28,44-57,132-158`; `YahooClient.kt:285-333`; `NextSessionEngine.kt:220-267`). This is a dynamic candidate sample, not all US equities. | Display exact universe definition, eligible count, fetched count, failed count, exclusions, and scan timestamp. Use a complete security master before saying “whole US market.” |
| H5 | **Data durability, privacy, and user control are incomplete.** There is no user export/import or restore flow, Room is created without database encryption, raw bank-notification content is persisted, backups are allowed, and there is no privacy/disclosure document or app lock (`AurumDatabase.kt:28-39`, `Entities.kt:44-52`, `AndroidManifest.xml:9-13`). | CSV/JSON export and verified restore, encrypted sensitive storage, biometric/PIN option, explicit backup policy, clear/delete controls, retention settings, and in-app privacy/data-source disclosures. |
| H6 | **No professional research workflow or recommendation audit trail.** There is no peer-comparison workspace, thesis with assumptions/catalysts/invalidation, decision journal, source library, approval/review state, or immutable history of what the app recommended and why. `TransactionEntity.note` exists but is not exposed in trade entry/edit flows (`Entities.kt:16`, `PortfolioRepository.kt:35-52`). | Saved thesis and watchlist rationale, peer comparison, model assumption history, recommendation snapshot with inputs/model version, user decision/outcome journal, and exportable audit trail. |

## Medium-priority gaps and credibility fixes

| ID | Gap | Evidence / action |
|---|---|---|
| M1 | User-defined alerts are missing. | Only next-session and U-pattern workers notify; add arbitrary price, volume, indicator, earnings, filing, stop, target, and portfolio-risk alerts with independent preferences (`work\NextSessionWorker.kt`, `work\UPatternWorker.kt`, `SettingsScreen.kt:100-272`). |
| M2 | Long-horizon context is thin. | Stock detail offers only 1D/1W/1M/3M charts, and technique display is capped at 120 candles (`PositionDetailScreen.kt:191-220`, `Techniques.kt:258-289`). Add 1Y/3Y/5Y/MAX, adjusted/total-return views, benchmark overlays, and drawdown view. |
| M3 | Asset and account scope is narrow and not enforced consistently. | Search and screeners target US equities, while parsed notifications recognize several currencies. Explicitly enforce/document supported assets or add ETFs, ADRs, options, fixed income, international instruments, and multiple accounts. |
| M4 | Disclosures are fragmented. | Short disclaimers are duplicated across screens, but there is no canonical methodology/data/risk disclosure with source latency and backtest limitations (`AnalysisScreen.kt:753`, `PicksScreen.kt:371,626,1068`, `WealthScreen.kt:421-430`, `SettingsScreen.kt:251-275`). Add one versioned disclosure center linked from every action card. |
| M5 | Visible copy contains stale or overconfident claims. | Stock detail says “35-technique analysis” but describes “Ichimoku & 11 more” (`PositionDetailScreen.kt:255-270`); the README and engines say “whole market” for predefined-screen samples; scores are shown without definitions. Correct the copy and expose methodology. |
| M6 | Recommendation monitoring is incomplete. | Technique and U-pattern histories exist, but there is no consolidated outcome dashboard for every emitted buy/sell/hold recommendation, including false positives, drawdown, costs, and benchmark. Add model cards and ongoing drift/performance monitoring. |
| M7 | Advice is inconsistent across screens. | Detail advice includes recent news tone, while Dashboard hardcodes `newsScore = 0` and Watchlist omits it (`PositionDetailViewModel.kt:104-118`, `DashboardViewModel.kt:177-184`, `StocksViewModel.kt:194-201`). Compute one canonical advice snapshot or label technical-only variants. |
| M8 | “Exact” sell-target wording ignores exit costs and tax. | The target repository applies the requested percentage directly to average cost, while Dashboard calls the result an exact sell price. Include configured sell fees/tax assumptions or label it “before fees and tax” (`TargetsRepository.kt`, `DashboardScreen.kt:715-812`). |

## Recommended execution order

### P0 — Trust foundation

1. Fix oversell and currency-import correctness; rename holdings value.
2. Add complete account/cash/corporate-action ledger and broker reconciliation.
3. Add source/freshness/coverage contracts and fail-closed recommendation states.
4. Add investor policy/suitability and account-aware risk sizing; replace the fixed $3,000 plan.
5. Relabel uncalibrated scores and implement out-of-sample model validation.
6. Establish tests, migrations, CI, static analysis, and model-version audit logs.

### P1 — Research and portfolio depth

1. Ship company profile, statements, ratios, estimates, valuation scenarios, and peer comparison.
2. Add earnings, filings, dividends, ownership/insider activity, and structured catalysts.
3. Add portfolio equity curve, total-return benchmark, attribution, risk, correlation, and stress testing.
4. Add canonical data/methodology/risk disclosures.

### P2 — Professional workflow and coverage

1. Add thesis/journal, recommendation history, saved screens, and research export.
2. Add configurable alerts and notification controls.
3. Add encrypted backup/restore, privacy controls, and app lock.
4. Expand asset/account coverage only after the US-equity workflow is complete and reconciled.

## Release validation loop

Aurum should pass all of these gates before using “advisory-grade,” “professional,” “trusted,” or probability language:

1. **Accounting:** representative broker statements reconcile cash, positions, income, fees, FX, and realized/unrealized P/L.
2. **Data:** each user-visible metric exposes source, as-of time, stale status, and measured-input coverage; outage tests fail closed.
3. **Suitability:** no personalized action is produced without enough profile and account context; constraints are traceable.
4. **Research:** sampled large-cap, mid-cap, dividend, newly listed, and loss-making companies show complete or explicitly unavailable fundamentals and catalysts.
5. **Models:** untouched walk-forward evaluation beats declared baselines net of costs, with intervals, drawdown, calibration, and no future leakage.
6. **Quality:** CI build/test/lint/migration checks pass; critical financial modules meet an agreed coverage threshold and golden vectors.
7. **Privacy:** export/restore, deletion, encryption, backup policy, and notification retention are verified on a clean device.
8. **UX:** an investor can trace every recommendation from action → evidence → source → assumptions → risks → invalidation → historical outcome.

## Audit method and verification

Four independent read-only agents reviewed the full application rather than accepting README claims:

1. Product and information-completeness audit.
2. Equity-analysis and advisory-rigor audit.
3. End-to-end investor-journey audit.
4. Claims, provenance, freshness, and evidence-quality audit.

Their high-confidence consensus was independently checked against the source: no suitability profile, no professional fundamentals, ambiguous stale/error states, uncalibrated score language, incomplete portfolio/accounting context, and no tests. Distinct agent findings—false “no setup” states, news-blind Dashboard advice, short-history misdiagnosis, and bank auto-import risk—are included above.

Repository validation completed successfully:

- `gradlew.bat :app:assembleDebug --no-daemon --console=plain` — **BUILD SUCCESSFUL**.
- `gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` — **BUILD SUCCESSFUL, but every unit-test task reported `NO-SOURCE`**.
- Production/test inventory — **89 production Kotlin files, 0 test Kotlin files**.

## Bottom line

Aurum should preserve its technical depth, but stop treating additional scanners as the highest-value work. The shortest path to an elite product is to make every number **complete, reconciled, sourced, current, statistically honest, suitable for the user, and reviewable after the decision**.
