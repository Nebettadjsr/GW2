#!/usr/bin/env python3
"""Observe-existing-stack guest command for the journey runner (STORY-RESOURCE-002). Operator-run over SSH.

It never starts, stops, builds or redeploys anything: it only reads /proc and `docker stats` of the already
running Compose project and writes bounded, timestamped samples. Whole-VM and per-container readings stay
separate boundaries (reusing host_resources and container_measure parsing); unavailable data is `null` with an
`unavailableReason`, never zero. Output is `preparation-only` / `not-qualified`.

  python3 scripts/guest_observe.py sample --seconds 120 --out guest-samples.json [--project gw2]
  python3 scripts/guest_observe.py align --samples guest-samples.json --run <journey-run>.json --out aligned.json
  python3 scripts/guest_observe.py overhead --seconds 30 --out overhead.json

`sample` and `overhead` run on the guest; copy the run JSON from the generator (or the samples back) with scp, e.g.
`scp gw2lab@172.24.59.83:~/guest-samples.json .` then `align` anywhere. Clocks of generator and guest are separate:
pass `--clock-offset-ms` (guest minus generator) if they are known to differ; the offset is recorded, not guessed.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import container_measure as cm  # noqa: E402
import host_resources as hr  # noqa: E402

SCHEMA = "gw2-guest-observation-3"
MODE = {"mode": "preparation-only", "qualification": "not-qualified"}
MAX_SAMPLES = 3600
MAX_SECONDS = 3600


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_utc(text: str) -> float:
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def container_sample(project: str, runner=cm.run) -> dict:
    """Per-container CPU/memory of the compose project's containers; the whole result may be unavailable."""
    try:
        names = runner(["docker", "ps", "--filter", f"label=com.docker.compose.project={project}", "--format", "{{.Names}}"], timeout=30)
        listed = [n for n in names.stdout.split() if n]
        if names.returncode != 0 or not listed:
            return {"value": None, "unavailableReason": f"no running containers for compose project {project!r} (docker exit {names.returncode})"}
        stats = runner(["docker", "stats", "--no-stream", "--format", "{{json .}}", *listed], timeout=60)
    except (OSError, subprocess.SubprocessError) as error:
        return {"value": None, "unavailableReason": f"docker unavailable: {type(error).__name__}"}
    value = {}
    for line in stats.stdout.splitlines():
        parsed = cm.parse_stats_line(line)
        if parsed:
            value[parsed["name"]] = {"cpuPercent": parsed["cpu_percent"], "memBytes": parsed["mem_bytes"]}
    if not value:
        return {"value": None, "unavailableReason": "docker stats returned no parsable container lines"}
    return {"boundary": hr.BOUNDARY_CONTAINERS, "value": value}


def host_point(sampler: hr.HostSampler) -> dict:
    before_cpu, before_mem = len(sampler.cpu), len(sampler.mem_used)
    previous_read_start = getattr(sampler, "guest_cpu_read_start", None)
    read_start = utc_now()
    sampler.sample()
    read_end = utc_now()
    # A CPU delta spans two counter reads, unlike the current memory point. Bracket both
    # reads conservatively; the actual /proc/stat reads occur inside these sample calls.
    sampler.guest_cpu_read_start = read_start if sampler.prev is not None else None
    cpu = sampler.cpu[-1] if len(sampler.cpu) > before_cpu else None
    mem = sampler.mem_used[-1] if len(sampler.mem_used) > before_mem else None
    point = {"boundary": hr.BOUNDARY_HOST}
    point["cpuPercent"] = cpu if cpu is not None else None
    point["memUsedBytes"] = mem if mem is not None else None
    if cpu is not None and previous_read_start is not None:
        point["cpuObservationStartedUtc"] = previous_read_start
        point["cpuObservationEndedUtc"] = read_end
    if cpu is None:
        point["cpuUnavailableReason"] = (sampler.cpu_reasons or ["first sample has no delta"])[-1]
    if mem is None:
        point["memUnavailableReason"] = (sampler.mem_reasons or ["unavailable"])[-1]
    return point


def sample(seconds: int, project: str, containers=container_sample, sampler=None, sleep=time.sleep) -> dict:
    if not 1 <= seconds <= MAX_SECONDS:
        raise ValueError(f"--seconds must be 1..{MAX_SECONDS}")
    sampler = sampler or hr.HostSampler()
    points, started_mono = [], time.monotonic()
    started_utc = utc_now()
    while time.monotonic() - started_mono < seconds and len(points) < MAX_SAMPLES:
        tick = time.monotonic()
        point = {"utc": utc_now(), "monoMs": round((tick - started_mono) * 1000)}
        host_start = utc_now()
        point["wholeVm"] = {**host_point(sampler), "collectionStartedUtc": host_start, "collectionEndedUtc": utc_now()}
        container_start = utc_now()
        point["containers"] = {**containers(project), "collectionStartedUtc": container_start, "collectionEndedUtc": utc_now()}
        points.append(point)
        sleep(max(0.0, 1.0 - (time.monotonic() - tick)))
    return {
        "schema": SCHEMA, "kind": "samples", **MODE, "startedAtUtc": started_utc, "endedAtUtc": utc_now(), "project": project,
        "samples": points, "truncatedSamples": 0 if len(points) < MAX_SAMPLES else "cap reached",
        "boundaries": {"wholeVm": hr.BOUNDARY_HOST, "containers": hr.BOUNDARY_CONTAINERS},
        "limits": hr.SAMPLING_LIMITS,
        "unavailable": {
            "gw2OutboundTraffic": "backend container network traffic is not GW2-specific and is not collected",
            "jvmHeapGc": "continuous JVM heap/GC is optional and not collected",
            "disk": "not collected by this minimal observer", "database": "not collected by this minimal observer",
        },
    }


def window_summary(samples: list[dict], container_samples: list[dict] | None = None, cpu_samples: list[dict] | None = None) -> dict:
    container_samples = samples if container_samples is None else container_samples
    cpu_samples = samples if cpu_samples is None else cpu_samples
    cpu = [s["wholeVm"]["cpuPercent"] for s in cpu_samples if s["wholeVm"].get("cpuPercent") is not None]
    mem = [s["wholeVm"]["memUsedBytes"] for s in samples if s["wholeVm"].get("memUsedBytes") is not None]
    vm = {"boundary": hr.BOUNDARY_HOST, "cpuPercent": hr.stats(cpu) if cpu else {"count": 0, "unavailableReason": "no whole-VM CPU samples in window"},
          "memUsedBytes": hr.stats(mem) if mem else {"count": 0, "unavailableReason": "no whole-VM memory samples in window"}}
    per: dict[str, list[dict]] = {}
    reasons = []
    for s in container_samples:
        value = s["containers"].get("value")
        if value is None:
            reasons.append(s["containers"].get("unavailableReason", "unavailable"))
            continue
        for name, reading in value.items():
            per.setdefault(name, []).append({"cpu_percent": reading["cpuPercent"], "mem_bytes": reading["memBytes"]})
    containers = {"boundary": hr.BOUNDARY_CONTAINERS, "perContainer": {name: cm.summarize(rows) for name, rows in sorted(per.items())}}
    if not per:
        containers["unavailableReason"] = reasons[-1] if reasons else "no container samples in window"
    return {"sampleCount": len(samples), "sampleCounts": {"wholeVm": len(samples), "wholeVmCpu": len(cpu_samples), "containers": len(container_samples)}, "wholeVm": vm, "containers": containers,
            "note": "Whole-VM and container readings are separate; host minus containers is not attributed overhead."}


def align(samples_doc: dict, run: dict, clock_offset_ms: int = 0) -> dict:
    """Slices guest samples to the run's timing window (UTC), after an explicit, recorded clock offset."""
    window = run["resources"]["alignmentWindow"]
    start = parse_utc(window["startUtc"]) + clock_offset_ms / 1000
    end = parse_utc(window["endUtc"]) + clock_offset_ms / 1000
    def contained(point, boundary, a_key="collectionStartedUtc", b_key="collectionEndedUtc"):
        reading = point.get(boundary, {})
        a, b = reading.get(a_key), reading.get(b_key)
        return bool(a and b and start <= parse_utc(a) <= parse_utc(b) <= end)

    host_inside = [s for s in samples_doc["samples"] if contained(s, "wholeVm")]
    cpu_inside = [s for s in samples_doc["samples"] if contained(s, "wholeVm", "cpuObservationStartedUtc", "cpuObservationEndedUtc")]
    container_inside = [s for s in samples_doc["samples"] if contained(s, "containers")]
    out = {
        "schema": SCHEMA, "kind": "aligned", **MODE, "runId": run["runId"], "journey": run["journey"],
        "generatorWindow": window, "clockOffsetMs": clock_offset_ms,
        "clockNote": "Generator and guest clocks are independent; the offset is operator-supplied, never inferred.",
        "covered": bool(host_inside and cpu_inside and container_inside),
        "coverage": {"wholeVm": bool(host_inside), "wholeVmCpu": bool(cpu_inside), "containers": bool(container_inside)},
        "alignmentNote": "Memory and containers use their own full collection intervals; whole-VM CPU uses conservative brackets around BOTH counter reads. Partial overlaps and legacy samples without intervals are excluded. CPU values are interval observations, not instantaneous peaks.",
        "window": window_summary(host_inside, container_inside, cpu_inside) if host_inside or cpu_inside or container_inside else {
            "sampleCount": 0, "unavailableReason": "no guest samples fall inside the run window"},
        "generatorHealth": run.get("generatorHealth"),
        "generatorHealthNote": "Generator health is reported apart from guest/container health; it is not VM saturation.",
        "conclusion": "No supported-user, capacity or qualification conclusion is made or implied.",
    }
    return out


def cpu_window(seconds: int, work, sampler_factory=hr.HostSampler) -> dict:
    """Whole-VM CPU over `seconds` from the /proc/stat delta at the window ends (one read pair)."""
    sampler = sampler_factory()
    sampler.sample()
    work(seconds)
    sampler.sample()
    return {"cpuPercent": sampler.cpu[-1] if sampler.cpu else None, "reason": None if sampler.cpu else (sampler.cpu_reasons or ["unavailable"])[-1]}


def overhead(seconds: int, project: str) -> dict:
    """Idle whole-VM CPU with the observer disabled (passive wait) vs enabled (the sample loop), one window each."""
    disabled = cpu_window(seconds, time.sleep)
    enabled = cpu_window(seconds, lambda s: sample(s, project))
    delta = None if disabled["cpuPercent"] is None or enabled["cpuPercent"] is None else round(enabled["cpuPercent"] - disabled["cpuPercent"], 3)
    return {
        "schema": SCHEMA, "kind": "observer-overhead", **MODE, "seconds": seconds, "observerDisabled": disabled, "observerEnabled": enabled,
        "differencePercentagePoints": delta,
        "unavailableReason": None if delta is not None else "a window had no CPU delta",
        "note": "Two idle windows, not repeated trials: whole-VM background noise is included, so the difference is an observation, not a measured cost.",
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("sample")
    s.add_argument("--seconds", type=int, default=60)
    s.add_argument("--project", default="gw2")
    s.add_argument("--out", required=True)
    a = sub.add_parser("align")
    a.add_argument("--samples", required=True)
    a.add_argument("--run", required=True)
    a.add_argument("--out", required=True)
    a.add_argument("--clock-offset-ms", type=int, default=0)
    o = sub.add_parser("overhead")
    o.add_argument("--seconds", type=int, default=30)
    o.add_argument("--project", default="gw2")
    o.add_argument("--out", required=True)
    args = p.parse_args(argv)
    if args.command == "sample":
        doc = sample(args.seconds, args.project)
    elif args.command == "align":
        doc = align(json.loads(Path(args.samples).read_text()), json.loads(Path(args.run).read_text()), args.clock_offset_ms)
    else:
        doc = overhead(args.seconds, args.project)
    Path(args.out).write_text(json.dumps(doc, indent=2) + "\n")
    print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
