# Security review

One entry per feature. Every Critical/High finding is fixed in the same commit with a regression test.
Severity scale: Critical / High / Medium / Low.

## Feature: Home, transaction history (Room) and payment state model
- Attacks tried:
  - SQL injection through the history search (`'; DROP TABLE payments; --`, `" OR 1=1 --`, `%' OR '1'='1`): Room binds the
    parameter, nothing is spliced (regression: `PaymentDaoTest.search text is bound, never spliced into sql`).
  - LIKE wildcard abuse (`%`, `_`) in the search: only widens the match within the user's own data (`PaymentDaoTest`).
  - Control characters / invisible characters in the search: a leading NUL (`\u0000`) or BOM (`﻿`) made SQLite treat the
    LIKE pattern as `%` (match-all), silently dropping the filter.
  - Unicode, RTL overrides (`‮`), emoji and 10,000-character payee names stored and searched (`PaymentDaoTest`).
  - 500-character and control-character search text from the UI (`HistoryViewModelTest.query is trimmed and length capped`).
  - Negative / zero / overflow amounts (`Payment` init: 1 paisa to ₹1,00,000, `PaymentStateMachineTest`).
  - Illegal state transitions (all 36 state/event pairs), double confirmation, 50 concurrent confirmations of one pending
    payment (`PaymentRepositoryImplTest.concurrent confirmations ... succeed exactly once`).
  - Process death: history filter/query restored from `SavedStateHandle`; garbage in saved state falls back to `ALL`.
  - Tampered saved state (`"DROP TABLE"` as filter name) → `HistoryFilter.fromNameOrAll` falls back safely.
  - Exported components: only `MainActivity` (launcher). `allowBackup=false`, data extraction rules exclude everything,
    `usesCleartextTraffic=false`.
- What broke:
  - **Low**: NUL / BOM-prefixed search text collapsed to match-all. Not a data leak (only the user's own history) but a
    correctness/robustness bug.
  - **Medium (open)**: Room data (payee names, VPAs, amounts) is stored in plaintext inside the app sandbox. Field-level
    encryption with the Keystore `CryptoBox` is feature 7.
  - **Low (open)**: a corrupt row (amount outside range) would make `toDomain()` throw and the whole list show the error
    state instead of skipping the row.
- Fixed now:
  - `PaymentRepositoryImpl.sanitizeQuery` strips ISO control and Unicode FORMAT characters; `HistoryViewModel.setQuery` does
    the same before saving the query (regression: `PaymentRepositoryImplTest.control characters in the search cannot widen
    the match`, `HistoryViewModelTest.query is trimmed and length capped`).
- Scope for improvement:
  - Encrypt sensitive columns (feature 7); skip (and report) corrupt rows instead of failing the list; paging for very large
    histories (the full 100k list materialises in ~1.8 s, see the load report); `FLAG_SECURE` on payment screens (feature 7).
