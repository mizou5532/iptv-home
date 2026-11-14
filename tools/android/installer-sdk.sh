#!/usr/bin/env bash
# Installe le SDK Android (command-line tools, platform-tools, plate-forme, build-tools)
# dans $HOME/android-sdk et écrit apps/local.properties. Idempotent.
set -euo pipefail

CMDLINE_TOOLS_VERSION="16111833"
PLATFORM="android-36"
BUILD_TOOLS="36.0.0"

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "Téléchargement des command-line tools..."
  curl -fsSL -o "$tmp/cmdline.zip" "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/cmdline.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

# Les licences sont acceptées automatiquement (yes ferme le tube : SIGPIPE attendu).
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null
set -o pipefail
"$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS"

mkdir -p "$RACINE/apps"
echo "sdk.dir=$SDK" > "$RACINE/apps/local.properties"
echo "SDK prêt : $SDK"
