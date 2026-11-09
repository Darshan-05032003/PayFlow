# PayFlow

**A UPI payments app for Android, built the way a payments team would build it: a payment state machine that
cannot be pushed into an illegal state, idempotent submits, a hardened client, and an honest line between what
the app knows and what it is told.**
Pay through the UPI apps installed on the phone (GPay, PhonePe, Paytm, BHIM) or against a realistic
**sandbox PSP**; scan or receive UPI QRs, validate UPI IDs, handle collect requests and AutoPay mandates.

> **Two modes.** *UPI apps* (default on a real phone): PayFlow hands `upi://pay` to the UPI app you pick and
> records what that app reports back. Real money moves; PayFlow has no bank or PSP behind it, so it never
> calls such a result "verified". *Sandbox*: every payment is settled by an in-app `FakePsp` behind the
> `PspClient` interface and no money moves. See [Real UPI apps vs sandbox](#real-upi-apps-vs-sandbox).

<p align="center">
  <img src="docs/screenshots/home-dark.png" width="200" alt="Home, dark">
  <img src="docs/screenshots/pay-swipe-dark.png" width="200" alt="Swipe to pay, dark">
  <img src="docs/screenshots/pay-success-light.png" width="200" alt="Payment success with printed receipt, light">
  <img src="docs/screenshots/payment-sheet-dark.png" width="200" alt="Payment sheet with the demo scratch card, dark">
</p>

**Aurum Noir**, PayFlow's own design system: layered graphite, one champagne accent, serif numerals, tactile press physics
and motion that answers every touch. Every drawing and effect is made in code.

### In motion

Recorded on the emulator (software rendering, so motion is slower than on a phone) with sandbox data and invented names.

<p align="center">
  <img src="docs/media/unlock.webp" width="240" alt="Cold start: splash, lock screen, a wrong PIN, then the correct PIN and the reveal into Home">
  <img src="docs/media/swipe-to-pay.webp" width="240" alt="Swipe to pay a sandbox payee, confirmation with the provider, checkmark burst and the printed receipt">
</p>

The rows marked "to record" are still pending.

| Flow | What to watch | File |
|---|---|---|
| Swipe to pay | Swipe a sandbox payment; the provider confirms it; checkmark draws, coin burst, receipt prints | [`docs/media/swipe-to-pay.webp`](docs/media/swipe-to-pay.webp) |
| Scan to pay | Scan & Pay's breathing ring opens into the scanner; the payee card moves into Pay | `docs/media/scan-to-pay.webp` (to record) |
| Unlock | Splash, then PIN dots fill on springs; a wrong PIN shakes and pulses; the app opens with a circular reveal | [`docs/media/unlock.webp`](docs/media/unlock.webp) |
| Home | Header docks into the top bar on scroll; hide amounts blurs and scrambles; pull to refresh flips the coin | `docs/media/home.webp` (to record) |
| History | Search grows out of its button; swipe tray; a row grows into the payment sheet; the demo scratch card | `docs/media/history.webp` (to record) |
| Collect request | Heads-up entrance, countdown ring, approve by swipe | `docs/media/collect.webp` (to record) |
| AutoPay | Card stack fling; pause and resume morph | `docs/media/autopay.webp` (to record) |
| Security centre | Five checks tick in sequence | `docs/media/security.webp` (to record) |
| Reduce motion | The same payment with essential motion only | `docs/media/reduce-motion.webp` (to record) |

## Features

| # | Feature | Status |
|---|---|---|
| 1 | Home, transaction history (Room), payment state model (created → pending → success / failed → refund) | done |
| 2 | UPI intent flow: `upi://pay` deep links, app chooser, response parsing, **PSP status confirmation** before success | done |
| 3 | QR scan-to-pay with CameraX + ML Kit, **or from a gallery image** (Photo Picker, no storage permission; ML Kit + zxing fallback, downsampled), safe payload validation, camera permission handling | done |
| 4 | VPA validation (format + simulated verify returning the payee name) and collect requests (approve / decline / expire) | done |
| 5 | AutoPay mandates: create / view / pause / resume / revoke with frequency and max-amount rules | done |
| 6 | Status polling with exponential backoff + jitter and idempotency keys stored in Room before the PSP call | done |
| 7 | Security: Keystore AES-GCM, biometric/PIN lock (PBKDF2 + lockout), simulated device binding, root/tamper detection, Play Integrity, `FLAG_SECURE` | done (Keystore / BiometricPrompt / Play Integrity request are device-only) |
| 8 | CheckoutKit integration: the [CheckoutKit](https://github.com/Darshan-05032003/CheckoutKit) payment sheet from JitPack for merchant checkout | done |
| 9 | **UPI-apps mode**: pay through installed UPI apps (own app sheet, pinned intent, app-reported result, manual settlement), incoming `upi://` links, Receive-money QR, sandbox/UPI-apps switch | done |
| 10 | **UI/UX overhaul**: Aurum Noir design system and debug catalog, animated shell, Home, Scan, Pay with swipe-to-pay and outcomes, History, Collect, AutoPay, demo scratch card, Security centre and Me settings (haptics, sound, reduce motion, theme) | done (device recordings and frame timings pending) |

## Design system and motion

Full guide: [`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md). Direction and reasoning: [`docs/DESIGN_DIRECTION.md`](docs/DESIGN_DIRECTION.md).

- **Tokens only.** Screens read colour, type, spacing, radii, sizes and motion from the design system. No raw hex, `dp`, `sp`,
  duration or easing appears in screen code; `scripts/token_audit.py` and `TokenAuditTest` check it on every test run.
- **Colour.** Warm paper or near-black pages, a graphite hero in both themes, champagne as the single accent. Every text pair
  meets WCAG AA in both themes (enforced by a test).
- **Type.** Newsreader for display and money (tabular serif numerals), Geist for everything else. Tab roots name themselves in
  the serif; pushed screens and sheets use the sans title.
- **Depth.** Three tone steps and a 1dp hairline. No drop shadows. Film grain on hero surfaces only.
- **Motion.** One vocabulary of tokens: springs for anything touched or dragged (Snappy, Bouncy, Gentle), timed motion in three
  bands (micro 90 to 150 ms, small 200 to 300 ms, screen 350 to 500 ms) on emphasized curves, 40 ms stagger. No linear easing.
  Animated values are read in the draw or layer phase, so a moving frame recomposes nothing.
- **Delight is earned.** The coin burst, sheen and scratch-card glints play only for a provider-confirmed payment.
- **Haptics.** Ticks for detents, a light tap per press, confirm and reject for outcomes, through the view's haptic channel (no
  permission). A setting turns them off.
- **Effects in code.** Metallic sheen and the scan beam are AGSL shaders on API 33+ with gradient fallbacks; checkmarks, crosses,
  receipts, illustrations and the success chime are drawn or synthesised in code.

![Design catalog, light and dark](docs/screenshots/design-catalog.png)

## Screenshots

Before: the app at the start of the UI overhaul, on the Pixel 7 emulator. After: Roborazzi renders of the same screens from the
screenshot tests (JVM, 411 × 891 dp). Sample names are invented.

| | Home | Pay | Result | History |
|---|---|---|---|---|
| **Before** | ![Home before](docs/screenshots/before/home.png) | ![Pay before](docs/screenshots/before/pay.png) | ![Result before](docs/screenshots/before/result-reported.png) | ![History before](docs/screenshots/before/history.png) |
| **After** | ![Home after](docs/screenshots/home-light.png) | ![Pay after](docs/screenshots/pay-swipe-dark.png) | ![Result after](docs/screenshots/result-reported.png) | ![History after](docs/screenshots/history-light.png) |

| | Choose a UPI app | Pending: mark by hand | Scan | Security |
|---|---|---|---|---|
| **Before** | ![App sheet before](docs/screenshots/before/upi-app-sheet.png) | ![Pending before](docs/screenshots/before/pending-manual-mark.png) | ![Scan before](docs/screenshots/before/scan.png) | ![Security before](docs/screenshots/before/security-mode.png) |
| **After** | ![App sheet after](docs/screenshots/upi-app-sheet.png) | ![Pending after](docs/screenshots/pending-manual-mark.png) | ![Scan after](docs/screenshots/scan-found-dark.png) | ![Security after](docs/screenshots/security-light.png) |

More after screens:

| Collect request | AutoPay | Me | Receive | Lock |
|---|---|---|---|---|
| ![Collect](docs/screenshots/collect-dark.png) | ![AutoPay](docs/screenshots/autopay-light.png) | ![Me](docs/screenshots/me-dark.png) | ![Receive](docs/screenshots/receive-light.png) | ![Lock](docs/screenshots/lock-dark.png) |

The remaining "before" emulator shots (gallery QR, incoming link, the debug Sandbox UPI app) are in
[`docs/screenshots/before/`](docs/screenshots/before/). All 202 current renders are in `app/src/test/screenshots/`.

## Tech stack

Kotlin 2.4 · Jetpack Compose (BOM 2026.09) + Material 3 · Navigation Compose (type-safe routes) · Hilt (KSP) · Room 2.8 ·
DataStore · kotlinx.coroutines / Flow · kotlinx.serialization · OkHttp 5 · CameraX · ML Kit Barcode Scanning ·
AndroidX Biometric · Android Keystore · Play Integrity · AGP 9.5 (built-in Kotlin) · Gradle 9.6 · JUnit 4 · Truth ·
MockK · Turbine · Robolectric 4.17 · MockWebServer · Kover · Roborazzi (screenshot tests) · Macrobenchmark + Baseline Profile ·
LeakCanary (debug only) · Splash Screen API · AGSL shaders · Newsreader + Geist (OFL, bundled).

minSdk 26, compileSdk/targetSdk 37, JDK 17 toolchain.

## Architecture

MVVM + Clean Architecture (`ui` / `domain` / `data`). Every ViewModel exposes one `StateFlow<UiState>` with
loading / empty / error / content states; dispatchers and `java.time.Clock` are injected; money is `Long` paise.
The UI is built only from `ui/designsystem` (tokens, components, motion, haptics). Animation and gesture rules live in plain,
JVM-tested state holders (`SwipeToPayHolder`, `CardStackHolder`, `OdometerLogic`, `LockMotionLogic`, `CheckReveal`, …); one-off
effects such as haptics go through a separate effect channel.

```mermaid
flowchart TB
    subgraph ui["ui (Compose, one package per screen)"]
        nav["PayFlowNavHost"]
        home["HomeRoute → HomeViewModel"]
        history["HistoryRoute → HistoryViewModel"]
        detail["PaymentDetailRoute → PaymentDetailViewModel"]
        pay["PayRoute → PayViewModel<br/>(own UPI-app sheet, ActivityResult → UpiAppLauncher)"]
        receive["ReceiveRoute → ReceiveViewModel<br/>(zxing QR of the encrypted UPI profile)"]
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
        usecases["PayViaUpiIntentUseCase (idempotent, mode-aware) · ConfirmPaymentUseCase<br/>RecordUpiAppResultUseCase · MarkPaymentManuallyUseCase · VerifyVpaUseCase<br/>PollPaymentStatusUseCase (single-flight) · ReconcilePendingPaymentsUseCase<br/>RefreshCollectRequestsUseCase · RespondToCollectUseCase<br/>Create/Update/Execute/ExpireMandateUseCase<br/>UpiPayRequest · VpaValidator · MandateRules · BackoffPolicy · RetryExecutor"]
        mrepo["MandateRepository (interface)"]
        crepo["CollectRepository (interface)"]
    end
    subgraph data["data"]
        repoImpl["PaymentRepositoryImpl<br/>(compare-and-set transitions)"]
        room["Room: PayFlowDatabase (v4) · PaymentDao · CollectDao · MandateDao"]
        settings["PaymentModeRepository (DataStore) · UpiProfileRepository (SecureStore)<br/>IncomingUpiLinkStore"]
        crepoImpl["CollectRepositoryImpl"]
        psp["PspClient → FakePsp (sandbox) · HttpPspClient (OkHttp)"]
        ckgw["PayFlowPaymentGateway (CheckoutKit PaymentGateway → PspClient)"]
        upi["UpiLinkBuilder · UpiResponseParser · AndroidUpiAppLauncher (installed apps, pinned intents)<br/>ReceiveQrBuilder · QrCodeGenerator"]
        qr["UpiQrPayloadParser · CameraController → CameraXController<br/>BarcodeScanner → MlKitBarcodeScanner"]
        sec["CryptoBox → KeystoreCryptoBox (AES-GCM) · EncryptedSecureStore<br/>PinManager (PBKDF2 + lockout) · AppLock · BiometricAuthenticator<br/>DeviceBindingManager · TamperDetector · IntegrityGate → PlayIntegrityChecker<br/>PaymentGuard"]
    end
    lock --> sec
    security --> sec
    pay --> sec
    checkout --> ckgw --> psp
    checkout --> usecases
    nav --> home & history & detail & pay & scan & collect & mandates & security & checkout & receive
    receive --> settings
    pay --> settings
    security --> settings
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
    PENDING --> SUCCESS: Confirm (PSP check · UPI app report · user mark; source is persisted)
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

### Real UPI apps vs sandbox

An individual developer cannot get a bank or PSP licence, so PayFlow offers two clearly labelled modes
(Me → Security centre → Payment mode; the default is *UPI apps* when at least one UPI app is installed, *Sandbox* otherwise, e.g. on an emulator):

| | UPI apps mode | Sandbox mode |
|---|---|---|
| Who moves the money | The UPI app you pick (GPay, PhonePe, Paytm, BHIM, …) and your bank. **Real money.** | Nobody. `FakePsp` in memory. |
| How a payment starts | `Pay ₹X` → PayFlow's own sheet lists the installed UPI apps (icons from `PackageManager`) → `upi://pay?pa&pn&am&cu=INR&tn&tr` is launched **pinned to that app**; the system chooser is the fallback | `upi://pay` through the system chooser after the order is registered with the sandbox PSP |
| How the result is decided | The app's `response` extra (`txnId`, `responseCode`, `Status`, `txnRef`, `ApprovalRefNo`) is parsed defensively and matched against **our** `txnRef`. SUCCESS / FAILURE are stored with `statusSource = UPI_APP`; another reference → refused; no answer (back press, app killed, SUBMITTED) → **PENDING** with "Check in your UPI app" | PSP status check; only a PSP success matching `txnRef`, amount and payee becomes SUCCESS |
| What the UI says | **"Reported by GPay. Not verified by PayFlow."** or **"Marked by you"**, on the result screen, the detail screen and the shared receipt. Never "verified". | "Confirmed by the sandbox payment provider" |
| Pending payments | The user checks the UPI app and marks the payment paid / not paid (`statusSource = USER`) | Polled with exponential backoff; reconciled on Home start / History refresh |
| Payee verification | Skipped: the UPI app shows the bank-registered name before you confirm | Sandbox directory lookup; registered name wins |
| Collect, AutoPay, Checkout | Always sandbox (no PSP exists for them) | Sandbox |
| Badge | none | amber **Sandbox** pill in every top bar |

What stays the same in both modes: one idempotency key per attempt written on the payment row *before* anything is launched (a
double tap, retry or process death can never create a second payment), the `txnRef` on the link, the payment state machine with
its SQL compare-and-set, and the device checks (root / hooking / tampered build block a payment).

**The honest limitation.** In UPI-apps mode PayFlow has nothing independent to confirm against. A UPI app's answer, or the
user's own mark, *is* the stored outcome. The mitigations are structural (pinned package, `txnRef` match, idempotency, PENDING by
default, status source persisted and shown) rather than cryptographic; a real deployment replaces manual marking with a PSP
status API. Test with ₹1 to your own VPA.

### Testing without a real UPI app

Debug builds ship a fake UPI app, **Sandbox UPI (debug)** (`app/src/debug/.../SandboxUpiActivity`), so the whole UPI-apps path can run
on an emulator: it appears in PayFlow's app sheet and in Android's "Open with" dialog, shows the payee, amount and `tr` it received,
and answers with the standard UPI `response` extra for **Success**, **Failure**, **Pending (SUBMITTED)**, **Cancel** (no response) or
**Tamper** (success for a different `txnRef`, which PayFlow refuses). It is declared only in the debug manifest; the release merged
manifest has no such component. Test incoming links with remote-shell quoting so the device shell does not split the URI at `&`:
`adb shell "am start -a android.intent.action.VIEW -d 'upi://pay?pa=test@upi&pn=Test&am=1.00&cu=INR'"`.

PayFlow also registers as a handler for `upi://pay` links (`VIEW` + `BROWSABLE`): a UPI link from a browser, chat app or another QR
reader opens the Pay screen prefilled and locked, after the same hostile-input-safe parser that checks scanned QRs. The Receive
screen stores your own name + UPI ID encrypted (Keystore AES-GCM) and renders a `upi://pay?pa=&pn=[&am=]` QR (zxing) that any UPI
app can scan; copy the ID or share the link / PNG. PayFlow cannot see incoming money and says so.

## Tests

```bash
./gradlew testDebugUnitTest        # unit + integration (Robolectric); screenshot tests run without comparing images; stress excluded
./gradlew verifyRoborazziDebug     # the same run, comparing every screenshot with its baseline
./gradlew recordRoborazziDebug --tests '*HomeScreenshotTest*'   # re-record one class after an intended change
./gradlew stressTest               # load / stress simulations (prints STRESS [...] lines)
./gradlew koverLogDebug koverVerifyDebug   # line coverage of domain + data (rule: ≥ 80 %)
./gradlew assembleDebug assembleRelease lintDebug   # the rest of the pre-commit gate
scripts/token_audit.py             # raw colours, dp/sp, durations or easing in screen code
```

| Suite | Count |
|---|---|
| Unit (state machines, rules, money, parsers, validators, backoff, crypto, PIN, binding, tamper, integrity, gateway adapter, QR image decoding, use cases, ViewModels, motion and gesture state holders, design-system components, Compose screens at 1.0× and 1.3× with reduced motion on and off) | 1,035 |
| Integration (Room + DAO + repository + ViewModel, Compose flows, Hilt activity incl. an incoming `upi://` link, `HttpPspClient` vs MockWebServer, the real CheckoutKit sheet, screenshot suites) | 216 |
| Screenshot baselines (Roborazzi: every design-system component and every screen state, light and dark, 1.3× text, mid-animation frames) | 202 images |
| Stress (`stress` package, `./gradlew stressTest` only) | 67 |

Before the UI overhaul the suites were 350 unit, 57 integration and 24 stress tests.

Domain + data line coverage (Kover): **90.7 %** (device-only classes such as `KeystoreCryptoBox`, `AndroidBiometricAuthenticator`,
`PlayIntegrityChecker`, `AndroidTamperProbe`, `CameraXController` and `MlKitBarcodeScanner` are counted and untested on the JVM).

Everything is proven on the JVM with Robolectric and fakes (installed UPI apps are faked through the `UpiAppLauncher` interface). On
top of that, every flow was walked on a Pixel 7 emulator (API 37) in light and dark: the UPI-app sheet → Sandbox UPI → success,
failure, pending + manual marking, cancel and a tampered `txnRef`; sandbox mode; incoming `upi://pay` links (cold and warm start);
Receive QR (the on-screen QR was decoded back to the exact link); camera scan screen and gallery QR (valid, non-UPI); History refresh.
The walkthrough found and fixed four bugs that unit tests had missed (see SECURITY_REVIEW). `connectedAndroidTest` is not used.

## Performance

**Emulator numbers, not a mid-range phone.** The Pixel 7 AVD runs with software rendering on a busy 15 GB laptop, so these
numbers only compare before and after on the same setup. Method and raw tables: [`docs/LOAD_TEST_REPORT.md`](docs/LOAD_TEST_REPORT.md).

| Metric (Macrobenchmark, `NoCompilation`) | Before (`9c48cc2`, 4 GB guest) | After |
|---|---:|---:|
| Cold start, time to initial display, median | 876 ms | pending |
| Home scroll, frame CPU time P90 | 95.6 ms | pending |
| Home scroll, jank (frames over deadline) | 99.3 % | pending |
| History scroll, frame CPU time P90 | 52.9 ms | pending |
| History scroll, jank | 97.3 % | pending |
| Release APK | 25,265,073 bytes | 25,819,547 bytes (+2.2 %) |

The "after" column is still pending. Macrobenchmark with Perfetto tracing pushed the emulator past the memory cap that
protects the rest of this laptop, so the after numbers will come from a lighter method (`scripts/light_bench.sh`:
`am start -W` cold starts and `dumpsys gfxinfo` frame statistics, run on `9c48cc2` and on the redesign in one session).
Those numbers will sit in their own table, because they are not comparable to the Macrobenchmark column above. The Home
scroll now measures a different, richer page, so its comparison is labelled as such. Measured on the JVM so far: a
600-step Home scroll in 6 recompositions, 300 History flings over 10,000 rows with no recomposition, and the release
Compose compiler metrics in LOAD_TEST_REPORT ("Compose stability").

## Accessibility

- **TalkBack.** Every control has a label; each payment row reads as one sentence: direction, payee, amount and status. Swipe-to-pay, the
  scratch card, the AutoPay stack and the history swipe tray all have TalkBack actions, and anything behind an open sheet is hidden
  from TalkBack.
- **No gesture is required.** Swipe-to-pay has "Pay with a button instead" and a confirmation sheet; collect requests can be paid
  through a review sheet; history swipe actions are also row actions; the AutoPay stack has Next and Previous.
- **Text up to 1.3×.** Compose tests run the screens at 1.3× and check their parts stay inside their bounds; the 1.3× screenshot
  baselines were reviewed for clipping. Long names ellipsize by design.
- **Reduce motion.** An in-app setting, also on when the system animator scale is 0. Decorative motion (ambient rings, sheen,
  burst, glints) stops; essential transitions become 150 ms fades. No step depends on an animation finishing, and a collect request's
  slider becomes a review button, because the slider's feedback is all motion.
- **Touch targets** are at least 48dp (a token, checked by a test). **Contrast** meets WCAG AA for every text pair in both themes.
- **Haptics and sound** can be turned off; sound is off by default.

| Home at 1.3× text | Collect requests with reduced motion |
|---|---|
| ![Home at 1.3x](docs/screenshots/home-1_3x-light.png) | ![Collect, reduced motion](docs/screenshots/collect-reduced-motion-dark.png) |

## Security highlights

Full review per feature: [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md).

- Payment transitions are a pure, exhaustively tested state machine applied with a SQL compare-and-set: 50 concurrent
  confirmations of one payment succeed exactly once.
- Amounts are `Long` paise bounded to 1 paisa … ₹1,00,000; history search parameters are bound and stripped of
  control / invisible characters (a leading NUL or BOM used to collapse the SQLite `LIKE` filter to match-all).
- **The UPI app's answer is never trusted as verification.** Sandbox: after the app returns, the PSP is asked; only a PSP success
  whose `txnRef`, amount and payee match the stored payment marks it SUCCESS (10,000 spoofed `Status=SUCCESS` responses never
  produced one). UPI-apps mode: the answer must name *our* `txnRef` (10,000 responses for other references never settled the
  payment), it is stored as `UPI_APP`-reported, the launch intent is pinned to the app the user picked, and 1,000 racing
  deliveries / manual marks settle a payment exactly once.
- **Incoming `upi://` links** go through the same bounded parser as scanned QRs (10,000 hostile links, 0 exceptions, only
  well-formed `upi://pay` reaches the Pay screen with payee and amount locked); the link is consumed once and only while unlocked.
- **QR from a gallery image**: Photo Picker (no storage permission), image read via the content resolver only (never copied or logged),
  decoded downsampled (≤ 2,048 px) so a 50 MP photo cannot exhaust memory, then the same parser and locked Pay screen as a camera scan.
- **Receive QR**: the profile is AES-GCM encrypted at rest; name and VPA are percent-encoded so nothing can inject a parameter.
- **Payment mode** can only be changed on the Security screen (behind the app lock) and UPI-apps mode needs an installed handler.
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
- **UPI-apps mode**: 10,000 spoofed app responses for other references in 757 ms, payment still pending; 1,000 racing
  settlements (app success / failure / user marks) in 289 ms → exactly 1; 10,000 hostile incoming links in 72 ms, 0 exceptions.
- **UPI-apps mode on a device**: a pending UPI-app payment survives app restarts and History refresh (it was being failed by the
  sandbox reconciler before the fix); PayFlow never offers itself in the system chooser.
- **1,000 concurrent CheckoutKit sessions** through the gateway adapter against a flaky PSP: 915 ms, 1,000 history rows, 1,000
  distinct orders despite 1,225 PSP initiate calls (SDK retries deduped by key), every success matched to a captured order.
- **Gallery QR**: 1,000 generated images (valid, non-UPI, hostile, noise) decoded in 1.35 s (1.35 ms each), heap peak +33 MB.
- **QR parser**: 10,000 fuzzed payloads in 75 ms; 1,000 × 100 KB payloads rejected in 1 ms; 1,000 concurrent parses in 39 ms.
- **UI under load (JVM)**: 3,000 submits from the Pay screen → 1 payment; 1,000 injected swipes → 1; 1,000 collect approvals → 1
  payment; 100,000 history rows grouped into days in 12 ms; a 1,000-guess PIN attack runs 5 PBKDF2 verifies. Compose release metrics:
  unstable composable parameters 26 → 7.

## Project docs

- [`docs/PLAN.md`](docs/PLAN.md): build plan, package structure, test and stress plan
- [`docs/DECISIONS.md`](docs/DECISIONS.md): design decisions and compromises
- [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md): per-feature attacks, findings and fixes
- [`docs/LOAD_TEST_REPORT.md`](docs/LOAD_TEST_REPORT.md): per-feature load scenarios and numbers
- [`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md): tokens, components, motion rules and the catalog
- [`docs/DESIGN_DIRECTION.md`](docs/DESIGN_DIRECTION.md): the two proposed directions and why Aurum Noir
- [`docs/UI_AUDIT.md`](docs/UI_AUDIT.md): the audit the overhaul started from
- [`docs/CREDITS.md`](docs/CREDITS.md): fonts and licences

## Credits

Fonts: Newsreader and Geist under the SIL Open Font License 1.1 (see [`docs/CREDITS.md`](docs/CREDITS.md)). Icons: Material
Icons (Apache 2.0). Every illustration, effect, animation and sound is original and made in code; no Lottie files or third-party
artwork are used.

Motion and craft inspired by best-in-class Indian consumer apps; not affiliated with any of them.

## License

Portfolio project by Darshan Sonawane. All rights reserved unless a license file says otherwise.
