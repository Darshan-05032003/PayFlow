# Load / stress report

All numbers from `./gradlew stressTest` on the build laptop (15 GB RAM, Gradle JVM `-Xmx2g`, test JVM `-Xmx1g`,
Robolectric SDK 35 with its native SQLite). Stress tests live in `app/src/test/.../stress` and never run in the normal suite.

## Feature: Home, transaction history (Room) and payment state model
- Scenario and scale:
  - `HistoryRoomStressTest.hundredThousandTransactionsInRoom`: 100,000 payments inserted in 100 batches of 1,000, then the
    real DAO queries the screens use: recent 5, status filter, text search, SQL summary, full list, one 50-row page.
  - `HistoryRoomStressTest.thousandConcurrentPaymentsThroughTheStateMachine`: 1,000 coroutines on `Dispatchers.IO`, each
    creating a payment and driving it CREATED → PENDING → SUCCESS (90 %) or FAILED (10 %) through the repository's
    compare-and-set.
- Results (numbers):
  - 100k insert: **550 ms** (batched). recent-5: **2 ms**. filter FAILED (16,667 rows): **93 ms**. text search over 100k:
    **55 ms**. SQL summary over 100k: **26 ms**. Full un-paged list of 100k rows: **1,807 ms**. One 50-row page: **26 ms**.
    Heap delta after everything: **11 MB**.
  - 1,000 concurrent payments (3 CAS writes each): **1,069 ms** total, 900 success / 100 failed / 0 pending, no lost update.
- What broke:
  - Nothing failed, but materialising the full 100k-row list takes ~1.8 s and would allocate 100k `Payment` objects on the
    UI path: with a 100k history the un-paged `observeHistory` Flow is the bottleneck.
- Failure flow (step by step):
  1. User with a very large history opens History with filter ALL.
  2. `observeHistory` runs `SELECT *` without LIMIT and maps every row.
  3. ~1.8 s of work on IO plus a large list diff in `LazyColumn` → visible jank, higher memory.
- Fix applied / recommendation:
  - Indices on `created_at`, `status`, `txn_ref`, `idempotency_key` are in place (filter and summary are ≤ 100 ms at 100k).
  - `PaymentDao.historyPage(limit, offset)` exists (26 ms per page); recommendation: move the History screen to Paging 3 /
    page windows if real users approach tens of thousands of transactions. The home screen only ever loads 5 rows + the SQL
    summary, so it is unaffected.

## Feature: UPI intent flow with PSP status confirmation
- Scenario and scale:
  - `UpiIntentStressTest.thousandConcurrentIntentPaymentsAgainstAFlakyPsp`: 1,000 coroutines each run
    `PayViaUpiIntentUseCase` then `ConfirmPaymentUseCase` (up to 8 confirms) against `FakePsp` with 20 % failed outcomes,
    10 % timeouts, 5 % 5xx and one pending poll, then every stored payment is compared with the PSP's truth.
  - `tenThousandSpoofedAppResponsesNeverYieldSuccess`: one payment, 10,000 concurrent spoofed app responses (5 variants).
  - `pspMismatchStormNeverConfirms`: 2,000 concurrent confirms while the PSP reports success for wrong amounts / payees.
- Results (numbers):
  - 1,000 intent payments: **418 ms** total, 1,916 PSP status calls, outcomes `Success=659, Failed=166, initiate-failed=175`,
    **0 disagreements** between Room and the PSP.
  - 10,000 spoofs: **657 ms**, final status FAILED, **9,859 PSP status calls**.
  - 2,000 mismatch confirms: 2,000 `Mismatch` results, payment still PENDING, never confirmed.
- What broke:
  - Nothing incorrect, but the spoof storm shows that every `confirm` hits the PSP once until the payment is terminal: a
    hostile or buggy caller can turn the confirm path into a PSP status-call storm (9,859 calls for one payment).
- Failure flow (step by step):
  1. The payment is PENDING (PSP has not settled).
  2. The UI or an attacker triggers `confirm` repeatedly.
  3. Each call performs a PSP status request; there is no client-side rate limit for a single payment.
- Fix applied / recommendation:
  - Once a payment is terminal, `confirm` returns without calling the PSP (that is why 141 of the 10,000 calls were free).
  - Feature 6 adds a single polling loop per payment with exponential backoff + jitter and `Retry-After` support, which is the
    right place for a per-payment in-flight guard (one status request at a time).

## Feature: QR scan-to-pay (CameraX + ML Kit)
- Scenario and scale:
  - `QrParserStressTest.tenThousandFuzzedPayloadsNeverThrow`: 10,000 random payloads mixing valid/invalid schemes, keys, bidi, NUL
    and bad escapes.
  - `hundredKilobytePayloadsAreRejectedFast`: 1,000 × 100 KB payloads and 1,000 × ~4 KB payloads (just under the cap).
  - `thousandConcurrentParses`: 1,000 parses on `Dispatchers.Default`.
- Results (numbers):
  - 10,000 fuzzed payloads: **75 ms**, 0 exceptions (`NOT_UPI=3361, UNSUPPORTED=3102, MISSING_VPA=2562, INVALID_VPA=971,
    INVALID_CURRENCY=1, valid=3`).
  - 1,000 × 100 KB: **1 ms** (length check rejects before parsing). 1,000 × 4 KB near-limit payloads: **56 ms**.
  - 1,000 concurrent parses: **39 ms**.
- What broke: nothing. The parser is stateless and allocation-light; camera frame rate (typically 30 fps) is the real bound
  and `CameraXController` analyses one frame at a time with `STRATEGY_KEEP_ONLY_LATEST`, so decoding never queues stale frames.
- Failure flow (step by step): a hostile QR that is large and valid-looking (4 KB of `tn=`) costs ~56 µs per frame; even at
  30 fps that is < 2 ms/s of CPU. No failure path found.
- Fix applied / recommendation: keep the 4 KB cap; on a device, verify ML Kit decode latency per frame (not run: no device).

## Feature: VPA validation (PSP verify) and collect requests
- Scenario and scale:
  - `CollectAndVerifyStressTest.tenThousandVpaVerificationsUnderRateLimiting`: 10,000 concurrent verifications (25 % malformed,
    25 % unknown) against a PSP returning 20 % 429 and 10 % 5xx.
  - `thousandCollectRequestsAnsweredConcurrently`: 1,000 incoming requests (10 % already expired), answered concurrently
    (2/3 approve, 1/3 decline) against a PSP with 5 % timeouts; then every request is cross-checked against the payments table.
  - `oneRequestAnsweredTwoHundredTimesConcurrentlyCreatesOnePayment`: 200 racing answers for one request.
- Results (numbers):
  - 10,000 verifications: **58 ms**; `InvalidFormat=2500` never hit the PSP, 7,500 PSP calls: `Verified=3473, NotFound=1733,
    Unavailable=2294` (429/5xx surfaced as "could not verify", never as "not found").
  - 1,000 collect answers: **~0.9–1.0 s** (3 Room writes + 2 PSP calls each); `Approved=572 (547 SUCCESS + 25 PENDING),
    Declined=286, Expired=100, Unavailable=42`; 0 approved requests without a payment, 0 duplicate payments.
  - 200 racing answers: 1 answered, 199 `AlreadyAnswered`, exactly 1 payment row.
- What broke:
  - The first version of the test expected `approved == successful payments`; 25 approvals had been accepted by the PSP but
    the follow-up status check timed out. The code was right (payment PENDING, not FAILED), the expectation was wrong; the
    test now asserts `approved == success + pending`.
  - Verification load scales with typing pauses: without the debounce, 10 keystrokes = 10 PSP lookups.
- Failure flow (step by step): user approves → payment CREATED → PSP accepts → `Initiate` → status check times out →
  payment stays PENDING with the request APPROVED → feature 6 polling resolves it later.
- Fix applied / recommendation: debounce + final-value-only verification (applied); a small per-session LRU of verified
  names (recommended); polling with backoff for PENDING collect payments (feature 6).

## Feature: AutoPay mandates
- Scenario and scale:
  - `MandateStressTest.tenThousandDebitAttemptsNeverBreakTheRules`: 100 mandates (all six frequencies, caps ₹10–₹1,000), 10,000
    concurrent debit attempts (every 7th above the cap), then every mandate's counter is cross-checked with the payments table.
  - `thousandRacingLifecycleActionsStayConsistent`: 1,000 concurrent PAUSE/RESUME/REVOKE actions on one mandate, local vs PSP state.
- Results (numbers):
  - 10,000 debit attempts: **658 ms**; `Executed=100, Rejected.AmountExceedsMax=1429, Rejected.AlreadyExecutedThisPeriod=8471`;
    100 SUCCESS payments, each mandate executed exactly once (fixed clock → one period), counters equal to payments.
  - 1,000 racing actions: **131 ms** with the per-mandate mutex (28 ms before the fix, but inconsistent); final state REVOKED both locally and at the PSP.
- What broke: the lifecycle race (see the security entry): PSP `REVOKED`, local `PAUSED`.
- Failure flow (step by step):
  1. Coroutine A reads ACTIVE, decides PAUSE; coroutine B reads ACTIVE, decides REVOKE.
  2. PSP applies A (PAUSED) then B (REVOKED): both legal at the PSP.
  3. A's local CAS (ACTIVE→PAUSED) wins; B's CAS (ACTIVE→REVOKED) fails because the row is PAUSED.
  4. Device shows PAUSED; the PSP has REVOKED; the user can "resume" a revoked mandate locally (PSP would answer 409).
- Fix applied / recommendation: per-mandate `Mutex` around read → PSP → write, and the status returned by the PSP is what gets
  written. Recommendation for a real backend: version numbers on mandate updates (optimistic concurrency) and a periodic
  reconciliation pull.

## Feature: status polling, exponential-backoff retries and idempotency keys
- Scenario and scale:
  - `sameIdempotencyKeyThousandTimesConcurrentlyCreatesExactlyOnePayment`: 1,000 coroutines submit the same request with one key
    against the real Room unique index.
  - `pollingStormAgainstAFlakyPsp`: 1,000 payments registered against a PSP with 15 % timeouts, 10 % 5xx, 5 % 429, 20 % declines
    and 2 pending polls each; then 3,000 racing pollers (3 per payment) with a real-time backoff policy (2 ms → 20 ms, full jitter, 12 attempts).
  - `jitterPreventsAThunderingHerd`: 10,000 retry delays for attempt 3 bucketed into 10 ms windows, with and without jitter.
- Results (numbers):
  - Same key × 1,000: **76 ms**, **1 payment row, 1 PSP initiate call**, all 1,000 callers got the same payment id.
  - Polling storm: 734 of 1,000 registrations succeeded against the flaky PSP (the rest failed at initiate: no retries in this
    run); **3,000 pollers finished in 246 ms with 3,105 PSP status calls** (≈ 4.2 per payment, not 3 × 12), final state
    589 success / 411 failed / **0 pending, 0 stuck in CREATED**.
  - Jitter: peak retries in one 10 ms window **10,000 → 45** with full jitter.
- What broke:
  - Nothing failed, but the run showed that *registration* errors were not retried (266 of 1,000 initiates failed on transient
    errors). `PayViaUpiIntentUseCase` now wraps the PSP registration in `RetryExecutor(BackoffPolicy.INITIATE)` (0.5 s → 4 s,
    4 attempts); the registration is safe to retry because the PSP dedupes by `Idempotency-Key` / `txnRef`
    (`IdempotencyTest.transient psp errors at registration are retried…`).
- Failure flow (step by step): outage → N clients fail at the same time → without jitter all retry in the same 10 ms window →
  the PSP sees the whole herd again → more 429s → repeat. With full jitter the same herd spreads over the whole backoff window.
- Fix applied / recommendation: full jitter by default (`BackoffPolicy.DEFAULT`), `Retry-After` honoured and capped,
  single-flight polling. Recommendation: cap concurrent reconciliation (batch of 200) — already applied in `pendingOlderThan`.

## Feature: Security
- Scenario and scale:
  - `SecurityStressTest.bruteForceHitsLockoutAndStopsHashing`: 1,000 sequential PIN guesses against production PBKDF2 parameters.
  - `tenThousandAesGcmRoundTrips`: 10,000 concurrent 1 KB encrypt+decrypt operations.
  - `hundredConcurrentPinVerificationsAreConsistent`: 100 racing verifications (half right, half wrong) with 10k iterations.
- Results (numbers):
  - One PBKDF2-HmacSHA256 verification at **150,000 iterations: ~97 ms** on the build laptop (slower on phones: deliberate).
    1,000 guesses finished in **332 ms** total: 4 hashed attempts, **997 refused by the lockout without hashing**.
  - 10,000 AES-GCM round trips of 1 KB: **120 ms** (**0.012 ms per operation**).
  - 100 concurrent verifications: 50 ok / 50 wrong, consistent counters (mutex-serialised).
- What broke: nothing. The brute-force test confirms the lockout short-circuits before any CPU-heavy work, so an attacker
  cannot use the PIN check as a DoS vector either.
- Failure flow (step by step): attacker scripts PIN entry → 5 attempts cost ~0.5 s of hashing → lockout 30 s → each further
  attempt costs a store read and is refused → with doubling lockouts the 6-digit space (1,000,000) would take years.
- Fix applied / recommendation: keep 150k iterations (OWASP ≥ 100k); on low-end devices consider measuring and tuning the
  iteration count at setup time (store it in the record, as already done).

## Feature: CheckoutKit integration
- Scenario and scale: `CheckoutKitStressTest.thousandConcurrentCheckoutSessions`: 1,000 concurrent `CheckoutKit.createSession(...).submit(Upi)`
  calls through `PayFlowPaymentGateway` against `FakePsp` (20 % declines, 5 % timeouts, 5 % 5xx, one pending poll each), every
  `CheckoutResult` recorded into Room through `RecordCheckoutResultUseCase`.
- Results (numbers): **915 ms** for 1,000 sessions; outcomes `Success=798, Failed=202`, **1,000 rows recorded, 1,000 distinct
  order references, 0 pending, 0 lost**; PSP calls: 1,225 initiates (SDK retries of transient failures) and 2,208 status calls
  (one pending poll + confirmation per session). Every SUCCESS row matched a PSP order captured for the same amount.
- What broke: nothing. The SDK's retry policy plus the adapter's key→order mapping mean retried charges never created a second order
  (1,225 initiate calls, 1,000 orders).
- Failure flow (step by step): transient 5xx on `charge` → SDK retries with the same idempotency key → adapter reuses the same
  `txnRef` → PSP dedupes → status call confirms → one order, one history row.
- Fix applied / recommendation: none needed; for production, wire the SDK `Pending` outcome straight into the feature 6 poller
  rather than waiting for the 30 s reconciler window.

## Feature: Real UPI apps (UPI-apps mode) and incoming links
- Scenario and scale:
  - `UpiAppModeStressTest.tenThousandSpoofedAppResponsesForOtherReferencesNeverSettleThePayment`: one pending UPI-app payment,
    10,000 concurrent app responses (five hostile variants: other `txnRef` in mixed case, FAILURE for another ref, SUBMITTED,
    `%00` / bidi / 5 KB padding) through `RecordUpiAppResultUseCase` against Room.
  - `aThousandReplayedDeliveriesOfOneSuccessSettleExactlyOnce`: 1,000 concurrent settlements of one payment (app SUCCESS, app
    FAILURE, user "I paid", user "not paid" in equal shares).
  - `tenThousandHostileIncomingLinksNeverThrowAndOnlyValidOnesAreOffered`: 10,000 fuzzed `upi://` strings through `IncomingUpiLinkStore`.
- Results (numbers):
  - 10,000 spoofs: **757 ms**, `Mismatch=6000, Unknown=4000`, payment still PENDING, zero settlements, zero PSP calls.
  - 1,000 racing settlements: **289 ms**, exactly **1** settlement (`Marked=1`), 500 `AlreadySettled`, 499 `NotAllowed`.
  - 10,000 hostile links: **72 ms**, 384 valid links offered and consumed, 0 exceptions, nothing left pending.
- What broke: nothing. The compare-and-set on `payments.status` is what makes "exactly one" hold whether the writer is the UPI
  app's response or the user's tap.
- Failure flow (step by step): user pays in GPay → returns → PayFlow records the response (one CAS) → a stale second delivery or a
  tap on "I paid" after the fact hits a settled row → refused, status unchanged.
- Fix applied / recommendation: none needed. On a device the cost is dominated by the app switch, not by PayFlow.

## Feature: Scan a UPI QR from a gallery image
- Scenario and scale: `GalleryQrStressTest.thousandGalleryDecodes`: 1,000 generated images through the zxing fallback decoder and the
  result mapping: 400 valid UPI QRs at 2–5 px per module, 200 URL QRs, 200 hostile `javascript:` UPI QRs with up to 800 chars of `tn`,
  200 random-noise images (320 × 320).
- Results (numbers): **1,351 ms** total (**1.35 ms per image**), `Found=400, NotUpi=400, NoQrFound=200`, 0 `Unreadable`, 0 exceptions;
  heap peak **+33 MB** over the run.
- What broke: nothing. On a device ML Kit runs first and zxing only when ML Kit finds nothing.
- Failure flow (step by step): user picks a 50 MP photo → bounds read (no pixels) → decoded at 1/4 scale (≈ 2,040 × 1,530, ~12 MB ARGB)
  → ML Kit → (zxing) → result; peak memory is bounded by the 2,048 px cap, not by the photo.
- Fix applied / recommendation: keep the cap; the decode runs on the IO dispatcher so the UI shows a progress state, not jank.

## UI overhaul baseline (before)
**Emulator, software rendering, not a mid-range phone.** These numbers are only for comparing before and after on this setup. They say nothing about real-device speed.

- Commit: `9c48cc2` (the current UI, before any overhaul screen work).
- Device: `Pixel_7` AVD, API 37, x86_64 (`sdk_gphone16k_x86_64`), 4 vCPUs, `-gpu swiftshader_indirect`. Guest RAM is 4 GB: the emulator raises `-memory 2048` to 4096 MB. Animation scales 1.
- Host: 15 GB RAM with other applications open, so runs are noisy. Treat small differences as no change.
- Build type: `benchmark` (`initWith(release)`: R8 and resource shrinking on, non-debuggable, debug-signed, profileable). No Baseline Profile is in the APK yet.
- Method: Macrobenchmark 1.5.0 with `androidx.benchmark.suppressErrors=EMULATOR`. `StartupTimingMetric` over 10 cold starts; `FrameTimingMetric` over 5 iterations of 3 flings down and 3 up, with a new process each iteration.
- Data: 200 payments (settled statuses only) inserted through `PaymentRepository`. Home shows at most 5 recent rows, so its scroll is short by design.
- Scenarios: `homeScroll*` flings the home screen; `historyScroll*` opens History and flings the list.
- Reading the tables: CPU is `frameDurationCpuMs`. Overrun is `frameOverrunMs`, how far a frame missed its deadline (negative means early). Jank % is the share of frames with an overrun above 0. Software rendering pushes nearly every frame over budget here, so compare the frame times rather than expecting low jank.
- `NoCompilation` is the fair before/after comparison. `Partial` compiles from a 3-iteration warm-up and will also use a Baseline Profile once one ships.
- **Not run:** `homeScrollPartial`, `historyScrollPartial`. Sessions on this machine were restarting every 10 to 60 minutes, so the `NoCompilation` tests came first. Compare only tests that exist on both sides.

| Cold start (time to initial display, ms) | min | median | max | iterations |
|---|---|---|---|---|
| startupNoCompilation | 778 | 876 | 1042 | 10 |
| startupPartial | 514 | 573 | 736 | 10 |

| Scroll | CPU P50 ms | CPU P90 ms | CPU P99 ms | Overrun P50 ms | Overrun P90 ms | Overrun P99 ms | Jank % | frames |
|---|---|---|---|---|---|---|---|---|
| historyScrollNoCompilation | 29.7 | 52.9 | 103.5 | 20.6 | 55.4 | 138.9 | 97.3 | 2391 |
| homeScrollNoCompilation | 55.3 | 95.6 | 258.8 | 60.3 | 115.8 | 314.6 | 99.3 | 613 |

Release APK size: 25,265,073 bytes (24.1 MiB), from `./gradlew assembleRelease` (unsigned).

Seed data is benchmark-only. `aapt2 dump xmltree` shows `SeedDataReceiver` in the `benchmark` manifest and not in the debug or release manifests, and the release dex has no reference to it.

Reproduce (run on an emulator, and set the serial explicitly so no other connected device is used):

    ./gradlew --stop
    emulator -avd Pixel_7 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -memory 2048
    ANDROID_SERIAL=emulator-5554 scripts/run_benchmarks.sh /path/to/results
    ./gradlew assembleRelease

The script installs a fresh copy per test and skips tests that already have a result in that directory, so it can be re-run after an interruption. `BENCHMARK_TESTS="ScrollBenchmark#homeScrollNoCompilation"` limits it to named tests.

To keep these benchmarks working as the UI changes:

- `PayFlowTestTags.HOME_HISTORY` (`payflow:home:history`) stays on a control visible on the first screen that opens History.
- `PayFlowTestTags.PAYMENT_LIST` (`payflow:history:list`) stays on the history list.
- The `EXPOSE_TEST_TAGS` wrapper in `MainActivity` stays at the root of the composition, above the lock and navigation.
- Home stays vertically scrollable with seeded data: the benchmark picks the tallest scrollable node, whichever component draws it.
- No new first-launch screen may block a fresh install. If one is added, `SeedDataReceiver` must mark it done.
- `SeedDataReceiver` keeps writing through `PaymentRepository`; update it if the `Payment` model changes.


## UI Feature: Design system and catalog
- Scenario and scale (all from `DesignSystemStressTest`, JVM timings, useful for comparison but not phone frame times):
  - 1,000 clicks in one tick, 1,000 injected taps with and without a loading state.
  - 10,000 list rows with avatar, amount and title, with long scroll jumps.
  - 1,000 interrupting odometer updates at the maximum amount, with and without reduced motion.
  - 120 frames of an odometer, 5 status pills and a nav pill all animating.
  - 1,000,000 calls to the pure debounce, nav and roll rules; 100,000 toasts and effects.
- Results:

| Scenario | Result |
|---|---|
| 1,000 clicks inside the debounce window | 1 action, 1 haptic, under 1 ms |
| 1,000 injected taps, loading on accept | 1 action, 249 ms |
| 1,000 injected taps, no loading state, 30 simulated seconds | 59 actions (limit 61) |
| 10,000 rows | 13 composed at first, 91 after 6 long jumps, 391 ms for the jumps |
| 10,000 avatar initials and tones | 2 ms |
| 1,000 interrupting updates at ₹99,99,99,999.99 | 2,135 ms in total, ends on the exact value |
| Same load, reduced motion | 149 ms |
| 120 busy animation frames | 0 recomposition cycles |
| 1,000,000 pure rule calls | 20 ms |
| 100,000 toasts | 13 ms, 4 kept |
| 100,000 effects into a 16-slot channel | 24 ms |

- Release APK: 25,265,073 → 25,523,345 bytes, +258,272 bytes (+1.0 %). The two fonts take 239,440 bytes compressed. Subsetting saved about 117 KB compressed. This is almost all fonts for now: R8 removes components no screen uses yet, so code size grows as screens adopt them.
- What broke: the first version of the 1,000-tap test expected 1 action from injected taps. Those taps move the simulated clock about 30ms each, so they pass the 500ms window 59 times. That is correct for a debounce: over 30 seconds of tapping, the loading state is what guarantees one action, so the test now covers each case separately.
- Failure flow:
  1. A screen relies on the debounce alone for a payment.
  2. A user keeps tapping past 500ms.
  3. A second click is accepted.
  4. Only the ViewModel's idempotency stops a duplicate.
- Fix applied / recommendation: money actions must set `loading = true` on the first accepted click. The debounce is a second layer, not the guard. The no-per-frame-recomposition checks have a control test showing the counter does move when a value is read in composition.
- Not measured: real frame times and jank. Macrobenchmark needs the device run in the QA step, and these components aren't on a screen yet. 3× animator speed isn't a separate case: Compose scales durations itself and `ThemeTest` confirms motion stays full; scale 0 resolves to reduced motion.

## UI Feature: App shell
- Scenario and scale: `ShellStressTest`, `LockTapStressTest` (run with `stressTest`), JVM under Robolectric. Reduced motion in the gate and tab tests, so they measure the shell rather than animation time.
- Results:

| Scenario | Result |
|---|---|
| 1,000 unlock and relock cycles through `AppGate` | 8,003 ms, 8.0 ms per cycle, heap growth -26 KB, app composed exactly 1,000 times, `FLAG_SECURE` set after every relock |
| 1,000 tab switches on a NavHost | 694 ms, back stack depth 2 at the end (the tab and Home) |
| 1,000 rapid taps on one PIN key | 1 attempt burned, 1 rejection |
| 1,000 PIN unlock and lock cycles (1,000 PBKDF2 iterations each) | 539 ms, state correct at every step |
| 200,000 digit and backspace calls with nobody collecting haptics | Channel stays bounded |
| 1,000,000 route and transition lookups | 34 ms, 34 ns each |
| 10,000 bar selections | 2 ms |
| 10,000 payment rows, 150 list jumps | 1,153 ms without shared bounds, 1,191 ms with them on every row (+3%) |
| Reveal, 10 frames | At most 2 recomposition cycles, app content not recomposed (`AppGateTest`) |
| Dot fill, 8 frames | At most 1 recomposition cycle (`LockScreenMotionTest`) |

- What broke: nothing at this scale. One test artifact: `ActivityScenario` finds its activity by comparing intents, so rewriting a launch intent made `close()` wait 45 s per test. Those tests now use `Robolectric.buildActivity`, and `onNewIntent` no longer calls `setIntent`, which wasn't needed.
- Failure flow: none found.
- Fix applied / recommendation:
  - A tab's scroll to top jumps instead of animating past 30 rows, so it doesn't compose every row on the way.
  - The first frame of a reveal also composes the app. On a slow phone that frame could be long, and only a device run will show it.
- Release APK: 25,523,345 → 25,591,195 bytes, +67,850 bytes (+0.3 %), with core-splashscreen and the shell.
- Not measured:
  - Real frame times, jank and cold start with the splash hold. Holding the first frame for DataStore and the lock can add to time to initial display, so compare `startupNoCompilation` against the 876 ms baseline in the QA step.
  - 3x animator speed isn't a separate case: Compose scales durations, and the logic doesn't depend on them.
- Device run: not run. The emulator booted in 19 s under its 4 GB memory cap, and the cap stopped it about 4 s later (4.16 GB resident, software rendering), before the app could install. The rest of the machine was not affected.
