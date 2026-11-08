#!/usr/bin/env python3
"""Prints a Markdown table from Macrobenchmark results.

Usage: scripts/perf_summary.py <result.json> [...]

Jank % is the share of frames with frameOverrunMs > 0 (the frame missed its deadline). P50/P90/P99 are taken over all
frames from all iterations, the same way Macrobenchmark reports them.
"""
import json
import sys


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def flatten(metric):
    return [value for run in metric["runs"] for value in run]


def startup_row(name, result):
    metric = result["metrics"]["timeToInitialDisplayMs"]
    return f"| {name} | {metric['minimum']:.0f} | {metric['median']:.0f} | {metric['maximum']:.0f} | {len(metric['runs'])} |"


def frame_row(name, result):
    sampled = result["sampledMetrics"]
    cpu = flatten(sampled["frameDurationCpuMs"])
    overrun = flatten(sampled["frameOverrunMs"]) if "frameOverrunMs" in sampled else None
    cells = [f"{percentile(cpu, f):.1f}" for f in (0.5, 0.9, 0.99)]
    if overrun:
        cells += [f"{percentile(overrun, f):.1f}" for f in (0.5, 0.9, 0.99)]
        cells.append(f"{100 * sum(1 for v in overrun if v > 0) / len(overrun):.1f}")
    else:
        cells += ["n/a"] * 4
    return f"| {name} | " + " | ".join(cells) + f" | {len(cpu)} |"


def main(paths):
    if not paths:
        sys.exit(__doc__)
    startup, frames = [], []
    for path in paths:
        with open(path) as handle:
            for result in json.load(handle)["benchmarks"]:
                name = result["name"].removeprefix("EMULATOR_")
                if "timeToInitialDisplayMs" in result.get("metrics", {}):
                    startup.append(startup_row(name, result))
                elif "frameDurationCpuMs" in result.get("sampledMetrics", {}):
                    frames.append(frame_row(name, result))
    if startup:
        print("| Cold start (time to initial display, ms) | min | median | max | iterations |\n|---|---|---|---|---|")
        print("\n".join(sorted(startup)))
        print()
    if frames:
        print("| Scroll | CPU P50 ms | CPU P90 ms | CPU P99 ms | Overrun P50 ms | Overrun P90 ms | Overrun P99 ms | Jank % | frames |\n|---|---|---|---|---|---|---|---|---|")
        print("\n".join(sorted(frames)))


if __name__ == "__main__":
    main(sys.argv[1:])
