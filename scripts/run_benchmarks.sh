#!/usr/bin/env bash
# Runs the macrobenchmarks one test at a time and collects the results, so an interrupted run loses at most one test.
# Finished tests are skipped when the same output directory is used again.
#
# Usage: ANDROID_SERIAL=emulator-5554 scripts/run_benchmarks.sh <output-dir>
set -u
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the device to measure on}"
out="$(realpath -m "${1:?Output directory required}")"
mkdir -p "$out"
cd "$(dirname "$0")/.."

package=com.darshan.payflow
tests="StartupBenchmark#startupNoCompilation StartupBenchmark#startupPartial
ScrollBenchmark#homeScrollNoCompilation ScrollBenchmark#homeScrollPartial
ScrollBenchmark#historyScrollNoCompilation ScrollBenchmark#historyScrollPartial"

# Flings decay through the animation system, so the scale must match between runs.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$ANDROID_SERIAL" shell settings put global "$scale" 1
done

failed=0
for test in $tests; do
  name="${test#*#}"
  [ -s "$out/$name.json" ] && { echo "skip $name"; continue; }
  # A fresh install means no app lock and no leftover data.
  adb -s "$ANDROID_SERIAL" uninstall "$package" >/dev/null 2>&1
  echo "run $name"
  if ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$package.macrobenchmark.$test" >"$out/$name.log" 2>&1; then
    find macrobenchmark/build/outputs/connected_android_test_additional_output -name '*benchmarkData.json' -exec cp {} "$out/$name.json" \;
  else
    echo "FAILED $name (see $out/$name.log)"
    failed=1
  fi
done
python3 scripts/perf_summary.py "$out"/*.json
exit "$failed"
