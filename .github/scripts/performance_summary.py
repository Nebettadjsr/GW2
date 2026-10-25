#!/usr/bin/env python3
"""Informational GitHub Actions summary for the application resource measurements.

Never raises on bad input: a missing, unreadable or incompatible report becomes text in the
summary. The exit status is always 0 so the performance workflow cannot block delivery.
Deltas are shown only against a previous report with the same schema, fixture version and
environment (which records the benchmark sizes); nothing is compared across those boundaries.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

CONTAINER_SCOPE_NOTE = (
    "Container measurements (`container-stack`) are Docker-reported figures for the owned three-service "
    "Compose stack (frontend, backend, PostgreSQL) under a global-read workload. They are not crafting, "
    "account-sync or live-upstream performance, not hosting capacity, and not comparable with the "
    "application-process section above."
)

SCOPE_NOTE = (
    "Measurements are for the application JVM process (`application-process`), not a container; "
    "they include the in-process load client and fixture writers. Shared runners are noisy and "
    "nothing here states hosting capacity."
)


def load(path: Path | None) -> tuple[dict | None, str | None]:
    """Return (report, problem). Exactly one is None."""
    if path is None or not path.is_file():
        return None, "no report file was produced"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return None, f"report could not be read: {error}"
    if not isinstance(data, dict) or not isinstance(data.get("workloads"), list):
        return None, "report has no workload list"
    return data, None


def incompatibility(current: dict, previous: dict) -> str | None:
    """Why the two reports must not be compared, or None when they may be."""
    for key in ("schemaVersion", "fixtureVersion", "measurementScope", "workloadVersion"):
        if current.get(key) != previous.get(key):
            return f"{key} differs ({previous.get(key)!r} -> {current.get(key)!r})"
    cur_env, prev_env = current.get("environment") or {}, previous.get("environment") or {}
    if current.get("measurementScope") == "container-stack":
        for key in ("engineVersion", "enginePlatform", "postgresImageId", "backendRuntimeBaseLayers", "frontendRuntimeBaseLayers"):
            if any(env.get(key) in (None, "unknown", []) for env in (cur_env, prev_env)):
                return f"runtime metadata unavailable ({key})"
    changed = sorted(k for k in set(cur_env) | set(prev_env) if cur_env.get(k) != prev_env.get(k))
    if changed:
        return "environment differs (" + ", ".join(changed) + ")"
    return None


def metric_index(report: dict) -> dict[tuple[str, str], dict]:
    out = {}
    for workload in report.get("workloads", []):
        for metric in workload.get("metrics", []):
            out[(workload.get("name", "?"), metric.get("name", "?"))] = metric
    return out


def number(value) -> str:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return str(value)
    if value == int(value) and abs(value) < 1e15:
        return str(int(value))
    return f"{value:.3f}"


def delta(current: dict, previous: dict | None) -> str:
    if previous is None:
        return ""
    cur, prev = current.get("value"), previous.get("value")
    if not all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in (cur, prev)):
        return "n/a"
    if current.get("unit") != previous.get("unit"):
        return "n/a (unit changed)"
    diff = cur - prev
    pct = f" ({diff * 100 / prev:+.1f}%)" if prev else ""
    return f"{diff:+.3f}{pct}".replace(".000", "")


def cell(text) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def metadata(report: dict | None, args) -> dict:
    env = (report or {}).get("environment") or {}
    return {
        "commit": (report or {}).get("commit") or args.commit,
        "workflowCommit": args.commit,
        "timestamp": (report or {}).get("timestamp") or args.timestamp,
        "workloads": [w.get("name") for w in (report or {}).get("workloads", [])],
        "fixtureVersion": (report or {}).get("fixtureVersion"),
        "schemaVersion": (report or {}).get("schemaVersion"),
        "measurementScope": (report or {}).get("measurementScope"),
        "environment": env,
        "runUrl": args.run_url,
        "benchmarkOutcome": args.outcome,
        "reportAvailable": report is not None,
        "workloadVersion": (report or {}).get("workloadVersion"),
        "images": (report or {}).get("images"),
    }


def render(report, problem, previous, previous_problem, args, container=False) -> tuple[str, dict]:
    title = "Container performance" if container else "Application performance"
    lines = [f"## {title} (informational, nonblocking)", ""]
    lines.append(f"- Commit: `{args.commit}`")
    lines.append(f"- Trigger: {args.event or 'unknown'}; run: {args.run_url or 'n/a'}")
    lines.append(f"- Benchmark step outcome: **{args.outcome}**")
    lines.append(f"- {CONTAINER_SCOPE_NOTE if container else SCOPE_NOTE}")
    lines.append("")
    if args.setup_log and Path(args.setup_log).is_file():
        tail = Path(args.setup_log).read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
        if args.outcome != "success":
            lines += ["### Benchmark/setup log (last lines)", "", "```", *tail, "```", ""]
    comparison = None
    if report is None:
        lines += [f"### No valid report", "", f"Reason: {problem}. No measurements, deltas or "
                  "workload results are available for this run. This does not fail CI.", ""]
        return "\n".join(lines) + "\n", metadata(None, args)

    lines += [f"- Report timestamp: {report.get('timestamp')}; fixture: `{report.get('fixtureVersion')}`; "
              f"schema: `{report.get('schemaVersion')}`"]
    env = report.get("environment") or {}
    lines += [f"- Environment: " + ", ".join(f"{k}={v}" for k, v in env.items()), ""]
    if container:
        lines += ["### Images (provenance; application image ids change with code)", "",
                  "| Service | Image id | Size (bytes) |", "|---|---|---|"]
        for svc, img in (report.get("images") or {}).items():
            size = img.get("sizeBytes")
            lines.append(f"| {cell(svc)} | `{cell(img.get('id'))}` | {size if size is not None else 'unavailable'} |")
        lines += ["", "Image sizes are on-disk Docker sizes, not memory or capacity evidence.",
                  f"CPU unit: {report.get('cpuUnit', 'unknown')}.", ""]
        lines += ["**Not measured in container mode:**", ""] + [f"- {n}" for n in report.get("notMeasured", [])] + [""]

    if previous is None:
        lines += [f"**Comparison:** no previous report to compare ({previous_problem}).", ""]
    else:
        reason = incompatibility(report, previous)
        if reason:
            lines += [f"**Comparison:** previous report (commit `{previous.get('commit')}`, "
                      f"{previous.get('timestamp')}) is incompatible: {reason}. No deltas shown.", ""]
        else:
            comparison = previous
            lines += [f"**Comparison:** against compatible report from commit `{previous.get('commit')}` "
                      f"({previous.get('timestamp')}). Deltas are informational, not a pass/fail result.", ""]
    prev_metrics = metric_index(comparison) if comparison else {}

    unavailable, diagnostics = [], []
    for workload in report["workloads"]:
        name = workload.get("name", "?")
        lines += [f"### {name}", "", cell(workload.get("description", "")), ""]
        lines += ["| KPI | Value | Unit | Change |", "|---|---|---|---|"]
        for metric in workload.get("metrics", []):
            mname = metric.get("name", "?")
            if metric.get("value") is None:
                reason = metric.get("unavailableReason") or "unavailable"
                unavailable.append((name, mname, reason))
                value, change = f"unavailable: {reason}", ""
            else:
                value = number(metric["value"])
                change = delta(metric, prev_metrics.get((name, mname))) if comparison else ""
                if comparison and (name, mname) not in prev_metrics:
                    change = "new"
            lines.append(f"| {cell(mname)} | {cell(value)} | {cell(metric.get('unit') or '')} | {cell(change)} |")
        lines.append("")
        for diag in ([] if container else workload.get("diagnostics", [])):
            diagnostics.append((name, diag))
    lines += ["### Benchmark errors and diagnostics", ""]
    lines += [f"- `{n}`: {d}" for n, d in diagnostics] or ([] if container and report.get("diagnostics") else ["None reported."])
    lines += [f"- {d}" for d in report.get("diagnostics", [])]
    lines += ["", "### Unavailable KPIs", ""]
    lines += [f"- `{n}` / `{m}`: {r}" for n, m, r in unavailable] or ["None."]
    return "\n".join(lines) + "\n", metadata(report, args)


def safe_name(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", text)[:80]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--summary-out", type=Path, required=True)
    parser.add_argument("--metadata-out", type=Path, required=True)
    parser.add_argument("--commit", default="unknown")
    parser.add_argument("--timestamp", default="unknown")
    parser.add_argument("--event", default="")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--outcome", default="unknown")
    parser.add_argument("--setup-log")
    parser.add_argument("--container", action="store_true",
                        help="render a container-stack report (distinct section and title)")
    args = parser.parse_args(argv)
    try:
        report, problem = load(args.report)
        previous, previous_problem = load(args.previous)
        text, meta = render(report, problem, previous, previous_problem, args, args.container)
    except Exception as error:  # informational: never fail the workflow
        text = (("## Container performance" if args.container else "## Application performance")
                + " (informational, nonblocking)\n\n"
                f"Summary generation failed: {error!r}. This does not fail CI.\n")
        meta = {"commit": args.commit, "reportAvailable": False, "benchmarkOutcome": args.outcome,
                "summaryError": repr(error)}
    args.summary_out.write_text(text, encoding="utf-8")
    args.metadata_out.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
