# Install pocket-ledger on your phone

pocket-ledger is sideloaded, not on the Play Store: it is an offline, no-account
app that has no `INTERNET` permission, so there is nothing for a store listing to
add. You install a signed APK, the same way as any other Android app from
outside Play.

## What you need

| | |
|---|---|
| Android | 8.0 (API 26) or newer |
| CPU | `arm64-v8a` phone (every recent phone); the same APK also carries an `x86_64` build for emulators |
| Space | about 45 MB, plus the ledger (a few hundred KB) |
| Download | the latest `pocket-ledger-<version>.apk` from [Releases](https://github.com/Unccllin/pocket-ledger/releases/latest) |

## Install

1. Download the APK on the phone (or copy it over).
2. Tap it in Files/Downloads. Android asks whether to allow installs from that
   app the first time: allow it for Files (or your browser), then go back.
3. Tap **Install**. Google Play Protect may warn that the app is unknown; that is
   expected for any sideloaded app.
4. Optionally verify the download first:
   ```sh
   sha256sum pocket-ledger-0.3.0.apk
   # compare with SHA256SUMS.txt on the release page
   ```
5. Open **Pocket Ledger**. The first screen explains Notification Access; tap
   **Open notification access settings**, find *pocket-ledger M-Pesa listener*,
   allow it, come back and tap **Continue**.

### Updating

Install the newer APK over the old one - your ledger stays. Android only allows
this when both APKs are signed with the same key; release builds are signed with
the project's release key, so do not switch to a locally built debug APK and
expect the update to apply (it will refuse). If you must switch, uninstall first,
which deletes the local ledger.

### Uninstalling

Long-press the icon -> **App info** -> **Uninstall**. This deletes the ledger and
the queued jobs; nothing was ever sent off the phone, so there is no server copy.

## After installing

- The home screen widget is added from the launcher's widget picker: **Spent
  today** (forest, 2x2) and **This week** (white, 2x2).
- The daily limit (Ksh 4,000 by default) is only used for the progress bar on
  Home; change it in **Settings**.
- Messages that look like M-Pesa but cannot be parsed are kept in **Activity ->
  Needs a look**, where you can read them and Retry or Ignore. Nothing is
  silently dropped.

## Verifying the build

Every release page includes `SHA256SUMS.txt`. You can also check the signing
certificate on a downloaded APK:

```sh
apksigner verify --print-certs pocket-ledger-0.3.0.apk
# Signer #1 certificate SHA-256 digest:
# e1588e0a325a276679f85b1a859a808ff045135013ffe1599a0857a1b6ea7fcc
```

The certificate digest is stable across releases; if it changes, the APK was not
built by this project - do not install it.

## Building the APK yourself

```sh
scripts/release.sh            # builds Rust libs, signs, writes dist/
scripts/release.sh --install  # also installs on a connected phone
```

The first run creates a release key at `~/.pocketledger/release.jks` (outside the
repo, `chmod 600`) and prints its fingerprint. Keep that file: without it you
cannot ship an update that installs over an existing app.

Maintainers publish with a tag, which runs the `release` workflow and attaches
the signed APK and checksums to the GitHub Release:

```sh
git tag v0.3.0
git push origin v0.3.0
```

The workflow needs the four signing secrets listed at the top of
`.github/workflows/release.yml`; without them it produces an unsigned artifact
and does not create a release.
