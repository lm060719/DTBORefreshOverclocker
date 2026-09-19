#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

required="
$ROOT/settings.gradle.kts
$ROOT/build.gradle.kts
$ROOT/app/build.gradle.kts
$ROOT/app/src/main/AndroidManifest.xml
$ROOT/app/src/main/java/io/mo/dtbooverclocker/core/NativeToolExecutor.kt
$ROOT/app/src/main/java/io/mo/dtbooverclocker/core/SlotDetector.kt
$ROOT/app/src/main/java/io/mo/dtbooverclocker/core/DtboPatchEngine.kt
$ROOT/app/src/main/java/io/mo/dtbooverclocker/core/DtboImageCodec.kt
$ROOT/app/src/main/java/io/mo/dtbooverclocker/core/SafetyGuardManager.kt
$ROOT/app/src/main/java/io/mo/dtbooverclocker/ui/MainActivity.kt
"

for f in $required; do
    test -f "$f" || { echo "Missing: $f" >&2; exit 1; }
done

echo "Project source layout OK"
