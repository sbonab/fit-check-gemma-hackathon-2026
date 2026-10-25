#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
package=dev.local.outfitroast
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  devices=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
  [[ ${#devices[@]} -eq 1 ]] || { adb devices -l; echo 'Connect one authorized phone.' >&2; exit 2; }
  export ANDROID_SERIAL="${devices[0]}"
fi
mode="${1:-help}"
args=()
case "$mode" in
  performance)
    variant="${2:?Supply benchmark variant}"
    image="${3:?Supply local image}"
    [[ -f "$image" ]] || { echo 'Image missing' >&2; exit 2; }
    extension="${image##*.}"
    case "$extension" in jpg|jpeg|png|webp) ;; *) echo 'Use JPEG/PNG/WebP' >&2; exit 2;; esac
    name="performance.$extension"
    adb shell -T "run-as $package sh -c 'mkdir -p files/eval && cat > files/eval/$name'" < "$image"
    args=(-e image_path "/data/user/0/$package/files/eval/$name" -e variant "$variant" -e repeats "${4:-2}")
    testclass=dev.local.outfitroast.RoastPerformanceTest
    ;;
  engine)
    image="${2:?Supply a local outfit image}"
    [[ -f "$image" ]] || { echo 'Image missing' >&2; exit 2; }
    extension="${image##*.}"
    case "$extension" in jpg|jpeg|png|webp) ;; *) echo 'Use JPEG/PNG/WebP' >&2; exit 2;; esac
    name="fixture.$extension"
    adb shell -T "run-as $package sh -c 'mkdir -p files/eval && cat > files/eval/$name'" < "$image"
    args=(-e image_path "/data/user/0/$package/files/eval/$name")
    if [[ "${3:-}" == no-outfit ]]; then args+=(-e no_outfit true)
    elif [[ -n "${3:-}" ]]; then args+=(-e expected_garments "$3"); fi
    testclass=dev.local.outfitroast.RoastEngineTest
    ;;
  voice) testclass=dev.local.outfitroast.VoicePipelineTest ;;
  acoustic) testclass=dev.local.outfitroast.VoiceAcousticLoopbackTest ;;
  ui) testclass='dev.local.outfitroast.OutfitUiTest#cameraFirstScreenAndPauseStopsListening' ;;
  ui-polish) testclass='dev.local.outfitroast.OutfitUiTest#simplifiedControlsAndTransientCaptions' ;;
  caption-tts) testclass='dev.local.outfitroast.OutfitUiTest#captionStaysThroughRealSpeechThenFades' ;;
  wake-ui) testclass='dev.local.outfitroast.OutfitUiTest#prerecordedWakeThenRequestUsesRealActivityPipeline' ;;
  camera) testclass='dev.local.outfitroast.OutfitUiTest#freshCameraRoastThenContextualImprovement' ;;
  camera-empty) testclass='dev.local.outfitroast.OutfitUiTest#noOutfitCameraClarifiesSpeaksAndRearms' ;;
  *) echo 'Usage: eval.sh engine IMAGE [garment,labels|no-outfit] | performance VARIANT IMAGE [repeats] | voice | acoustic | ui | ui-polish | caption-tts | wake-ui | camera | camera-empty'; exit 0 ;;
esac
if [[ "$mode" == ui || "$mode" == ui-polish || "$mode" == caption-tts || "$mode" == wake-ui || "$mode" == camera || "$mode" == camera-empty ]]; then
  if adb shell dumpsys window policy | grep -Eq 'showing=true|inputRestricted=true'; then
    echo 'Unlock the phone before UI tests; no test started.' >&2; exit 3
  fi
fi
# Direct instrumentation preserves installed application data and the provisioned model.
adb shell am force-stop "$package"
log="$OUTFIT_ROOT/eval/results/$mode-$(date +%Y%m%d-%H%M%S).txt"
args+=(-e class "$testclass")
adb shell am instrument -w -r "${args[@]}" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
python3 - "$log" <<'PY'
import re,sys
s=open(sys.argv[1]).read()
if not re.search(r'OK \(1 test\)',s) or 'FAILURES!!!' in s:
 raise SystemExit('Instrumentation did not pass; inspect '+sys.argv[1])
PY
