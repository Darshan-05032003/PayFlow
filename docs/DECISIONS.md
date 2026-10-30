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
  bar would have duplicated Home / Scan / History and cost vertical space on the Pay form.

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

