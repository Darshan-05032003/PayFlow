#!/usr/bin/env bash
# Light before/after measurement without Macrobenchmark or Perfetto (a Perfetto startup run got the capped emulator
# OOM-killed). Cold start from `am start -W` and frame stats from `dumpsys gfxinfo`, the same method on both builds.
#
#   scripts/light_bench.sh <app-benchmark.apk> <results-dir>
#
# Per build: fresh install, the benchmark seed broadcast (200 settled payments), dex reset to `verify` (no AOT code, like
# Macrobenchmark's CompilationMode.None), one discarded launch, then COLD_RUNS cold starts (force-stop + am start -W).
# Frames: ROUNDS rounds per screen; each round is a cold launch, gfxinfo reset, FLINGS flings down and FLINGS up.
# Emulator only (emulator-5554), adb only: no Gradle may run while the emulator is up.
# Emulator numbers with software rendering: compare before and after from one session, never against a phone or against
# Macrobenchmark tables.
set -euo pipefail

apk="${1:?app-benchmark.apk}"
results="$(realpath -m "${2:?results dir}")"
adb=(adb -s emulator-5554)
pkg=com.darshan.payflow
cold_runs="${COLD_RUNS:-10}"
rounds="${ROUNDS:-3}"
flings="${FLINGS:-3}"
history_tag="payflow:home:history"

pgrep -f '^[^ ]*java .*GradleWrapperMain' >/dev/null && { echo "light_bench: a Gradle build is running" >&2; exit 1; }
[ "$("${adb[@]}" get-state 2>/dev/null)" = device ] || { echo "light_bench: emulator-5554 is not up" >&2; exit 1; }
mkdir -p "$results"

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "${adb[@]}" shell settings put global "$scale" 1
done
"${adb[@]}" uninstall "$pkg" >/dev/null 2>&1 || true
"${adb[@]}" install -r -t "$apk" >/dev/null
seed="$("${adb[@]}" shell am broadcast --include-stopped-packages -n "$pkg/.benchmark.SeedDataReceiver" | tr -d '\r')"
case "$seed" in *seeded=*) ;; *) echo "light_bench: seeding failed: $seed" >&2; exit 1 ;; esac
"${adb[@]}" shell cmd package compile -f -m verify "$pkg" >/dev/null
activity="$("${adb[@]}" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r' | tail -1)"
echo "activity $activity" | tee "$results/meta.txt"

size="$("${adb[@]}" shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)"
width="${size%x*}"; height="${size#*x}"
x=$((width / 2)); low=$((height * 3 / 4)); high=$((height / 4))

cold_launch() { # prints TotalTime
    "${adb[@]}" shell am force-stop "$pkg"
    sleep 1
    "${adb[@]}" shell am start -W -n "$activity" | tr -d '\r' | awk -F': ' '/TotalTime/{print $2}'
}

cold_launch >/dev/null # first launch creates the database and caches; not measured
sleep 3
: >"$results/startup.txt"
for i in $(seq 1 "$cold_runs"); do
    t="$(cold_launch)"
    echo "$t" >>"$results/startup.txt"
    echo "cold start $i: $t ms"
    sleep 2
done

tap_tag() { # <test tag>: taps the node whose resource-id is the tag (EXPOSE_TEST_TAGS)
    "${adb[@]}" shell uiautomator dump /sdcard/light_bench.xml >/dev/null 2>&1
    local b
    b="$("${adb[@]}" shell cat /sdcard/light_bench.xml | tr '>' '\n' | grep -F "resource-id=\"$1\"" | head -1 |
        grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')"
    [ -n "$b" ] || { echo "light_bench: no node tagged $1" >&2; return 1; }
    set -- $b
    "${adb[@]}" shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}

for screen in home history; do
    for round in $(seq 1 "$rounds"); do
        cold_launch >/dev/null
        sleep 3
        if [ "$screen" = history ]; then tap_tag "$history_tag"; sleep 3; fi
        "${adb[@]}" shell dumpsys gfxinfo "$pkg" reset >/dev/null
        for _ in $(seq 1 "$flings"); do "${adb[@]}" shell input swipe "$x" "$low" "$x" "$high" 120; sleep 1.2; done
        for _ in $(seq 1 "$flings"); do "${adb[@]}" shell input swipe "$x" "$high" "$x" "$low" 120; sleep 1.2; done
        sleep 1
        "${adb[@]}" shell dumpsys gfxinfo "$pkg" | tr -d '\r' >"$results/gfx-$screen-$round.txt"
        echo "$screen round $round: $(grep -E 'Total frames|Janky frames:' "$results/gfx-$screen-$round.txt" | head -2 | tr '\n' ' ')"
    done
done
"${adb[@]}" shell am force-stop "$pkg"

python3 - "$results" <<'EOF' | tee "$results/summary.md"
import pathlib, re, statistics, sys
d = pathlib.Path(sys.argv[1])
times = [int(t) for t in (d / "startup.txt").read_text().split()]
print("| Cold start (am start -W TotalTime, ms) | min | median | max | runs |\n|---|---|---|---|---|")
print(f"| cold | {min(times)} | {statistics.median(times):.0f} | {max(times)} | {len(times)} |\n")
print("| Screen | frames | janky % | P50 ms | P90 ms | P95 ms | P99 ms | rounds |\n|---|---|---|---|---|---|---|---|")
def first(pattern, text):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else float("nan")
for screen in ("home", "history"):
    rows = []
    for f in sorted(d.glob(f"gfx-{screen}-*.txt")):
        t = f.read_text()
        rows.append(dict(total=first(r"Total frames rendered: (\d+)", t), janky=first(r"Janky frames: (\d+)", t),
                         **{p: first(rf"{p}th percentile: (\d+)ms", t) for p in ("50", "90", "95", "99")}))
    if not rows:
        continue
    total = sum(r["total"] for r in rows); janky = sum(r["janky"] for r in rows)
    med = {p: statistics.median(r[p] for r in rows) for p in ("50", "90", "95", "99")}
    print(f"| {screen} | {total:.0f} | {100 * janky / total:.1f} | {med['50']:.0f} | {med['90']:.0f} | {med['95']:.0f} | {med['99']:.0f} | {len(rows)} |")
print("\nPercentiles: median over rounds of gfxinfo's per-round percentile. Janky %: janky frames / all frames, all rounds.")
EOF
