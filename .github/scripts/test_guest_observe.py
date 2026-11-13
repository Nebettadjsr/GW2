"""Deterministic checks for the guest observe-existing-stack command (STORY-RESOURCE-002). No Docker needed."""

import itertools
import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import guest_observe as go  # noqa: E402
import host_resources as hr  # noqa: E402

STAT = ["cpu  100 0 100 800 0 0 0 0 0 0\n", "cpu  200 0 200 1600 0 0 0 0 0 0\n", "cpu  400 0 400 2000 0 0 0 0 0 0\n"]
MEM = "MemTotal:       16000000 kB\nMemFree: 1 kB\nMemAvailable:    4000000 kB\n"


def reader(stats, cycle=False):
    it = itertools.cycle(stats) if cycle else iter(stats)

    def read(path):
        if path == "/proc/stat":
            return next(it), None
        return MEM, None
    return read


def docker_runner(stdout_by_command):
    def run(cmd, timeout=0):
        key = cmd[1]
        out, code = stdout_by_command.get(key, ("", 1))
        return SimpleNamespace(stdout=out, returncode=code)
    return run


STATS_LINE = json.dumps({"Name": "gw2-backend-1", "CPUPerc": "12.5%", "MemUsage": "512MiB / 2GiB"})


def sample_doc(containers):
    return {"samples": [
        {"utc": f"2026-11-13T10:00:0{i}.000Z", "monoMs": i * 1000,
         "wholeVm": {"boundary": hr.BOUNDARY_HOST, "cpuPercent": 10.0 * (i + 1), "memUsedBytes": 1000 * (i + 1),
                     "collectionStartedUtc": f"2026-11-13T10:00:0{i}.000Z", "collectionEndedUtc": f"2026-11-13T10:00:0{i}.000Z"},
         "containers": {**containers, "collectionStartedUtc": f"2026-11-13T10:00:0{i}.000Z", "collectionEndedUtc": f"2026-11-13T10:00:0{i}.000Z"}}
        for i in range(5)]}


RUN = {"runId": "r1", "journey": "crafting", "generatorHealth": {"boundary": "load-generator-process"},
       "resources": {"alignmentWindow": {"startUtc": "2026-11-13T10:00:01.000Z", "endUtc": "2026-11-13T10:00:03.000Z"}}}


class ContainerSampleTest(unittest.TestCase):
    def test_parses_running_containers(self):
        result = go.container_sample("gw2", docker_runner({"ps": ("gw2-backend-1\n", 0), "stats": (STATS_LINE + "\n", 0)}))
        self.assertEqual(result["boundary"], hr.BOUNDARY_CONTAINERS)
        self.assertEqual(result["value"]["gw2-backend-1"]["cpuPercent"], 12.5)

    def test_missing_stack_is_unavailable_not_zero(self):
        result = go.container_sample("gw2", docker_runner({"ps": ("", 0)}))
        self.assertIsNone(result["value"])
        self.assertIn("no running containers", result["unavailableReason"])

    def test_missing_docker_is_unavailable(self):
        def broken(cmd, timeout=0):
            raise FileNotFoundError("docker")
        self.assertIn("docker unavailable", go.container_sample("gw2", broken)["unavailableReason"])


class SampleTest(unittest.TestCase):
    def test_first_sample_has_no_cpu_value_and_says_why(self):
        sampler = hr.HostSampler(reader=reader(STAT), system="Linux")
        first = go.host_point(sampler)
        self.assertIsNone(first["cpuPercent"])
        self.assertTrue(first["cpuUnavailableReason"])
        self.assertEqual(first["memUsedBytes"], 12000000 * 1024)
        second = go.host_point(sampler)
        self.assertAlmostEqual(second["cpuPercent"], 100.0 * 200 / 1000, places=3)

    def test_non_linux_host_is_unavailable(self):
        point = go.host_point(hr.HostSampler(reader=reader(STAT), system="Windows"))
        self.assertIsNone(point["cpuPercent"])
        self.assertIn("Linux-only", point["cpuUnavailableReason"])

    def test_sample_is_bounded_and_not_qualified(self):
        doc = go.sample(1, "gw2", containers=lambda p: {"value": None, "unavailableReason": "x"},
                        sampler=hr.HostSampler(reader=reader(STAT, cycle=True), system="Linux"), sleep=lambda s: None)
        self.assertEqual((doc["mode"], doc["qualification"]), ("preparation-only", "not-qualified"))
        self.assertIn("gw2OutboundTraffic", doc["unavailable"])
        with self.assertRaises(ValueError):
            go.sample(go.MAX_SECONDS + 1, "gw2")


class AlignTest(unittest.TestCase):
    def test_boundaries_use_their_own_collection_intervals(self):
        containers = {"value": {"backend": {"cpuPercent": 5, "memBytes": 100}}}
        doc = sample_doc(containers)
        doc["samples"] = doc["samples"][:2]
        doc["samples"][0]["containers"].update(collectionStartedUtc="2026-11-13T10:00:01.400Z", collectionEndedUtc="2026-11-13T10:00:01.500Z")
        doc["samples"][1]["containers"].update(collectionStartedUtc="2026-11-13T10:00:02.000Z", collectionEndedUtc="2026-11-13T10:00:04.000Z")
        result = go.align(doc, RUN)
        self.assertTrue(result["covered"])
        self.assertEqual(result["window"]["sampleCounts"], {"wholeVm": 1, "containers": 1})
        self.assertEqual(result["window"]["wholeVm"]["cpuPercent"]["peak"], 20)
        self.assertEqual(result["window"]["containers"]["perContainer"]["backend"]["cpu_peak"], 5)

    def test_legacy_shared_timestamps_cannot_prove_collection_alignment(self):
        doc = sample_doc({"value": None})
        for point in doc["samples"]:
            for boundary in ("wholeVm", "containers"):
                point[boundary].pop("collectionStartedUtc")
                point[boundary].pop("collectionEndedUtc")
        self.assertFalse(go.align(doc, RUN)["covered"])

    def test_slices_to_the_run_window_keeping_boundaries_apart(self):
        containers = {"boundary": hr.BOUNDARY_CONTAINERS, "value": {"gw2-backend-1": {"cpuPercent": 5.0, "memBytes": 100.0}}}
        aligned = go.align(sample_doc(containers), RUN)
        self.assertTrue(aligned["covered"])
        self.assertEqual(aligned["window"]["sampleCount"], 3)
        self.assertEqual(aligned["window"]["wholeVm"]["cpuPercent"]["peak"], 40.0)
        self.assertEqual(aligned["window"]["containers"]["perContainer"]["gw2-backend-1"]["cpu_peak"], 5.0)
        self.assertNotEqual(aligned["window"]["wholeVm"]["boundary"], aligned["window"]["containers"]["boundary"])
        self.assertEqual(aligned["generatorHealth"]["boundary"], "load-generator-process")
        self.assertEqual(aligned["qualification"], "not-qualified")

    def test_clock_offset_is_applied_and_recorded(self):
        containers = {"value": None, "unavailableReason": "no stack"}
        aligned = go.align(sample_doc(containers), RUN, clock_offset_ms=1000)
        self.assertEqual(aligned["clockOffsetMs"], 1000)
        self.assertEqual(aligned["window"]["sampleCount"], 3)
        self.assertEqual(aligned["window"]["containers"]["unavailableReason"], "no stack")

    def test_window_without_samples_is_explicit(self):
        aligned = go.align(sample_doc({"value": None}), RUN, clock_offset_ms=3_600_000)
        self.assertFalse(aligned["covered"])
        self.assertIn("unavailableReason", aligned["window"])


class OverheadTest(unittest.TestCase):
    def test_difference_is_unavailable_without_cpu_delta(self):
        result = go.cpu_window(1, lambda s: None, sampler_factory=lambda: hr.HostSampler(reader=reader(STAT[:1] * 2), system="Linux"))
        self.assertIsNone(result["cpuPercent"])
        self.assertTrue(result["reason"])


if __name__ == "__main__":
    unittest.main()
