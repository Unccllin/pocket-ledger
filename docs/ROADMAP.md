# Roadmap: from a private ledger to a product

The destination is one ledger engine with three modes — **Personal**, **Business**,
**Chama** — shipped in that order, in two editions: **Libre** (no `INTERNET`) and
**Plus** (opt-in E2EE sync, subscriptions). This file is the plan of record; `README.md`
describes what exists today.

## Product principles

1. **Local-first is the trust story.** Capture and reading never need a network. Anything
   networked is opt-in, separately packaged, and encrypts before it leaves the phone.
2. **One core, three modes.** A mode is screens, reports and fields over the same
   transactions — not a fork, not a second parser.
3. **Capture beats entry.** The app must be useful on day one; statement import exists so
   a new user does not wait a month for a ledger.
4. **Money is integer cents, parsed by fixtures.** No floats; every new message shape
   lands as a fixture before code.
5. **A small thing that works ships before a big thing that does not.**

## Editions and modes

| Edition | Package | Network | Sync/backup | Billing |
|---|---|---|---|---|
| Libre | `com.pocketledger` | none (manifest has no `INTERNET`) | local encrypted file | none |
| Plus | `com.pocketledger.plus` | opt-in | E2EE cloud, multi-device, family | Play Billing |

| Mode | Adds | Ships in |
|---|---|---|
| Personal | budgets, months, reminders, statement import, widgets | Libre and Plus |
| Business | tills/wallets, tags, daily reconciliation, receipts, PDF statements | Plus (Libre later, offline reports) |
| Chama | members, contributions, loans, member statements | Plus only (needs sync) |

## Phases, owners, acceptance

Owners: **me** = core engineering; **you** = accounts, publishing, device tests;
**contractor** = isolated milestones against the interfaces below.

### Phase 0 — publish what exists (this week)
- [ ] you: publish `v0.3.3` (signed APK in `dist/`), then create the Play developer
      account and an internal testing track.
- [ ] me: AAB build, listing copy, Data safety answers, privacy policy page,
      feature graphic + screenshots, Play Console checklist.
- Acceptance: 0.3.3 on the install page and in Play internal testing.

### Phase 1 — Personal, Libre (weeks 1–2)
- [ ] flavor scaffold: `libre`/`plus`, CI proof that the Libre APK has no `INTERNET`.
- [ ] schema v2 + forward-only migrations: `accounts`, `tags`, `transaction_tags`,
      `budgets`, `recurring`, `attachments`; repository API versioned.
- [ ] statement import via SAF (CSV first), idempotent by transaction code.
- [ ] search, filters, month view, CSV/PDF export.
- [ ] encrypted local backup/restore file.
- [ ] budgets per category + recurring bill reminders.
- [ ] Swahili/English, 200% font, Tecno/Infinix/Xiaomi pass.
- Acceptance: a new user imports six months of statements, sets one budget, and gets a
  correct monthly total; Play Vitals crash-free ≥ 99.5%.

### Phase 2 — Business (weeks 3–5)
- [ ] tills/wallets, customer/supplier tags, daily reconciliation report, receipt photo.
- [ ] Fuliza/M-Shwari/loan fees and cost-of-M-Pesa summary.
- [ ] shareable PDF statement.
- Acceptance: a duka owner reconciles a day across two tills and exports a statement a
  lender would accept.

### Phase 3 — Plus and Chama (weeks 6–9)
- [ ] Plus flavor: E2EE backup/sync, multi-device, family sharing, Play Billing.
- [ ] Chama mode: members, contributions, loans, member statements.
- [ ] one SACCO/chama and one agent-network pilot.
- Acceptance: two devices and a chama member see the same ledger with no plaintext data
  on the server; a subscription can be bought in KES.

### Phase 4 — growth (weeks 10–12)
- [ ] ASO around real searches; short-form demos; referral codes.
- [ ] Galaxy Store / AppGallery listings.
- Acceptance: Play production release, crash-free ≥ 99.5%, support workflow documented.

## Engineering invariants (CI gates)

- `cargo fmt --check`, `cargo clippy -D warnings`, `cargo test --workspace`.
- `assembleLibreDebug` and `assemblePlusDebug`; `aapt2 dump badging` proves the Libre
  APK declares no `INTERNET`.
- `actionlint` on workflows; signed release verification (`apksigner --print-certs`).
- Schema migrations are append-only and tested against a database from the previous
  release.
- New message shapes: fixture + parser test before code.

## What stays in-house

Play Console, signing keys, schema migrations, release workflow, parser golden corpus.
Contractors get scoped screens/features behind repository interfaces.

## Risks

| Risk | Mitigation |
|---|---|
| Play rejects SMS permission | never request `READ_SMS`; listener + statement import |
| New Play accounts need 12 testers × 14 days | start internal testing now with 12+ people |
| Sync turns "no network" into a broken promise | separate Plus package; Libre never declares `INTERNET` |
| Samsung/OEM kills the listener | rebind + catch-up (0.3.3), battery guidance, field testing matrix |
| Statement PDF formats differ | start with CSV; PDF behind a feature flag until fixtures exist |
| Outsourcing erodes quality | CI gates, previews, fixtures-first, milestone payments |
