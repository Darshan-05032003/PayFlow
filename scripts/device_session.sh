#!/usr/bin/env bash
# One device session for Macrobenchmark and the Baseline Profile, with Gradle and the emulator never up together:
# every APK is built first, Gradle exits, then the emulator boots and the tests run through `adb shell am instrument`.
#
#   scripts/device_session.sh build          <out>   Gradle: current tree -> <out>/after/*.apk
#   scripts/device_session.sh build-baseline <out>   Gradle: commit 9c48cc2 in a git worktree -> <out>/before/*.apk
#   scripts/device_session.sh bench <apk-dir> <results-dir>   adb only: startup + scroll benchmarks, one test per run
#   scripts/device_session.sh profile <apk-dir> <results-dir> adb only: Baseline Profile generator, pulls the profile
#   scripts/device_session.sh all <out>      all of the above in order, booting and stopping the emulator itself
#
# The emulator is always emulator-5554 (never another connected device), booted only by ../tools/safe_emulator.sh.
# Numbers are emulator numbers: compare before and after from the same session, never against a phone.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tools="$root/../tools"
serial=emulator-5554
adb=(adb -s "$serial")
app_pkg=com.darshan.payflow
bench_pkg=com.darshan.payflow.macrobenchmark
profile_pkg=com.darshan.payflow.baselineprofile
runner=androidx.test.runner.AndroidJUnitRunner
baseline_commit=9c48cc2
tests="${BENCHMARK_TESTS:-StartupBenchmark#startupNoCompilation StartupBenchmark#startupPartial
ScrollBenchmark#homeScrollNoCompilation ScrollBenchmark#homeScrollPartial
ScrollBenchmark#historyScrollNoCompilation ScrollBenchmark#historyScrollPartial}"

die() { echo "device_session: $*" >&2; exit 1; }
# Only our builds count: safe_gradle.sh runs the wrapper without a daemon. An editor's own Gradle daemon (VS Code keeps
# one up all day) never shares the emulator's memory budget with us, so it must not block the session.
no_gradle() { ! pgrep -f '^[^ ]*java .*GradleWrapperMain' >/dev/null || die "a Gradle build is running; wait for it to exit"; }
need_emulator() { [ "$("${adb[@]}" get-state 2>/dev/null)" = device ] || die "$serial is not up (../tools/safe_emulator.sh start)"; }

# A variant built from release can come out unsigned; adb only installs signed APKs, so sign it with the debug key.
sign_if_unsigned() { # <apk>
    case "$(basename "$1")" in *unsigned*) ;; *) return 0 ;; esac
    local signer
    signer="$(ls -d "$HOME"/Android/Sdk/build-tools/*/apksigner | sort -V | tail -1)"
    "$signer" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --out "${1/-unsigned/}" "$1"
}

# APKs for one tree: the release-like app the benchmarks target, the self-instrumenting benchmark APK, and the
# profile generator with the non-minified app it profiles. One Gradle run per pair, through the memory-capped wrapper.
build_tree() { # <project-dir> <apk-out> <with-profile>
    local dir="$1" out="$2"
    mkdir -p "$out"
    (cd "$dir" && "$tools/safe_gradle.sh" :app:assembleBenchmark :macrobenchmark:assembleBenchmark)
    cp "$dir/app/build/outputs/apk/benchmark/app-benchmark.apk" "$out/app-benchmark.apk"
    cp "$dir"/macrobenchmark/build/outputs/apk/benchmark/*.apk "$out/macrobenchmark.apk"
    if [ "$3" = yes ]; then
        (cd "$dir" && "$tools/safe_gradle.sh" :app:assembleNonMinifiedRelease :baselineprofile:assembleNonMinifiedRelease)
        local app_apk
        app_apk="$(ls "$dir"/app/build/outputs/apk/nonMinifiedRelease/*.apk | head -1)"
        sign_if_unsigned "$app_apk"
        cp "${app_apk/-unsigned/}" "$out/app-nonminified.apk"
        cp "$dir"/baselineprofile/build/outputs/apk/nonMinifiedRelease/*.apk "$out/baselineprofile.apk"
    fi
    ls -l "$out"
}

cmd_build() { build_tree "$root" "${1:?out dir}/after" yes; }

cmd_build_baseline() {
    local out="${1:?out dir}/before" wt="$root/../wt-payflow-baseline"
    [ -d "$wt" ] || git -C "$root" worktree add --detach "$wt" "$baseline_commit"
    cp "$root/local.properties" "$wt/local.properties"
    build_tree "$wt" "$out" no
    echo "Worktree kept at $wt; remove it with: git -C $root worktree remove $wt"
}

# Results go to a subfolder of the test app's media folder, as Gradle's connected tasks do. Deleting the media folder
# itself from the shell (or writing results straight into it) made the Perfetto trace move fail after the first iteration.
cmd_bench() {
    local apks="${1:?apk dir}" results="${2:?results dir}" media="/sdcard/Android/media/$bench_pkg/additional_test_output"
    no_gradle; need_emulator; mkdir -p "$results"
    # Flings decay through the animation system, so every run uses the same scales.
    for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
        "${adb[@]}" shell settings put global "$scale" 1
    done
    "${adb[@]}" install -r -t "$apks/macrobenchmark.apk" >/dev/null
    for test in $tests; do
        local name="${test#*#}"
        [ -s "$results/$name.json" ] && { echo "skip $name"; continue; }
        # A fresh install per test: no app lock, no data but the seed the benchmark itself broadcasts.
        "${adb[@]}" uninstall "$app_pkg" >/dev/null 2>&1 || true
        "${adb[@]}" install -r -t "$apks/app-benchmark.apk" >/dev/null
        "${adb[@]}" shell "rm -rf $media && mkdir -p $media"
        echo "run $name"
        "${adb[@]}" shell am instrument -w -r \
            -e class "$bench_pkg.$test" \
            -e androidx.benchmark.suppressErrors EMULATOR \
            -e additionalTestOutputDir "$media" \
            "$bench_pkg/$runner" >"$results/$name.log" 2>&1 || true
        if grep -q "OK (1 test)" "$results/$name.log"; then
            local json
            json="$("${adb[@]}" shell "ls $media/*benchmarkData.json" 2>/dev/null | tr -d '\r' | head -1)"
            [ -n "$json" ] && "${adb[@]}" pull "$json" "$results/$name.json" >/dev/null || echo "FAILED $name: no JSON on the device"
        else
            echo "FAILED $name (see $results/$name.log)"
        fi
    done
    if ls "$results"/*.json >/dev/null 2>&1; then
        python3 "$root/scripts/perf_summary.py" "$results"/*.json | tee "$results/summary.md"
    else
        echo "no results yet in $results"
    fi
}

cmd_profile() {
    local apks="${1:?apk dir}" results="${2:?results dir}" media="/sdcard/Android/media/$profile_pkg/additional_test_output"
    no_gradle; need_emulator; mkdir -p "$results"
    "${adb[@]}" uninstall "$app_pkg" >/dev/null 2>&1 || true
    "${adb[@]}" install -r -t "$apks/app-nonminified.apk" >/dev/null
    "${adb[@]}" install -r -t "$apks/baselineprofile.apk" >/dev/null
    "${adb[@]}" shell "rm -rf $media && mkdir -p $media"
    "${adb[@]}" shell am instrument -w -r \
        -e class "$profile_pkg.BaselineProfileGenerator#generate" \
        -e androidx.benchmark.enabledRules BaselineProfile \
        -e additionalTestOutputDir "$media" \
        "$profile_pkg/$runner" >"$results/profile.log" 2>&1 || true
    grep -q "OK (1 test)" "$results/profile.log" || die "generator failed (see $results/profile.log)"
    "${adb[@]}" pull "$media" "$results/profile" >/dev/null
    ls -l "$results/profile"
    echo "Next (after stopping the emulator): copy the *-baseline-prof.txt into"
    echo "  app/src/main/generated/baselineProfiles/baseline-prof.txt (and *-startup-prof.txt to startup-prof.txt),"
    echo "  rebuild with build, and re-run bench for the Partial numbers with the profile."
}

cmd_all() {
    local out="${1:?out dir}"
    out="$(realpath -m "$out")"
    cmd_build "$out"
    cmd_build_baseline "$out"
    no_gradle
    "$tools/safe_emulator.sh" start
    trap '"$tools/safe_emulator.sh" stop' EXIT
    cmd_bench "$out/after" "$out/results-after"
    cmd_bench "$out/before" "$out/results-before"
    cmd_profile "$out/after" "$out/results-after"
}

case "${1:-}" in
    build) cmd_build "${2:-}" ;;
    build-baseline) cmd_build_baseline "${2:-}" ;;
    bench) cmd_bench "${2:-}" "${3:-}" ;;
    profile) cmd_profile "${2:-}" "${3:-}" ;;
    all) cmd_all "${2:-}" ;;
    *) sed -n '2,13p' "$0"; exit 2 ;;
esac
