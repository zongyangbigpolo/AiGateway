#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/geodata-dependencies.env"
DEST="$ROOT/.native-build/geodata"
DOWNLOADS="$ROOT/.native-build/geodata-downloads"
mkdir -p "$DEST" "$DOWNLOADS"

verify() {
    [[ -f "$1" ]] && [[ "$(shasum -a 256 "$1" | cut -d ' ' -f 1)" == "$2" ]]
}

fetch() {
    local url="$1" name="$2" sha="$3"
    if ! verify "$DEST/$name" "$sha"; then
        curl --fail --silent --show-error --location --retry 3 \
            "$url" --output "$DOWNLOADS/$name"
        if ! verify "$DOWNLOADS/$name" "$sha"; then
            echo "$name checksum mismatch; refusing to bundle it." >&2
            exit 1
        fi
        mv "$DOWNLOADS/$name" "$DEST/$name"
    fi
    echo "Verified $name ($sha)"
}

RULES_URL="https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/$RULES_TAG"
GEOIP_URL="https://github.com/Loyalsoldier/geoip/releases/download/$GEOIP_TAG"
fetch "$RULES_URL/geosite.dat" geosite.dat "$GEOSITE_SHA256"
fetch "$RULES_URL/geoip.dat" geoip.dat "$GEOIP_SHA256"
fetch "$GEOIP_URL/geoip-only-cn-private.dat" geoip-only-cn-private.dat "$GEOIP_CN_PRIVATE_SHA256"
fetch "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/$RULES_COMMIT/LICENSE" \
    GEODATA-LICENSE-GPL-3.0 "$GPL_LICENSE_SHA256"
fetch "https://raw.githubusercontent.com/Loyalsoldier/geoip/$GEOIP_COMMIT/LICENSE" \
    GEODATA-LICENSE-CC-BY-SA-4.0 "$CC_LICENSE_SHA256"
cp "$ROOT/GEODATA-NOTICE" "$DEST/GEODATA-NOTICE"
echo "Pinned geodata and license notices ready in $DEST"
