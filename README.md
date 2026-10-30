# pocket-ledger

> Rivium + Apalis pocket backend challenge entry: an offline-first M-Pesa auto-ledger.

M-Pesa confirmation notifications are captured by an Android Notification Listener,
queued as Apalis jobs, parsed by a Rust worker running under Rivium inside the app,
stored in SQLite, and surfaced on a home screen widget plus a notification.

<!-- AGENTS: fill every section below as the work lands. No placeholders at submission. -->

## What it does
TODO

## Demo
TODO (link media/demo.gif)

## Run on laptop (no phone)
TODO

## Run on Android
TODO

## Versions used (pinned)
| Thing | Version |
|---|---|
| Rust | TODO |
| Rivium | TODO |
| Apalis (+ storage crate) | TODO |
| Android Gradle Plugin / compileSdk | TODO |
| Test device / Android version | TODO |

## How jobs survive app restarts
TODO

## Real phone test notes (bonus)
See docs/DEVICE_TEST.md

## Privacy
Everything stays on the device. No network calls. Fixtures are synthetic.
