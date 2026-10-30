#!/usr/bin/env bash
# Builds the signed, sideloadable APK.
#
#   scripts/release.sh            build + verify + copy to dist/
#   scripts/release.sh --install  also install it on the connected phone
#
# The release key lives OUTSIDE the repository, in $POCKETLEDGER_KEYS
# (default ~/.pocketledger). It is created once, on first run. Back it up:
# without that file you cannot ship an update that installs over the old app.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
KEYDIR=${POCKETLEDGER_KEYS:-$HOME/.pocketledger}
KEYSTORE="$KEYDIR/release.jks"
PROPS="$KEYDIR/keystore.properties"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}
JAVA_HOME=${JAVA_HOME:-$HOME/jdk-17.0.20.1+1}
BUILD_TOOLS=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}

export JAVA_HOME ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" ANDROID_NDK_HOME="$NDK"
export PATH="$JAVA_HOME/bin:$PATH"

version() {
    sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1
}

die() { echo "error: $*" >&2; exit 1; }

[ -x "$JAVA_HOME/bin/keytool" ] || die "keytool not found; set JAVA_HOME to a JDK 17+"
command -v cargo-ndk >/dev/null || die "cargo-ndk not found (cargo install cargo-ndk --version 4.1.2)"
[ -d "$NDK" ] || die "NDK not found at $NDK; set ANDROID_NDK_HOME"
[ -x "$BUILD_TOOLS/apksigner" ] || die "apksigner not found under $BUILD_TOOLS"

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$KEYDIR"
    chmod 700 "$KEYDIR"
    PASSWORD=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
    echo "Creating a release key (this is the first run)..."
    "$JAVA_HOME/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias pocketledger \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$PASSWORD" -keypass "$PASSWORD" \
        -dname "CN=pocket-ledger, OU=Android, O=pocket-ledger, L=Nairobi, C=KE" >/dev/null
    cat > "$PROPS" <<EOF
storeFile=$KEYSTORE
storePassword=$PASSWORD
keyAlias=pocketledger
keyPassword=$PASSWORD
EOF
    chmod 600 "$KEYSTORE" "$PROPS"
    cat <<EOF

Generated a release key at $KEYSTORE
Store a copy somewhere safe (password manager, encrypted backup). Every future
APK must be signed with this key, or Android will refuse to update the app.

EOF
fi

set -a
# shellcheck disable=SC1090  # generated at run time, so its path is dynamic
. "$PROPS"
set +a

# shellcheck disable=SC2154  # storeFile..keyPassword come from that file
export PL_KEYSTORE="$storeFile" PL_KEYSTORE_PASSWORD="$storePassword"
# shellcheck disable=SC2154
export PL_KEY_ALIAS="$keyAlias" PL_KEY_PASSWORD="$keyPassword"

echo "Building Rust libraries (arm64-v8a, x86_64)..."
(cd "$ROOT" && cargo ndk -t arm64-v8a -t x86_64 \
    -o android/app/src/main/jniLibs build -p ledger-jni --release)

echo "Building the signed APK..."
(cd "$ROOT/android" && ./gradlew --quiet assembleRelease)

APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "no signed APK at $APK (did the signing config load?)"

echo "Verifying signature..."
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" >/dev/null \
    || die "signature verification failed"
CERT=$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ -n "$CERT" ] || die "could not read the certificate digest"

NAME="pocket-ledger-$(version).apk"
mkdir -p "$ROOT/dist"
cp "$APK" "$ROOT/dist/$NAME"
(cd "$ROOT/dist" && sha256sum "$NAME" > "$NAME.sha256")

BADGING=$("$BUILD_TOOLS/aapt2" dump badging "$APK" 2>/dev/null \
    | sed -n 's/^package: \(.*\)$/\1/p')
echo
echo "APK:     dist/$NAME"
echo "Package: $BADGING"
echo "SHA-256: $(cut -d' ' -f1 "$ROOT/dist/$NAME.sha256")"
echo "Cert:    $CERT"
echo
echo "On the phone: open the APK, allow installs from that app, Install."
echo "Checksum:  sha256sum $NAME"

if [ "${1:-}" = "--install" ]; then
    ADB=${ADB:-adb}
    "$ADB" install -r "$APK"
    "$ADB" shell am start -n com.pocketledger/.MainActivity >/dev/null
fi
