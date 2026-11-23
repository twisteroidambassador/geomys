#!/usr/bin/env bash
set -euo pipefail

# Android 17 (API 37) v3.2 hybrid signing:
#   K0   classical fallback signer (v3.0)
#   C1   new classical signer for the hybrid pair
#   PQC1 ML-DSA-65 signer for the hybrid pair
#
# All three key entries live in one PKCS#12 keystore with distinct aliases. The
# lineage files remain separate proof-of-rotation artifacts.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$REPO_DIR/.." && pwd)"
PROPS="${HYBRID_SIGNING_PROPERTIES:-$ROOT_DIR/hybrid-signing.properties}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-37.0.0}"
ANDROID_HOME="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"

APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/zipalign"

if [[ ! -f "$PROPS" ]]; then
  echo "Missing hybrid signing properties: $PROPS" >&2
  exit 1
fi
if [[ ! -x "$APKSIGNER" || ! -x "$ZIPALIGN" ]]; then
  echo "Build-tools $BUILD_TOOLS_VERSION (with apksigner and zipalign) is required." >&2
  exit 1
fi

prop() {
  grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-
}
resolve_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$ROOT_DIR" "$1" ;;
  esac
}

STORE="$(resolve_path "$(prop storeFile)")"
LEGACY_ALIAS="$(prop legacyKeyAlias)"
CLASSICAL_ALIAS="$(prop classicalKeyAlias)"
PQC_ALIAS="$(prop pqcKeyAlias)"
CLASSICAL_LINEAGE="$(resolve_path "$(prop classicalLineage)")"
PQC_LINEAGE="$(resolve_path "$(prop pqcLineage)")"

for file in "$STORE" "$CLASSICAL_LINEAGE" "$PQC_LINEAGE"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing signing input: $file" >&2
    exit 1
  fi
done

# One PKCS#12 password is passed by environment; the lineages are still separate.
export STORE_PASS="$(prop storePassword)"
if [[ -z "$STORE_PASS" ]]; then
  echo "Missing storePassword in $PROPS" >&2
  exit 1
fi

GRADLE_CMD="${GRADLE_CMD:-$REPO_DIR/gradlew}"
if [[ ! -x "$GRADLE_CMD" ]]; then
  GRADLE_CMD="gradle"
fi

UNSIGNED="$REPO_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$(mktemp /tmp/geomys-hybrid-aligned.XXXXXX.apk)"
OUT="$REPO_DIR/app/build/outputs/apk/release/app-hybrid-release.apk"
trap 'rm -f "$ALIGNED"' EXIT

echo "Building unsigned release..."
(cd "$REPO_DIR" && "$GRADLE_CMD" --no-daemon assembleRelease -PunsignedRelease=true)

if [[ ! -f "$UNSIGNED" ]]; then
  echo "Unsigned release APK not found: $UNSIGNED" >&2
  exit 1
fi

echo "Aligning..."
"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"

echo "Signing with v3.2 hybrid configuration..."
rm -f "$OUT"
"$APKSIGNER" sign \
  --ks "$STORE" --ks-key-alias "$LEGACY_ALIAS" \
    --ks-pass env:STORE_PASS --key-pass env:STORE_PASS \
  --next-signer \
    --ks "$STORE" --ks-key-alias "$CLASSICAL_ALIAS" \
    --ks-pass env:STORE_PASS --key-pass env:STORE_PASS \
    --signer-lineage "$CLASSICAL_LINEAGE" --hybrid-signer-role classical \
  --next-signer \
    --ks "$STORE" --ks-key-alias "$PQC_ALIAS" \
    --ks-pass env:STORE_PASS --key-pass env:STORE_PASS \
    --signer-lineage "$PQC_LINEAGE" --hybrid-signer-role pqc \
  --hybrid-min-sdk-version 37 \
  --v4-signing-enabled false \
  --out "$OUT" "$ALIGNED"

unset STORE_PASS

echo "Verifying..."
"$APKSIGNER" verify -v --print-certs "$OUT"
echo
echo "Hybrid release APK: $OUT"
