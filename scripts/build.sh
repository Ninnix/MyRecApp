#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.local/opt/android-studio/jbr/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/opt/android-studio/jbr"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
exec ./gradlew "$@"
