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

## Feature: VPA validation (PSP verify) and collect requests
- Attacks tried:
  - **VPA enumeration / amplification**: malformed VPAs never reach the PSP (format check first: 2,500 of 10,000 in the stress
    run); typing is debounced (600 ms) and only the final value is verified (`PayViewModelTest`, `CollectAndVerifyStressTest`).
  - **Paying an unknown UPI ID**: `NotFound` blocks submit with `VPA_NOT_FOUND`; PSP outage is `Unavailable` (pay allowed with
    the typed name plus a visible warning), never silently treated as "found".
  - **Self-declared payee names** (QR `pn=`, typed names): the PSP-registered name replaces them and the name field locks
    (`a qr payee is verified on open and the registered name replaces the qr text`) — closes the feature 3 Medium finding.
  - **Hostile collect rows from the PSP**: invalid VPA, negative amounts, unknown status → dropped row by row, never a failed
    list (`HttpPspClientTest.collect requests are listed and answered`, `RefreshCollectRequestsUseCase`).
  - **Replay / double-answer**: the same request approved 200× concurrently → exactly one PSP answer, one payment
    (`oneRequestAnsweredTwoHundredTimesConcurrentlyCreatesOnePayment`); approve then approve/decline again → `AlreadyAnswered`.
  - **Stale server state**: a server row still saying PENDING cannot revert a local APPROVED/DECLINED (`CollectDaoTest`).
  - **Expired requests**: approving after expiry is refused locally and the PSP answers 410 (`an expired request cannot be approved`).
  - **Clock changes**: expiry derives from the injected `Clock`; a device clock moved backwards would only delay local expiry,
    the PSP still enforces 410.
  - **PSP down mid-approval**: request stays PENDING, the never-opened payment is FAILED, the user can retry (`Unavailable`).
  - **Approval accepted, status unknown** (found by the stress run): the payment stays PENDING, never FAILED (25 of 572 in the run).
  - **409 from the PSP** (answered on another device/process): mapped to `AlreadyAnswered`, not `Unavailable`.
- What broke:
  - **Medium (open, product-level)**: collect requests are the main UPI fraud vector ("approve to receive ₹5,000"). The UI shows
    VPA, amount, note and received time, and approval is an explicit tap; feature 7's biometric/PIN lock should gate approvals
    and a first-time-payee / large-amount warning is recommended.
  - **Low (fixed)**: PSP 409 was reported as `Unavailable` (retry hint) instead of `AlreadyAnswered` — fixed with a regression test.
  - **Low (accepted)**: the per-request mutex is in-process; cross-process double answers rely on the PSP's 409 + the Room CAS.
- Fixed now: 409 mapping; registered name overrides QR/typed names; hostile collect rows dropped.
- Scope for improvement: cache verified names per session (LRU) to cut PSP lookups; gate approvals behind the app lock
  (feature 7); first-time-payee warning; show the collect requester's verified name from the directory as well.

## Feature: AutoPay mandates (create / view / pause / resume / revoke, frequency + max-amount rules)
- Attacks tried:
  - **Rule bypass at creation**: amounts 0 / negative / > ₹1,00,000, start in the past, end before start, 10-years-plus validity,
    malformed and PSP-unknown payees (`MandateRulesTest`, `MandateUseCasesTest.create rejects rule violations before touching the psp`).
  - **Debit above the cap, twice in a period, on paused / revoked / expired mandates, outside the validity window**
    (`MandateRulesTest`, `MandateUseCasesTest`; 10,000 debit attempts in `MandateStressTest`: 1,429 over-cap rejections, exactly one
    debit per period per mandate).
  - **Period boundary games**: the frequency window is computed in the injected `ZoneId` (a debit at 20:30 UTC is already the next
    IST day), ISO weeks and quarters (`period boundaries use the given time zone, not UTC`).
  - **Double debit race**: the period is reserved with a counter compare-and-set *before* the PSP is called, so two racing debits
    never both reach the PSP; a PSP rejection releases the slot (`psp rejecting an execution ... does not count as executed`).
  - **Illegal lifecycle transitions**: all 16 (status, action) pairs classified; resume on ACTIVE, anything on REVOKED/EXPIRED rejected.
  - **Racing pause / resume / revoke** (1,000 concurrent actions): see "What broke".
  - **PSP down**: create stores nothing; update keeps the local status; execute fails the payment and gives the period back.
  - **Process death on the create form**: fields restored from `SavedStateHandle`.
- What broke:
  - **High (fixed)**: `UpdateMandateUseCase` did read-local → PSP → compare-and-set without serialisation. With PAUSE and REVOKE
    racing, the PSP applied both (ACTIVE→PAUSED→REVOKED) but the REVOKE's local CAS (expecting ACTIVE) lost, leaving the device
    showing PAUSED for a mandate the PSP had revoked. Found by `MandateStressTest.thousandRacingLifecycleActionsStayConsistent`.
    Fixed with a per-mandate mutex around the whole update and by mirroring the status the PSP returns; the stress test is the regression.
  - **Low (accepted)**: `AS_PRESENTED` mandates have no period cap by design (NPCI semantics); the max-amount rule still applies and
    the UI labels them clearly.
  - **Low (open)**: the simulated "merchant debit" lives in the app (sandbox); a real PSP would push debit notifications and the
    rules would run server-side as well as on the client.
- Fixed now: the race above; rule order (validity window before status) so an ended mandate reports the precise reason.
- Scope for improvement: gate revoke/create behind the app lock (feature 7); mandate debit notifications from the PSP (polling in
  feature 6 can reconcile PENDING mandate payments); UMN display with copy-to-clipboard disabled for sensitive data.

## Feature: status polling, exponential-backoff retries and idempotency keys
- Attacks tried:
  - **Replay / double-spend with one key**: 100 (Room integration) and 1,000 (stress) concurrent submits with the same
    idempotency key → exactly one payment row, one PSP registration (`IdempotencyRoomTest`, `PollingAndIdempotencyStressTest`).
  - **Key reuse with different details** (amount, payee): `IdempotencyConflictException`, no second payment, no PSP call
    (`IdempotencyTest.a key reused with a different amount or payee is a conflict`).
  - **Key reuse after the attempt settled** (FAILED/SUCCESS): conflict, never a silent "same result" that could hide a failed debit.
  - **Process death between "row stored" and "PSP called"**: a fresh repository/use case over the same database finds the CREATED
    row under the key and completes the registration once (`processDeathBetweenStoreAndPspCallIsRecoveredByTheKey`); the Pay
    screen keeps the key and the in-flight id in `SavedStateHandle`, so a relaunch cannot submit twice (`PayViewModelTest`).
  - **Polling storms / amplification**: 3 racing pollers per payment × 1,000 payments collapse into one single-flight poller each;
    a hostile caller hammering `poll` gets the shared result, not extra PSP calls (`concurrent polls ... single flight`).
  - **Thundering herd after an outage**: 10,000 retries scheduled from the same instant → peak of 10,000 in one 10 ms window
    without jitter vs **45** with full jitter (`jitterPreventsAThunderingHerd`).
  - **Absurd `Retry-After`**: capped by `maxDelayMillis` (`BackoffPolicyTest`, `HttpPspClientTest`); negative values ignored.
  - **Non-retryable errors** (401, 4xx, malformed): never retried; **cancellation**: no attempt after the scope is cancelled.
  - **Marking money as lost**: a payment that exhausts polling stays PENDING ("check again"), never FAILED.
- What broke:
  - **Medium (fixed during build)**: a failed attempt's idempotency key stayed in `SavedStateHandle`; the next tap would have hit
    `IdempotencyConflictException` instead of paying. The key is now spent (removed) when an attempt fails or settles.
  - **Low (accepted)**: the per-key mutex is in-process; cross-process callers rely on the Room unique index + the takeover rule
    (a CREATED row is completed by whoever finds it; the PSP dedupes the registration by `Idempotency-Key` / `txnRef`).
  - **Low (open)**: reconciliation runs when the Home screen starts; a background `WorkManager` job would also cover the case
    where the user never reopens the app.
- Fixed now: spent-key handling; single-flight poller closes the feature 2 load finding (status-call storm per confirm).
- Scope for improvement: WorkManager-based reconciliation; persist poll attempt counts across process death; server-side
  idempotency TTL documented in the PSP contract.

## Feature: Security (Keystore AES-GCM, biometric/PIN lock, device binding, root/tamper, Play Integrity, FLAG_SECURE)
- Attacks tried:
  - **Ciphertext tampering**: every flipped bit in `iv || ct || tag` → `AEADBadTagException`; AAD mismatch (moving a value to another
    key) → rejected; foreign key → rejected; empty / 1 MB payloads (`AesGcmCipherTest`, `SecureStoreTest`).
  - **Secrets at rest**: the PIN record and the device binding (phone number) never appear in clear in the store; a tampered or
    foreign ciphertext reads as *missing*, never as a different value (`SecureStoreTest`, `PinManagerTest`, `DeviceBindingTest`).
  - **PIN brute force**: 1,000 attempts → 5 hashed attempts, 995+ refused *before* PBKDF2 (locked-out path is ~free); lockout grows
    30 s → 60 s → … → 1 h cap; the correct PIN is refused while locked; clock moved back cannot shorten a lockout; lockout state
    survives process death (`PinManagerTest`, `SecurityStressTest.bruteForceHitsLockoutAndStopsHashing`).
  - **Weak PINs**: 111111, 123456, 654321, 121212, non-ASCII digits rejected. Constant-time compare (`MessageDigest.isEqual`).
  - **OTP guessing**: 3 attempts then the code is burned, 5-minute TTL from the injected clock, codes from `SecureRandom`
    (`DeviceBindingTest`); the binding breaks when the device fingerprint changes (cloned data / restored backup).
  - **Root / hooking / repackaging**: su paths, Magisk paths, `test-keys`, Frida default ports parsed field-by-field from
    `/proc/net/tcp(6)` (hex `69A2`/`69A3` in LISTEN state, not a substring match), Frida libraries in `/proc/self/maps`, attached
    debugger, debuggable flag, signing-certificate SHA-256 mismatch; probe failures never crash the scan (`TamperChecksTest`).
    Payments are blocked on root / Frida / signature mismatch, warned on debugger (`PayViewModelTest.a blocked device cannot pay`).
  - **Play Integrity**: the request hash binds the token to `paymentId|amount|payee`; a FAILED verdict blocks, UNEVALUATED or a
    Play-services error warns (never blocks: availability is not a user fault), unconfigured project → skipped (`IntegrityGateTest`).
  - **Screenshots / overlays**: `FLAG_SECURE` on lock, pay, scan, collect, mandate and security screens (reference-counted, cleared
    on dispose), `filterTouchesWhenObscured` on the decor view app-wide (`LockScreenTest.lockScreenIsSecureAndFiltersObscuredTouches`).
  - **Lock bypass**: the lock screen covers the whole NavHost; relock after > 60 s in background; biometric failure falls back to PIN;
    digits never leave the ViewModel (`AppLockViewModelTest`).
- What broke:
  - **Medium (fixed)**: a burned OTP (3 wrong codes) was deleted, so the next attempt said "nothing pending" instead of "expired"
    and the UI could have offered verification against nothing. The record is now kept with zero attempts and answered `Expired`.
  - **Medium (accepted, documented)**: Play Integrity tokens are *verified by a fake* (`FakeIntegrityVerifier`): a client can't verify
    its own token; real verification needs a server calling Google's `decodeIntegrityToken`. The UI labels the verdict as sandbox.
  - **Medium (accepted, documented)**: SMS OTP delivery is simulated (`FakeOtpSender` shows the code in-app). The flow (TTL, attempts,
    masking, encrypted record, fingerprint check) is real; the transport is not.
  - **Low (accepted)**: `BuildConfig` values (`PLAY_INTEGRITY_CLOUD_PROJECT`, `EXPECTED_SIGNING_SHA256`) are extractable from the APK;
    neither is a secret (a project *number* and a public certificate hash). No API keys live in the APK.
  - **Low (open)**: root detection is heuristic and bypassable by a determined attacker (Magisk DenyList, Frida on a non-default
    port); Play Integrity (server-verified) is the real control. Room columns stay plaintext (sandbox; `allowBackup=false`); SQLCipher
    or field-level encryption with `CryptoBox` is the next step.
  - **Low (open)**: Keystore key invalidation on new biometric enrollment is not used because the key protects data, not the
    unlock (the unlock is BiometricPrompt + PIN). A per-unlock key with `setUserAuthenticationRequired(true)` would bind decryption
    to a fresh biometric.
- Fixed now: burned-OTP semantics; API-28 guard on signing-info lookup; locale-independent OTP formatting.
- Scope for improvement: SQLCipher / column encryption; WorkManager relock; biometric-bound Keystore key; server-side integrity
  verification; gate collect approvals and mandate revokes behind a fresh unlock.

## Feature: CheckoutKit integration (JitPack `checkoutkit-ui:v1.0.0`)
- Attacks tried:
  - **SDK public-API misuse**: wrong coordinate (aggregate artifact → duplicate classes, documented), `Unresolved` without a
    transaction id, `Pending` delivered only on close, retry/pending policies with extreme values; all exercised through the real
    sheet in `CheckoutKitIntegrationTest` and `CheckoutKitStressTest`.
  - **Amount truthfulness**: the adapter reports the amount the PSP actually captured; a PSP success for a different amount is passed
    through unchanged so the SDK's own `ResponseMismatch` check fails the payment (`a psp success for a different amount is reported
    truthfully`).
  - **Replay / double charge through the SDK**: the SDK's idempotency key becomes the PSP order reference, so a retried `charge`
    (SDK retry policy, double submit) hits the same order; 1,000 concurrent sessions → 1,000 distinct orders, every SUCCESS row
    matched to a captured PSP order of the same amount (`CheckoutKitStressTest`).
  - **Transport failures**: timeouts, network, 429 + `Retry-After`, 5xx, 401, 4xx, malformed JSON → typed `GatewayException`
    / `GatewayResult.Failed` so the SDK retries only what is retryable (`PayFlowPaymentGatewayTest`).
  - **Captured-but-unanswered**: PSP accepts the charge then every status call times out → the SDK reports `Unresolved`; PayFlow
    stores it PENDING under the gateway's order reference so the feature 6 reconciler settles it later, never "failed".
  - **Cancelled checkout**: nothing is charged and nothing is stored (`cancelled_storesNothing`).
  - **Recording idempotency**: the same order recorded twice yields one history row (`checkout:<orderId>` key).
  - **Screen capture**: the checkout screen is a `SecureWindow`; the SDK's sheet sets `FLAG_SECURE` itself.
- What broke:
  - **Medium (fixed)**: `CheckoutResult.Unresolved` carries only the last error, so a host cannot correlate it with its PSP order.
    Fixed on the PayFlow side by having the gateway adapter remember `orderId → txnRef`; reported as SDK feedback (see DECISIONS).
  - **Low (fixed)**: the sandbox PSP's decline code `U30` is not in the SDK's NPCI table, so declines surfaced as a generic
    `GatewayError(U30)` instead of `Declined`. The sandbox now returns `ZA` (declined by payer); feedback recorded for the SDK.
  - **Low (accepted)**: card details typed into the sheet never reach PayFlow (the adapter only sees `method.type`); the sandbox PSP
    has no card tokenisation, which the SDK README already states.
- Fixed now: order-reference mapping for `Unresolved`; sandbox decline code.
- Scope for improvement: pass the SDK's `Pending` through the feature 6 poller immediately (today the reconciler picks it up after
  30 s); refunds from the history screen via `CheckoutKit.refund`; PSP-side card tokenisation.

## Feature: Real UPI apps (UPI-apps mode), incoming `upi://` links, receive QR
- Attacks tried:
  - **Spoofed / replayed app responses in UPI-apps mode**: `Status=SUCCESS` for *another* `txnRef` (upper/lower case, `%00`,
    bidi prefix, 5 KB padding) 10,000× concurrently against the real Room compare-and-set → every one refused as `Mismatch` or
    `Unknown`, payment still PENDING, nothing settled (`UpiAppUseCasesTest`, `UpiAppModeStressTest.tenThousandSpoofedAppResponses…`).
    A FAILURE for another reference must not fail *our* payment either (tested).
  - **Double delivery / racing settlements**: 1,000 concurrent deliveries mixing the app's SUCCESS, the app's FAILURE and the user's
    "I paid" / "not paid" → exactly one settles; the rest are `AlreadySettled` / `NotAllowed` (`aThousandReplayedDeliveriesOfOneSuccessSettleExactlyOnce`).
  - **Wrong app answering**: the chosen package is pinned on the launch intent (`setPackage`) *and* stored on the row; the sheet only
    accepts a package it listed (`PayViewModelTest.picking an app…`: an unlisted package is ignored, nothing created).
  - **Response-code trust**: the app's `Status` is never shown as "verified". UI, detail screen and the shared receipt say
    "Reported by <app>" or "Marked by you"; `statusSource` is persisted so the history can never upgrade it later.
  - **Hostile incoming `upi://` links** (VIEW intent from browsers / chat apps): `javascript:` VPAs, homoglyph/bidi VPAs, negative /
    oversized / `1e9` amounts, `cu=USD`, `upi://mandate`, `intent://`, 20 KB payloads, 10,000 fuzzed links → zero exceptions, only
    well-formed `upi://pay` links reach the Pay screen, where payee and amount are locked (`IncomingUpiLinkStoreTest`,
    `IncomingUpiLinkTest` (Hilt activity launched with the intent), `UpiAppModeStressTest.tenThousandHostileIncomingLinks…`).
    The link is consumed exactly once (a rotation cannot re-open Pay) and only while the app is unlocked.
  - **Receive QR injection**: `&`, `=`, unicode in the profile name cannot add parameters (percent-encoded; parses back with the
    scanner's parser), the amount is formatted from paise, the VPA has passed `VpaValidator` (`UpiProfileTest`).
  - **Profile at rest**: name and VPA are AES-GCM encrypted through `SecureStore` (AAD = key name); the ciphertext never contains
    the VPA; a corrupt record reads as "no profile" (`UpiProfileTest`).
  - **Mode toggle abuse**: the only UI that changes `PaymentMode` is the Security screen, which the lock gate covers when a PIN is
    set; `UPI_APPS` cannot be selected without an installed handler (`SecurityViewModelTest`, `SecurityScreenTest`); garbage in the
    preference falls back to the device default (`PaymentModeRepositoryTest`).
  - **Shared QR image**: written to `cache/qr/` and exposed through a non-exported `FileProvider` with a read-only grant on the
    share intent; no world-readable file.
  - **Intent surface**: `MainActivity` is `singleTop`; the new filter is `VIEW` + `BROWSABLE` on `upi://pay` only; the `FileProvider`
    is `exported=false`; no other component was added.
- What broke:
  - **Medium (accepted, inherent to a non-PSP app)**: in UPI-apps mode PayFlow has no bank or PSP to confirm against, so a UPI app's
    answer (or the user's manual mark) *is* the stored outcome. Mitigations: `txnRef` matching, idempotency key per attempt, pinned
    package, "reported / marked" wording everywhere, PENDING by default when no answer arrives, manual settlement recorded as
    `USER`. This is documented in the README as the honest limitation.
  - **Low (fixed during build)**: a prefilled payee was verified on `init` regardless of mode, so in UPI-apps mode a real VPA
    would have been checked against the sandbox directory and blocked as "not found". Verification is now skipped in UPI-apps mode
    (`VpaVerificationState.Skipped`) and only route-prefilled payees are verified on open (`PayViewModelTest.upi apps mode skips…`).
  - **Low (fixed)**: a Home "History" icon and a "History" tile both carried the same content description; the icon was removed.
  - **Low (open)**: the recent-payees row shows names as stored from earlier payments; in UPI-apps mode those names are what the user
    typed (or the VPA handle), not bank-registered names.
- Fixed now: mode-aware verification; duplicate accessibility label.
- Scope for improvement: ask the user to re-authenticate (biometric) before switching to UPI-apps mode; a real PSP/backend would
  replace manual marking; show the UPI app's `txnId` on the History row for support.

## Feature: Scan a UPI QR from a gallery image (Photo Picker)
- Attacks tried:
  - **Hostile QR contents in an image**: non-UPI QRs (URL, Wi-Fi), `upi://mandate`, `javascript:` / bidi / homoglyph VPAs, negative
    amounts, `cu=USD`, a 1.5 KB payload with `url=http://evil…` → every one goes through the same `UpiQrPayloadParser` as a camera scan;
    non-payable codes become "This image has a QR code, but it isn't a UPI payment QR", free text is capped, `url=` is ignored
    (`ImageQrDecodingTest`, `GalleryQrStressTest`).
  - **Several QRs in one screenshot**: all are decoded; only valid UPI ones count; the first in reading order (left→right, top→bottom)
    is used, a non-UPI code next to it can never be picked (`two qrs in one image…`).
  - **Corrupt / missing / non-image files, non-content URIs**: `Unreadable`, never a crash (`AndroidImageQrDecoderTest`, real
    `BitmapFactory` in Robolectric native graphics mode). Only `content:` and `file:` URIs are opened.
  - **Decompression bomb / 50 MP photo**: bounds are read first and the bitmap is decoded with `inSampleSize` so its longest side is at
    most 2,048 px before any decoder sees it (`ImageSampling.sampleSize`, tested down to a 100,000 × 100,000 header → 1/64).
  - **Frame/pick races**: while an image decodes, camera frames and a second pick are ignored, so a stale camera frame cannot replace the
    gallery result (`ScanViewModelTest`).
  - **Privacy**: no storage permission (Photo Picker grants one image); the image is read through the content resolver only, never
    copied into app storage, and neither the URI nor the payload is logged or persisted (the URI is not put in `SavedStateHandle`;
    only the decoded payload is, exactly as for a camera scan).
- What broke: nothing in the decoder. **Low (fixed during build)**: the first draft let a rotation's permission re-check overwrite an
  image error / a gallery result found without camera permission; `onPermissionResult` now keeps decode and error states and restores a
  found payload regardless of the camera grant.
- Fixed now: rotation/permission interplay above.
- Scope for improvement: show a thumbnail of the picked image next to the result; run ML Kit on a cropped region when a screenshot has
  many codes.

### Follow-up found on the emulator walkthrough (UPI-apps mode)
- **High (fixed)**: the Home-start reconciler polled *every* stale pending payment against the sandbox PSP, including UPI-apps
  payments the PSP has never seen. Its "not found" marked a pending real payment (money possibly moved) as FAILED, and the detail
  screen then read "Confirmed by the sandbox payment provider". Payments launched through the system-chooser fallback had no marker
  at all. Fix: every UPI-apps payment now carries a handler marker (`Payment.VIA_SYSTEM_CHOOSER` when the app is unknown), the
  reconciler's SQL excludes `handler_package IS NOT NULL`, and `ConfirmPaymentUseCase` refuses to ask the PSP about such a payment.
  Regressions: `UpiAppUseCasesTest.the psp status check and the reconciler never touch a pending upi app payment`,
  `PaymentDaoTest.stale pending lookups for the reconciler skip upi app payments`.
- **High (fixed)**: gallery decoding rejected every real image (bounds-only decode treated as unreadable), and stopping the camera
  mid-frame closed the `ImageProxy` under ML Kit's native recognizer (SIGSEGV). See commit "release ML Kit frames only after
  recognition completes".
- **Medium (fixed)**: registering for `upi://pay` made PayFlow appear in its own system chooser (sandbox mode / chooser fallback);
  choosing it would send the payment link back into PayFlow. `chooserIntent` now passes `EXTRA_EXCLUDE_COMPONENTS` with PayFlow's
  `MainActivity` (`AndroidUpiAppLauncherTest`), verified on the emulator (Android then opens the only real handler directly).
- **Test-harness note**: `adb shell am start -d "upi://pay?pa=…&pn=…&am=…"` loses everything after the first `&` because the device
  shell splits the command; the app correctly showed a payee-only link. Quote for the remote shell when testing.
