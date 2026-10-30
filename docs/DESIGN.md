# DESIGN.md: how to build the pocket-ledger UI

Source of truth for the look: the design canvas (3 screens: Home, Activity, Widgets and notifications).
https://claude.ai/artifact/MgDDNWXFTKLeaCVMAuagWx
If this file and the canvas disagree, the canvas wins. Match it closely; do not redesign.

## Principle
The app captures money automatically, so the UI shows RESULTS, never plumbing. The queue appears only as a calm amber "waiting" state. A queued or retrying job is normal, never an error. Only truly unreadable messages ask for the user.

## Build order (design is the LAST layer; do not start before Gate 4)
1. Tokens + theme (`Color.kt`, `Type.kt`, `Theme.kt`)
2. Shared components
3. Home screen
4. Activity screen
5. Result notification
6. Widgets
Each step must run against real data from the Rust worker via JNI `stats()` / list calls. Until Phase 5 is working, use a fake repository with the same interface, and swap it out without changing UI code.

## Tokens
| Token | Hex | Use |
|---|---|---|
| ground | #F4F7F2 | screen background |
| surface | #FFFFFF | cards, nav, notification |
| ink | #10231B | primary text |
| muted | #5B6B62 | secondary text (passes 4.5:1 on ground and surface) |
| line | #DCE5DE | dividers, chip borders |
| forest | #0F3D2B | hero card, selected chip, primary widget |
| forest-soft | #1E5A42 | progress track on hero |
| mint | #7FD6A0 | progress fill |
| mint-text | #BFE3CC | secondary text on forest |
| green | #14783F | positive accents, active nav, initials on mint |
| mint-tint | #E3EFE6 | avatar bg for spent |
| blue-tint / blue | #DDEBFA / #1F4E8C | received avatar |
| amber-tint / amber-ink | #FFF3DC / #5A3A00 | waiting strip |
| amber | #E8A33D | waiting dot |
| amber-avatar-ink | #8A5A00 | queued row initials |
| red-tint / red-ink | #FBE3E0 / #9B2C1F | needs-review row |

Rules: never put white text on #1F9D55 or lighter greens (fails contrast). Colour is never the only signal: queued and review rows also carry words ("Waiting in queue", "Review").

## Type
Plus Jakarta Sans (bundle it in `res/font/`, do not download at runtime: the app is offline-first). Fallback: system sans.
- Screen title 26 / ExtraBold (800)
- Hero number 40 / ExtraBold
- Row title 15 / SemiBold (600); row amount 15 / Bold
- Secondary 13 / SemiBold-Regular, `muted`
- Section label 12 / Bold, uppercase, `muted`
Use `sp`, support system font scaling up to 200% without clipping (rows must grow, not truncate amounts).

## Shape and spacing
- Screen horizontal padding 20dp. Top padding 56dp under the status bar (use real window insets, not fixed).
- Hero card radius 24dp, padding 22dp. Small tiles radius 16dp, padding 12dp. Widgets radius 28dp, padding 16dp. Chips fully rounded.
- Rows: 40dp rounded-12 avatar with initials (no icons, no emoji), 12dp gap, 12dp vertical padding, 1dp `line` divider.
- Touch targets at least 48dp (the canvas uses 44px; use 48dp on Android).

## Screens

### Home
1. Date label (muted) + title "Pocket Ledger".
2. Hero card (forest): "Spent today", amount "Ksh 2,450", line "3 payments · M-Pesa balance Ksh 12,340", progress bar (8dp) with caption "61% of your Ksh 4,000 daily limit". Daily limit is a user setting with a sensible default; hide the bar if unset.
3. Waiting strip (amber-tint): shown ONLY when queued jobs > 0. Copy: "2 messages waiting to be read. Works offline." Singular when 1. Disappears with a short fade when 0.
4. Three category tiles: top 3 categories today by amount.
5. "Latest": 3 most recent rows. Row tap opens a bottom sheet with details (code, time, balance after, raw text hidden behind "Show original").
6. Bottom nav: Home, Activity, Settings (text labels; add icons only if time allows).
Data: `spent_today`, `payment_count`, `balance`, `queued_count`, `top_categories`, `latest[]` from the worker.

### Activity
- Filter chips: All, Spent, Received, Review (selected = forest fill, white text).
- Grouped by day with section labels (TODAY, YESTERDAY, then dates). Use a LazyColumn with stable keys = transaction code.
- Row states: done (mint-tint avatar), queued (amber avatar "…", title "Reading message…", subtitle "Waiting in queue", amount "Ksh —"), needs review (red avatar "?", title "Could not read this one", subtitle "Tap to fix or ignore", amount "Review").
- Review flow: tap opens a sheet showing the original message with actions "Retry" and "Ignore". Retry re-enqueues the job; Ignore marks it dismissed. No data is ever silently dropped.
- Queued rows update live when jobs finish (observe a stream or poll `stats()` every 1s while the screen is visible).

### Widgets and notifications
- Widget A "Spent today" (forest, 2x2): label, amount, "N waiting" in amber text only when N > 0.
- Widget B "This week" (surface, 2x2): amount and change vs last week ("8% less than last week" in green; "more" in ink, never red-shaming).
- Result notification: title "Spent Ksh 350 at Java House", body "Food · 61% of daily limit used". For received: "Received Ksh 1,500 from Jane Doe", body "Balance Ksh 12,340". Channel name "Transactions", default importance, no sound. Group multiple results; do not spam when many jobs finish at once (summary notification).
- Widget refresh: triggered by worker completion events (not a timer). Widgets must render sensibly with zero data and when the Rust service is not running (read last-known values from SQLite or a small cache, never block on starting the service).

## Android implementation notes
- UI: Jetpack Compose + Material 3 as the base, fully re-themed with the tokens above (do not ship default purple Material colours). Light theme only for v1; note dark theme as a future idea.
- Widgets: use Jetpack Glance if its current docs support what you need, else RemoteViews. Follow the current official docs; do not guess APIs.
- Architecture: UI -> `LedgerRepository` (interface) -> JNI bridge. A `FakeLedgerRepository` with sample data exists for previews and for building the UI before Phase 5.
- Compose `@Preview` for every screen and each row state (done, queued, review) and for widgets where supported.
- Strings in `strings.xml`; money formatted by one function (`Ksh 1,234`, thousands separators, no decimals unless non-zero cents).
- First-run flow: one screen explaining why Notification Access is needed (reads only M-Pesa confirmations, nothing leaves the phone), a single button that deep-links to the system setting, and a clear "granted" state on return. This screen is part of the design; make it match the Home style.

## Empty and edge states
- No transactions yet: friendly line "Your M-Pesa payments will appear here automatically." and a hint to grant notification access if not granted.
- Notification access revoked: persistent top banner on Home with a button back to the setting.
- Large amounts and long names: wrap to two lines, never overlap the amount.

## Accessibility
- Real buttons with content descriptions; rows expose one merged semantic label ("Java House, Food, minus 350 shillings, 1:00 PM").
- Contrast 4.5:1 minimum for all text; check every token pair above if you change one.
- Works at 200% font scale; test it.

## Definition of done (design)
- [ ] Home, Activity and widgets visually match the canvas at 390dp width
- [ ] Queued, review, empty, and permission-revoked states all exist and are reachable
- [ ] Runs on real data from the worker, not only the fake repository
- [ ] 200% font scale and TalkBack pass a quick manual check
- [ ] Screenshots of all screens added to `media/` and referenced in the README
