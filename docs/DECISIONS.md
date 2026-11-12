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

## UI overhaul: settings and security decisions (2026-11-10)
- **Me is a landing, not Security.** The Me tab shows who you are on UPI (name and UPI ID on a graphite card), a door to the Security centre and to Receive, the look-and-feel toggles and About. Security is a page inside Me with a back arrow. The one primary action on Me is "Set up your UPI ID", and only while there isn't one.
- **Toggles live on Me, act at once, with no save button.** Haptics, sound, reduce motion and theme (system, light, dark) write the same `UiPreferences` that `MainActivity` feeds into the theme, so each takes effect across the app when flipped. About is an in-window sheet.
- **Sound: one code-synthesised chime, off by default.** Two sine notes a fifth apart with a short attack and an exponential tail, built in `ChimeSynth` (no audio asset). It plays only for a provider-confirmed success, the same moment as the coin burst, and only when the ringer is on normal.
- **Five checks, each from the real security layer.** Device binding, root and tamper scan, Play Integrity, app lock, biometric unlock. A row ticks only when its check ran and passed. A sandbox verdict, a build with no Play Integrity project, a debug build (the debuggable flag alone) and a phone without biometrics are "Note", drawn as a level dash, never a tick. A row still running shows the loader and holds the sequence.
- **The ticks play in sequence, 280 ms apart, on the frame clock.** Each one draws its glyph on the Small token. While an answer is out, the sequence waits without asking for frames. Reduced motion shows every final state at once.
- **The headline guides.** "Checking this phone…", "Everything checks out" (every row passed), "Nothing needs you" (notes only), "N things need you", or "Payments are paused on this phone". It was "0 of 4 protections active" in red, which alarmed without saying what to do. Below the checks is one primary action, the most important next step: what the pause means, set up app lock, bind this phone, or check integrity again.
- **Each row opens a sheet with what it means and what to do.** The binding OTP flow, the findings list, the integrity explanation, and turning the lock on or off live there, so the centre stays calm.
- **Turning the app lock off needs the current PIN or a biometric.** It was one tap. The PIN check is the same PBKDF2 verify with the same lockout as the lock screen.
- **`PinManager.changes`.** A counter that moves on every set or clear. The lock, Security and Me re-read whether a PIN exists when it moves, so Home's banner can't go stale.
- **The root/tamper warning replaces the blocked-payment toast.** It's a full-screen, calm page: a shield holding a pause mark, "Payments are paused on this phone", what was found in plain words, why that matters for money, what still works, and what would fix it. It changes nothing about the block: the payment guard runs before every payment, the page has no way to pay, and the form behind it is hidden from TalkBack. In the Security centre it also offers "Check again", and it closes by itself once a re-scan comes back clean. Its reasons come from the same facts the guard reads, tested against the real guard.
- **Receive:** your card is graphite with the QR on white paper (black on white in both themes, so any scanner reads it), the amount in the display numerals, one primary action ("Share QR image"), text share as a ghost button, and toasts instead of the Material snackbar. The card rises in once.
- **Checkout:** a graphite store header, products in one card with a quiet Buy each, the last outcome as a card with its status pill. `BrandLoader` replaces the progress bars here and in Security.
- **Retired:** `ui/components` `LoadingView`, `EmptyView`, `ErrorView`, `StateIcon`, `SectionCard`, `InfoBanner`, `DetailRow`, `StatusChip`, `InitialAvatar` and `PaymentStatus.color()`. Nothing used them any more.
- **Copied payment references are flagged sensitive** on the clipboard (`SensitiveClip`). Your own UPI ID is copied plainly, because you copy it to share it.
- **New size token:** `Sizes.qrCode` (220dp).

### CheckoutKit boundary
- **CheckoutKit 1.0.0 has no theming API.** `CheckoutSheetOptions` takes methods, a merchant name and the secure-window flag, nothing about colour or shape. CheckoutKit is read-only for this overhaul, so it wasn't changed.
- **What PayFlow does instead:** the sheet reads the ambient Material theme, so `CheckoutKitTheme` scopes one built from PayFlow's tokens around it (surface, text, primary, accent tint, outline, error, scrim, and the large top corner radius). The sheet's own entry and exit are `ModalBottomSheet`'s window animation, which a host can't drive, so the store behind it recedes on PayFlow's Settle spring (6 % smaller, rounded edge) while the sheet is up and comes back when its result arrives. Under reduced motion it stays still.
- **Scope for improvement: add a theming API to CheckoutKit** (colours, shapes, type and an entry/exit motion spec in `CheckoutSheetOptions`), and an in-window variant of the sheet so a host's `filterTouchesWhenObscured` and in-window sheet rules apply to it. Until then the sheet is a separate window that sets `FLAG_SECURE` itself.

### Compromises
- No device look for this row; JVM screenshots only. The real BiometricPrompt over the removal sheet and CheckoutKit's sheet over the receded store are unseen on a device.

## Design QA (row 7a, 2026-11-10)
A whole-app pass over all 202 Roborazzi baselines (light, dark, 1.3× text, reduced motion), judged as one product. Findings, then what was done.

| # | Finding | Fix |
|---|---|---|
| 1 | The hero's text was champagne in light and ivory in dark on the same graphite. Things placed on a hero used the page's palette: in light, Me's "Set up your UPI ID" was a graphite button on a graphite card, the AutoPay card's status pill and avatars were pale light-theme tints, and the Security headline bar used light status colours. | `onHero` is ivory in both themes. `PayFlowSurface(Hero)` gives its content the dark palette (`HeroPalette`), so buttons, pills and avatars on a hero look the same in both themes. Champagne stays the accent. |
| 2 | Title hierarchy wasn't a rule. History named itself in the serif display face, Me in the sans title. | Rule: a tab root (Home's wordmark, History, Me) uses serif `display`; pushed screens and sheets use sans `title`. `PayFlowTopBar(large = true)` for tab roots; Me now uses it. |
| 3 | Payment detail: Method read "UPI app" under "Confirmed by the sandbox payment provider". The fixture is consistent: a sandbox payment started from a typed UPI ID is handed to a UPI app and then confirmed by the provider. The label was the problem. Method describes how a payment started, and "UPI app" is the status-source vocabulary ("Reported by the UPI app"), so the two lines read as two answers to "who said this was paid". | `PaymentMethod.UPI_INTENT` reads "UPI ID", next to "QR scan", "UPI link", "Collect request". Regression test `detailMethodNamesHowThePaymentStartedNotWhoConfirmedIt`. |
| 4 | "Copy reference" was a borderless button whose inner padding pushed its icon and label in from the details' gutter. | Full-width outlined secondary on the gutters, like every other secondary action. Test `copyReferenceSitsOnTheDetailsGutter`. |
| 5 | Scan's torch is a tonal button. Its visible circle sat 4dp from the screen edge while the title sat on the 20dp gutter. | The circle now sits on the gutter. |
| 6 | Home's error state had a second primary button (Retry) under Scan & Pay, and a generic two-line title. | `ErrorState(primaryRetry = false)` on Home, so Scan & Pay stays the one primary action. Home and History say what failed: "Couldn't load your payments/history. Nothing was lost. Try again in a moment." |
| 7 | AutoPay at 1.3× text: the cards behind the front one showed their text in the peek strip. Two causes: cards had different heights, and the layer translation multiplied the scale compensation by the depth twice, so the third card dropped by `step × height`. | All cards in the stack take the tallest card's height (`IntrinsicSize.Max`), and the geometry is a tested pure object (`StackGeometry`): each card's bottom lands exactly one peek lower at any height. Tests `cardsInTheStackShareTheTallestHeightAtLargeText`, `stackCardsSitOnePeekApartAtAnyHeight`. |
| 8 | Security centre at 1.3×: the Play Integrity row's explanation was cut at three lines mid-sentence. | No line cap on check details; the row grows. Covered by the `security_large_font` baseline. |
| 9 | Create AutoPay offered "1 months". | Plural resource. Asserted in `createFormInputsAndErrors`. |
| 10 | Sample and seed data used real businesses: Blue Tokai Coffee (scan, Home), Chai Point (the in-app sandbox directory and a demo collect request, so it shipped in the app), Metro Recharge with `dmrc@upi`, Netflix, Spotify, Streambox, Indigo Laundry, Brightline Power, Zeta Pay. | Replaced with invented names: Monsoon Roast Coffee, Tulsi Tea Corner, Lotusline Transit, Reelwise, Tunewell, Lumora, Jasmine Laundry, Sunvale Power, Vela Pay. Kept: bank handles in UPI IDs (`@okicici`, `@ybl`) because they are address formats, not payees; UPI app names in payment-mode copy; the device brand in a binding-fingerprint unit test. |

Checked and kept as they are:
- **Grid and gutters.** Spacing tokens are all multiples of 4 (`TokensTest`). Screens use only tokens (now checked by `scripts/token_audit.sh` and `TokenAuditTest`). Content sits on the 20dp gutter; list rows, section headers and top-bar titles line up on it.
- **Radii and depth.** 28 for hero and sheet tops, 20 for cards and buttons, 12 for small controls, pills for chips and status. No drop shadows anywhere: depth is the bg → surface → raised tone steps plus a 1dp hairline.
- **Hero use.** Graphite heroes appear only where a screen has one subject: Home's total, Me's profile, Receive's card, Security's verdict, Checkout's store, the AutoPay card. Never as a list item style.
- **Chips and pills.** One choice chip (filters, quick amounts, frequency, validity, theme) and one status pill everywhere. Horizontal chip rows run off the edge on purpose, as a scroll hint.
- **Empty and error states.** Every one has a code-drawn illustration and plain words. After fix 6 every screen has at most one primary action.
- **One exception, accepted.** Collect requests under reduced motion show "Review and pay" on each open request card. Each card is its own decision about someone else's request; in full motion each card has its own slider in the same role. Merging them into one action would make a payment ambiguous.
- **Contrast.** Every text pair in both palettes passes WCAG AA, enforced by `TokensTest`; ivory on the graphite hero is above 12:1.
- **Not default Material.** No stock spinner, text field, card, chip or snackbar remains. CheckoutKit's sheet is Material, themed from tokens (see "CheckoutKit boundary").
- **1.3× and reduced motion.** After fixes 7 and 8 no clipping in the large-text baselines; long user-supplied names ellipsize by design. "Payment requests" ellipsizes in its top bar at 1.3× beside the Sandbox badge and refresh; TalkBack reads it whole, so it stays.

Re-recorded baselines (intended changes only, one class at a time): Home, History, Money, Pay, Scan, Settings, Shell, and `ds_surfaces_light` from the catalog. Lock and Motion are unchanged. Roborazzi's default 1 % change threshold let the small "Hero" label in the catalog pass verification, so that class was recorded on purpose to keep the baseline truthful.

## Token audit (row 7a)
- **One rule set, two runners.** `scripts/token_audit.py` (for people, milliseconds, exit 1 on a finding) and `TokenAuditTest` (the same expressions in the normal test gate) scan screen code: everything under `ui/` in `main` and `debug` except `ui/designsystem/` and `ui/theme/`. They flag raw hex and named colours, non-zero `dp`/`sp` literals, numbers inside animation specs (`tween`, `snap`, `delay`, `durationMillis`, `keyframes`), a raw `spring(...)`, `*_MS` constants, and easing curves. The test also proves each rule catches its kind, so a path mistake can't make it pass by finding nothing.
- **Exceptions say why, on the line.** `// token-audit: allow (reason)`. There are eleven lines: the QR's white paper and black modules (scanners need it in both themes), the scratch card's erase colour (`BlendMode.Clear`, never drawn), and eight product timings that are not motion: the input debounce, the 15 s back block, when processing copy evolves, the longest splash hold, the shortest visible refresh, two one-second countdown ticks and a minute conversion.
- **Fixed:** `SandboxBadge` was the last Material-styled piece (raw 10/4/14dp, Material type). It's now built from tokens: pill, `Spacing.xs`/`xxs`, `Sizes.iconSmall`, `labelStrong`, the pending tone. The Security centre's 280 ms reveal step was a motion value defined in screen code; it's now `MotionDurations.SequenceStep`, same value.
- Baselines re-recorded for the badge (Pay, Money, Home sandbox, Security). Each change is under Roborazzi's 1 % threshold, so verification alone would have kept the old images.

## Compose stability (row 7a)
- **Reports are a Gradle property, off by default:** `-PcomposeReports=true` writes the compiler's reports and metrics to `app/build/compose_compiler/`. They slow compilation, so the normal build doesn't produce them.
- **`java.time.*` is declared stable** in `app/compose_stability.conf`. The JDK documents these types as immutable, but the compiler infers any Java library class as unstable, which made `Payment` unstable because of its `Instant`. `HomeUiState.Content` is `@Immutable`. Numbers in LOAD_TEST_REPORT ("Compose stability").
- **No `kotlinx.collections.immutable`.** Strong skipping already makes every named composable skippable; the lists are read-only and rebuilt per emission. A new dependency and a change to every ViewModel isn't justified without a device measurement asking for it.

## LeakCanary (row 7a)
- **2.14, `debugImplementation` only.** 2.14 is the latest stable on Maven Central (3.0 is still alpha). It installs itself through a provider in the debug manifest, so no code change is needed.
- **Verified absent from release:** the merged release and benchmark manifests contain no LeakCanary entry, and `dexdump` finds 0 `leakcanary`/`shark` classes in the release APK (1,165 in the debug APK). The benchmark build type starts from release, so the Macrobenchmarks measure an app without it.
- **Findings need a device.** It watches activities, fragments, view models and views on a running app and dumps the heap when something is retained; that happens in row 7b on the emulator. Robolectric tests run with it on the classpath unchanged (it turns heap dumps off under JUnit).

## Device findings fixed (row 7b, emulator)
- **Amounts never count up from ₹0.** On the emulator the success screen read "Payment successful ₹0.00" for a frame or two while its amount rolled up, and Home's hero showed ₹0 before counting up to the month's total after a cold start. A money amount must be true in every frame, so: the success amount is the real number from its first frame and the celebration is the sheen sweeping across it once (plus the checkmark and burst); Home's hero shows the first total after loading as it is and rolls only from a total the user has already seen (`HomeHeaderLogic.shownTotal` / `rollsTo`). Rejected: rolling from a "sensible start" such as the previous digits, because any intermediate number on a receipt reads as a fact. The device also exposed the root cause inside `AmountOdometer`: it marked itself settled in an effect that ran before `BoxWithConstraints` laid out its digit cells, so every freshly shown amount rolled up from 0 even when given its final value. The settled mark now comes from the subcomposition that holds the cells.
- **The status bar follows the surface under it during the unlock reveal.** Home switched the icons to light as soon as it was composed, while the ivory lock still filled the bar. `AppGate` now exposes `LocalAppOwnsStatusBar`, which turns true only when the reveal circle has covered both top corners (`LockMotionLogic.statusBarCovered`; half opacity for the reduced-motion fade); Home's status-bar effect waits for it.
- **The note no longer sits half under the keypad.** At 1080x2400 (411x891dp) the note field was cut by the docked keypad, and with the system keyboard up the swipe track overlapped it. The note is now a full-width "Add a note" button until it's wanted (or already has text), which keeps the form above the keypad; opened, the field takes focus and, while focused, is brought fully into view above the slider and the system keyboard as the keyboard slides in. Rejected: a hairline over the cut edge (the field would still be under the tray) and moving the keypad into the scrolling form (the amount keys would scroll away).

## Device session (2026-11-10, emulator)
- **Going back on the AutoPay stack brings the previous card in front at once.** Swiping right from the second card flew it off to the right first, which uncovered the *third* card for about half a second before the first card rose from behind: it read as moving forward. `StackSettle` now decides how a release settles: forward, the front card flies out and the next rises (unchanged); backward, the previous card is in front from the first frame and slides back from the side it left by while the old front card sinks behind it. Guarded by `MandateLogicTest` and `MandateStackScreenTest.goingBackShowsThePreviousCardAtOnceAndNeverTheNextOne` (it failed against the old code: the previous card was not in front during the move).
- **One fling that snapped back was not a bug.** The first fling right after a screen recording started registered too few touch events on the loaded emulator; five repeats all moved one card, and the last card resisted at the edge as designed.
- **The row → sheet transform can't be judged on this emulator.** Software rendering dropped the middle frames: History faded for about 330 ms and the sheet then appeared almost full size. Start and end states are right; the motion needs a phone's GPU.
- **Numbers are measured with `am start -W` and `dumpsys gfxinfo`, one half per fresh boot.** Macrobenchmark with Perfetto and even ten back-to-back cold starts outgrew the emulator's memory cap; four cold starts per boot and frame rounds in their own boots fitted. The cap was not changed.
- **The redesign's cold start is slower and is reported as it is** (median 631 → 1,409 ms on the emulator). The splash deliberately waits for the theme and the lock state; whether that wait or the larger first composition dominates was not profiled here. A Baseline Profile and a phone run are the next steps, and `light_bench.sh` can now measure with the profile (`COMPILE=speed-profile`).

## Enhancement pass (row 16, 2026-11-12)
A second design-director pass over all 202 baselines, light and dark, plus the two known performance gaps. Findings, ranked
by what a user would feel, and what was done with each.

| # | Finding | Decision |
|---|---|---|
| 1 | At 1.3× text the catalog's narrow-slot odometer read ₹99,99,99,999.9: the last paise digit fell outside the slot. The fit guessed the scaled width from the full-size width, but each digit cell rounds up to whole pixels and glyphs don't scale linearly, so at 1.3× the "fitting" scale still needed 384 px of a 336 px slot. A clipped amount is a wrong amount. | **Done.** `OdometerLogic.fitScale(available, floor, widthAt)` measures the real cells at each candidate step and steps down until they fit. Tested on the real fonts at 1.0×, 1.3× and 2.0× in five slot widths. |
| 2 | Cold start 631 → 1,409 ms on the emulator. Traced on the JVM, the splash waited for: (a) the lock's ViewModel, which was only created by the first composition (after `onCreate`, `onStart`, `onResume` and the first traversal); (b) then a secure-store read for "is a PIN set?" that decrypted the PIN record, a second identical read, and, when locked, two more decrypts for the attempts and the lockout: four Keystore operations; (c) then a hop back to the main thread behind the first composition, which had composed the Checking placeholder, followed by a second composition of the whole app. The theme read started only when the activity was injected. Also: the 631 ms "before" build drew a blank lock placeholder as its first frame, while the redesign's first frame is the real Home, so the two numbers measure different milestones. | **Done.** `StartupPrefetch` (started in `PayFlowApp.onCreate`) begins the theme read and the lock's read as the process starts. "Is a PIN set?" is now the presence of the record's key (`SecureStore.contains`): no decryption. An unreadable record now shows the lock instead of opening the app straight away; since nothing can verify against it, the first attempt drops it and the app says no PIN is set (anyone able to damage the file could already delete it, so this is no weaker than before). The lock's attempts and lockout are decrypted once and kept in step by every write (`PinManager.lockStatus`). When the answer is already in, `AppLockViewModel` starts in its final state, so the first composition is the right screen; when it isn't, it holds on Checking exactly as before, and nothing listens to `AppLock` before its first read (it reads "unlocked" until then). `MainActivity` creates the ViewModel in `onCreate`. Home and the lock now call `ReportDrawn`, so time to full display can be measured next to the first frame (`light_bench.sh` records both). Rejected: a plaintext "PIN is set" flag in preferences (a second source of truth an attacker could delete to skip the lock, where the record's own key can't be removed without removing the PIN). |
| 3 | History flings got heavier on the emulator (P90 31 → 50 ms, janky 3.8 → 14 %). On the JVM, History scrolled on its own recomposed nothing, which is why the earlier stress test saw 0 cycles; inside the shell's shared-element layout, as on a phone, it recomposed 1,373 times over 60 flings (about 23 per fling) and allocated 6.1 MB per fling. The layout alone cost nothing; every row being one end of the row → sheet container transform did: each `sharedBounds` registers with the shell as its row scrolls in. Also checked: a row's press feedback already lets go when a scroll takes the gesture (`waitForUpOrCancellation` sees the scroll's consumption), now locked by `PressInScrollTest`. | **Done.** Only the row under the finger, or the last one opened (for the way back), carries the transform. The finger lands at least a frame before a tap's click, which is what a shared element needs to find its pair; navigation stays immediate. A click with no finger (TalkBack, keyboard) opens with the cross-fade that reduced motion already uses. JVM after: 59 cycles over 60 flings (one hand-over per fling), 5.5 MB per fling (−9 %), guarded by `HistoryFlingCostStressTest` and `HistoryContainerTransformTest` (both directions still match; the test fails if rows lose the transform). Device frame times not re-measured (no emulator headroom); Home's few feed rows keep the transform on every row. |
| 4 | Text cut at large font sizes. A toast's message was capped at three lines with no ellipsis, so at 1.3× the catalog's failure toast lost "across several lines." mid-sentence, and the end of an error is usually what to do next. Me's row subtitles were one line, so at 1.3× "App lock on · checks and payment mode" read "checks and paym…". | **Done.** No line cap on toast text (the queue is bounded, and repeats collapse). `PayFlowListRow(subtitleMaxLines)`: 1 by default, so payment rows keep one rhythm and long UPI IDs still ellipsize; Me's settings rows wrap. `LargeTextTest` checks both at 1.0×, 1.3× and 2.0× on real text layout (`assertTextWhole`: no line cap reached, no ellipsis). |
| 5 | History's states. A filter that found nothing said "No pending payments. Payments you make will show up here." to someone with payments, and offered no way back; a search that found nothing offered none either. The first load showed the pull spinner turning over the skeleton (two loading signs), and a pull-to-refresh swapped the whole list for skeletons while the filter chips jumped to "All" for the duration. | **Done.** A filter that finds nothing says "Your other payments are under All." with "Show all payments"; a search offers "Clear search", which also closes the field. Only a truly empty history has no action. `HistoryReload` decides what shows while reloading: a refresh the user asked for (pull or the refresh button) keeps the last answer and its chips in view under the spinner; the first load and a retry from an error show the skeleton alone. `EmptyState` gained `actionModifier`. Tested at 1.0×, 1.3× and 2.0×, with reduced motion (`HistoryStatesTest`, `HistoryReloadLogicTest`). |
| 6 | Home's error hero said "Not available" in the money face, where the month's total sits, above an empty line kept only for height: a sentence styled as a figure, then a hole. | **Done.** The amount slot shows ₹ and a dash (the dash from Geist, like an amount's sign, because Newsreader's sits below the figures); TalkBack hears "This month's total isn't available". The line under it says "Couldn't load this month's total." Tested at 1.0×, 1.3×, 2.0× and reduced motion (`HomeHeroErrorTest`). |
| 7 | Checkout's "Last checkout" card: a borderless "View payment" whose inner padding pushed its label 20dp in from the card's gutter, the same kind of misalignment 7a fixed for Copy reference. | **Done.** Full-width outlined secondary on the card's gutter, as everywhere else. `viewPaymentSitsOnTheOutcomeCardsGutter` at 1.0×, 1.3× and 2.0× (it fails against the old button). |
