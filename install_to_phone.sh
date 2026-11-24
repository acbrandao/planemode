#!/usr/bin/env bash
# Installs the planeMode debug APK on a USB-connected Android phone and launches it.
#
# Usage:
#   ./install_to_phone.sh            # install the existing debug APK
#   ./install_to_phone.sh --build    # rebuild the APK first, then install
#   ./install_to_phone.sh --emulator # allow installing on an emulator if no phone is connected
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.planemode"
BUILD=0
ALLOW_EMULATOR=0

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --emulator) ALLOW_EMULATOR=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

# --- Locate adb ---
ADB=""
for candidate in \
  "${ANDROID_HOME:-}/platform-tools/adb" \
  "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
  "$HOME/Library/Android/sdk/platform-tools/adb" \
  "/opt/homebrew/share/android-commandlinetools/platform-tools/adb" \
  "$(command -v adb 2>/dev/null || true)"; do
  if [[ -n "$candidate" && -x "$candidate" ]]; then ADB="$candidate"; break; fi
done
if [[ -z "$ADB" ]]; then
  echo "❌ adb not found. Install it with:  brew install --cask android-platform-tools"
  exit 1
fi

# --- Build if requested or if the APK is missing ---
if [[ $BUILD -eq 1 || ! -f "$APK" ]]; then
  echo "🔨 Building debug APK..."
  if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" && -z "$JAVA_HOME" ]] && \
      JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    export JAVA_HOME
  fi
  (cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug -q)
fi
[[ -f "$APK" ]] || { echo "❌ APK not found at $APK"; exit 1; }

# --- Find a device ---
"$ADB" start-server >/dev/null 2>&1
pick_device() {
  local phones=() emulators=() unauthorized=()
  while read -r serial state _; do
    [[ -z "$serial" || "$serial" == "List" ]] && continue
    case "$state" in
      device) if [[ "$serial" == emulator-* ]]; then emulators+=("$serial"); else phones+=("$serial"); fi ;;
      unauthorized) unauthorized+=("$serial") ;;
    esac
  done < <("$ADB" devices | tail -n +2)

  if [[ ${#unauthorized[@]} -gt 0 && ${#phones[@]} -eq 0 ]]; then
    echo "UNAUTHORIZED"; return
  fi
  if [[ ${#phones[@]} -ge 1 ]]; then echo "${phones[0]}"; return; fi
  if [[ $ALLOW_EMULATOR -eq 1 && ${#emulators[@]} -ge 1 ]]; then echo "${emulators[0]}"; return; fi
  echo ""
}

echo "📱 Looking for a connected phone (USB debugging must be enabled)..."
SERIAL=""
for _ in $(seq 1 30); do
  SERIAL="$(pick_device)"
  if [[ "$SERIAL" == "UNAUTHORIZED" ]]; then
    echo "   Phone found but not authorized – unlock it and tap 'Allow' on the USB debugging prompt..."
    SERIAL=""
  elif [[ -n "$SERIAL" ]]; then
    break
  fi
  sleep 2
done

if [[ -z "$SERIAL" ]]; then
  cat <<EOF
❌ No phone detected. Checklist:
   1. Settings → About phone → tap "Build number" 7 times (enables Developer options)
   2. Settings → System → Developer options → enable "USB debugging"
   3. Connect with a data-capable USB cable and accept the "Allow USB debugging?" prompt
   4. Run: $ADB devices
EOF
  exit 1
fi

MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
SDK="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "✅ Using $MODEL (serial $SERIAL, Android API $SDK)"
if [[ "$SDK" -lt 26 ]]; then echo "❌ planeMode requires Android 8.0 (API 26) or newer."; exit 1; fi

# --- Install ---
echo "📦 Installing $(du -h "$APK" | cut -f1) APK..."
if ! OUT="$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1)"; then
  echo "$OUT"
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$OUT"; then
    echo "⚠️  An existing planeMode signed with a different key is installed."
    read -r -p "   Uninstall it (deletes its saved flights and settings) and retry? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }
    "$ADB" -s "$SERIAL" uninstall "$PACKAGE"
    "$ADB" -s "$SERIAL" install "$APK"
  else
    echo "❌ Install failed."; exit 1
  fi
else
  echo "$OUT" | tail -1
fi

# --- Launch ---
"$ADB" -s "$SERIAL" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
echo "🚀 planeMode launched on $MODEL. Grant precise location and notification permissions when asked."
