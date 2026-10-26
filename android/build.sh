#!/bin/sh
# Builds the APK with the portable tools in Claudio/_tools (nothing installed system-wide).
T="$(cd "$(dirname "$0")/../../_tools" && pwd)"
export JAVA_HOME="$T/jdk-17.0.20.1+1" ANDROID_HOME="$T/android-sdk" GRADLE_USER_HOME="$T/gradle-home" ANDROID_USER_HOME="$T/android-home"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
echo "sdk.dir=$(cygpath -m "$ANDROID_HOME" 2>/dev/null || echo "$ANDROID_HOME")" > local.properties
"$T/gradle-8.10.2/bin/gradle" --no-daemon -q "${@:-assembleRelease}" && \
  cp app/build/outputs/apk/release/app-release.apk DieselHeater.apk && ls -la DieselHeater.apk
