# MASTER PROMPT: build `pocket-ledger` (paste this to your coding agent, or keep it as AGENTS.md)

You are a senior Rust and Android engineer. Build a complete entry for the **rivium + apalis pocket backend challenge** inside this repository. Work autonomously, but stop at each GATE to report evidence before continuing.

## 0. The product
**pocket-ledger**: an offline-first M-Pesa auto-ledger for Android.

1. An Android **NotificationListenerService** sees M-Pesa confirmation notifications.
2. Each one is enqueued as an **Apalis job** `ParseTransaction { raw_text, received_at }`.
3. A **Rust worker running under Rivium**, inside the app, parses it, categorises it, and stores it in **SQLite**.
4. The phone shows the result: a **home screen widget** (today's spend + jobs still queued) and a **notification** ("Spent Ksh350 at JAVA HOUSE").
5. Jobs are persisted, so queued and in-flight work survives the app being closed, killed, or the phone rebooting.

## 1. Scoring: optimise for this (100 pts)
- 40: works end to end (notification in, parsed row out, shown on phone)
- 30: Android feature is creative AND genuinely used (Notification Listener + widget, demoed with a real incoming notification, not a fake button)
- 15: jobs survive closing the app (prove it, don't claim it)
- 15: README clear (what, how to run, pinned versions)
- +10 bonus: tested on a real phone, with honest notes in `docs/DEVICE_TEST.md`

Rule: a small thing that works beats a big thing that doesn't. One job type only. No scope creep.

## 2. Hard requirements
- Rust code must use BOTH Rivium and Apalis. Rust 1.92+.
- Runs on a laptop with plain `cargo run` first, no phone needed.
- Then runs inside the Android app via Rivium's JNI example.
- Jobs persisted (SQLite-backed Apalis storage). Restart must not lose or duplicate work.
- README: what it does, how to run, exact versions used.
- Submission is a PR: code + README + short video/GIF at `media/demo.gif`.

## 3. Ground rules for you, the agent
1. **Never guess Rivium or Apalis APIs.** Both are young and changing. Before writing code that touches either: read the current README, docs, and `examples/` in their repos (use the repo links from the challenge), and run Rivium's own example first:
   `cargo run -p rivium-examples --example basic-service -- --root target/basic-example`
   Copy the patterns from their examples. If an API is unclear, write a 20-line spike to find out, then proceed. If still blocked, write the exact question (version, platform, minimal example) to `docs/RIVIUM_QUESTIONS.md` so it can be filed as an issue, and continue with the next unblocked task.
2. **Pin every dependency to an exact version** (`=x.y.z`) and record them in the README table. Pick versions at least a couple of weeks old where possible.
3. **Test first for the parser.** The parser is the part that decides correctness; fixtures drive it.
4. **Idempotency is mandatory.** The M-Pesa transaction code (e.g. `SJA1B2C3D4`) is the unique key. The same notification arriving twice (listener re-fire, retry, restart) must produce exactly one ledger row.
5. **Privacy.** No network calls anywhere. Fixtures are synthetic. Never commit real phone numbers, names, or balances. Add a note in the README that all data stays on the device.
6. Small commits with clear messages, one concern each. Keep `cargo fmt`, `cargo clippy -- -D warnings`, and `cargo test` green at every commit.
7. Don't add features not listed here. If you think something is a great idea, put it under "Future ideas" in the README and move on.
8. Be honest in docs. If something only compiles but wasn't run on a device, say so.

## 4. Architecture
```
pocket-ledger/
  crates/
    ledger-core/     pure Rust: parser + categoriser + domain types. No Android, no Rivium. Fully unit tested.
    ledger-worker/   Apalis worker + SQLite storage + Rivium service wrapper. Has a CLI for laptop use.
    ledger-jni/      cdylib exposing: start(root), stop(), enqueue(raw_text, ts), stats() -> JSON. Follows Rivium's JNI example.
  android/           Kotlin app: NotificationListenerService, widget (AppWidgetProvider), notification posting, JNI bridge.
  docs/              DESIGN.md (UI spec), DEVICE_TEST.md, RIVIUM_QUESTIONS.md
  media/             demo.gif
```
Data flow: `NotificationListenerService -> JNI enqueue -> Apalis queue (SQLite) -> worker runs ParseTransaction -> ledger table row -> JNI stats / callback -> widget refresh + result notification`.

Domain types (adjust to real needs):
- `Transaction { code, kind, amount_cents, counterparty, balance_cents, cost_cents, occurred_at, raw_hash }`
- `kind`: Sent, Received, Paybill, BuyGoods, Airtime, Withdraw
- Money in integer cents, never floats.
- Categoriser: simple keyword rules (e.g. KPLC -> Utilities, JAVA/restaurant -> Food, airtime -> Airtime). Keep it a small table in code.

Job semantics:
- Unparseable but M-Pesa-looking text: retry a limited number of times, then store as `unparsed` for review (don't loop forever, don't drop silently).
- Non-M-Pesa notifications must be filtered out before enqueue (check for the transaction-code + "Confirmed" shape) so the app never stores unrelated personal notifications.

## 5. Phases and GATES
**Phase 1: Parser (laptop).** Implement `ledger-core` against `crates/ledger-core/tests/fixtures/mpesa_messages.txt`. Write a test harness that reads each block's `# kind=... amount=...` header and asserts the parse. Handle spacing quirks (missing space after "Confirmed.", double spaces, case differences, thousands separators). Add property-style tests for money parsing.
GATE 1: `cargo test -p ledger-core` green; show the output.

**Phase 2: Apalis, plain Rust.** `ledger-worker` with Apalis + SQLite storage. CLI: `cargo run -p ledger-worker -- --root target/dev ingest "<message>"`, `... ingest-file fixtures`, `... stats`, `... run`. Show retry + dedup behaviour with a test.
GATE 2: ingest the fixtures, kill the process mid-run, restart, and show nothing is lost or duplicated.

**Phase 3: Rivium service.** Run the worker under Rivium's service lifecycle (clean start/stop) using the patterns from `basic-service`. `cargo run` still works on the laptop.
GATE 3: start/stop cleanly, repeatedly, with no leaked tasks.

**Phase 4: Android + JNI.** Follow Rivium's JNI example exactly. Build `ledger-jni` for Android targets (arm64-v8a at minimum, plus x86_64 for the emulator). Minimal Kotlin app that starts the service, enqueues a hardcoded message via a debug button, and shows stats. This button is only a debug aid.
GATE 4: debug enqueue works on an emulator; row visible in stats; app killed and reopened, data still there.

**Phase 5: The real Android feature.** `NotificationListenerService` (+ the settings screen deep-link for granting Notification Access), filtering to M-Pesa confirmations, enqueueing via JNI. Result notification on completion. Home screen widget: today's spend and queued job count, refreshed when jobs complete. Handle the process being dead when a notification arrives (the listener must be able to start the Rust service on demand).
GATE 5: send a test SMS or notification on the emulator/phone, watch the widget update without opening the app.

**Phase 6: Prove persistence and ship.** Scenario script in the README: enqueue several, force-stop the app mid-processing, reopen, show they complete exactly once. Record `media/demo.gif` (airplane-mode phone, real incoming notification, widget updating, force-stop and recover). Fill `docs/DEVICE_TEST.md` from a real phone. Finish the README (pinned versions table, laptop run, Android run, how persistence works, limitations). Open the PR.

## 6. Definition of done
- [ ] `cargo run` ingest works on a laptop with fixtures
- [ ] Rivium + Apalis both used in the worker
- [ ] SQLite-persisted jobs; dedup by transaction code
- [ ] Android app installs and runs the worker via JNI
- [ ] Notification Listener feeds jobs; widget + notification show results
- [ ] Force-stop/reopen test passes and is documented
- [ ] README complete with pinned versions; no TODOs left
- [ ] `media/demo.gif` recorded; `docs/DEVICE_TEST.md` filled from a real device
- [ ] fmt, clippy, tests all green; no real personal data committed

## 7. Report format at every GATE
Give: what you built, the exact commands you ran, the real output (not paraphrased), what's next, and any risks or open questions. Then wait for approval or continue if the gate is clearly green.

Start now with Phase 1. First action: read Rivium's repo and run its basic example, then begin the parser tests.

## 8. UI and design (read `docs/DESIGN.md` BEFORE touching any UI code)
- `docs/DESIGN.md` is the full spec: tokens, type, spacing, per-screen layout, states, copy, widget and notification rules. The design canvas linked inside it is the visual source of truth. Match it; do not invent a new look and do not use default Material colours.
- Do the UI last: only after Gate 4 (JNI works). Build against a `FakeLedgerRepository` first, then swap in the real JNI-backed repository without changing UI code.
- Order: tokens/theme -> shared components -> Home -> Activity -> result notification -> widgets. Commit after each, with a screenshot or @Preview.
- Show the user's money, never the machinery: queued work is a calm amber state ("N messages waiting to be read. Works offline."), never an error. Only unreadable messages ask for action, via the Review flow (Retry / Ignore), and nothing is ever silently dropped.
- Include the first-run Notification Access explainer screen (reads only M-Pesa confirmations; nothing leaves the phone).
- Widgets update from worker completion events and must work when the Rust service isn't running.
- Accessibility and 200% font scale are part of "done". Add screenshots to `media/` and reference them in the README.
- GATE 6 (design): show screenshots of Home, Activity (all three row states), the widgets, and a real result notification, captured from an emulator or phone.
