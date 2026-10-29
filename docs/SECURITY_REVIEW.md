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

## Feature: UPI intent flow with PSP status confirmation
- Attacks tried:
  - **Spoofed UPI-app responses**: `Status=SUCCESS&ApprovalRefNo=…` while the PSP says FAILED / PENDING; 10,000 concurrent
    spoofs with odd casing, 5 KB values, `%00`, bidi overrides (`UpiIntentUseCasesTest`, `UpiIntentStressTest.tenThousandSpoofed…`).
  - **PSP success for different details**: amount, payee VPA or `txnRef` differ from our stored payment (`…psp success for a
    different amount, payee or ref is a mismatch`, `pspMismatchStormNeverConfirms`).
  - **Tampered link inputs**: VPA `javascript:alert(1)@x`, Cyrillic `а`, `‮` prefix, 300-char VPAs; amounts 0 / negative /
    > ₹1,00,000; `&`/`=`/unicode in payee name and note (parameter injection into the link); 200-char names/notes; `tr` with
    spaces or > 35 chars (`UpiLinkBuilderTest`, `VpaValidatorTest`).
  - **Hostile `response` extra**: null, empty, 400 KB, duplicate keys, control chars, `a=b=c&d` (`UpiResponseParserTest`,
    `AndroidUpiAppLauncherTest` bounds it to 16 KB).
  - **Rapid double tap** on Pay (`PayViewModelTest.rapid double tap creates exactly one payment`).
  - **Process death while inside the UPI app**: in-flight payment id in `SavedStateHandle`; the late result is still confirmed.
  - **Airplane mode / PSP down**: status check throws → `Unresolved`, payment stays PENDING, UI says "do not pay again".
  - **HTTP client**: path traversal via `txnRef` (`../admin?x=1` is path-encoded), 400/401/429/5xx, connection drop, body
    timeout, HTML instead of JSON, wrong types, unknown status, `txnRef` echo mismatch, absurd `Retry-After` (`HttpPspClientTest`).
  - **Intent hygiene**: chooser wraps a plain `ACTION_VIEW` with no URI grants; `<queries>` declares only `VIEW upi:`;
    no new exported components.
- What broke:
  - **High (fixed)**: a timeout while *reading the response body* surfaced as a raw `SocketTimeoutException` instead of
    `PspException.Timeout`. `ConfirmPaymentUseCase` only catches `PspException`, so this would have crashed the coroutine and
    left the payment stuck in PENDING with no UI. Regression: `HttpPspClientTest.timeout becomes a Timeout exception`.
  - **Medium (accepted, inherent to UPI intents)**: any app registered for `upi://` can be chosen in the chooser and receives
    the payee/amount. Mitigation: the app's answer is never trusted; only the PSP confirms.
  - **Low (open)**: a `Mismatch` (PSP success with different details) is shown as "unresolved" but not persisted on the
    payment; a reconciliation flag/column would help support.
  - **Low**: transient PSP errors at *initiate* mark the payment FAILED (correct: the UPI app was never opened, no money moved),
    but a user retry creates a second `txnRef`; fine for intents, and feature 6 adds idempotency keys for the polling path.
- Fixed now:
  - Body-read IO errors are mapped to typed `PspException`s (above).
  - `UpiPayRequest` normalises and validates everything that goes into the link; `UpiResponseParser` is bounded and never throws.
- Scope for improvement:
  - Persist `Mismatch` on the payment; pin the PSP certificate when a real host exists (SSL pinning, with a documented
    pin-rotation plan); feature 6 polling with backoff for PENDING results; feature 7 `FLAG_SECURE` on the pay screen.

## Feature: QR scan-to-pay (CameraX + ML Kit)
- Attacks tried:
  - **Hostile QR payloads** (`UpiQrPayloadParserTest`, 14 cases; `QrParserStressTest`, 10,000 fuzzed payloads): non-UPI content
    (`https:`, `javascript:`, BharatQR/EMVCo TLV), `upi://mandate`, missing / malformed / homoglyph (Cyrillic `а`) / bidi-prefixed
    VPAs, `javascript:alert(1)@x`, amounts `-1`, `0`, `1e5`, `100000.01`, `1.234`, Arabic-Indic digits, `NaN`, `0x10`, 19-digit
    numbers, `cu=USD`, duplicate `pa=`/`am=` (first wins), `url=http://evil…` / `redirect=javascript:` extras (ignored, never
    followed), NUL and `‮` inside names, 500-char notes, bad percent-escapes (`%ZZ`), 100 KB payloads.
  - **Frame injection**: barcodes delivered before permission is granted, while a result is shown, or after an error are ignored
    (`ScanViewModelTest`).
  - **Permission denial**: denied with rationale, denied permanently ("open settings"), and the camera failing to start
    (`ScanViewModelTest`, `ScanScreenTest`, `AndroidCameraPermissionTest`).
  - **Payee swap after scan**: the pay screen locks payee VPA/name (and the amount for merchant QRs) coming from a QR
    (`PayViewModelTest.qr route arguments prefill the form, lock the payee…`), so a tap on the wrong field cannot redirect money.
  - **Process death with a scanned result**: the raw payload is restored from `SavedStateHandle` and re-parsed/re-validated.
  - **Leakage**: `UpiQrPayload.toString()` omits the raw payload; frames are analysed in memory and never persisted; no logging.
- What broke:
  - **Medium (open until feature 4)**: the payee *name* in a QR is attacker-controlled text ("Government refund desk"). The UI
    always shows the VPA next to it, and feature 4's PSP VPA verification will replace the QR name with the registered name.
  - **Low**: the 4 KB raw cap (`MAX_RAW_LENGTH`) is generous (real UPI QRs are < 500 chars); fine for DoS, kept for odd generators.
  - **Low (cosmetic)**: the torch button uses a placeholder icon from the core icon set.
- Fixed now:
  - Nothing broke in the parser: every hostile input above was rejected or sanitised on the first run. The parser is bounded
    (length check before any parsing, first-value-wins, printable-only free text, strict amount regex before numeric parsing).
- Scope for improvement:
  - PSP VPA verification for scanned payees (feature 4); `FLAG_SECURE` on the scan/pay screens (feature 7); device run of
    CameraX + ML Kit (`CameraXController`, `MlKitBarcodeScanner` are compile-only here: no device was available).
