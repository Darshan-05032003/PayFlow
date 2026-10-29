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

## Compromises
_None yet._
