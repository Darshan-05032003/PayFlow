# PayFlow build plan

PayFlow is a UPI payments app built against a **sandbox PSP** (no real bank/PSP backend exists for an
individual developer). Every resume claim in `BUILD_PROMPT.md` section 7.2 is implemented for real on
the client; the payment service provider is a realistic fake behind an interface, plus a real
OkHttp client exercised against MockWebServer so the network path is real code.

## Features, in order (one Conventional Commit each)

| # | Feature | Resume claim |
|---|---|---|
| 1 | Home, transaction history (Room), payment state model | success / pending / failed / refund model, Room history |
| 2 | UPI intent flow | `upi://pay` deep links, app chooser, response parsing, **PSP status confirm** |
| 3 | QR scan-to-pay | CameraX + ML Kit, safe payload parsing, camera permission denial |
| 4 | VPA validation + collect requests | format + simulated verify (payee name); approve / decline / expire |
| 5 | AutoPay mandates | create / view / pause / resume / revoke, frequency + max-amount rules |
| 6 | Polling + backoff + idempotency keys | key stored in Room before the call; dedupe on retry and after process death |
| 7 | Security | Keystore AES-GCM, BiometricPrompt + PIN (PBKDF2 + lockout), device binding, root/tamper, Play Integrity, FLAG_SECURE |
| 8 | CheckoutKit integration | JitPack dependency, payment sheet for merchant checkout, integration tests |

## Package structure (`com.darshan.payflow`)

```
core/            Clock, DispatcherProvider, Money, Result helpers
domain/
  model/         Payment, PaymentStatus, PaymentStateMachine, Mandate, CollectRequest, Vpa, UpiPayload, ...
  repository/    PaymentRepository, MandateRepository, CollectRepository, ... (interfaces)
  usecase/       one class per use case (PayViaUpiIntent, ConfirmPayment, PollPaymentStatus, ...)
data/
  local/         Room: PayFlowDatabase, DAOs, entities, mappers
  psp/           PspClient interface, FakePsp (sandbox), HttpPspClient (OkHttp), DTOs
  upi/           UpiLinkBuilder, UpiResponseParser, UpiAppLauncher (interface + Android impl)
  qr/            BarcodeScanner / CameraController interfaces, ML Kit + CameraX implementations, UpiQrPayloadParser
  security/      CryptoBox (Keystore AES-GCM), PinManager, BiometricAuthenticator, DeviceBinding, TamperDetector, IntegrityChecker
  repository/    *RepositoryImpl
di/              Hilt modules (Database, Psp, Dispatchers, Security, Checkout)
ui/              navigation, theme, one package per screen (home, history, pay, scan, collect, mandates, lock, checkout)
```

Architecture: MVVM + Clean (`ui` / `domain` / `data`). Each ViewModel exposes one
`StateFlow<UiState>` (Loading / Empty / Error / Content) and one-off events via a `Channel`.
Dispatchers and `java.time.Clock` are injected. Money is `Long` paise.

## Sandbox PSP

`PspClient` (interface): `initiatePayment`, `getPaymentStatus`, `verifyVpa`, `listCollectRequests`,
`respondToCollect`, `createMandate`, `updateMandate`, `refund`. Two implementations:
- `FakePsp`: in-memory, injectable `Clock`, configurable latency / outcome script (pending → success,
  failed, timeout, 5xx, 429), deterministic by seed. Used by the app's debug build and all tests.
- `HttpPspClient`: OkHttp + kotlinx.serialization against a documented JSON REST shape. Exercised
  against MockWebServer (success, 4xx, 401, 429, 5xx, timeout, malformed JSON). Not used by default
  (there is no server); selectable with a `PSP_BASE_URL` local property.

## Test plan (JVM only: no device)

- Unit: state machine (exhaustive), money, parsers (UPI link, UPI response, QR payload), validators,
  mappers, use cases, ViewModels (Turbine + `StandardTestDispatcher`), backoff math (virtual time).
- Integration (Robolectric `@Config(sdk = [35])`): Room in-memory + DAO + repository + ViewModel;
  Compose UI flows via `createComposeRule`; `HttpPspClient` against MockWebServer; process-death
  simulation (recreate repository/ViewModel over the same in-memory DB and SavedStateHandle).
- Stress (`stress` package, `./gradlew stressTest` only): see below.
- Coverage: Kover, target ≥ 80 % lines on `domain` + `data`.
- Device-only (camera, real UPI apps, connectedAndroidTest): compile-only, "not run: no device".

## Stress scenarios per feature

| # | Scenario |
|---|---|
| 1 | 100k transactions inserted into Room; history query and filter timings; state machine 1,000 concurrent payments |
| 2 | 1,000 concurrent intent payments (fake launcher + FakePsp); 10k spoofed responses never yield success |
| 3 | 10k hostile / random QR payloads through the parser; 100 KB payloads |
| 4 | 10k VPA verifications with 429/5xx; 1,000 collect requests approve/decline/expire concurrently |
| 5 | 10k mandate executions against frequency/max-amount rules |
| 6 | same idempotency key 1,000x concurrently → exactly 1 payment; polling storm with random pending/timeout/5xx; backoff jitter histogram (no thundering herd) |
| 7 | 10k PIN verifications (PBKDF2 cost), 1,000 brute-force attempts hit lockout; 10k AES-GCM encrypt/decrypt |
| 8 | 1,000 concurrent CheckoutKit sessions through the PayFlow gateway adapter, every outcome recorded once in Room |

## Security review per feature

Every feature gets an entry in `docs/SECURITY_REVIEW.md` (attacks tried, what broke + severity,
fixed now, scope for improvement). Critical/High findings are fixed immediately with a regression test.
