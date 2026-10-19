#!/bin/zsh
# Build the launcher, install it on the TV, and (re)enable the Home-button redirect.
# Usage: ./install.sh            (TV at 192.168.0.2)
#        ANDROID_SERIAL=<ip>:5555 ./install.sh
set -e
cd "${0:A:h}"
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.0.2:5555}
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
SERVICE=tv.minimal.home/tv.minimal.home.HomeRedirectService

adb connect $ANDROID_SERIAL >/dev/null
./gradlew -q assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# Toggle off/on so Android re-reads the service config after an update.
adb shell settings put secure enabled_accessibility_services '""'
sleep 1
adb shell settings put secure enabled_accessibility_services $SERVICE
adb shell settings put secure accessibility_enabled 1
adb shell am start -n tv.minimal.home/.MainActivity >/dev/null
echo "Installed; redirect: $(adb shell settings get secure enabled_accessibility_services)"
