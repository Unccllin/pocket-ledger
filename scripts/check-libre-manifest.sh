#!/usr/bin/env bash
# Fails if the Libre APK declares INTERNET. The privacy claim is a manifest fact,
# so it is checked on the built artifact rather than trusted from source.
set -euo pipefail
APK=${1:?usage: scripts/check-libre-manifest.sh <apk>}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
AAPT2=${AAPT2:-$SDK/build-tools/36.0.0/aapt2}
[ -x "$AAPT2" ] || { echo "error: aapt2 not found at $AAPT2" >&2; exit 1; }
if "$AAPT2" dump badging "$APK" | grep -q "uses-permission: name='android.permission.INTERNET'"; then
    echo "error: $APK declares INTERNET; the Libre edition must not" >&2
    exit 1
fi
echo "ok: no INTERNET permission in $(basename "$APK")"
