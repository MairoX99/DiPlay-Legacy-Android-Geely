#!/bin/sh
# Build the Geely E01 car-test APK and name it DiPlay-Legacy-Geely-V<version>.apk
#
# Credentials and the signing key are read from outside the repository:
#   DIPLAY_AUTH_ASSETS_DIR   default ~/.diplay-auth-assets    (offline-mfi identity)
#   ~/.diplay-signing/        keystore.properties + the release keystore
#
# Usage: scripts/package-geely.sh [release|debug|both]   (default: release)
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
kind=${1:-release}
out=${DIPLAY_DIST_DIR:-$root/dist}

# Filename tag keeps only the numeric version: "0.2.7-geely" -> V027
version=$(sed -n 's/.*versionName *= *"\([0-9][0-9.]*\).*".*/\1/p' "$root/mobile/build.gradle.kts" | head -1)
[ -n "$version" ] || { echo "package-geely: cannot read versionName" >&2; exit 1; }
tag="V$(printf '%s' "$version" | tr -d '.')"

: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
: "${DIPLAY_AUTH_ASSETS_DIR:=$HOME/.diplay-auth-assets}"
export ANDROID_HOME DIPLAY_AUTH_ASSETS_DIR

[ -f "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8" ] || {
    echo "package-geely: $DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8 is missing" >&2; exit 1; }

mkdir -p "$out"

build_release() {
    sign=$HOME/.diplay-signing
    [ -f "$sign/keystore.properties" ] || {
        echo "package-geely: $sign/keystore.properties is missing" >&2; exit 1; }
    # Gradle reads ANDROID_*; the properties file uses storePassword/keyPassword.
    set -a; . "$sign/keystore.properties"; set +a
    ANDROID_KEYSTORE_PASSWORD=$storePassword
    ANDROID_KEY_PASSWORD=$keyPassword
    ANDROID_KEYSTORE_PATH=$sign/diplay-release.jks
    ANDROID_KEY_ALIAS=diplay
    export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD ANDROID_KEYSTORE_PATH ANDROID_KEY_ALIAS
    (cd "$root" && sh gradlew :mobile:assembleRelease)
    cp "$root/mobile/build/outputs/apk/release/mobile-release.apk" "$out/DiPlay-Legacy-Geely-$tag.apk"
    echo "→ $out/DiPlay-Legacy-Geely-$tag.apk"
}

build_debug() {
    (cd "$root" && sh gradlew :mobile:assembleStandaloneDebug)
    cp "$root/mobile/build/outputs/apk/debug/mobile-debug.apk" "$out/DiPlay-Legacy-Geely-$tag-hudtest.apk"
    echo "→ $out/DiPlay-Legacy-Geely-$tag-hudtest.apk"
}

case "$kind" in
    release) build_release ;;
    debug)   build_debug ;;
    both)    build_release; build_debug ;;
    *) echo "package-geely: expected release|debug|both, got '$kind'" >&2; exit 1 ;;
esac
