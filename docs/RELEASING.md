# Releasing pocket-ledger

Two channels ship from this repository:

| Channel | What | Trigger |
|---|---|---|
| GitHub Releases | signed `pocket-ledger-<version>.apk` + `SHA256SUMS.txt` | pushing a `v<version>` tag, with the signing secrets configured |
| GitHub Packages (`ghcr.io`) | the laptop CLI/worker as a container image, tagged `latest`, `X.Y.Z` and `X.Y` | every push to `master`, and every `v*` tag |

Both workflows are in `.github/workflows/` (`release.yml`, `package.yml`). The
tag must equal `versionName` in `android/app/build.gradle.kts`; the release
workflow fails loudly if they disagree.

## One-time setup

### 1. The signing key

`scripts/release.sh` creates `~/.pocketledger/release.jks` on first use. Keep a
backup (password manager / encrypted drive): every future APK must be signed
with this key, or Android refuses to install the update over the old app.

If you already have a key, put its path/passwords in
`~/.pocketledger/keystore.properties`:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=pocketledger
keyPassword=...
```

### 2. The repository secrets

Only needed for automated releases; the container package uses the built-in
`GITHUB_TOKEN`.

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 ~/.pocketledger/release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` from the properties file |
| `ANDROID_KEY_ALIAS` | `pocketledger` |
| `ANDROID_KEY_PASSWORD` | `keyPassword` from the properties file |

Add them under **Settings -> Secrets and variables -> Actions**. Without them,
`release.yml` still builds an unsigned artifact and skips the Release.

### 3. Package visibility

Images are published under the caller's package namespace. If the package should
be pullable by everyone, set its visibility to public under **Packages ->
pocket-ledger -> Package settings** after the first push (private by default).

## Cut a release

1. Bump the version in `android/app/build.gradle.kts` (both `versionCode` and
   `versionName`).
2. Run the gates: `cargo fmt --all --check`, `cargo clippy --workspace
   --all-targets -- -D warnings`, `cargo test --workspace`, and
   `./gradlew assembleDebug` in `android/`.
3. Build and verify locally:
   ```sh
   scripts/release.sh
   # prints dist/pocket-ledger-<version>.apk, its SHA-256 and certificate digest
   ```
   Install it on a device before tagging when the change touches the app.
4. Commit, tag, push:
   ```sh
   git commit -am "release: v0.3.0"
   git tag -a v0.3.0 -m "pocket-ledger 0.3.0"
   git push origin master --tags
   ```
5. Watch the two workflows. `release` attaches the APK and checksums to the
   GitHub Release; `package` publishes `ghcr.io/<owner>/pocket-ledger:0.3.0`
   (plus `0.3` and, on `master`, `latest`).
6. Verify from the outside:
   ```sh
   curl -L -o app.apk https://github.com/Unccllin/pocket-ledger/releases/latest/download/pocket-ledger-0.3.0.apk
   sha256sum app.apk                      # compare with SHA256SUMS.txt
   apksigner verify --print-certs app.apk # digest e1588e0a...7fcc
   docker run --rm ghcr.io/unccllin/pocket-ledger:0.3.0 --help
   ```

## Manual fallback (no CI secrets)

The APK is already signed locally; upload it by hand:

1. **Releases -> Draft a new release**, tag `v<version>` (matching `versionName`).
2. Attach `dist/pocket-ledger-<version>.apk` and its `.sha256` file.
3. Publish.

Push the image manually with a personal access token that has `write:packages`:

```sh
echo "$GH_TOKEN" | docker login ghcr.io -u <user> --password-stdin
docker build -t ghcr.io/<owner>/pocket-ledger:<version> .
docker push ghcr.io/<owner>/pocket-ledger:<version>
```

## Rollback

- Delete the GitHub Release and the tag (`git push --delete origin v<version>`)
  and bump the version for the next attempt. Do not reuse a version number.
- Container images can be removed under **Packages -> pocket-ledger -> Version
  history**; a pushed `latest` will be replaced by the next `master` build.

## Checklist

- [ ] `versionCode` incremented, `versionName` bumped, tag matches it
- [ ] fmt / clippy / tests / `assembleDebug` green
- [ ] `scripts/release.sh` verified and installed on a device
- [ ] `release` workflow produced the APK + `SHA256SUMS.txt`
- [ ] `package` workflow produced the image tags
- [ ] Install notes in `docs/INSTALL.md` still accurate
