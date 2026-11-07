"""Deterministic fixtures for whole-host CPU/memory observations and budget comparison (STORY-KPI-001)."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import host_resources as hr  # noqa: E402
import container_measure as cm  # noqa: E402

STAT1 = "cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 1 1 1 1 0 0 0 0 0 0\n"
STAT2 = "cpu  200 0 200 1600 0 0 0 0 0 0\n"
MEM = "MemTotal:       16000000 kB\nMemFree: 1 kB\nMemAvailable:    4000000 kB\n"


def fake_reader(files):
    def read(path):
        return (files[path], None) if path in files else (None, f"cannot read {path}: missing")
    return read


class CpuTest(unittest.TestCase):
    def test_delta_from_synthetic_counters(self):
        a = hr.parse_proc_stat(STAT1)
        b = hr.parse_proc_stat(STAT2)
        self.assertEqual((200, 1000, None), a)
        pct, why = hr.cpu_delta(a[:2], b[:2])
        self.assertAlmostEqual(20.0, pct)
        self.assertIsNone(why)

    def test_first_sample_zero_delta_reset_malformed_missing(self):
        self.assertIsNone(hr.cpu_delta(None, (1, 2))[0])
        self.assertIn("zero-duration", hr.cpu_delta((1, 2), (1, 2))[1])
        self.assertIn("reset", hr.cpu_delta((5, 10), (1, 12))[1])
        self.assertIsNotNone(hr.parse_proc_stat("cpu a b c d")[2])
        self.assertIsNotNone(hr.parse_proc_stat("cpu 1 2")[2])
        self.assertIn("no aggregate", hr.parse_proc_stat("intr 1")[2])
        self.assertIn("no aggregate", hr.parse_proc_stat(None)[2])

    def test_sampler_first_sample_does_not_fabricate(self):
        s = hr.HostSampler(reader=fake_reader({"/proc/stat": STAT1, "/proc/meminfo": MEM}), system="Linux")
        s.sample()
        self.assertEqual([], s.cpu)
        self.assertEqual(1, len(s.mem_used))

    def test_sampler_two_snapshots(self):
        files = {"/proc/stat": STAT1, "/proc/meminfo": MEM}
        s = hr.HostSampler(reader=lambda p: (files[p], None), system="Linux")
        s.sample()
        files["/proc/stat"] = STAT2
        s.sample()
        self.assertEqual([20.0], s.cpu)

    def test_non_linux_and_missing_counters_are_unavailable_with_reason(self):
        for sampler in (hr.HostSampler(system="Darwin"), hr.HostSampler(reader=fake_reader({}), system="Linux")):
            sampler.sample()
            obs = hr.window_observation("idle", 1.0, sampler, 4)
            for m in obs["metrics"]:
                self.assertIsNone(m["value"])
                self.assertTrue(m["unavailableReason"], m)
            for c in obs["comparisons"].values():
                self.assertEqual("unavailable", c["status"])
                self.assertTrue(c["unavailableReason"])


class MemoryTest(unittest.TestCase):
    def test_parse_and_used_formula(self):
        mem, why = hr.parse_meminfo(MEM)
        self.assertIsNone(why)
        self.assertEqual(16000000 * 1024, mem["total"])
        self.assertEqual((16000000 - 4000000) * 1024, hr.memory_used(mem))

    def test_invalid_and_missing_fields(self):
        for bad in ("", "MemTotal: 10 kB\n", "MemAvailable: 10 kB\n", "MemTotal: x kB\nMemAvailable: 1 kB\n",
                    "MemTotal: 10 kB\nMemAvailable: 20 kB\n", "MemTotal: 10 MB\nMemAvailable: 1 kB\n", None):
            mem, why = hr.parse_meminfo(bad)
            self.assertIsNone(mem, bad)
            self.assertTrue(why)


class BudgetTest(unittest.TestCase):
    def test_cpu_headroom_and_normalization(self):
        c = hr.cpu_comparison("idle", {"count": 2, "mean": 10.0, "peak": 25.0}, 4)
        self.assertAlmostEqual(1.0, c["peak"]["observed"])  # 25% of 4 logical CPUs
        self.assertAlmostEqual(1.0, c["peak"]["absoluteHeadroom"])
        self.assertAlmostEqual(50.0, c["peak"]["percentHeadroom"])
        self.assertEqual("within-local-budget", c["status"])
        self.assertIn("not equivalent", c["normalization"])

    def test_docker_percent_of_core_is_not_host_percent(self):
        # 150% of one core from Docker is 1.5 busy CPUs; on an 8-CPU host that is 18.75% of the host.
        self.assertAlmostEqual(18.75, 150.0 / 8)

    def test_over_budget(self):
        c = hr.cpu_comparison("t", {"count": 1, "mean": 90.0, "peak": 90.0}, 4)
        self.assertEqual("over-local-budget", c["status"])
        m = hr.memory_comparison("t", {"count": 1, "mean": 13e9, "peak": 13e9})
        self.assertEqual("over-local-budget", m["status"])
        self.assertLess(m["peak"]["absoluteHeadroom"], 0)

    def test_memory_headroom(self):
        m = hr.memory_comparison("t", {"count": 1, "mean": 6e9, "peak": 6e9})
        self.assertEqual(6e9, m["peak"]["absoluteHeadroom"])
        self.assertAlmostEqual(50.0, m["peak"]["percentHeadroom"])
        self.assertEqual("whole-host-linux", m["boundary"])

    def test_incompatible_or_incomplete_inputs_are_unavailable(self):
        for c in (hr.cpu_comparison("t", {"count": 1, "mean": 1, "peak": 1}, 4, unit_ok=False),
                  hr.cpu_comparison("t", {"count": 1, "mean": 1, "peak": 1}, None),
                  hr.cpu_comparison("t", {"count": 0}, 4),
                  hr.memory_comparison("t", {"count": 0}),
                  hr.memory_comparison("t", {"count": 1, "mean": 1, "peak": 1}, unit_ok=False)):
            self.assertEqual("unavailable", c["status"])
            self.assertIsNone(c["observed"])
            self.assertTrue(c["unavailableReason"])


class LabelTest(unittest.TestCase):
    def test_architecture_labels(self):
        self.assertEqual("local-x86-64", hr.classify_architecture("Linux", "x86_64")["classification"])
        self.assertEqual("non-oracle-native-arm64", hr.classify_architecture("Linux", "aarch64")["classification"])
        self.assertEqual("local-or-ci-arm64", hr.classify_architecture("Darwin", "arm64")["classification"])
        self.assertEqual("unknown", hr.classify_architecture("Linux", "riscv64")["classification"])
        self.assertEqual("local-x86-64", hr.classify_architecture("Windows", "AMD64")["classification"])
        self.assertEqual("unknown", hr.classify_architecture("Linux", "")["classification"])
        self.assertEqual("Linux ARM64", hr.classify_architecture("Linux", "x86_64")["targetArchitecture"])

    def test_default_qualification_is_not_qualified_for_every_environment(self):
        for system, machine in (("Linux", "x86_64"), ("Linux", "aarch64"), ("Darwin", "arm64"), ("Windows", "AMD64")):
            files = {"/proc/stat": STAT1, "/proc/meminfo": MEM}
            smp = hr.HostSampler(reader=fake_reader(files), system=system)
            smp.sample()
            rep = hr.build_host_report([("idle", 1, smp)], system=system, machine=machine)
            q = rep["qualification"]
            self.assertEqual("not-qualified", q["status"])
            self.assertFalse(q["tenancyVerified"])
            self.assertFalse(q["nativeTargetHost"])
            self.assertNotEqual("native-oracle-a1", rep["hostObservations"]["architecture"]["classification"])
            self.assertTrue(rep["budget"]["scope"].startswith("whole deployment"))
            self.assertEqual(2, rep["budget"]["cpuOcpuCeiling"])
            self.assertEqual(12 * 1000 ** 3, rep["budget"]["memoryBytesCeiling"])

    def test_no_qualified_string_in_source(self):
        text = Path(hr.__file__).read_text()
        self.assertNotIn('"qualified"', text)


class RenderTest(unittest.TestCase):
    def report(self):
        files = {"/proc/stat": STAT1, "/proc/meminfo": MEM}
        smp = hr.HostSampler(reader=lambda p: (files[p], None), system="Linux")
        smp.sample()
        files["/proc/stat"] = STAT2
        smp.sample()
        return {"measurementScope": "container-stack", "project": "p", "workloadVersion": "w", "cpuUnit": "u",
                "images": {}, "workloads": [], "notMeasured": [], "diagnostics": [],
                **hr.build_host_report([("idle", 1, smp)], system="Linux", machine="x86_64")}

    def test_markdown_and_summary_derive_from_json_facts(self):
        rep = json.loads(json.dumps(self.report()))
        md = cm.to_markdown(rep)
        for text in ("whole-host-linux", "not-qualified", "tenancyVerified=false", "nativeTargetHost=false",
                     "unattributed", "within-local-budget", "not a JVM allowance"):
            self.assertIn(text, md)
        self.assertNotIn("Oracle-qualified", md)
        self.assertNotIn("fits Always Free\n", md)
        import performance_summary as perf
        from types import SimpleNamespace
        rep["schemaVersion"] = cm.SCHEMA_VERSION
        rep.update(fixtureVersion="f", timestamp="t", environment={})
        args = SimpleNamespace(commit="c", event="e", run_url="", outcome="success", setup_log=None)
        text, _ = perf.render(rep, None, None, "none", args, container=True)
        self.assertIn("not-qualified", text)
        self.assertIn("host_cpu_peak", text)
        self.assertIn("within-local-budget", text)

    def test_partial_failure_keeps_values_and_surfaces_diagnostics(self):
        files = {"/proc/stat": STAT1, "/proc/meminfo": MEM}
        smp = hr.HostSampler(reader=lambda p: (files[p], None), system="Linux")
        smp.sample()
        files["/proc/stat"] = STAT2
        smp.sample()
        files["/proc/stat"] = "garbage\n"
        files["/proc/meminfo"] = "MemTotal: 1 kB\n"
        smp.sample()
        rep = {"measurementScope": "container-stack", "project": "p", "workloadVersion": "w", "cpuUnit": "u",
               "images": {}, "workloads": [], "notMeasured": [], "diagnostics": [],
               **hr.build_host_report([("idle", 1, smp)], system="Linux", machine="x86_64")}
        w = rep["hostObservations"]["windows"][0]
        vals = {m["name"]: m.get("value") for m in w["metrics"]}
        self.assertEqual(20.0, vals["host_cpu_mean"])
        self.assertEqual(2, vals["host_memory_samples"])
        self.assertTrue(any(d.startswith("cpu:") for d in w["diagnostics"]))
        self.assertTrue(any(d.startswith("memory:") for d in w["diagnostics"]))
        rep = json.loads(json.dumps(rep))
        self.assertIn("Sampling problem", cm.to_markdown(rep))
        import performance_summary as perf
        from types import SimpleNamespace
        rep.update(schemaVersion=cm.SCHEMA_VERSION, fixtureVersion="f", timestamp="t", environment={})
        text, _ = perf.render(rep, None, None, "none", SimpleNamespace(commit="c", event="e", run_url="", outcome="success", setup_log=None), container=True)
        self.assertIn("sampling problem", text)

    def test_healthy_window_has_no_diagnostics_and_official_source(self):
        files = {"/proc/stat": STAT1, "/proc/meminfo": MEM}
        smp = hr.HostSampler(reader=lambda p: (files[p], None), system="Linux")
        smp.sample()
        files["/proc/stat"] = STAT2
        smp.sample()
        self.assertEqual([], hr.window_observation("idle", 1, smp, 2)["diagnostics"])
        self.assertTrue(hr.BUDGET["sourceUrl"].startswith("https://docs.oracle.com/"))
        self.assertEqual(12 * 1000 ** 3, hr.BUDGET["memoryBytesCeiling"])

    def test_unavailable_metrics_carry_reason_in_json(self):
        smp = hr.HostSampler(system="Darwin")
        smp.sample()
        rep = hr.build_host_report([("idle", 1, smp)], system="Darwin", machine="arm64")
        self.assertIsNone(rep["hostObservations"]["logicalCpuCount"]["value"])
        self.assertTrue(rep["hostObservations"]["logicalCpuCount"]["unavailableReason"])
        for m in rep["hostObservations"]["windows"][0]["metrics"]:
            self.assertTrue(m["unavailableReason"])


if __name__ == "__main__":
    unittest.main()
