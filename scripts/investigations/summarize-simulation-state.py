"""Read the isolated five-pair state-reuse experiment without mixing it with pool results."""
import argparse
import hashlib
import json
import statistics
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
args = parser.parse_args()
reports = {}
manifest = []
for pair in range(1, 6):
    for mode in ("false", "true"):
        path = args.source / f"pair-{pair}-reuse-{mode}" / "multi-account-resources.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["fixtureVersion"] == "multiaccount-fixtures-1"
        assert data["environment"]["investigationReuseState"] == mode
        assert data["environment"]["investigationPoolSize"] == "8"
        assert data["environment"]["maxHeapBytes"] == "2147483648"
        assert all(not w["diagnostics"] for w in data["workloads"])
        reports[pair, mode] = data
        manifest.append({"run": path.parent.name, "commit": data["commit"], "timestamp": data["timestamp"],
                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
first = reports[1, "false"]
environment = dict(first["environment"])
environment.pop("investigationReuseState")
for data in reports.values():
    other = dict(data["environment"])
    other.pop("investigationReuseState")
    assert other == environment
    assert data["commit"] == first["commit"]
summary = {"scope": "five alternating paired 13000-recipe runs; both modes use pool 8; JVM includes client",
           "commit": first["commit"], "fixtureVersion": first["fixtureVersion"], "environment": environment,
           "rawReports": manifest, "workloads": []}
for name in [w["name"] for w in first["workloads"] if w["name"].startswith("accounts_")]:
    row = {"name": name, "metrics": {}}
    for field in ("http_latency_p95", "http_throughput", "cpu_time", "peak_heap_used", "http_errors"):
        values = {}
        for mode in ("false", "true"):
            values[mode] = [next(m["value"] for m in next(w for w in reports[pair, mode]["workloads"] if w["name"] == name)["metrics"] if m["name"] == field) for pair in range(1, 6)]
        before, after = values["false"], values["true"]
        if field == "http_errors":
            assert all(x == 0 for x in before + after)
        changes = [(a - b) / b * 100 for a, b in zip(after, before) if b]
        row["metrics"][field] = {"control": before, "candidate": after,
            "controlMedian": statistics.median(before), "candidateMedian": statistics.median(after),
            "pairedChangePercentMedian": statistics.median(changes) if changes else None,
            "pairedChangePercentRange": [min(changes), max(changes)] if changes else None}
    summary["workloads"].append(row)
output = Path(__file__).resolve().parents[2] / "docs/investigations/2026-10-26/paired-state-summary.json"
output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
for row in summary["workloads"]:
    if row["name"].startswith("accounts_mixed"):
        v = row["metrics"]["http_latency_p95"]
        print(row["name"], f"median per-run p95 {v['controlMedian']:.2f} -> {v['candidateMedian']:.2f} ms, paired {v['pairedChangePercentMedian']:+.1f}%")
