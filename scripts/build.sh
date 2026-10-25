#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
if [[ $# -eq 0 ]]; then set -- assembleDebug testDebugUnitTest lintDebug; fi
exec "$OUTFIT_ROOT/android/gradlew" -p "$OUTFIT_ROOT/android" "$@"
