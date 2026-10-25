#!/bin/bash
OUTFIT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -d "$OUTFIT_ROOT/../mobile-coach/.tools/android-sdk" ]]; then
  export ANDROID_HOME="${ANDROID_HOME:-$OUTFIT_ROOT/../mobile-coach/.tools/android-sdk}"
else
  export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -d "$OUTFIT_ROOT/../mobile-coach/.gradle-home" ]]; then
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$OUTFIT_ROOT/../mobile-coach/.gradle-home}"
fi
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 23 2>/dev/null || /usr/libexec/java_home -v 17)"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
export PATH="$ANDROID_HOME/platform-tools:$PATH"
