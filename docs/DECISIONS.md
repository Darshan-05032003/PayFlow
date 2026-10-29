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

## Compromises
_None yet._
