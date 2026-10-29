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
