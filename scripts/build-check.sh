#!/usr/bin/env bash
# 编译 shell 源并跑纯 JVM 自测；首次运行会下载 android-all 与 json。
set -euo pipefail
cd "$(dirname "$0")/.."

CACHE="${JAR_CACHE:-.cache}"
mkdir -p "$CACHE"
ANDROID_ALL="$CACHE/android-all.jar"
JSON_JAR="$CACHE/json.jar"
[ -f "$ANDROID_ALL" ] || curl -fsSL \
  "https://repo1.maven.org/maven2/org/robolectric/android-all/13-robolectric-9030017/android-all-13-robolectric-9030017.jar" \
  -o "$ANDROID_ALL"
[ -f "$JSON_JAR" ] || curl -fsSL \
  "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar" -o "$JSON_JAR"

rm -rf build/classes
mkdir -p build/classes
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp "$ANDROID_ALL:$JSON_JAR" -d build/classes \
  $(find app/src/shell/java tests -name '*.java')

java -cp "build/classes:$ANDROID_ALL:$JSON_JAR" com.jiyan.cloud.shell.ShellCryptoTest
