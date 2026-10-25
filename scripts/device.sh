#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
package=dev.local.outfitroast
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  devices=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
  [[ ${#devices[@]} -eq 1 ]] || { adb devices -l; echo 'Connect and authorize one phone.' >&2; exit 2; }
  export ANDROID_SERIAL="${devices[0]}"
fi
case "${1:-help}" in
  install)
    bash "$OUTFIT_ROOT/scripts/build.sh" --offline assembleDebug assembleDebugAndroidTest
    adb install -r "$OUTFIT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
    adb install -r "$OUTFIT_ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    adb shell pm grant "$package" android.permission.CAMERA
    adb shell pm grant "$package" android.permission.RECORD_AUDIO
    ;;
  model)
    model="${2:-${OUTFIT_MODEL_PATH:-$OUTFIT_ROOT/../models/gemma-4-E2B-it.litertlm}}"
    expected=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
    actual="$(adb shell run-as "$package" sha256sum files/models/gemma-4-E2B-it.litertlm 2>/dev/null | awk '{print $1}' || true)"
    if [[ "$actual" == "$expected" ]]; then echo 'Model already verified.'; exit 0; fi
    echo "$expected  $model" | shasum -a 256 -c -
    adb shell -T "run-as $package sh -c 'mkdir -p files/models && cat > files/models/gemma-4-E2B-it.litertlm.part'" < "$model"
    actual="$(adb shell run-as "$package" sha256sum files/models/gemma-4-E2B-it.litertlm.part | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || { echo 'Device checksum mismatch' >&2; exit 1; }
    adb shell run-as "$package" mv files/models/gemma-4-E2B-it.litertlm.part files/models/gemma-4-E2B-it.litertlm
    echo 'Device model SHA-256 verified.'
    ;;
  start) adb shell am start -n "$package/.MainActivity" ;;
  pull)
    destination="${2:-$OUTFIT_ROOT/eval/results/device-$(date +%Y%m%d-%H%M%S)}"
    mkdir -p "$destination"
    adb exec-out run-as "$package" tar -cf - -C files runs > "$destination/runs.tar"
    tar -xf "$destination/runs.tar" -C "$destination"
    if adb shell run-as "$package" test -f files/ui-events.jsonl; then
      adb exec-out run-as "$package" cat files/ui-events.jsonl > "$destination/ui-events.jsonl"
    fi
    echo "Exported to $destination"
    ;;
  *) echo 'Usage: scripts/device.sh install | model [model-path] | start | pull [directory]. Device mutations only during Codex phone ownership.' ;;
esac
