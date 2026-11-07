"""Pure helpers for whole-host Linux CPU/memory observations and the Oracle budget context (STORY-KPI-001).

Reporting infrastructure only: nothing here can emit a qualification pass. Unavailable data is always
`value: None` with a nonempty `unavailableReason`, never zero.
"""

from __future__ import annotations

import os
import platform

BOUNDARY_HOST = "whole-host-linux"
BOUNDARY_CONTAINERS = "container-stack"

BUDGET = {
    "name": "Oracle Cloud Infrastructure VM.Standard.A1.Flex (Always Free)",
    "checkedDate": "2026-11-07",
    "sourceUrl": "https://github.com/Nebettadjsr/GW2/blob/master/docs/ROADMAP.md",
    "targetArchitecture": "Linux ARM64",
    "cpuOcpuCeiling": 2,
    "memoryBytesCeiling": 12 * 1000 ** 3,
    "memoryCeilingLabel": "12 GB (decimal, 12e9 bytes)",
    "scope": "whole deployment: OS, Docker/container engine, PostgreSQL, backend, frontend and measurement overhead",
    "notJvmAllowance": True,
    "tenancyAvailability": "unverified",
}

SAMPLING_LIMITS = [
    "Host values are point samples taken about once per second between docker stats calls; peaks are observed sample maxima, not sustained peaks.",
    "Host CPU includes the reporter, Docker commands and the local traffic driver, which run on the same host.",
    "Container aggregate CPU/memory is not a decomposition of whole-host use; any host-minus-container difference is unattributed and is not Docker, OS, cache, JVM or application overhead.",
    "Container memory is already part of host used memory and is never added to it.",
]


def unavailable(name, unit, reason, boundary=BOUNDARY_HOST):
    return {"name": name, "value": None, "unit": unit, "boundary": boundary,
            "unavailableReason": reason or "unavailable"}


def value(name, v, unit, boundary=BOUNDARY_HOST):
    return {"name": name, "value": v, "unit": unit, "boundary": boundary}


def parse_proc_stat(text):
    """First `cpu` line of /proc/stat -> (busy_jiffies, total_jiffies, None) or (None, None, reason)."""
    try:
        for line in (text or "").splitlines():
            parts = line.split()
            if parts and parts[0] == "cpu":
                nums = [int(x) for x in parts[1:]]
                if len(nums) < 4 or any(n < 0 for n in nums):
                    return None, None, "malformed cpu line in /proc/stat"
                # user nice system idle iowait irq softirq steal [guest guest_nice]; guest time is already
                # inside user/nice, so only the first eight fields count.
                fields = nums[:8]
                idle = fields[3] + (fields[4] if len(fields) > 4 else 0)
                total = sum(fields)
                return total - idle, total, None
    except ValueError:
        return None, None, "malformed cpu line in /proc/stat"
    return None, None, "no aggregate cpu line in /proc/stat"


def cpu_delta(prev, cur):
    """prev/cur: (busy, total) jiffies. Returns (percent_of_whole_host, None) or (None, reason)."""
    if prev is None or cur is None:
        return None, "no previous counter snapshot (first sample)"
    d_total, d_busy = cur[1] - prev[1], cur[0] - prev[0]
    if d_total < 0 or d_busy < 0:
        return None, "counter reset or decrease between snapshots"
    if d_total == 0:
        return None, "zero-duration interval between snapshots"
    if d_busy > d_total:
        return None, "inconsistent counters (busy delta exceeds total delta)"
    return 100.0 * d_busy / d_total, None


def parse_meminfo(text):
    """/proc/meminfo -> ({'total': bytes, 'available': bytes}, None) or (None, reason)."""
    found = {}
    for line in (text or "").splitlines():
        parts = line.replace(":", " ").split()
        if len(parts) >= 3 and parts[0] in ("MemTotal", "MemAvailable"):
            if parts[2].lower() != "kb" or not parts[1].isdigit():
                return None, f"malformed {parts[0]} line in /proc/meminfo"
            found[parts[0]] = int(parts[1]) * 1024
    for key in ("MemTotal", "MemAvailable"):
        if key not in found:
            return None, f"{key} missing from /proc/meminfo"
    if found["MemTotal"] <= 0 or found["MemAvailable"] > found["MemTotal"]:
        return None, "inconsistent MemTotal/MemAvailable"
    return {"total": found["MemTotal"], "available": found["MemAvailable"]}, None


def memory_used(mem):
    """Documented definition: used = MemTotal - MemAvailable (Linux kernel estimate; includes reclaimable-excluded use)."""
    return mem["total"] - mem["available"]


def stats(values):
    return {"count": len(values), "mean": sum(values) / len(values), "peak": max(values)} if values else {"count": 0}


def read_text(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read(), None
    except OSError as e:
        return None, f"cannot read {path}: {e.strerror or type(e).__name__}"


class HostSampler:
    """Collects whole-host CPU and memory samples for one window. `reader(path)` -> (text, reason)."""

    def __init__(self, reader=read_text, system=None):
        self.reader = reader
        self.system = system or platform.system()
        self.prev = None
        self.cpu, self.mem_used, self.mem_avail, self.mem_total = [], [], [], []
        self.cpu_reasons, self.mem_reasons = [], []

    def sample(self):
        if self.system != "Linux":
            reason = f"whole-host counters are Linux-only (host is {self.system})"
            self.cpu_reasons.append(reason)
            self.mem_reasons.append(reason)
            return
        text, why = self.reader("/proc/stat")
        busy, total, why2 = parse_proc_stat(text) if text is not None else (None, None, why)
        if why2:
            self.cpu_reasons.append(why2)
            self.prev = None
        else:
            pct, reason = cpu_delta(self.prev, (busy, total))
            if pct is None:
                if self.prev is not None:
                    self.cpu_reasons.append(reason)
            else:
                self.cpu.append(pct)
            self.prev = (busy, total)
        text, why = self.reader("/proc/meminfo")
        mem, why2 = parse_meminfo(text) if text is not None else (None, why)
        if mem is None:
            self.mem_reasons.append(why2)
        else:
            self.mem_total.append(mem["total"])
            self.mem_avail.append(mem["available"])
            self.mem_used.append(memory_used(mem))


def logical_cpus(reader=read_text, system=None):
    """(count, source) or (None, reason)."""
    if (system or platform.system()) != "Linux":
        return None, "logical CPU count source is Linux-only here"
    n = os.cpu_count()
    if n:
        return n, "os.cpu_count()"
    return None, "os.cpu_count() returned no value"


def classify_architecture(system, machine, native_target_host=False, tenancy_verified=False):
    m = (machine or "").lower()
    if m in ("x86_64", "amd64"):
        arch = "x86-64"
    elif m in ("aarch64", "arm64"):
        arch = "arm64"
    else:
        arch = "unknown"
    if native_target_host and tenancy_verified and arch == "arm64":
        label = "native-oracle-a1"  # reserved for later verified evidence; never set by this script
    elif arch == "x86-64":
        label = "local-x86-64"
    elif arch == "arm64":
        label = "non-oracle-native-arm64" if system == "Linux" else "local-or-ci-arm64"
    else:
        label = "unknown"
    return {"hostArchitectureReported": machine or "unknown", "hostArchitectureFamily": arch,
            "classification": label, "targetArchitecture": BUDGET["targetArchitecture"],
            "architectureMatchesTarget": arch == "arm64"}


def headroom(observed, ceiling):
    return {"absoluteHeadroom": ceiling - observed, "percentHeadroom": 100.0 * (ceiling - observed) / ceiling}


def status_for(observed, ceiling):
    return "over-local-budget" if observed > ceiling else "within-local-budget"


def cpu_comparison(window, cpu_stats, ncpu, unit_ok=True):
    """Compare host busy logical CPUs (percent_of_host * logical CPUs / 100) with the 2 OCPU ceiling."""
    base = {"name": "cpu_vs_2_ocpu", "ceiling": BUDGET["cpuOcpuCeiling"], "ceilingUnit": "ocpu",
            "observedUnit": "busy_logical_cpus",
            "normalization": "busy_logical_cpus = host_cpu_percent / 100 * logical_cpu_count; one local logical CPU is "
                             "treated as one OCPU for a mathematical comparison only (not equivalent hardware)",
            "boundary": BOUNDARY_HOST, "window": window}
    if not unit_ok:
        return {**base, "status": "unavailable", "observed": None, "unavailableReason": "incompatible units or boundaries"}
    if not cpu_stats.get("count"):
        return {**base, "status": "unavailable", "observed": None, "unavailableReason": "no host CPU samples"}
    if not ncpu:
        return {**base, "status": "unavailable", "observed": None, "unavailableReason": "logical CPU count unavailable"}
    out = {**base}
    for key in ("mean", "peak"):
        busy = cpu_stats[key] / 100 * ncpu
        out[key] = {"observed": busy, **headroom(busy, BUDGET["cpuOcpuCeiling"]),
                    "status": status_for(busy, BUDGET["cpuOcpuCeiling"])}
    out["status"] = out["peak"]["status"]
    return out


def memory_comparison(window, used_stats, unit_ok=True):
    base = {"name": "memory_vs_12_gb", "ceiling": BUDGET["memoryBytesCeiling"], "ceilingUnit": "bytes",
            "observedUnit": "bytes", "definition": "host used = MemTotal - MemAvailable (whole host, not JVM or container-only)",
            "boundary": BOUNDARY_HOST, "window": window}
    if not unit_ok:
        return {**base, "status": "unavailable", "observed": None, "unavailableReason": "incompatible units or boundaries"}
    if not used_stats.get("count"):
        return {**base, "status": "unavailable", "observed": None, "unavailableReason": "no host memory samples"}
    out = {**base}
    for key in ("mean", "peak"):
        v = used_stats[key]
        out[key] = {"observed": v, **headroom(v, BUDGET["memoryBytesCeiling"]),
                    "status": status_for(v, BUDGET["memoryBytesCeiling"])}
    out["status"] = out["peak"]["status"]
    return out


def window_observation(name, seconds, sampler, ncpu):
    cpu_s, used_s = stats(sampler.cpu), stats(sampler.mem_used)
    metrics = []
    if cpu_s["count"]:
        metrics += [value("host_cpu_samples", cpu_s["count"], "count"),
                    value("host_cpu_mean", round(cpu_s["mean"], 2), "percent_of_whole_host"),
                    value("host_cpu_peak", round(cpu_s["peak"], 2), "percent_of_whole_host")]
    else:
        reason = "; ".join(dict.fromkeys(sampler.cpu_reasons)) or "fewer than two counter snapshots in the window"
        metrics += [unavailable(n, u, reason) for n, u in (("host_cpu_samples", "count"), ("host_cpu_mean", "percent_of_whole_host"),
                                                           ("host_cpu_peak", "percent_of_whole_host"))]
    if used_s["count"]:
        metrics += [value("host_memory_samples", used_s["count"], "count"),
                    value("host_memory_total", sampler.mem_total[-1], "bytes"),
                    value("host_memory_available_last", sampler.mem_avail[-1], "bytes"),
                    value("host_memory_used_mean", int(used_s["mean"]), "bytes"),
                    value("host_memory_used_peak", int(used_s["peak"]), "bytes")]
    else:
        reason = "; ".join(dict.fromkeys(sampler.mem_reasons)) or "no memory sample in the window"
        metrics += [unavailable(n, u, reason) for n, u in (("host_memory_samples", "count"), ("host_memory_total", "bytes"),
                                                           ("host_memory_available_last", "bytes"),
                                                           ("host_memory_used_mean", "bytes"), ("host_memory_used_peak", "bytes"))]
    cpu_cmp = cpu_comparison(name, cpu_s, ncpu)
    mem_cmp = memory_comparison(name, used_s)
    for cmp_ in (cpu_cmp, mem_cmp):
        if cmp_["status"] == "unavailable" and not cmp_.get("unavailableReason"):
            cmp_["unavailableReason"] = "unavailable"
    return {"window": name, "windowSeconds": round(seconds, 2), "metrics": metrics,
            "comparisons": {"cpu": cpu_cmp, "memory": mem_cmp}}


def build_host_report(windows, system=None, machine=None, reader=read_text, observer_pid=None):
    """windows: list of (name, seconds, HostSampler). Returns the JSON-ready host/budget/qualification facts."""
    system = system or platform.system()
    machine = machine or platform.machine()
    ncpu, source = logical_cpus(reader, system)
    arch = classify_architecture(system, machine)
    cpu_info = {"value": ncpu, "source": source} if ncpu else {"value": None, "unavailableReason": source}
    return {
        "hostObservations": {
            "boundary": BOUNDARY_HOST, "hostOs": system, "logicalCpuCount": cpu_info, "architecture": arch,
            "windows": [window_observation(n, s, smp, ncpu) for n, s, smp in windows],
            "samplingLimits": SAMPLING_LIMITS,
        },
        "boundaries": {
            "containerStackServices": "per-service docker stats observations (boundary container-stack), kept separate",
            "wholeHostLinux": "host-wide /proc/stat CPU and /proc/meminfo memory (boundary whole-host-linux)",
            "measurementProcess": "colocated: the reporter, docker CLI calls and sampling share the host",
            "trafficDriver": "colocated: the single-client HTTP driver shares the host",
            "environmentKind": "ci" if os.environ.get("GITHUB_ACTIONS") else "local",
            "unexplainedResidual": "host minus container-stack is unattributed and must not be labelled overhead",
        },
        "budget": BUDGET,
        "qualification": {"status": "not-qualified", "tenancyVerified": False, "nativeTargetHost": False,
                          "reason": "local or ordinary CI run; actual tenancy and native Oracle A1 host evidence are deferred. "
                                    "within-local-budget is a mathematical comparison, not proof of fit or capacity."},
    }
