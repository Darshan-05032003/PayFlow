# Decisions

Decisions taken during the build, with the reasoning, so nothing is a surprise later.

## Toolchain (Phase 0)
- **AGP 9.5.0** (latest stable on maven.google.com at scaffold time) with AGP's **built-in Kotlin** support, so the `org.jetbrains.kotlin.android` plugin is not applied.
- **Kotlin 2.4.21**, **KSP 2.3.12**, **Compose BOM 2026.09.00**, **Hilt 2.60.1** (KSP, not kapt).
- **Gradle 9.6.0** wrapper instead of the newest 9.8.2: 9.6.0 was already cached on the build machine and the disk had less than 7 GB free; every new Gradle version adds ~2 GB of per-version caches. 9.6.0 is fully compatible with AGP 9.5.0 (verified by building). Upgrading later is a one-line change in `gradle/wrapper/gradle-wrapper.properties`.
- **JDK toolchain 17** (`jvmToolchain(17)`), built on a JDK 21 host. JitPack is configured for JDK 17.
- **compileSdk / targetSdk 37** (latest stable platform installed), **minSdk 26**.
- Robolectric tests pin `@Config(sdk = [35])` because Robolectric 4.17 doesn't ship an SDK 37 runtime yet.

## Feature 1: model, Room, home/history
- **Payment state model**: `CREATED → PENDING → SUCCESS | FAILED`, `SUCCESS → REFUND_PENDING → REFUNDED | SUCCESS`. `SUCCESS` is
  deliberately not terminal (a refund can start from it); `FAILED` and `REFUNDED` are. `CREATED → SUCCESS` is illegal: a
  payment must have been handed to the PSP/UPI app first, so a success can only ever come from a status confirmation.
- **Compare-and-set in SQL** (`UPDATE … WHERE id = ? AND status = ?`) instead of a Kotlin mutex: it also protects against a
  second process/WorkManager writer, and it is what makes "1,000 concurrent confirmations → exactly one success" hold.
- **Amount bound**: 1 paisa to ₹1,00,000 (`Payment.MAX_AMOUNT_PAISE`), the NPCI per-transaction cap for most categories.
- **Search sanitising**: control and Unicode FORMAT characters are stripped from history searches (see SECURITY_REVIEW).
- **History spinner only on first load/retry**, not on chip changes (no flicker); `reload` counter drives it.
- **Robolectric + ViewModels**: Compose's `waitUntil` does not drain Robolectric's main looper, so `viewModelScope`
  continuations never ran in UI integration tests. Tests use `waitUntilWithMainLooper` / `awaitFlowValue` /
  `runOffMain` (`testutil/RobolectricFlows.kt`) instead of changing production dispatchers.
- **Room 2.8**: `room-runtime` only (`room-ktx` is merged into runtime since 2.7); schemas exported to `app/schemas`.

## Feature 2: UPI intent flow
- **The PSP decides, never the UPI app.** `ConfirmPaymentUseCase` ignores the app's `Status` for the outcome and asks the PSP;
  a `Success` must match `txnRef`, amount and payee VPA or it is a `Mismatch` and the payment stays PENDING. The app response is
  only used to enrich the stored `upiTxnId` / failure reason.
- **Initiate failure = FAILED.** If the PSP rejects/can't register the order, the UPI app is never opened, so no money can have
  moved and FAILED is honest. After the app was opened, a PSP error is `Unresolved` (stay PENDING), never FAILED.
- **`txnRef` format**: `PF` + 9 base-36 time chars + 12 random chars from a 32-symbol alphabet (SecureRandom), 23 chars,
  within the 35-char UPI `tr` limit and URL-safe without encoding.
- **Launcher as an interface.** `UpiAppLauncher` (`isUpiAppInstalled`, `chooserIntent`, `parseActivityResult`) keeps Intents out
  of the ViewModel; the VM emits `PayEvent.LaunchUpiApp(uri)` and the route uses `rememberLauncherForActivityResult`.
  Real apps often return `RESULT_CANCELED` with a FAILURE `response`, so a present `response` wins over the result code.
- **Sandbox PSP REST shape** for `HttpPspClient`: `POST /v1/payments` (body txnRef/amountPaise/payeeVpa/payeeName/note,
  `Idempotency-Key` header) and `GET /v1/payments/{txnRef}` → `{txnRef, status: PENDING|SUCCESS|FAILED, pspTxnId, upiTxnId,
  amountPaise, payeeVpa, failureCode, failureReason}`. Selected only when `PSP_BASE_URL` is an https URL; otherwise `FakePsp`.
- **Pay form is a single `PayUiState`** (fields + errors + phase) rather than Loading/Empty/Error/Content objects: a form has no
  "empty" state; `Submitting`/`Confirming` are the loading states and `Done(FAILED)`/`Unresolved` the error states.

## Feature 3: QR scan-to-pay
- **Scan hands off to the Pay screen** (`PayDestination(vpa, name, amountPaise, note, source="qr")`) instead of duplicating the
  pay/confirm UI. The pay screen locks the payee (and the amount for merchant QRs) and records `PaymentMethod.QR_SCAN`.
- **`amountPaise = 0` means "not set"** in the route: Navigation's type-safe routes don't support nullable `Long`, and a real
  amount is always ≥ 1 paisa.
- **Interfaces**: `BarcodeScanner.scan(image: Any)` (ML Kit `InputImage` on Android) and `CameraController.start(lifecycleOwner,
  PreviewView?, onBarcode, onError)`. The ViewModel never sees CameraX or ML Kit; tests use `FakeCameraController`.
- **Parser rules**: only `upi://pay`; 4 KB cap checked first; first value wins for duplicates; `+` decoded as a space (common in
  generators even though RFC 3986 says otherwise); `url=`/`mode=`/`sign=` ignored; invalid `tr` is dropped, not fatal.
- **Device-only code is compile-only**: `CameraXController` and `MlKitBarcodeScanner` need Play services and a camera; they
  are covered by lint and compilation, and recorded as "not run: no device".
- `@androidx.annotation.OptIn(ExperimentalGetImage::class)` (not Kotlin's `@OptIn`) is what Android lint accepts for CameraX's
  Java `@RequiresOptIn` marker.

## Feature 4: VPA verification + collect requests
- **Format first, then the PSP.** `VerifyVpaUseCase` rejects malformed VPAs offline so hostile input never reaches the network;
  the result is `Verified(name)`, `NotFound`, `Unavailable(error)` or `InvalidFormat`.
- **Registered name wins and locks the name field.** It replaces QR `pn=` text and typed names; `NotFound` blocks payment;
  `Unavailable` allows paying with the typed name plus a warning (an outage must not make the app unusable).
- **Debounce 600 ms, final value only**; `submit()` verifies first if the debounce never fired.
- **Approving a collect = a normal payment.** `RespondToCollectUseCase` creates a COLLECT payment (CREATED) before the PSP call,
  answers the PSP, `Initiate`s, marks the request APPROVED with the payment id and confirms through the same status check as the
  intent flow. `txnRef = "COL" + requestId` is the shared convention with the sandbox.
- **Exactly-once answers**: per-request `Mutex` (in-process) + Room compare-and-set + PSP 409/410 mapping.
- **Server rows never revert local answers** (`CollectDao.merge`): a stale PENDING from the PSP cannot undo an APPROVED/DECLINED.
- **Room v2** adds `collect_requests` with an `AutoMigration(1, 2)` (schemas exported under `app/schemas`).
- **Demo collect requests** are seeded into the sandbox `FakePsp` in `PspModule` so the screen has content; a real PSP would push them.

## Feature 5: AutoPay mandates
- **Rules are pure** (`MandateRules`): creation limits (1 paisa–₹1,00,000, start ≥ today, end ≥ start, ≤ 10 years) and per-debit
  checks in order: amount > 0, validity window, status ACTIVE, ≤ max amount, one debit per period. Period keys are computed in the
  injected `ZoneId` (ISO week / quarter / month / year); `AS_PRESENTED` has no period.
- **Reserve, then debit.** `ExecuteMandateUseCase` wins the period with a counter compare-and-set before calling the PSP and
  releases it if the PSP rejects; the payment row (method MANDATE, `mandateId`) is created before the PSP call like every payment.
- **Per-mandate mutex for lifecycle actions** and mirror the PSP-returned status (race found by the stress test).
- **Simulated merchant debit in the detail screen** (sandbox): the only way to demonstrate the rules without a merchant backend;
  clearly labelled.
- **Payee verified at creation** via the PSP directory (reuses feature 4): the stored payee name is the registered one.
- **Room v3**: `mandates` table + `payments.mandate_id`, both via `AutoMigration(2, 3)`.
- **Validity as months chips** (1/3/6/12/24) instead of date pickers: fewer input errors, start = today.

## Feature 6: polling, backoff, idempotency
- **The idempotency key lives on the payment row** (`payments.idempotency_key`, unique index) rather than in a separate table:
  the row *is* the reservation, written before the PSP call, and the unique index is the cross-process guarantee.
- **Takeover rule**: a CREATED row found under a key (process died before the PSP answered) is completed by the caller; a
  PENDING row is returned as-is; a settled row is a conflict (a spent key must never pretend the new tap succeeded).
- **Per-key mutex in-process** so 1,000 racing taps register once at the PSP; PSP-side dedupe (`Idempotency-Key`/`txnRef`) covers
  the rest.
- **Spent keys**: the Pay screen drops the key when an attempt fails or settles; it keeps it (with the in-flight id) only while the
  UPI app is open, which is the process-death window that matters.
- **Full jitter by default**, `Retry-After` wins but is capped, non-retryable errors never retried, cancellation never swallowed.
- **Single-flight poller** (`CompletableDeferred` per payment id): the screen, the detail page and the reconciler share one poller.
- **Reconcile on Home start** (payments CREATED/PENDING for > 30 s, batch of 200) instead of WorkManager: simpler, testable with
  virtual time, and sufficient for a sandbox; WorkManager noted as scope for improvement.
- **Exhausted polling = still PENDING**: honesty over closure; the UI says "check again".

## Feature 7: security
- **One cipher, two key sources.** `AesGcmCipher` (AES-256-GCM, 96-bit random IV, 128-bit tag, AAD) is the only crypto code; the
  production key comes from the Android Keystore (`KeystoreCryptoBox`), tests use an in-memory key (`InMemoryCryptoBox`). Robolectric
  has no `AndroidKeyStore`, so the Keystore class is device-only and the cipher is what gets tested.
- **AAD = key name** in `EncryptedSecureStore`, so a ciphertext cannot be copied from one preference to another.
- **PBKDF2-HmacSHA256, 150k iterations, 16-byte salt**, stored with the record so the cost can be tuned later. Lockout: 5 attempts,
  30 s doubling to a 1 h cap, absolute `lockoutUntil` (clock moved back cannot shorten it), checked before hashing.
- **Lock is optional in the sandbox** (banner on Home until a PIN is set) so the app stays usable for reviewers; once set, the lock
  screen covers the NavHost and relocks after 60 s in the background. `MainActivity` is a `FragmentActivity` (BiometricPrompt).
- **No ticker in the lock ViewModel**: the screen asks for a recount once a second while locked out; the ViewModel stays
  virtual-time friendly.
- **Device binding is simulated at the transport only**: OTP generation, TTL, attempt limit, masking, encrypted record and the
  fingerprint comparison are real; `FakeOtpSender` shows the code in-app because there is no SMS gateway.
- **Play Integrity**: the token request is real (`StandardIntegrityManager`, cloud project number from `local.properties`);
  verification is `FakeIntegrityVerifier` because decoding needs server credentials. Request hash = SHA-256(paymentId|amount|payee).
- **Tamper checks are pure** over a `TamperProbe`; `/proc/net/tcp` is parsed by field (local port hex + LISTEN state) to avoid false
  positives; a check that cannot run is not a finding. Root/Frida/signature mismatch block payments, debugger only warns.
- **Hilt test module** (`FakeSecurityModule`, `@TestInstallIn`) swaps Keystore/Biometric/Integrity for fakes in Robolectric Hilt tests.
- `EXPECTED_SIGNING_SHA256` is optional in `local.properties`; without it the signature check is skipped (debug builds differ per machine).

## Feature 8: CheckoutKit integration
- **Dependency**: `com.github.Darshan-05032003.CheckoutKit:checkoutkit-ui:v1.0.0` from JitPack (resolved on the first attempt;
  brings `checkoutkit-core` → `checkoutkit-core-android` through Gradle module metadata). The aggregate coordinate is avoided (duplicate
  classes with the JVM core variant). The `includeBuild` fallback was not needed.
- **Adapter, not a second PSP**: `PayFlowPaymentGateway` implements the SDK's `PaymentGateway` on top of the existing `PspClient`, so
  CheckoutKit payments go through the same sandbox, the same history and the same reconciler. The SDK idempotency key becomes the PSP
  `txnRef` (`CK` + UUID without dashes), the merchant is the payee, the payer's method type goes into the note.
- **Refund endpoint added to `PspClient`** (`refund(txnRef, amount, refundKey)`, `PspPaymentStatus.Refunded`) because the SDK's gateway
  contract requires `refund`; `ConfirmPaymentUseCase` walks a refunded order through SUCCESS → REFUND_PENDING → REFUNDED locally.
- **Recording rules**: Success → SUCCESS, Pending → PENDING (under the SDK transaction id), Unresolved → PENDING under the gateway's
  order reference (reconciled later), Failed/Rejected → FAILED with the typed error code, Cancelled → nothing. Idempotent per order.
- **Sheet result timing**: the SDK delivers `onResult` once when the sheet closes; the Checkout screen keeps the active order in
  `SavedStateHandle` so a late result after process death is still recorded.
- **Sandbox decline code** changed from `U30` to `ZA` so CheckoutKit maps it to `Declined` (see API feedback).

## CheckoutKit API feedback (found while integrating v1.0.0; not changed here, CheckoutKit is read-only for this build)
1. `CheckoutResult.Unresolved(lastError)` carries no `transactionId`/order reference, so a host cannot store the attempt under the PSP
   reference it needs for reconciliation. Worked around by remembering `orderId → txnRef` inside the gateway adapter. Suggestion: add
   `transactionId: String?` (or the idempotency key) to `Unresolved`.
2. `GatewayErrorCodes` lacks NPCI `U30` ("debit has failed") and several other `Uxx` bank-side declines; they surface as a generic
   `GatewayError(code)` instead of `Declined`. Suggestion: extend the table or document the expected code set for gateway authors.
3. `GatewayResult.Pending` requires `amountPaise`, but a PSP's pending status usually carries no amount; the adapter has to remember the
   charged amount per order. Suggestion: make it optional or derive it from the `ChargeRequest`.
4. `onResult` for `Pending` fires only when the payer closes the sheet; a host that wants to start its own polling while the sheet is
   still open has no hook. Suggestion: an optional `onStateChange` / exposing `CheckoutSession.state` from `CheckoutSheetState`.
5. Positive: the explicit-API surface, `CheckoutSheetTestTags` and the `PaymentGateway` KDoc contract made the Robolectric integration
   tests straightforward; the aggregate-coordinate pitfall is documented in the SDK README.

## Compromises
- Play Integrity token **verification** is a fake (no server). SMS OTP **delivery** is simulated (no gateway). Both are labelled in
  the UI and README; the surrounding logic is real and tested.

## Real UPI apps: UPI-apps mode vs sandbox
- **Two modes, one code path.** `PaymentMode { UPI_APPS, SANDBOX }` is a DataStore setting; the default is `UPI_APPS` when
  `PackageManager` sees a `upi://pay` handler, else `SANDBOX` (emulators, JVM tests). `PayViaUpiIntentUseCase` takes the mode: in
  sandbox it registers the order with the PSP, in UPI-apps mode it moves the row to PENDING and records the chosen app, nothing else
  changes (same idempotency key, same `txnRef`, same state machine).
- **PayFlow's own app sheet** (built from `queryIntentActivities`, PayFlow excluded) instead of the system chooser, so the user sees
  icons and names and the launch intent can be pinned to that package; the system chooser remains the fallback when package
  visibility hides the list. Nothing is stored until an app is picked.
- **"Reported", never "verified".** `StatusSource { PSP, UPI_APP, USER }` is persisted on the row (Room v4) and drives every status
  line, the detail screen and the shared receipt. There is no PSP to ask in UPI-apps mode, so "Check status" is not offered;
  a payment with no usable answer stays PENDING with "Check in <app>" and the user can mark it paid / not paid (`USER`).
- **`txnRef` matching stays.** A response that names another reference is refused (`Mismatch`); an absent `txnRef` is accepted
  because some apps omit it, and the response still cannot name a different payment.
- **Incoming links reuse the QR parser.** `IncomingUpiLinkStore` holds one parsed payload; the NavHost consumes it once and only
  while unlocked. The Pay screen treats `source = "link"` like a QR (payee + amount locked, method `DEEP_LINK`).
- **Receive = profile + QR, nothing more.** PayFlow cannot see incoming money (no bank connection); the screen says so. The profile
  is stored through the encrypted `SecureStore`. zxing `core` renders the matrix; the Compose `Canvas` draws it black-on-white
  regardless of theme so scanners work in dark mode.
- **Sandbox-only features stay sandbox.** Collect requests, AutoPay mandates and Checkout talk to the fake PSP in both modes; the
  mode only changes how a `Pay` / QR / link payment is executed. The Sandbox badge appears in top bars whenever sandbox is active.
- **No bottom navigation.** The dashboard grid (two rows of four) plus the Pay FAB already makes every screen one tap away; a bottom
  bar would have duplicated Home / Scan / History and cost vertical space on the Pay form. (Superseded by the UI overhaul; see "app shell decisions".)

## Scan from a gallery image
- **Photo Picker, no permission.** `ActivityResultContracts.PickVisualMedia(ImageOnly)`; no `READ_MEDIA_IMAGES` / storage permission.
  The action is on the camera overlay *and* on both camera-permission states, so a user who refuses the camera can pay from a screenshot.
- **Decoder behind an interface** (`ImageQrDecoder.decode(uri) → ImageQrResult`: Found / MultipleFound / NotUpi / NoQrFound /
  Unreadable). `AndroidImageQrDecoder` loads the image downsampled (longest side ≤ 2,048 px, EXIF rotation applied) and hands that
  bitmap to ML Kit via `InputImage.fromBitmap`, not `fromFilePath`, because `fromFilePath` decodes at full size (a 50 MP photo could OOM);
  zxing-core (`QRCodeMultiReader`, then `MultiFormatReader` with `TRY_HARDER`) is the fallback when ML Kit finds nothing.
- **Same path as a camera scan.** Decoded texts go through `UpiQrPayloadParser`; the payload is saved in `SavedStateHandle` like a scan
  and navigates with `source = "qr"` (method `QR_SCAN`, payee and amount locked). The URI itself is never persisted.

## Emulator testing and fixes from the walkthrough
- **Debug-only fake UPI app** (`app/src/debug`, `SandboxUpiActivity`, label "Sandbox UPI (debug)") instead of mocking at the launcher:
  it exercises the real path (package query → PayFlow's sheet → pinned intent → activity result → response parsing) on an emulator.
  Because it lives in PayFlow's own package, the launcher now excludes only `MainActivity` (PayFlow's link handler), and targeted
  launches resolve to an explicit component.
- **UPI-apps payments are invisible to the sandbox PSP.** Every one carries a handler marker (`Payment.VIA_SYSTEM_CHOOSER` when the app
  is unknown); the reconciler's query and `ConfirmPaymentUseCase` skip them. Found on the emulator, where the reconciler had failed a
  pending real payment.
- **ML Kit waits are not cancellable**: the frame / bitmap / client are released only after ML Kit's native task completes (a SIGSEGV
  on the emulator when the camera stopped mid-frame).
- **The system chooser excludes PayFlow itself** (`EXTRA_EXCLUDE_COMPONENTS`).


## UPI app visibility: `<queries>` must declare `upi://pay` (2026-10-30)
- **Bug (found on a real phone):** PayFlow's "Pay with" sheet listed only the preinstalled Amazon app and PayFlow's own debug Sandbox UPI, never GPay, PhonePe, Paytm or BHIM. Android 11+ package visibility only exposes apps matched by the manifest `<queries>`, and the old query declared `<data android:scheme="upi"/>` with no host. UPI apps register `upi://pay` (scheme **and** host), and a host-less query intent does not match a filter that requires a host, so they were invisible. Preinstalled apps are force-queryable, which is why Amazon still appeared.
- **Fix:** `<queries>` now declares `VIEW upi://pay`, keeps the scheme-only form for apps that register the scheme alone, and lists the major UPI apps by package as a fallback. Regression test `UpiPackageVisibilityTest` checks the manifest.
- **Verified on the emulator** with a stand-in app (`com.example.fakeupi`, not in the package list) that registers `upi://pay` exactly like the real apps: invisible to the old build's sheet, listed after the fix, and the hand-off → app response → "Reported by …" result path worked through it. The real apps on a phone need the new build installed; the payment path itself is unchanged.

## UI overhaul: library decisions (2026-11-09)
| Library | Latest stable checked 2026-11-09 | Decision |
|---|---|---|
| Compose BOM | 2026.09.00 (Compose 1.12.1, Material3 1.4.0) | **Keep.** Already on the latest. |
| Material 3 Expressive | Expressive APIs are still graduating inside `material3` 1.5.0-rc01 (Nov 2026), not in stable 1.4.0 | **Do not adopt.** Both apps use a custom design system on top of plain Material 3 slots, so nothing depends on it. Revisit when 1.5.0 is stable. |
| `SharedTransitionLayout`, shared elements | Compose Animation 1.12.1 | **Adopt** for container transforms. |
| Predictive back | Navigation Compose 2.10.2 | **Adopt** with `android:enableOnBackInvokedCallback`. |
| `core-splashscreen` | 1.2.0 | **Adopt** for the icon → home hand-off. |
| Lottie Compose | 6.7.1 | **Do not adopt by default.** Every animation is code-drawn (Canvas/AGSL), which keeps the APK small and gives full control. Only added if you supply Lottie files. |
| Haze | 2.1.0 (Compose 1.12) | **Adopt only where glass adds depth** (bottom bar, sheets), API 31+, with a dim scrim below. |
| Roborazzi | 1.78.0 (`roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule`, `roborazzi-gradle-plugin`) | **Adopt** for screenshot tests. It runs on the existing Robolectric 4.17 setup. Fallback: Paparazzi 1.3.5. |
| Macrobenchmark, Baseline Profile plugin | 1.5.0 | **Adopt** (SplitSmart already has both modules; PayFlow gets them in Wave 2). |
| CRED `neopop` | Apache-2.0, View/XML based, repo last active July 2024, no `club.cred:neopop` artifact on Maven Central | **Reject.** Not Compose, unmaintained, and the brief rules out a NeoPOP look. |

- **Baseline before the overhaul:** `testDebugUnitTest` 407 tests after pinning one date-dependent test to a fixed clock (F0, `a1d2301`), 24 stress tests, `assembleDebug` and `lintDebug` with no errors.
- **Order and worktrees:** this machine has 15 GB of RAM, and only one Gradle build runs safely at a time. PayFlow is done first, then SplitSmart. Feature work is integrated one at a time.

## UI overhaul: chosen direction (2026-11-09)
- **Aurum Noir** (see `DESIGN_DIRECTION.md`), no tweaks. No Lottie: all motion is code-drawn.
- Features are built one at a time on `main`, because this machine runs one Gradle build safely at a time; no parallel worktrees are needed.
- Performance numbers come from the Pixel 7 emulator with software rendering and are labelled as such. They show before/after differences, not what a real mid-range phone would measure.

## UI overhaul: design system decisions (2026-11-09)
- **Screenshot tests:** Roborazzi 1.78.0 works with AGP 9.5 and Robolectric 4.17 with no workaround, so Paparazzi was not needed. Baselines are in `app/src/test/screenshots/`. Tests that need the debug-only catalog are in `src/testDebug`, so release unit-test compilation is unaffected.
- **Theme name:** `ui.theme.PayFlowTheme` stays the single entry point and delegates to the design system, so existing screens change with no import churn.
- **Haze: skipped.** Aurum Noir gets its depth from layered tones and a hairline. No component needs real blur, and skipping it avoids a dependency and a blur cost on mid-range phones. Revisit only if a later screen truly needs glass.
- **Haptics use the view channel only.** `View.performHapticFeedback` needs no `VIBRATE` permission and respects the phone's touch-feedback setting. A payments app shouldn't ask for a permission it can avoid. `CONFIRM` and `REJECT` exist from API 30; older phones fall back to `VIRTUAL_KEY` and `LONG_PRESS`.
- **Bottom sheet is in-window, not a dialog.** A dialog or popup is a separate window that doesn't inherit `FLAG_SECURE`, so payment details in it could reach screenshots and the recents thumbnail. Trade-off: the caller places it last in a full-screen `Box`, and content behind it must be hidden from TalkBack by the screen.
- **Film grain is on hero surfaces only.** The direction asks for 3% grain on dark surfaces. Per-pixel noise across a scrolling screen is a poor trade on weaker GPUs for an effect few would notice, so `SurfaceLevel.Hero` carries it. The hero stays graphite in light theme, so it keeps 2% there.
- **Fonts are subset** to Latin, Latin-1, Latin Extended-A, punctuation, ₹ and −, with variation axes and OpenType features kept. Other scripts such as Devanagari fall back to the system font.
- **Sheen strength differs by theme:** 35% on champagne, 14% on graphite. The same strength looked scratchy on the dark button.
- **Additions beyond the brief's component list**, because later screens would otherwise improvise them: `PayFlowTopBar`, `PayFlowListRow`, `PayFlowChoiceChip`, `PayFlowSwitch`, `PayFlowSurface`, `AmountText`, `DrawnCheckmark`, `DrawnCross`, `StaggeredEntrance`.

## UI overhaul: app shell decisions (2026-11-09)
- **Bottom navigation now exists,** replacing the earlier "No bottom navigation" decision, as the brief requires. Bar: Home, Scan, History, Me.
- **Scan is in the bar but is not a tab.** A camera is a mode. It opens full-bleed over the current tab, and back returns there. The pill moves to Scan while it's open and goes back, because the back stack is the single source of truth for the pill.
- **The bar shows on tab roots only.** A task inside a flow gets the whole screen, and a payment screen has nothing competing with its one action.
- **Me opens Security for now.** The settings rebuild decides what else it holds.
- **`HOME_HISTORY` moved to the bar's History item.** The Home tile that opens History is now `HOME_HISTORY_TILE`. The benchmarks still find the tag on the first screen, and now with no scrolling.
- **One transition per relationship, in `ShellLogic.transition`:**
  - Tab to tab: fade through. Tabs have no spatial order worth implying.
  - Along a flow: shared axis X, a 10% move with a fade.
  - Scanner: rises and sinks. A scanned code then moves forward into Pay on the shared axis.
  - Payment row to detail: container transform.
  - Going back always undoes the way in. Reduced motion makes all of them short fades, from the design system's helpers.
- **Shared elements: one in the shell, the rest later.** `Modifier.containerTransform(key)` is available to every screen. It's used for payment row to detail now. Home's Scan button to the scanner (home row) and the payee card from the QR (scan row) use the same modifier. It does nothing under reduced motion or outside the shell. Cost on a 10,000-row list: 3% on a JVM scroll test (1,153 vs 1,191 ms).
- **Predictive back uses the same transitions,** seeked by Navigation's own handling. A separate preview style would have been a second vocabulary. `enableOnBackInvokedCallback` is declared explicitly.
- **The app leaves the composition while locked,** rather than sitting under an opaque layer. Nothing can be read or tapped behind the lock. To keep the user's place, the app's saved state lives in a `SaveableStateHolder` above the gate, so unlocking restores the back stack the way a rotation does, with the same ViewModels. Before this, a relock always dropped the user on Home.
- **Circular reveal for PIN as well as biometrics.** The brief names biometrics. A second unlock motion would be noise, so both use the circle, opening from the control that unlocked: the fingerprint key or the dots. Reduced motion is a short fade. The reveal never delays the app, which is open and tappable from its first frame.
- **Back on the lock isn't intercepted.** It leaves the app with the system's own back animation, and nothing is behind the lock to fall back to. During PIN setup, which the user opened from inside the app, back means "Not now".
- **The lock follows the user's theme** rather than always being dark. A locked app is still the user's app, and light is first-class.
- **Splash is graphite in both themes,** matching the launcher icon, so a stored theme can't flash the wrong one. It holds for the theme and the lock's answer, up to 2.5 s, so a stuck read can't trap the app. The lock still covers everything after that limit. Below API 31 the icon is static, because the library doesn't play animated icons there.
- **`FLAG_SECURE` is app-wide,** held by `AppRoot`. Per-screen flags left Home and History uncovered and would change mid-transition. `-PscreenshotMode=true` still turns it off in debug.
- **A background is painted behind the NavHost.** Mid-fade the window shows through, and the window follows the system theme rather than the app's, so a light app on a dark phone would flash dark between screens.

## UI overhaul: home decisions (2026-11-09)
- **One primary action: Scan & Pay. The floating Pay button is gone.** It covered the empty state's text and duplicated the Pay tile, and a QR scan is how most UPI payments start. Pay to a UPI ID stays as the first quick action. The empty state has no button of its own, because the primary action is already on screen.
- **Eight tiles became four quick actions.** Scan is the hero, History and Security are in the bar, so those tiles only repeated things one thumb away. Left: Pay, Receive, Requests, AutoPay. `HOME_HISTORY_TILE` and `HOME_SECURITY` are removed; `HOME_HISTORY` stays on the bar's History item for the benchmarks, and "View all" still opens History.
- **Checkout is a labelled row at the end of the page, not a tile.** It's a demo store on the sandbox PSP in both modes, so it says "Demo" whichever mode is on.
- **Requests and AutoPay carry a "Sandbox" line in UPI apps mode only.** They use the fake PSP in both modes. In sandbox mode the badge and the hero caption already say so, and repeating it on every tile would be noise.
- **The header is full-bleed graphite with Scan & Pay across its edge, instead of a card.** A card under a top bar is what made the screen read as a template. The action sits in a notch of the page colour, so it stays separate in the light theme, where action and header are both graphite.
- **The hero is "Sent this month", with a caption that says what it isn't.** UPI apps mode: "Recorded on this phone. Not a bank balance." Sandbox: "Sandbox payments. No real money moved." The labelled demo balance the direction allowed was not added: a second, invented number next to a real one would weaken both. While loading or failed, the hero shows a skeleton or "Not available", never ₹0, because a total that isn't known is not zero.
- **Collapse uses layers, not layout.** The header is the first child of the scrolling column and is held in place by a layer translation once its range is used up. The wordmark gives way, the label and amount travel into the bar and the amount scales down. A scroll frame recomposes nothing. A second, docked copy of the amount was rejected: two nodes for one number, and they could disagree mid-roll.
- **Still a column rather than a lazy list.** Home holds at most 5 payments and 8 payees, however many are stored, so a lazy list would add composition during scrolling with nothing to save. It also keeps every node available to TalkBack. The payee row is a `LazyRow` with keys and `contentType`.
- **Hide applies to every amount on Home.** Hiding only the hero while the rows show their amounts would protect nothing. The all-time line and the rows mask at once; the hero blurs through a scramble. The scramble is random and as long as the mask, so no frame says anything about the amount or its size. The choice is stored, and amounts stay masked until the stored value has been read, so a hidden amount can't flash at launch. History and the payment detail don't honour it yet: left for the money screens row.
- **Successful rows have no pill.** Success is the normal case, so five green pills would hide the one that matters. Pending, failed and refund rows keep theirs, and TalkBack still hears the status on every row.
- **Entrance and last-seen amount live in the ViewModel, not saved state.** Coming back from Pay should roll the total up from what the user last saw, not replay the entrance. That number is an amount, and saved state leaves the process.
- **Status bar icons are light on Home in both themes,** since the header under them is graphite. Home restores them when it leaves.
- **Pull-to-refresh uses Material's gesture and PayFlow's drawing.** `Modifier.pullToRefresh` handles the nested scrolling; the well and the coin are ours. The pull moves a layer inside the scroller, because moving the scroller itself would shift the pointer position it measures from. It asks the provider about pending payments again and says what happened, including "Nothing new to check". The gesture has a TalkBack action.
- **The bar's base is now solid (shell change).** The old gradient ran the whole height of the bar, so half-faded text showed beside and below the dock and looked like a mistake. Content now fades out in the 24dp above the dock, and from the dock's top edge down it's plain background. This changes every tab.
- **Sheen on the hero sweeps when the total changes, and not on load.** That's a real payment settling. It doesn't play while amounts are hidden.

## UI overhaul: pay decisions (2026-11-09)
- **Swipe-to-pay commits on lift, by position only.** The handle must be at 90 % of the track when the finger lifts. Speed is ignored, so a careless fling that stops short does nothing. A cancelled gesture never pays. The rules are in `SwipeToPayHolder`, a plain class tested on the JVM; the composable only feeds it pointer deltas.
- **Haptics per gesture event:** a tick at each of 8 detents (once per detent, not again on the way back), a light tap when armed, a heavy one on commit, a reject on a disabled slider. Outcomes play confirm (success) or reject (failure) once, from the ViewModel's effect channel, never on rotation.
- **There's always a plain way to pay.** "Pay with a button instead" under the slider, and TalkBack's activate on the slider, both open a confirmation sheet with the amount, the payee's name and UPI ID, and a Pay button. Swiping is a choice, not a requirement, and reduced motion changes nothing about the steps.
- **The slider waits for the directory in sandbox mode.** It says "Checking the payee…" until the registered name is on screen, so nobody swipes to a name they haven't seen. A provider that can't be reached is a warning, not a block, as before. The name field is shown only when the directory can't supply a name (or in UPI apps mode, where it's optional).
- **Custom keypad, text kept in the ViewModel.** The system keyboard is gone for amounts; the note still uses it, and the keypad hides while it's open. A key that would pass ₹1,00,000 or a third decimal is refused. The keypad docks above the slider on tall windows and scrolls with the form below 600dp.
- **Celebration is earned by the record.** Full moment (drawn check, coin burst, odometer roll, sheen) only when the provider confirmed. An app-reported success gets the drawn check and confirm haptic, titled "GPay reports this as paid", with "Reported by GPay. Not verified by PayFlow." on the receipt. A user-marked result gets a neutral check and no haptic. Pending, refund, mismatch and failure never celebrate.
- **Processing copy evolves:** "Confirming with the payment provider…" → after 5 s "Still checking…" → after 15 s "The bank is taking longer than usual" with "Leave and check History". Back is blocked only during that first 15 s of a running call.
- **Pay again from a pending payment takes a second step.** "This payment is still pending… Paying again could charge you twice", with "Keep waiting" as the primary. From a failure, "Try again" is the primary.
- **Receipt sharing is the user's choice of image or text.** Screenshots are blocked app-wide, so this is the only way out. The image is drawn off screen in the light palette (it gets pasted into chats), from the same `ReceiptContent` as the text. The note isn't included, and the sheet says so.
- **Sandbox demo payees** (`slowbank@`, `stuck@`, `declined@`, `refunded@`, `mismatch@okbank`) make every outcome reachable from the app, shown as chips under an empty UPI ID in sandbox mode. Only the in-app fake provider treats them specially (`FakePspConfig.demoPayees`).
- **Scanner:** graphite in both themes. Brackets breathe and a scan beam sweeps (AGSL on API 33+, gradient below); both decorative, off under reduced motion. On a read code: confirm haptic, a 240 ms lock-on beat, then the payee card moves into Pay as a shared element (`PAYEE_CONTAINER_KEY`). Several codes in one image stop on the found card.
- **Camera inside the container transform:** the preview is a TextureView (`COMPATIBLE`), and the camera binds only once the transition from Home has settled. A SurfaceView inside an animating shared bound is a separate surface and can draw misplaced or black. Not yet seen on a device.

### Compromises
- Brackets lock on to a fixed square rather than the code's real bounds: ML Kit's box isn't mapped into preview coordinates. Mapping it needs a device to verify.
- No device or emulator look for this row (8,068 MB free; the launcher needs 8,200).

## UI overhaul: money screens decisions (2026-11-10)
- **History keeps the whole filtered list and windows it.** The data layer returns one list per filter and query; Paging 3 would mean a new data path for every caller. The ViewModel groups it into days off the main thread (12 ms for 100,000) and the list composes only what's visible. Paging is the recommendation past about 20,000 rows (LOAD_TEST_REPORT).
- **Days are counted in the user's zone, and a day's total is what really left:** successful outgoing payments only. A pending ₹400 isn't money gone, and money received isn't spending.
- **Rows under a day header show the time, not "2 h ago".** The header already says the day; Home keeps relative times.
- **Swipe actions are Pay again and Check status, nothing destructive.** A payment ledger has nothing to delete. Pay again is offered only on settled outgoing payments and fills in the payee, never the amount; offering it beside a pending payment is how people pay twice. Check status asks the sandbox provider about pending payments now (all of them, the row's included). Both are TalkBack actions too.
- **Search is a button that grows into the field.** History is browsed more than searched, so the title keeps the space until asked. The field is laid out at full width and revealed from the button outward by a clip read in the draw phase; a query keeps it open; closing clears it.
- **The payment detail is a sheet, but still a destination.** It keeps its route (deep links from Pay, Collect and AutoPay, back stack, process death) and draws itself as a sheet the row grows into. Dragging the top down past 40 % or flinging closes it. The sheet frame is drawn in every state, so the transform always has somewhere to land.
- **Detail reuses Pay's `ReceiptCard`** (extracted from the printing receipt, now with a `paper` colour) and `statusSourceLabel`, so a payment is worded the same everywhere. The details below it hold only what the receipt leaves out (method, provider ID, refund ID) and a "Copy reference" action, so no value is printed twice.
- **Hide amounts reaches History, its day totals, the payment sheet and a mandate's debits** through one `AmountVisibility` helper, masked until the stored choice is read. History and the sheet have their own eye; it flips the same setting as Home's. The demo reward isn't masked: it isn't money.
- **Collect requests: paying takes a swipe or a review, declining takes a tap.** The swipe is Pay's own `SwipeToPay`. TalkBack's activate, "Pay with a button" and reduced motion go to a review sheet that names the payee and UPI ID and says that approving sends money and receives nothing. Under reduced motion there is no slider at all, only "Review and pay", because the slider's whole feedback is motion. A one-tap decline is safe: the requester can ask again.
- **A countdown ring, ticking once a second on the injected clock.** The sweep eases between ticks (Small) rather than running linearly; it turns to the pending tone in the last two minutes. At zero the review closes and the request moves to Earlier. The use case checks expiry again, so the screen is never the only guard.
- **Heads-up entrance once per visit.** Open requests drop in on the Bouncy spring with the stagger token; the memory lives in the ViewModel so returning from a payment doesn't replay it.
- **AutoPay is a card stack with one quick action per card: pause or resume.** The icon shows what a tap will do, two bars or a triangle, and morphs only when the stored status changes; while the request runs the control holds and shows the loader. Revoke can't be undone, so it lives in the mandate and asks first. Behind cards are hidden from TalkBack, which gets "Next" and "Previous" actions on the stack.
- **"Expected", never "scheduled".** A mandate allows debits; the merchant chooses the day. The timeline lists the dates the frequency allows from the start date, one per calendar period, skips a period already debited, shows a still-due period as today, and says plainly when nothing will come (paused, ended, billed when presented). It says the sandbox has no merchant.
- **The demo scratch card lives on the payment sheet of a provider-confirmed outgoing success.** That's the one place delight is earned and nothing is at stake. The amount is ₹1 to ₹50, fixed by the payment ID so reopening can't fish for more; the card says "Demo" and "nothing is credited" on the foil, underneath and to TalkBack; no payment, balance or total changes. Scratching starts on touch with no slop and consumes the moves so it never scrolls the sheet; past 55 % the foil falls away with a ring of glints (off under reduced motion) and one confirm haptic. TalkBack's activate reveals it. Scratched cards are remembered for the run (`DemoRewardLedger`).
- **New shared pieces:** `PaymentFeedRow` (Home's row, now used by History and AutoPay), `PayFlowSearchField` in the design system, and `Sizes.rowSwipeAction`, `countdownRing`, `ringStroke`, `mandateCard`, `mandateStackPeek`, `timelineDot`, `scratchCard`, `scratchBrush`. `SwipeToPay` takes a `testTag`. The old `PaymentRow` is gone.
- **Collect uses `BrandLoader`.** Security and Checkout keep their progress bars for row 6.

### Compromises
- No device look for this row: the emulator refused (4,552 MB free, needs 8,200). The row-to-sheet transform, the stack's fling feel and the scratch on a real GPU are JVM-checked only.
