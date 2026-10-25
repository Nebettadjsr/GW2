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


def container_report(commit="c1", value=5.0, workload="container-workload-1", pg="sha256:pg", backend="sha256:b1",
                     diagnostics=()):
    return {
        "schemaVersion": "container-1", "commit": commit, "timestamp": "2026-01-01T00:00:00Z",
        "fixtureVersion": workload, "workloadVersion": workload, "measurementScope": "container-stack",
        "environment": {"engineVersion": "28.0.4", "enginePlatform": "linux/amd64", "postgresImageId": pg,
                        "backendRuntimeBaseLayers": ["sha256:jre"], "frontendRuntimeBaseLayers": ["sha256:nginx"]},
        "images": {"backend": {"id": backend, "sizeBytes": 100}, "frontend": {"id": "sha256:f", "sizeBytes": None}},
        "notMeasured": ["account synchronization is not measured in container mode"],
        "cpuUnit": "percent of one core", "diagnostics": list(diagnostics),
        "workloads": [{"name": "idle", "description": "d", "diagnostics": [],
                       "metrics": [{"name": "backend_memory_peak", "unit": "bytes", "value": value}]}],
    }


def run_container(current, previous=None, outcome="success"):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        args = ["--container", "--summary-out", str(tmp / "s.md"), "--metadata-out", str(tmp / "m.json"),
                "--commit", "abc", "--outcome", outcome]
        if current is not None:
            (tmp / "cur.json").write_text(json.dumps(current))
            args += ["--report", str(tmp / "cur.json")]
        if previous is not None:
            (tmp / "prev.json").write_text(json.dumps(previous))
            args += ["--previous", str(tmp / "prev.json")]
        code = perf.main(args)
        return code, (tmp / "s.md").read_text(), json.loads((tmp / "m.json").read_text())


class ContainerSummaryTest(unittest.TestCase):
    def test_distinct_section_images_and_not_measured_are_shown(self):
        code, text, meta = run_container(container_report(diagnostics=["image metadata unavailable for frontend"]))
        self.assertEqual(0, code)
        self.assertIn("## Container performance", text)
        self.assertIn("`container-stack`", text)
        self.assertIn("| backend | `sha256:b1` | 100 |", text)
        self.assertIn("| frontend | `sha256:f` | unavailable |", text)
        self.assertIn("account synchronization is not measured", text)
        self.assertIn("image metadata unavailable for frontend", text)
        self.assertEqual("container-workload-1", meta["workloadVersion"])

    def test_changed_application_image_still_compares(self):
        _, text, _ = run_container(container_report(value=6.0, backend="sha256:b2"), container_report("c0", 5.0))
        self.assertIn("compatible report from commit `c0`", text)
        self.assertIn("+1 (+20.0%)", text)

    def test_changed_runtime_base_or_workload_is_not_compared(self):
        for prev, why in ((container_report(pg="sha256:other"), "environment differs (postgresImageId)"),
                          (container_report(workload="container-workload-2"), "fixtureVersion differs")):
            _, text, _ = run_container(container_report(), prev)
            self.assertIn("incompatible: " + why.split(" (")[0], text)
            self.assertNotIn("%)", text)

    def test_process_report_is_not_compared_with_container_report(self):
        _, text, _ = run_container(container_report(), report())
        self.assertIn("incompatible", text)
        self.assertNotIn("%)", text)

    def test_changed_or_unknown_application_runtime_base_is_not_compared(self):
        for key in ("backendRuntimeBaseLayers", "frontendRuntimeBaseLayers"):
            for value in (["different-base"], "unknown"):
                previous = container_report("c0")
                previous["environment"][key] = value
                _, text, _ = run_container(container_report(), previous)
                self.assertIn("incompatible", text)
                self.assertNotIn("%)", text)

    def test_missing_container_report_exits_zero_and_keeps_log(self):
        code, text, meta = run_container(None, outcome="failure")
        self.assertEqual(0, code)
        self.assertIn("## Container performance", text)
        self.assertIn("No valid report", text)
        self.assertFalse(meta["reportAvailable"])


class ContainerHelperTest(unittest.TestCase):
    def setUp(self):
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
        import container_measure
        self.cm = container_measure

    def test_docker_stats_parsing_and_unparseable_lines(self):
        line = json.dumps({"Name": "x", "CPUPerc": "12.50%", "MemUsage": "1.5MiB / 15GiB"})
        self.assertEqual({"cpu_percent": 12.5, "mem_bytes": 1.5 * 1024 ** 2, "name": "x"}, self.cm.parse_stats_line(line))
        for bad in ("", "{not json", json.dumps({"Name": "x", "CPUPerc": "--", "MemUsage": "0B / 0B"}),
                    json.dumps({"Name": "x", "CPUPerc": "1%", "MemUsage": "-- / --"})):
            self.assertIsNone(self.cm.parse_stats_line(bad))

    def test_summary_without_samples_does_not_invent_zero(self):
        self.assertEqual({"count": 0}, self.cm.summarize([]))
        s = self.cm.summarize([{"cpu_percent": 1, "mem_bytes": 10}, {"cpu_percent": 3, "mem_bytes": 5}])
        self.assertEqual((3, 2.0, 10), (s["cpu_peak"], s["cpu_mean"], s["mem_peak"]))

    def test_p95_nearest_rank(self):
        self.assertIsNone(self.cm.p95([]))
        self.assertEqual(95, self.cm.p95([float(i) for i in range(1, 101)]))

    def test_unavailable_workload_metrics_are_explicit(self):
        args = type("A", (), {"id": "ut", "output_dir": ".", "commit": "c", "timestamp": "t",
                              "idle_seconds": 1, "traffic_seconds": 1, "paths": ["/"], "timeout": 1})()
        m = self.cm.Measurement(args)
        try:
            m.workload("idle", "d", {"postgres": [], "backend": [], "frontend": []}, None, 1.0, [])
        finally:
            import shutil
            shutil.rmtree(m.work, ignore_errors=True)
        cpu = [x for x in m.workloads[0]["metrics"] if x["name"] == "backend_cpu_peak"][0]
        self.assertIsNone(cpu["value"])
        self.assertTrue(cpu["unavailableReason"])
        self.assertTrue(any("no docker stats samples" in d for d in m.diagnostics))

    def test_unlabelled_existing_volume_is_rejected_without_starting_or_cleaning_stack(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        args = SimpleNamespace(id="collision", output_dir=".", commit="c", timestamp="t")
        m = self.cm.Measurement(args)
        calls = []
        def fake_run(cmd, **kwargs):
            calls.append(cmd)
            exists = cmd[1:3] == ["volume", "inspect"] and cmd[-1].endswith("_gw2-icons")
            return SimpleNamespace(returncode=0 if "ls" in cmd or exists else 1, stdout="", stderr="")
        try:
            with patch.object(self.cm, "run", fake_run):
                with self.assertRaisesRegex(SystemExit, "already exists"):
                    m.go()
                m.cleanup()
            self.assertFalse(m.started)
            self.assertFalse(any("compose" in c or "rmi" in c for c in calls))
        finally:
            if m.work.exists():
                m.cleanup_work()


class WorkflowConfigTest(unittest.TestCase):
    path = Path(__file__).resolve().parents[1] / "workflows" / "performance.yml"

    def test_trigger_and_nonblocking_configuration(self):
        text = self.path.read_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertIn("push:", text)
        self.assertNotIn("schedule:", text)
        self.assertIn("continue-on-error: true", text)
        self.assertIn("name: Performance report (informational)", text)

    def test_container_steps_are_nonblocking_and_owned(self):
        text = self.path.read_text()
        self.assertIn("scripts/container_measure.py", text)
        self.assertIn("container-performance-", text)
        self.assertEqual(0, text.count("schedule:"))
        step = text.split("Run container measurement")[1].split("- name:")[0]
        self.assertIn("continue-on-error: true", step)

    def test_required_check_names_untouched_by_performance_job(self):
        import merge_ready_pr as gate
        self.assertNotIn("Performance report (informational)", gate.REQUIRED_CHECKS)


if __name__ == "__main__":
    unittest.main()
