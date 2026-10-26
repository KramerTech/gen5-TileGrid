#!/bin/sh
# Run locally. Does not connect to or modify a vehicle.
# Signs with keystore/tilepad.jks (password in keystore/password) unless TILEPAD_KEYSTORE
# and TILEPAD_STORE_PASSWORD are set. Both live outside git (see .gitignore).
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_HOME
BT="$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION:-36.0.0}"
[ -x "$BT/apksigner" ] || { echo "Install Android build-tools ${BUILD_TOOLS_VERSION:-36.0.0}." >&2; exit 1; }
: "${TILEPAD_KEYSTORE:=$PWD/keystore/tilepad.jks}"
[ -f "$TILEPAD_KEYSTORE" ] || { echo "Missing $TILEPAD_KEYSTORE (set TILEPAD_KEYSTORE)." >&2; exit 1; }
if [ -z "${TILEPAD_STORE_PASSWORD:-}" ] && [ -f keystore/password ]; then
    TILEPAD_STORE_PASSWORD=$(cat keystore/password)
fi
: "${TILEPAD_STORE_PASSWORD:?Set TILEPAD_STORE_PASSWORD}"
: "${TILEPAD_KEY_PASSWORD:=$TILEPAD_STORE_PASSWORD}"
: "${TILEPAD_KEY_ALIAS:=tilepad}"
export TILEPAD_STORE_PASSWORD TILEPAD_KEY_PASSWORD
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
mkdir -p dist
python3 tools/kitkat_repackage.py app/build/outputs/apk/release/app-release-unsigned.apk dist/Tilepad-signing.apk
# Explicit v1 SHA1 signing: what Android 4.4's PackageManager verifies.
JARSIGNER=jarsigner
[ -z "${JAVA_HOME:-}" ] || JARSIGNER="$JAVA_HOME/bin/jarsigner"
"$JARSIGNER" -keystore "$TILEPAD_KEYSTORE" -storepass:env TILEPAD_STORE_PASSWORD \
    -keypass:env TILEPAD_KEY_PASSWORD -digestalg SHA1 -sigalg SHA1withRSA \
    dist/Tilepad-signing.apk "$TILEPAD_KEY_ALIAS"
"$BT/zipalign" -f 4 dist/Tilepad-signing.apk dist/Tilepad.apk
rm -f dist/Tilepad-signing.apk
"$BT/apksigner" verify --min-sdk-version 19 --verbose --print-certs dist/Tilepad.apk
