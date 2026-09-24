#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/native-dependencies.env"
LIBS="$ROOT/V2rayNG/app/libs"
WORK="$ROOT/.native-build"

if [[ $# -gt 1 || ( $# -eq 1 && $1 != --aar-only ) ]]; then
    echo "Usage: bash scripts/prepare-native.sh [--aar-only]" >&2
    exit 1
fi

verify_aar() {
    [[ -f "$1" ]] && [[ "$(shasum -a 256 "$1" | cut -d ' ' -f 1)" == "$XRAY_AAR_SHA256" ]]
}

mkdir -p "$LIBS" "$WORK"
if ! verify_aar "$LIBS/libv2ray.aar"; then
    curl --fail --location --retry 3 \
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/$XRAY_TAG/libv2ray.aar" \
        --output "$WORK/libv2ray.aar"
    if ! verify_aar "$WORK/libv2ray.aar"; then
        echo "libv2ray.aar checksum mismatch; refusing to use it." >&2
        exit 1
    fi
    mv "$WORK/libv2ray.aar" "$LIBS/libv2ray.aar"
fi
echo "Verified libv2ray $XRAY_TAG (source $XRAY_COMMIT)"

# JVM unit tests need the AAR's Java API, but do not execute Android JNI code.
if [[ ${1:-} == --aar-only ]]; then
    exit 0
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export NDK_HOME="${NDK_HOME:-${SDK:+$SDK/ndk/$NDK_VERSION}}"
if [[ ! -x "$NDK_HOME/ndk-build" ]] ||
    ! grep -Eq "^Pkg.Revision *= *${NDK_VERSION//./\\.}[[:space:]]*$" "$NDK_HOME/source.properties"; then
    echo "Install Android NDK $NDK_VERSION and set ANDROID_HOME (or NDK_HOME)." >&2
    exit 1
fi

export HEV_SOURCE="$WORK/hev-socks5-tunnel"
if [[ ! -d "$HEV_SOURCE/.git" ]]; then
    git init -q "$HEV_SOURCE"
    git -C "$HEV_SOURCE" remote add origin https://github.com/heiher/hev-socks5-tunnel.git
fi
git -C "$HEV_SOURCE" fetch --depth 1 origin "$HEV_COMMIT"
git -C "$HEV_SOURCE" checkout --detach "$HEV_COMMIT"
# These nested gitlinks are fixed by HEV_COMMIT; never track remote branch tips.
git -C "$HEV_SOURCE" submodule update --init --recursive --depth 1
bash "$ROOT/compile-hevtun.sh"
echo "Native libraries ready in $LIBS"
