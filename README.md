# pocket-ledger

> Rivium + Apalis pocket backend challenge entry: an offline-first M-Pesa auto-ledger.

M-Pesa confirmation notifications are captured by an Android Notification Listener,
queued as Apalis jobs, parsed by a Rust worker running under Rivium inside the app,
stored in SQLite, and surfaced on a home screen widget plus a result notification.

Everything stays on the phone. The app has no `INTERNET` permission at all.

## What it does

1. `MpesaListener` (a `NotificationListenerService`) sees incoming notifications and
   keeps only M-Pesa-shaped confirmations (a 10-character transaction code plus
   "Confirmed").
2. Each one is enqueued as the single Apalis job type
   `ParseTransaction { raw_text, received_at }`, persisted in SQLite, keyed by the
   M-Pesa transaction code.
3. A Rust worker running under Rivium parses it, categorises it, and stores one row
   in the `ledger` table.
4. The phone shows the result: a home screen widget with today's spend and the number
   of jobs still queued, a notification such as `Spent Ksh350.00` (`JAVA HOUSE · Food`),
   and a today-first home screen listing every confirmation.

One job type, no scope creep. The test button in Diagnostics is a debug aid; the real
trigger is an incoming notification.

## Demo

![pocket-ledger demo](media/demo.gif)

Recorded on the real phone in **airplane mode**: an M-Pesa confirmation arrives while the
app is closed and the home screen widget goes from `Ksh940.00` to `Ksh1,030.00` without the
app ever being opened; the app is then force-stopped and reopened with all five ledger rows
still in place. The GIF's notification shade is deliberately trimmed: it contained the
phone owner's personal notifications, which do not belong in a repository.

## Architecture

```text
notification ──► MpesaListener (Kotlin, NotificationListenerService)
                     │  shape check in Kotlin, then JNI
                     ▼
                 Bridge.nativeEnqueue ──► ledger-jni (Rust cdylib)
                                             │ Apalis queue row (SQLite)
                                             ▼
                 Rivium service ──► ledger-worker (Rust)
                     │  ParseTransaction job
                     ▼
                 ledger-core parser + categoriser ──► ledger row
                     │
                     ├──► MpesaText polls stats ──► result notification
                     └──► LedgerWidget ──► widget refresh (today's spend, queue)
```

```text
crates/
  ledger-core/     pure Rust: parser, categoriser, domain types (zero dependencies)
  ledger-worker/   Apalis worker + SQLite ledger + Rivium App + laptop CLI
  ledger-jni/      cdylib: Rivium's five-method ABI + nativeEnqueue/nativeStats
android/           Kotlin app: Compose UI, listener, widget, result notification, JNI bridge
docs/              DEVICE_TEST.md
```

## Run on laptop (no phone)

Requires Rust 1.92+ (the workspace pins `rust-version = "1.92"`; built here with 1.99.1).

```sh
# 1. Parse fixtures and enqueue them. The non-M-Pesa block is filtered out.
cargo run -p ledger-worker -- --root target/dev ingest-file \
  crates/ledger-core/tests/fixtures/mpesa_messages.txt

# 2. Inspect the queue (JSON).
cargo run -p ledger-worker -- --root target/dev stats

# 3. Run the worker under Rivium until Ctrl-C.
cargo run -p ledger-worker -- run --root target/dev

# 4. Check the ledger is complete and the queue is empty.
cargo run -p ledger-worker -- --root target/dev stats
```

`stats` output after processing the fixtures:

```json
{
  "ledger_rows": 6,
  "today_spend_cents": 0,
  "today_income_cents": 0,
  "queued_jobs": 0,
  "running_jobs": 0,
  "failed_jobs": 0,
  "unparsed": 0,
  "latest": [ { "code": "SJA3B4C5D6", "kind": "paybill",
                "category": "utilities", "amount_cents": 120000,
                "counterparty": "KPLC PREPAID", "occurred_at": 1791223200 } ]
}
```

`today_spend_cents` is `0` for the fixtures on purpose: they are dated `5/10/26`, and
the figure only counts transactions whose message timestamp falls inside today's
East Africa Time window. **Send a test message** in Diagnostics generates a message dated
today, so the widget moves.

`run` is a Rivium process host, so it also accepts `check-config`, `default-config`,
`--config FILE` and `--set key=value`. `--delay-ms N` is a laptop-only debug knob that
keeps a job in flight for the persistence scenario below.

### Tests

```sh
cargo test --workspace     # 61 tests: parser fixtures, dedup, retries, orphan recovery
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all --check
```

## Run on Android

Toolchain used (all pinned, see the table below): JDK 17, Android SDK platform 36,
build-tools 36.0.0, NDK 27.2.12479018, `cargo-ndk` 4.1.2, Gradle 8.14.6.

```sh
# 1. Cross-compile the Rust library into the app (arm64 phone + x86_64 emulator).
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a -t x86_64 \
  -o android/app/src/main/jniLibs build -p ledger-jni --release

# 2. Build the APK.
cd android && ./gradlew assembleDebug

# 3. Install on a connected device.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle build needs `android/local.properties` with `sdk.dir=/path/to/Android/Sdk`
(or `ANDROID_HOME` exported). `local.properties` is not committed.

In the app:

1. Open **pocket-ledger** and tap **Enable** on the notification-access banner (or grant
   it over adb, see below).
2. Add the **pocket-ledger** widget to the home screen.
3. Watch confirmations land: the home screen lists them, the widget and the result
   notification update without opening the app.

The home screen is the consumer view: today's spend, today's income, and the recent
confirmations with category and time. The **i** button opens **Diagnostics**, which keeps
the technical surface out of the way: queue counters, **Send a test message**,
**Notification access settings**, and **Stop the background service** (so on-demand start
can be demonstrated). The screen polls `stats` only while it is visible (every 2.5 s, or
every 0.8 s while jobs are in flight).

Granting Notification Access and sending a notification over adb, exactly as tested:

```sh
# needs the explicit user id, otherwise the command silently does nothing
adb shell cmd notification allow_listener com.pocketledger/com.pocketledger.MpesaListener 0

# quote the whole device command; without the single quotes the body is truncated
# and the listener (correctly) rejects it as not M-Pesa shaped. A code is exactly
# 2 letters + 8 alphanumerics and must contain a digit, so both the synthetic
# SJA1B2C3D4 (used in the device test) and a real-looking SJ41H2K9LM pass.
adb shell "cmd notification post -t 'M-PESA' mpesa 'SJA1B2C3D4 Confirmed. Ksh350.00 paid to JAVA HOUSE. on 5/10/26 at 1:00 PM. New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00.'"
```

Verified on a Samsung SM-A546U1 running Android 16; see `docs/DEVICE_TEST.md` for the
full log, including the bugs found on the way.

## How jobs survive app restarts

Jobs live in SQLite (`Jobs` table, Apalis) inside the app's private storage, so they
exist before any work starts and outlive the process. Two things make restarts safe:

1. **The transaction code is the idempotency key.** Enqueueing the same confirmation
   twice keeps exactly one queue row (`UNIQUE(job_type, idempotency_key)`), and the
   ledger insert is `INSERT ... ON CONFLICT(code) DO NOTHING`, so at-least-once
   execution still produces exactly one ledger row.
2. **Startup recovery re-queues interrupted work.** Apalis' own orphan recovery relies
   on a `Workers` heartbeat table that this backend never populates, so `kill -9` would
   leave jobs reserved forever. On start-up the service sets every `Running`/`Queued`
   job for this queue back to `Pending` (single worker per device is the documented
   assumption), then declares readiness.

Measured on this machine with the laptop binary (a `kill -9`, not a graceful stop):

```
# 6 fixtures enqueued, worker running with --delay-ms 3000
queued after ingest: 6
=== after kill -9 (4.2s into a 3s-per-job run) ===
{'ledger_rows': 2, 'queued_jobs': 6, 'running_jobs': 0, 'failed_jobs': 0, 'unparsed': 0}

# restart
worker ready: db=target/dev/ledger.db recovered_jobs=6 delay_ms=0
worker stopped
=== final state ===
{'ledger_rows': 6, 'queued_jobs': 0, 'running_jobs': 0, 'failed_jobs': 0, 'unparsed': 0}
distinct codes: (6, 6)          # 6 codes, 6 rows: nothing lost, nothing duplicated
job statuses: [('Done', 6)]
```

Three Rivium start/stop cycles on the same database also exit `0` with no leaked
tasks, and the ledger is unchanged between them.

### Malformed messages

A message that looks like M-Pesa but does not match a known shape is written to the
`unparsed` table on every attempt and returned as an error, so Apalis retries it a
bounded number of times (3) and then stops. It is never dropped silently and never
retried forever. Non-M-Pesa notifications are filtered in Kotlin before they reach the
native library, and again in Rust before a job is written.

## Privacy

No network calls anywhere: the app declares no `INTERNET` permission, the Rust crates
add no HTTP client, and all fixtures are synthetic. Phone numbers are stripped during
parsing and never stored; a stalled message keeps its raw text only in the local
`unparsed` table for review. The database lives in the app's private storage.

## Limitations (honest status)

- Fixtures are synthetic; the parser follows the message bodies they contain. Real
  M-Pesa wording varies slightly by release, and the fixture headers themselves had one
  internal disagreement (withdraw), which is documented rather than hidden in
  `crates/ledger-core/tests/fixtures.rs`. Transaction codes are accepted as two leading
  letters plus eight alphanumerics containing at least one digit (so `SJ41H2K9LM` and
  the synthetic `SJA1B2C3D4` both parse, while `WITHDRAWAL` does not).
- The message timestamp is interpreted as East Africa Time (UTC+3) because it has no
  zone in the text; messages from another zone would land on the wrong day.
- Startup recovery assumes one worker process per device. Running the laptop CLI
  against the same database file while the app runs is not supported.
- `today_spend_cents` counts amount plus transaction cost, excluding money received.
  It is a single-day figure, not a budget.
- The Android result notification is posted by Kotlin polling `stats` for up to 5
  seconds after enqueue, not by a native callback; a native completion callback is the
  obvious next step.
- The 0.2.0 UI depends on AndroidX Compose (BOM `2026.05.01`, i.e. Compose 1.11.x) and
  `activity-compose` 1.12.4. Those are UI libraries only; the app still declares no
  `INTERNET` permission, so nothing can phone home even in principle.
- No release signing, no CI, no Play packaging: this is a challenge entry, not a store
  app.

## Future ideas

- Native completion callback from the worker to Kotlin instead of polling.
- Monthly summary screen and a per-category breakdown.
- Re-process the `unparsed` table from a debug screen after the parser improves.
- Export to CSV through the system file picker (still no network).
- Re-record `media/demo.gif` with the 0.2.0 home screen (the current GIF shows 0.1.0).

## Versions used (pinned)

| Thing | Version |
|---|---|
| Rust | 1.99.1 (workspace MSRV 1.92) |
| Rivium (`rivium`, `rivium-host`, `rivium-jni`) | 0.2.0 |
| Apalis | 1.0.0 |
| Apalis SQLite storage (`apalis-sqlite`) | 1.0.0 |
| `sqlx` (SQLite driver shared with Apalis) | 0.9.0 |
| `tokio` | 1.54.1 |
| `serde` / `serde_json` | 1.0.229 / 1.0.154 |
| `jni` | 0.22.4 |
| `cargo-ndk` | 4.1.2 |
| JDK (Temurin) | 17.0.21+9 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Gradle | 8.14.6 |
| Android compileSdk / targetSdk / minSdk | 36 / 35 / 26 |
| Android build-tools / NDK / platform-tools | 36.0.0 / 27.2.12479018 / 37.0.1 |
| AndroidX Compose BOM | 2026.09.00 |
| `androidx.activity:activity-compose` | 1.13.0 |
| Test device | Samsung SM-A546U1, Android 16 (SDK 36), arm64-v8a |

## Real phone test notes (bonus)

See `docs/DEVICE_TEST.md` for the device model, Android version, what worked, what
broke and the exact errors observed.
