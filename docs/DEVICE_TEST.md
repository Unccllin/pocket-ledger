# Real device test log (bonus +10)

Device and OS as reported by `adb shell getprop`:

| Property | Value |
|---|---|
| Model | Samsung SM-A546U1 (Galaxy A54 5G) |
| Android | 16 (SDK 36) |
| ABI | arm64-v8a |
| Security patch | 2026-10-05 |
| Display | 1080x2340 |
| adb | platform-tools 37.0.1 (Windows adb server, driven from WSL), device `RZCWB07SZZE` |

Build and install used the documented commands:

```sh
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p ledger-jni --release
cd android && ./gradlew assembleDebug          # BUILD SUCCESSFUL, 21.4 MB APK, both ABIs
adb install -r app-debug.apk                    # Success
```

## What worked

1. **Service starts in-process under Rivium.** Opening the app shows
   `Rivium service running (pocket-ledger 0.1.0)` and `ledger.db` (WAL) appears in
   `files/ledger/`. No crash, no ANR.
2. **Debug enqueue (GATE 4).** Tapping the debug button produced exactly one row:
   `SJA1309937 | buygoods | food | 35000 | JAVA HOUSE`, and the job finished `Done`.
3. **Persistence (GATE 4).** `adb shell am force-stop com.pocketledger`, then relaunch: the
   row was still there and `ledger_rows: 1`.
4. **Real incoming notification (GATE 5).** With the app in the background
   (launcher focused the whole time), a notification posted with `adb shell cmd notification
   post` was picked up by the listener:
   ```text
   listener: notification from com.android.shell: M-PESA SJA8P9Q0R1 Confirmed. Ksh120.00 sent to KPLC PREPAID…
   enqueue response: {"outcome":"enqueued","code":"SJA8P9Q0R1"}
   +0.5s: notification from com.pocketledger: Spent Ksh120.00 at KPLC PREPAID Category: utilities
   ```
   Row written: `SJA8P9Q0R1 | paybill | utilities | 12000 | KPLC PREPAID`.
5. **Widget updates without opening the app.** Home screen widget went
   `Ksh470.00` → `Ksh820.00` → `Ksh1,030.00` across notifications, `Queue: empty` each time.
6. **On-demand start of the Rust service.** With the Rust service deliberately stopped
   (`nativeStop` via the debug button, status `Idle`), a notification still worked:
   `nativeStart root=/data/user/0/com.pocketledger/files/ledger → returned 0` in ~125 ms,
   then the job completed. So the listener can bring the backend up by itself.
7. **Airplane mode.** The whole demo ran in airplane mode; the app also declares no
   `INTERNET` permission at all.

## What broke (and the fixes)

These are mine, not Rivium's, and each was reproduced before being fixed.

| Symptom | Cause | Fix |
|---|---|---|
| `stats={"error":"cannot open ledger: io: File exists (os error 17)"}` — both JNI helpers dead | The app recorded the *database file* where `ledger-jni` expected the *root directory*, then appended `/ledger.db` again and called `create_dir_all()` on the file | Store the working directory in the hook; reproduce and verify on a desktop JVM first (`tools` snippet in the README's Android section) |
| Job stayed `Pending` >2 s after enqueue | Backend's default poll cadence is slow for a notification UX | `SqliteStorage::poll_with_interval(250ms)` |
| Status/stats text invisible (white on white) | No explicit text colours in the layout on this Samsung theme | Explicit `textColor` in the layout |
| Result notification never appeared | The message body never reached the listener: `cmd notification post -S bigtext` delivered only the title and a truncated token; and one test code was 11 characters, so the shape filter (correctly) rejected it | Post without `-S bigtext` and quote the whole device command; codes must be exactly 3 letters + 7 alphanumerics |
| `cmd notification allow_listener …` silently did nothing | Needs an explicit user id | `adb shell cmd notification allow_listener com.pocketledger/com.pocketledger.MpesaListener 0` |

`screenrecord` on this Samsung build also stops at ~20 s, so the demo GIF is two
concatenated segments rather than one take.

## Notes for the Rivium maintainers

- The embedded host (`rivium-host` with `embedded`, driven by `rivium_jni::export!`) worked
  as documented on Android 16 arm64; start returned `0` in ~125 ms including SQLite
  migrations.
- Adding extra natives to the same class works, but they must use jni 0.22's
  `EnvUnowned`/`with_env`/`resolve` shape; the older `JNIEnv`-by-value signature triggers
  `improper_ctypes_definitions` and is not FFI-safe.
- A documented way for extra native entry points to learn the `--root` that `nativeStart`
  received would help: this project records it inside `App::services` and reads it back
  from the JNI helpers, which works but is application-side glue.
- Under the process host, calling `rivium_host::process::run` from inside a Tokio runtime
  panics (`Cannot start a runtime from within a runtime`). It is obvious in hindsight, but
  a sentence in the README about not wrapping `main` in `#[tokio::main]` would save a
  beginner the same 20 minutes.
- The Apalis SQLite backend used here never populates the `Workers` table entry that its own
  orphan-requeue query joins on, so `kill -9` recovery had to be implemented in
  application code. Worth documenting for anyone pairing Apalis with an embedded host.

## Version 0.2.0 (UI pass) — build-verified, not device-verified

The log above was recorded against 0.1.0. Version 0.2.0 replaces the debug-only
`LinearLayout` screen with a Compose home screen (today's spend and income, recent
confirmations) plus a Diagnostics screen, restyles the widget, and widens the M-Pesa
code shape. At the time of writing no device was attached, so the new screens have been
compiled and packaged into the debug APK but not looked at on the phone. The pipeline
(notification → JNI → Apalis → ledger → widget) is unchanged, so the device results
above still describe the backend and the listener. The next device session should:

1. install the 0.2.0 APK, confirm the home screen renders with existing rows,
2. confirm the widget shows the new `SPENT TODAY` card,
3. re-run the force-stop scenario and the airplane-mode demo recording.

## Not tested

- Incoming M-Pesa SMS from the real Safaricom M-Pesa shortcode (the phone has no M-Pesa
  SIM traffic in this setup); notifications were injected through `cmd notification post`,
  which goes through the same `NotificationListenerService` path as an app notification.
- Behaviour under Doze / aggressive OEM battery savers beyond a normal force-stop.
- A release build (debug APK only).
- The 0.2.0 Compose screens and widget refresh on a physical device (see above).

## Version 0.3.0 design pass — phone and emulator

The 0.1.0 log above still describes the backend and the listener. The redesign
(Home, Activity, Settings, sheets, widgets, notification copy, app icon) was reviewed
against `docs/DESIGN.md` on two devices:

* **Physical phone** (Samsung SM-A546U1, Android 16): the three main screens, the details
  sheet, the revoked-access banner and 200% font scale. It is the owner's daily driver, so
  the status bar was cropped out of the committed screenshots — it carried their own
  notifications and music.
* **Android 16 (API 36) x86_64 emulator** (headless in Docker with `--device /dev/kvm`):
  the states that are awkward to stage on someone's phone — first run, empty Home, the
  review sheet, the notification shade, both widgets, and the queued ("Reading now") row.

The app was installed with the documented debug build (`versionCode 3`, `0.3.0`), both
ABIs, and data seeded with synthetic M-Pesa notifications through the listener.

### Evidence

| Screenshot | Device | Shows |
|---|---|---|
| `home.png` | phone | hero total, daily-limit bar, top categories, latest rows |
| `activity.png` | phone | filters, day heading, done and needs-review rows |
| `settings.png` | phone | daily limit, access, test message, stop service, worker version |
| `details.png` | phone | code, balance after, Show original |
| `home-revoked.png` | phone | persistent "Notification access is off" banner |
| `home-200.png` | phone | 200% font scale, nothing clipped |
| `first-run.png`, `first-run-granted.png` | emulator | explainer, then the granted state |
| `home-empty.png` | emulator | the empty ledger |
| `review.png` | emulator | original message, Retry, Ignore |
| `activity-waiting.png` | emulator | done + "Reading message…" + needs review together |
| `widgets.png` | emulator | both widgets on the launcher |
| `notifications.png` | emulator | grouped results and the summary entry |

### Two bugs this pass found (and fixed)

1. **The first-run screen never noticed access being granted.** The polling effect
   returned early whenever onboarding was not finished, so coming back from the system
   setting left the screen on "needs access". It now polls access on every tick and only
   skips the Rust `stats` call before onboarding is done.
2. **The app icon was the default Android robot.** Launcher, widget picker and status
   bar now use the receipt-slip mark described in the README.

A tooling gotcha worth recording: `adb.exe` from Windows (driven out of WSL) re-splits
`cmd notification post ... 'long body'` and the listener receives only
`M-PESA <code>`, so the shape filter correctly rejects it. Pushing a small `sh` script to
`/data/local/tmp` and running it on the device posts the full body.

### Not verified in this pass

- TalkBack proper: row semantics were inspected in the `uiautomator` tree (one merged
  label per row) but no spoken pass was recorded.
- Dark theme, by design: v1 is light-only.
- Widgets with a completely empty cache ("Ksh —", "No data yet"): the fallback exists,
  but it was not photographed.
