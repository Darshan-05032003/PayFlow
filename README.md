# PayFlow

**A UPI payments app for Android, built the way a payments team would build it: a payment state machine that
cannot be pushed into an illegal state, idempotent submits, PSP-confirmed success, and a hardened client.**
Pay by UPI intent or QR, validate UPI IDs, handle collect requests and AutoPay mandates, all against a
realistic **sandbox PSP** (no real money moves).

> **Sandbox disclaimer.** An individual developer cannot get a bank/PSP backend, so PayFlow talks to an
> in-memory `FakePsp` behind a `PspClient` interface. The HTTP client (`HttpPspClient`, OkHttp) is real code
> exercised against MockWebServer, but no production PSP endpoint exists. The UPI intent flow does open real
> UPI apps on a device (see "Real UPI apps" below).

## Features

| # | Feature | Status |
|---|---|---|
| 1 | Home, transaction history (Room), payment state model (created → pending → success / failed → refund) | done |
| 2 | UPI intent flow: `upi://pay` deep links, app chooser, response parsing, **PSP status confirmation** before success | done |
| 3 | QR scan-to-pay with CameraX + ML Kit, safe payload validation, camera permission handling | done (camera path compile-only: no device) |
| 4 | VPA validation (format + simulated verify returning the payee name) and collect requests (approve / decline / expire) | done |
| 5 | AutoPay mandates: create / view / pause / resume / revoke with frequency and max-amount rules | done |
| 6 | Status polling with exponential backoff + jitter and idempotency keys stored in Room before the PSP call | done |
| 7 | Security: Keystore AES-GCM, biometric/PIN lock (PBKDF2 + lockout), simulated device binding, root/tamper detection, Play Integrity, `FLAG_SECURE` | done (Keystore / BiometricPrompt / Play Integrity request are device-only) |
| 8 | CheckoutKit integration: the [CheckoutKit](https://github.com/Darshan-05032003/CheckoutKit) payment sheet from JitPack for merchant checkout | done |

## Screenshots

_Placeholder: screenshots will be added from a real device._

| Home | History | Payment detail | Pay | Scan QR |
|---|---|---|---|---|
| _todo_ | _todo_ | _todo_ | _todo_ | _todo_ |

## Tech stack

Kotlin 2.4 · Jetpack Compose (BOM 2026.09) + Material 3 · Navigation Compose (type-safe routes) · Hilt (KSP) · Room 2.8 ·
DataStore · kotlinx.coroutines / Flow · kotlinx.serialization · OkHttp 5 · CameraX · ML Kit Barcode Scanning ·
AndroidX Biometric · Android Keystore · Play Integrity · AGP 9.5 (built-in Kotlin) · Gradle 9.6 · JUnit 4 · Truth ·
MockK · Turbine · Robolectric 4.17 · MockWebServer · Kover.

minSdk 26, compileSdk/targetSdk 37, JDK 17 toolchain.

## Architecture

MVVM + Clean Architecture (`ui` / `domain` / `data`). Every ViewModel exposes one `StateFlow<UiState>` with
loading / empty / error / content states; dispatchers and `java.time.Clock` are injected; money is `Long` paise.

```mermaid
flowchart TB
    subgraph ui["ui (Compose, one package per screen)"]
        nav["PayFlowNavHost"]
        home["HomeRoute → HomeViewModel"]
        history["HistoryRoute → HistoryViewModel"]
        detail["PaymentDetailRoute → PaymentDetailViewModel"]
        pay["PayRoute → PayViewModel<br/>(ActivityResult → UpiAppLauncher)"]
        scan["ScanRoute → ScanViewModel<br/>(camera permission)"]
        collect["CollectRoute → CollectViewModel"]
        mandates["Mandates / CreateMandate / MandateDetail routes → ViewModels"]
        lock["AppRoot → AppLockViewModel → LockScreen (FLAG_SECURE)"]
        security["SecurityRoute → SecurityViewModel"]
        checkout["CheckoutRoute → CheckoutViewModel<br/>+ CheckoutKit rememberCheckoutSheet"]
    end
    subgraph domain["domain"]
        model["Payment · PaymentStatus · PaymentEvent<br/>PaymentStateMachine (pure)"]
        repo["PaymentRepository (interface)"]
        usecases["PayViaUpiIntentUseCase (idempotent) · ConfirmPaymentUseCase · VerifyVpaUseCase<br/>PollPaymentStatusUseCase (single-flight) · ReconcilePendingPaymentsUseCase<br/>RefreshCollectRequestsUseCase · RespondToCollectUseCase<br/>Create/Update/Execute/ExpireMandateUseCase<br/>UpiPayRequest · VpaValidator · MandateRules · BackoffPolicy · RetryExecutor"]
        mrepo["MandateRepository (interface)"]
        crepo["CollectRepository (interface)"]
    end
    subgraph data["data"]
        repoImpl["PaymentRepositoryImpl<br/>(compare-and-set transitions)"]
        room["Room: PayFlowDatabase (v3) · PaymentDao · CollectDao · MandateDao"]
        crepoImpl["CollectRepositoryImpl"]
        psp["PspClient → FakePsp (sandbox) · HttpPspClient (OkHttp)"]
        ckgw["PayFlowPaymentGateway (CheckoutKit PaymentGateway → PspClient)"]
        upi["UpiLinkBuilder · UpiResponseParser · AndroidUpiAppLauncher"]
        qr["UpiQrPayloadParser · CameraController → CameraXController<br/>BarcodeScanner → MlKitBarcodeScanner"]
        sec["CryptoBox → KeystoreCryptoBox (AES-GCM) · EncryptedSecureStore<br/>PinManager (PBKDF2 + lockout) · AppLock · BiometricAuthenticator<br/>DeviceBindingManager · TamperDetector · IntegrityGate → PlayIntegrityChecker<br/>PaymentGuard"]
    end
    lock --> sec
    security --> sec
    pay --> sec
    checkout --> ckgw --> psp
    checkout --> usecases
    nav --> home & history & detail & pay & scan & collect & mandates & security & checkout
    mandates --> usecases
    usecases --> mrepo -. implemented by .-> room
    collect --> usecases
    usecases --> crepo
    crepo -. implemented by .-> crepoImpl --> room
    scan --> qr
    scan -. prefilled PayDestination .-> pay
    home & history & detail --> repo
    pay --> usecases --> repo
    usecases --> psp
    usecases --> upi
    repo -. implemented by .-> repoImpl
    repoImpl --> room
    repoImpl --> model
    repoImpl --> psp
```

### Payment state machine

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PENDING: Initiate (handed to PSP / UPI app)
    CREATED --> FAILED: Fail
    PENDING --> SUCCESS: Confirm (PSP status check)
    PENDING --> FAILED: Fail
    SUCCESS --> REFUND_PENDING: RequestRefund
    REFUND_PENDING --> REFUNDED: RefundConfirmed
    REFUND_PENDING --> SUCCESS: RefundFailed
    FAILED --> [*]
    REFUNDED --> [*]
```

Every other (state, event) pair is rejected and leaves the payment untouched. Transitions are applied with a
SQL compare-and-set (`UPDATE … WHERE id = ? AND status = ?`), so racing writers cannot double-confirm a payment.

## How to run

```bash
git clone https://github.com/Darshan-05032003/PayFlow.git && cd PayFlow
./gradlew assembleDebug            # debug APK
./gradlew installDebug             # needs a device or emulator
```

`local.properties` (gitignored) keys:

| Key | Required | Purpose |
|---|---|---|
| `sdk.dir` | yes | Android SDK path |
| `PLAY_INTEGRITY_CLOUD_PROJECT` | no (default `0`) | Google Cloud project **number** for the Play Integrity request. An identifier, not a secret. The token is requested for real through `StandardIntegrityManager`; **verifying** it requires a server calling Google's `decodeIntegrityToken`, which this sandbox replaces with `FakeIntegrityVerifier` (labelled in the UI). |
| `EXPECTED_SIGNING_SHA256` | no | SHA-256 of the signing certificate; when set, the tamper detector flags a repackaged APK (`SIGNATURE_MISMATCH` blocks payments). |
| `PSP_BASE_URL` | no | Base URL of a PSP REST sandbox for `HttpPspClient`. When empty the app uses the in-memory `FakePsp`. |

### CheckoutKit integration (feature 8)

PayFlow consumes [CheckoutKit](https://github.com/Darshan-05032003/CheckoutKit) from JitPack:

```kotlin
// settings.gradle.kts: maven("https://jitpack.io")
implementation("com.github.Darshan-05032003.CheckoutKit:checkoutkit-ui:v1.0.0")
```

`PayFlowPaymentGateway` implements the SDK's `PaymentGateway` (charge / status / refund) on top of PayFlow's sandbox `PspClient`,
so a merchant checkout paid through the SDK's sheet lands in the same history as every other payment. The "Checkout" screen is a
sandbox merchant demo (three items); `rememberCheckoutSheet` hosts the sheet and `RecordCheckoutResultUseCase` stores the final
`CheckoutResult` as a `CHECKOUT` payment: success → SUCCESS, pending → PENDING, failed/rejected → FAILED with the typed error code,
unresolved → PENDING under the PSP order reference (settled later by the reconciler), cancelled → nothing. Robolectric integration
tests drive the real sheet through all six outcomes. API issues found while integrating are listed in
[`docs/DECISIONS.md`](docs/DECISIONS.md) under "CheckoutKit API feedback".

### Real UPI apps

The UPI intent flow (feature 2) builds a real `upi://pay` link and opens the installed UPI apps (GPay, PhonePe,
Paytm, …) through the system chooser. On a device this starts a **real payment**. Test with tiny amounts (₹1) to
**your own VPA**. The app only marks a payment successful after the PSP status check confirms it; in this
sandbox build that confirmation comes from `FakePsp`, so the in-app result will not reflect what the UPI app
actually did with your money.

## Tests

```bash
./gradlew testDebugUnitTest        # unit + integration (Robolectric), stress excluded
./gradlew stressTest               # load / stress simulations (prints STRESS [...] lines)
./gradlew koverLogDebug koverVerifyDebug   # line coverage of domain + data (rule: ≥ 80 %)
./gradlew assembleDebug lintDebug  # the rest of the pre-commit gate
```

| Suite | Count |
|---|---|
| Unit (state machines, rules, money, parsers, validators, backoff, crypto, PIN, binding, tamper, integrity, gateway adapter, use cases, ViewModels, Compose screens) | 266 |
| Integration (Room + DAO + repository + ViewModel, Compose flows, Hilt activity, `HttpPspClient` vs MockWebServer, the real CheckoutKit sheet) | 51 |
| Stress (`stress` package, `./gradlew stressTest` only) | 20 |

Domain + data line coverage (Kover): **88.0 %** (device-only classes such as `KeystoreCryptoBox`, `AndroidBiometricAuthenticator`,
`PlayIntegrityChecker`, `AndroidTamperProbe`, `CameraXController` and `MlKitBarcodeScanner` are counted and untested on the JVM). (the device-only `CameraXController` / `MlKitBarcodeScanner` are counted and
untested on the JVM).

No device or emulator was available during the build: camera, real UPI apps and `connectedAndroidTest` are
compile-only and marked "not run: no device". Everything else is proven on the JVM with Robolectric and fakes.

## Security highlights

Full review per feature: [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md).

- Payment transitions are a pure, exhaustively tested state machine applied with a SQL compare-and-set: 50 concurrent
  confirmations of one payment succeed exactly once.
- Amounts are `Long` paise bounded to 1 paisa … ₹1,00,000; history search parameters are bound and stripped of
  control / invisible characters (a leading NUL or BOM used to collapse the SQLite `LIKE` filter to match-all).
- **The UPI app's answer is never trusted.** After the app returns, the PSP is asked; only a PSP success whose `txnRef`,
  amount and payee match the stored payment marks it SUCCESS. 10,000 spoofed `Status=SUCCESS` responses never produced one.
- A PSP outage after the app was opened is "unresolved" (payment stays pending; "do not pay again"), never "failed".
- Hostile link inputs are rejected before a link is built (homoglyph/bidi VPAs, `javascript:`, amounts out of range, 35-char
  `tr`), and the `response` extra from the UPI app is bounded and parsed defensively.
- QR payloads are parsed by a bounded, allocation-light parser: 10,000 fuzzed payloads (homoglyph VPAs, `javascript:`, bidi
  overrides, 100 KB inputs, `url=` extras) never threw and never produced a payable payload from hostile input; a scanned
  payee is locked on the pay screen so it cannot be swapped by a stray tap.
- UPI IDs are checked for format offline, then verified with the PSP directory; the registered payee name replaces whatever a
  QR or the user typed and cannot be edited. Unknown IDs cannot be paid; a PSP outage shows a warning instead of a fake "verified".
- Collect requests are answered exactly once (per-request mutex + Room compare-and-set + PSP 409/410 mapping): 200 racing
  approvals produced one payment; expired requests cannot be approved; stale server rows never revert a local answer.
- AutoPay debits are checked against pure rules (max amount, one debit per frequency period computed in the device zone, validity
  window, status) and the period is reserved with a compare-and-set *before* the PSP is called, so racing debits cannot both go
  through. A pause/revoke race that left the device and the PSP disagreeing was found by the stress test and fixed with a
  per-mandate mutex.
- **Duplicate payments are impossible**: an idempotency key is written on the payment row (unique index) *before* the PSP is
  called; retries, double taps and relaunches after process death return the stored payment (1,000 concurrent submits → 1 row,
  1 PSP call), a key reused with different details is a conflict, and a CREATED row left behind by a crash is completed once.
- Pending payments are polled with exponential backoff and full jitter (10,000 simultaneous retries spread from one 10 ms
  window to a peak of 45), `Retry-After` is honoured and capped, non-retryable errors are never retried, and a single-flight
  poller means racing callers never multiply PSP calls. A payment that never settles stays pending, never "failed".
- **Local secrets are AES-256-GCM encrypted with an Android Keystore key** (`CryptoBox`); the PIN record and the device binding
  never touch disk in clear, every ciphertext is bound to its key name (AAD), and any flipped bit is detected.
- **App lock**: BiometricPrompt (strong/weak biometrics with device-credential fallback) or a 6-digit PIN hashed with
  PBKDF2-HmacSHA256 (150k iterations, random salt, constant-time compare); 5 wrong attempts lock for 30 s, doubling to an hour,
  checked before any hashing, immune to clock rollback and process death. Relocks after 60 s in the background.
- **Simulated device binding**: SHA-256 device fingerprint + phone number verified by a 6-digit OTP (3 attempts, 5-minute TTL);
  the binding breaks if the data moves to another device. OTP delivery is simulated in-app (no SMS gateway).
- **Root / tamper detection** (su, Magisk, test-keys, Frida ports and libraries, debugger, signing-certificate mismatch) and a real
  **Play Integrity** token request gate every payment: root, hooking or a repackaged build block it, warnings are shown otherwise.
- **`FLAG_SECURE`** on every payment screen and the lock screen, `filterTouchesWhenObscured` app-wide (tapjacking).
- CheckoutKit payments reuse the same PSP, the same idempotency (SDK key = PSP order reference) and the same reconciliation as every
  other payment; an `Unresolved` sheet outcome is stored pending under the PSP order reference, never dropped or marked failed.
- The HTTP client maps every failure (401, 429 + `Retry-After`, 5xx, timeouts, connection drops, malformed JSON) to typed
  exceptions; a body-read timeout that escaped as a raw exception was found by the review and fixed.
- `allowBackup=false`, data extraction rules exclude everything, no cleartext traffic, only the launcher activity is exported.

## Load-test highlights

Full report: [`docs/LOAD_TEST_REPORT.md`](docs/LOAD_TEST_REPORT.md).

- **100,000 transactions in Room**: insert 550 ms; status filter 93 ms; text search 55 ms; SQL summary 26 ms; a 50-row page
  26 ms; the full un-paged list 1.8 s (recommendation: page the History screen for very large histories).
- **1,000 concurrent payments** through the state machine with compare-and-set writes: 1.07 s, zero lost updates.
- **1,000 concurrent UPI intent payments** against a PSP with 20 % failures, 10 % timeouts and 5 % 5xx: 418 ms, 1,916 status
  calls, zero disagreements between Room and the PSP. **10,000 spoofed app responses**: 657 ms, payment still failed.
- **10,000 VPA verifications** under 20 % 429 / 10 % 5xx: 58 ms; malformed IDs never reach the PSP.
  **1,000 collect requests answered concurrently** (5 % PSP timeouts): ~1 s, zero duplicate or orphan payments; approvals whose
  status check timed out stay pending (never "failed").
- **10,000 AutoPay debit attempts** against 100 mandates: 658 ms, 1,429 over-cap rejections, exactly one debit per period.
  **1,000 racing pause/resume/revoke actions**: 131 ms, local and PSP state identical (they diverged before the fix).
- **Same idempotency key 1,000× concurrently**: 76 ms, exactly 1 payment and 1 PSP call. **Polling storm** (3,000 racing pollers
  over 734 payments against a PSP with timeouts/5xx/429): 246 ms, 3,105 status calls (single-flight), 0 stuck payments.
  **Backoff jitter**: peak retries per 10 ms window 10,000 → 45.
- **PIN brute force**: 1,000 guesses in 332 ms total, 997 refused by the lockout before any hashing (one PBKDF2 verify ≈ 97 ms).
  **AES-GCM**: 10,000 × 1 KB round trips in 120 ms.
- **1,000 concurrent CheckoutKit sessions** through the gateway adapter against a flaky PSP: 915 ms, 1,000 history rows, 1,000
  distinct orders despite 1,225 PSP initiate calls (SDK retries deduped by key), every success matched to a captured order.
- **QR parser**: 10,000 fuzzed payloads in 75 ms; 1,000 × 100 KB payloads rejected in 1 ms; 1,000 concurrent parses in 39 ms.

## Project docs

- [`docs/PLAN.md`](docs/PLAN.md): build plan, package structure, test and stress plan
- [`docs/DECISIONS.md`](docs/DECISIONS.md): design decisions and compromises
- [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md): per-feature attacks, findings and fixes
- [`docs/LOAD_TEST_REPORT.md`](docs/LOAD_TEST_REPORT.md): per-feature load scenarios and numbers

## License

Portfolio project by Darshan Sonawane. All rights reserved unless a license file says otherwise.
