#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "Repo root: $ROOT"

# ---------- Go ----------
if ! go version 2>/dev/null | grep -qE 'go1\.(2[3-9]|[3-9][0-9])'; then
  echo "Installing Go 1.23..."
  curl -sL https://go.dev/dl/go1.23.4.linux-amd64.tar.gz -o /tmp/go.tgz
  sudo rm -rf /usr/local/go
  sudo tar -C /usr/local -xzf /tmp/go.tgz
fi
export PATH=/usr/local/go/bin:$HOME/go/bin:$PATH
go version

# ---------- Android SDK ----------
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_HOME="$SDK"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses > /dev/null 2>&1 || true
"$SDKM" "platforms;android-34" "build-tools;34.0.0" "ndk;27.2.12479018"
export ANDROID_NDK_HOME="$SDK/ndk/27.2.12479018"
java -version

# ---------- Gradle ----------
if [ ! -x /opt/gradle-8.7/bin/gradle ]; then
  echo "Installing Gradle 8.7..."
  curl -sL https://services.gradle.org/distributions/gradle-8.7-bin.zip -o /tmp/gradle.zip
  sudo unzip -q /tmp/gradle.zip -d /opt
fi
export PATH=/opt/gradle-8.7/bin:$PATH

# ---------- libbox (sing-box core) ----------
git clone --depth 1 --branch v1.12.20 https://github.com/SagerNet/sing-box.git /tmp/sing-box
cd /tmp/sing-box
make lib_install
make lib_android
AAR="$(find /tmp/sing-box -name 'libbox.aar' | head -1)"
echo "libbox aar: $AAR"
test -n "$AAR"
mkdir -p "$ROOT/app/libs"
cp "$AAR" "$ROOT/app/libs/libbox.aar"
ls -la "$ROOT/app/libs"

# ---------- APK ----------
cd "$ROOT"
gradle assembleDebug --stacktrace
APK="$(ls app/build/outputs/apk/debug/*.apk | head -1)"
echo "APK: $APK"
cp "$APK" SinaTV.apk

# ---------- Release ----------
gh release create "build-${GITHUB_RUN_NUMBER}" SinaTV.apk \
  --title "SinaTV build ${GITHUB_RUN_NUMBER}" \
  --notes "Automatic build from ${GITHUB_SHA}"
