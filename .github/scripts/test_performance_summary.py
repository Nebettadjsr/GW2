"""Offline checks for the informational performance summary, using controlled sample reports."""

import json
import tempfile
import unittest
from pathlib import Path

import performance_summary as perf


def report(commit="c1", value=10.0, fixture="perf-fixtures-1", env=None, reason=None, diagnostics=()):
    metric = {"name": "http_p95", "unit": "ms", "boundary": "client"}
    if reason:
        metric.update(value=None, unavailableReason=reason)
    else:
        metric["value"] = value
    return {
        "schemaVersion": "1", "commit": commit, "timestamp": "2026-01-01T00:00:00Z",
        "fixtureVersion": fixture, "measurementScope": "application-process",
        "environment": env or {"sizes": "S1", "runner": "GitHub Actions"},
        "workloads": [{"name": "profit_small", "description": "d", "metrics": [metric],
                       "diagnostics": list(diagnostics)}],
    }


def run(current, previous=None, outcome="success", raw_current=None):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        args = ["--summary-out", str(tmp / "s.md"), "--metadata-out", str(tmp / "m.json"),
                "--commit", "abc", "--outcome", outcome]
        if current is not None or raw_current is not None:
            (tmp / "cur.json").write_text(raw_current if raw_current is not None else json.dumps(current))
            args += ["--report", str(tmp / "cur.json")]
        if previous is not None:
            (tmp / "prev.json").write_text(json.dumps(previous))
            args += ["--previous", str(tmp / "prev.json")]
        code = perf.main(args)
        return code, (tmp / "s.md").read_text(), json.loads((tmp / "m.json").read_text())


class PerformanceSummaryTest(unittest.TestCase):
    def test_compatible_history_shows_delta(self):
        code, text, meta = run(report(value=12.0), report("c0", 10.0))
        self.assertEqual(0, code)
        self.assertIn("compatible report from commit `c0`", text)
        self.assertIn("+2 (+20.0%)", text)
        self.assertEqual("c1", meta["commit"])
        self.assertEqual(["profit_small"], meta["workloads"])
        self.assertEqual("perf-fixtures-1", meta["fixtureVersion"])
        self.assertTrue(meta["reportAvailable"])

    def test_incompatible_history_explained_without_deltas(self):
        for prev, why in ((report(fixture="perf-fixtures-2"), "fixtureVersion differs"),
                          (report(env={"sizes": "S2", "runner": "GitHub Actions"}), "environment differs (sizes)")):
            _, text, _ = run(report(), prev)
            self.assertIn("incompatible: " + why, text)
            self.assertNotIn("%)", text)

    def test_no_history_is_stated(self):
        _, text, _ = run(report())
        self.assertIn("no previous report to compare", text)

    def test_unavailable_kpi_and_diagnostics_are_published(self):
        _, text, _ = run(report(reason="no /proc", diagnostics=["probe failed"]), report("c0", 10.0))
        self.assertIn("unavailable: no /proc", text)
        self.assertIn("`profit_small`: probe failed", text)
        self.assertNotIn("(+", text)

    def test_missing_report_still_publishes_metadata_and_exits_zero(self):
        code, text, meta = run(None, outcome="failure")
        self.assertEqual(0, code)
        self.assertIn("No valid report", text)
        self.assertIn("**failure**", text)
        self.assertFalse(meta["reportAvailable"])
        self.assertEqual("abc", meta["commit"])

    def test_corrupt_report_exits_zero(self):
        code, text, meta = run(None, raw_current="{not json", outcome="failure")
        self.assertEqual(0, code)
        self.assertIn("could not be read", text)
        self.assertFalse(meta["reportAvailable"])

    def test_container_scope_is_not_implied(self):
        _, text, _ = run(report())
        self.assertIn("not a container", text)


class WorkflowConfigTest(unittest.TestCase):
    path = Path(__file__).resolve().parents[1] / "workflows" / "performance.yml"

    def test_trigger_and_nonblocking_configuration(self):
        text = self.path.read_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertIn("push:", text)
        self.assertNotIn("schedule:", text)
        self.assertIn("continue-on-error: true", text)
        self.assertIn("name: Performance report (informational)", text)

    def test_required_check_names_untouched_by_performance_job(self):
        import merge_ready_pr as gate
        self.assertNotIn("Performance report (informational)", gate.REQUIRED_CHECKS)


if __name__ == "__main__":
    unittest.main()
