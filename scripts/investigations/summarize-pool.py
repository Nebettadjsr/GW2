"""Summarize five paired #116 experiments; preserve run-level values and provenance."""
import hashlib
import json
import statistics
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / "quality-reports/optimization-20261026/paired"
fields = ("http_latency_p95", "http_throughput", "http_errors", "peak_heap_used",
          "cpu_time", "cpu_utilization", "db_connections_open_peak")
reports = {}
manifest = []
for pair in range(1, 6):
    for mode in (0, 8):
        path = source / f"pair-{pair}-pool-{mode}" / "multi-account-resources.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["fixtureVersion"] == "multiaccount-fixtures-1"
        assert data["environment"]["investigationPoolSize"] == str(mode)
        assert data["environment"]["maxHeapBytes"] == "2147483648"
        assert all(not w["diagnostics"] for w in data["workloads"]), path
        reports[pair, mode] = data
        manifest.append({"run": path.parent.name, "commit": data["commit"], "timestamp": data["timestamp"],
                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})

first = reports[1, 0]
comparison_environment = dict(first["environment"])
comparison_environment.pop("investigationPoolSize")
for data in reports.values():
    environment = dict(data["environment"])
    environment.pop("investigationPoolSize")
    assert environment == comparison_environment
    assert data["commit"] == first["commit"]

summary = {"scope": "five alternating paired runs; in-process load generator; no rendering/live upstream",
           "commit": first["commit"], "fixtureVersion": first["fixtureVersion"],
           "environment": comparison_environment, "rawReports": manifest, "workloads": []}
for name in [w["name"] for w in first["workloads"] if w["name"].startswith("accounts_")]:
    modes = {}
    for mode in (0, 8):
        rows = []
        for pair in range(1, 6):
            workload = next(w for w in reports[pair, mode]["workloads"] if w["name"] == name)
            row = {m["name"]: m.get("value") for m in workload["metrics"] if m["name"] in fields}
            assert row["http_errors"] == 0
            rows.append(row)
        modes[mode] = rows
    results = {}
    for field in fields:
        before, after = [r[field] for r in modes[0]], [r[field] for r in modes[8]]
        if any(v is None for v in before + after):
            results[field] = {"unavailable": True}
            continue
        changes = [(a - b) / b * 100 for a, b in zip(after, before) if b]
        results[field] = {"control": before, "candidate": after,
                          "controlMedian": statistics.median(before), "candidateMedian": statistics.median(after),
                          "pairedChangePercentMedian": statistics.median(changes) if changes else None,
                          "pairedChangePercentRange": [min(changes), max(changes)] if changes else None}
    summary["workloads"].append({"name": name, "metrics": results})
output = root / "docs/investigations/2026-10-26/paired-pool-summary.json"
output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
for w in summary["workloads"]:
    if w["name"].startswith("accounts_mixed"):
        m = w["metrics"]["http_latency_p95"]
        print(w["name"], f"median per-run p95: {m['controlMedian']:.2f} -> {m['candidateMedian']:.2f} ms",
              f"median paired change {m['pairedChangePercentMedian']:+.1f}%")
