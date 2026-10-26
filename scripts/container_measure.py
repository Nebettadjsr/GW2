#!/usr/bin/env python3
"""Informational container measurement of the owned three-service Compose stack (STORY-PERF-007).

Starts docker/compose.yaml under its own unique project (gw2-cmeasure-<id>) on an ephemeral loopback port,
records Docker-reported image ids/sizes, startup-to-readiness, idle and proxied global HTTP traffic
samples (`docker stats`), and writes container-resources.json / .md. Only that project's containers,
network, volumes and built images are removed afterwards. No GW2 key, ArenaNet access or maintainer
database is used. Never a gate: failures become diagnostics in the report; the exit status is 0
unless --strict is given (local debugging). Needs Docker with Compose v2 and Python 3.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

SCHEMA_VERSION = "container-1"
WORKLOAD_VERSION = "container-workload-2"
SERVICES = ("postgres", "backend", "frontend")
ROOT = Path(__file__).resolve().parent.parent
BUILT = ("backend", "frontend")


def run(cmd, timeout=600, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)


def metric(name, value, unit, boundary, reason=None):
    m = {"name": name, "unit": unit, "boundary": boundary}
    if value is None:
        m.update(value=None, unavailableReason=reason or "unavailable")
    else:
        m["value"] = value
    return m


def parse_size(text: str) -> float | None:
    """Docker prints e.g. '12.5MiB', '1.2GiB', '900kB'; return bytes or None."""
    m = re.fullmatch(r"\s*([0-9.]+)\s*([A-Za-z]*)\s*", text or "")
    if not m:
        return None
    units = {"b": 1, "kb": 1e3, "mb": 1e6, "gb": 1e9, "kib": 1024, "mib": 1024**2, "gib": 1024**3}
    factor = units.get(m.group(2).lower())
    return float(m.group(1)) * factor if factor else None


def parse_stats_line(line: str) -> dict | None:
    """One `docker stats --format json` object -> {service-less cpu_percent, mem_bytes}."""
    try:
        o = json.loads(line)
        cpu = float(str(o["CPUPerc"]).rstrip("%"))
        mem = parse_size(str(o["MemUsage"]).split("/")[0])
    except (ValueError, KeyError, TypeError):
        return None
    return None if mem is None else {"cpu_percent": cpu, "mem_bytes": mem, "name": o.get("Name", "")}


def summarize(samples: list[dict]) -> dict:
    """samples: list of {cpu_percent, mem_bytes}. Observed peak/mean over the samples taken."""
    if not samples:
        return {"count": 0}
    cpus = [s["cpu_percent"] for s in samples]
    mems = [s["mem_bytes"] for s in samples]
    return {"count": len(samples), "cpu_peak": max(cpus), "cpu_mean": sum(cpus) / len(cpus),
            "mem_peak": max(mems), "mem_last": mems[-1]}


def stats_sample(ids: dict[str, str]) -> dict[str, dict | None]:
    out = {}
    cp = run(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids.values()], timeout=60)
    by_id = {}
    for line in cp.stdout.splitlines():
        s = parse_stats_line(line)
        if s:
            by_id[s["name"]] = s
    names = {i: n for i, n in ((v, run(["docker", "inspect", "-f", "{{.Name}}", v]).stdout.strip().lstrip("/"))
                                for v in ids.values())}
    for svc, cid in ids.items():
        out[svc] = by_id.get(names.get(cid))
    return out


def http_get(url: str, timeout=10) -> tuple[int | None, int, float]:
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            body = r.read()
            return r.status, len(body), (time.perf_counter() - start) * 1000
    except urllib.error.HTTPError as e:
        e.read()
        return e.code, 0, (time.perf_counter() - start) * 1000
    except Exception:
        return None, 0, (time.perf_counter() - start) * 1000


def p95(values: list[float]) -> float | None:
    if not values:
        return None
    s = sorted(values)
    return s[max(0, -(-95 * len(s) // 100) - 1)]


class Measurement:
    def __init__(self, args):
        self.args = args
        self.id = args.id or f"{int(time.time())}-{os.getpid()}"
        if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", self.id) or len(self.id) > 40:
            raise SystemExit("--id must be at most 40 lowercase letters/digits/hyphens")
        self.project = f"gw2-cmeasure-{self.id}"
        self.work = Path(tempfile.mkdtemp(prefix="gw2-cmeasure."))
        self.diagnostics: list[str] = []
        self.workloads: list[dict] = []
        self.images: dict = {}
        self.env_extra: dict = {}
        self.base_url = ""
        self.started = False
        self.password = f"measure-{os.getpid()}-{time.time_ns()}"

    def dc(self, *a, timeout=900):
        env = dict(os.environ, GW2_STACK_HTTP_PORT="", GW2_STACK_HTTP_BIND="127.0.0.1",
                   GW2_STACK_DB_PASSWORD=self.password)
        for k in ("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "GW2_API_KEY"):
            env.pop(k, None)
        override = self.work / "measure.yaml"
        override.write_text("services:\n  backend:\n    environment:\n"
                            "      GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS: \"86400000\"\n")
        return run(["docker", "compose", "-p", self.project, "-f", str(ROOT / "docker" / "compose.yaml"),
                    "-f", str(override), *a], timeout=timeout, env=env, cwd=ROOT)

    def preflight(self):
        for kind in ("container", "network", "volume"):
            cmd = ["docker", kind, "ls", "-aq" if kind == "container" else "-q",
                   "--filter", f"label=com.docker.compose.project={self.project}"]
            result = run(cmd)
            if result.returncode:
                raise RuntimeError(f"cannot check owned {kind} resources: {result.stderr.strip()}")
            if result.stdout.strip():
                raise SystemExit(f"project {self.project} already has {kind} resources; choose another --id")
        # Compose can adopt an unlabelled volume with an expected name. Reject it before up/down -v.
        for kind, names in (("network", [f"{self.project}_default"]),
                            ("volume", [f"{self.project}_gw2-postgres", f"{self.project}_gw2-icons"]),
                            ("image", [f"{self.project}-{s}" for s in BUILT])):
            for name in names:
                if run(["docker", kind, "inspect", name]).returncode == 0:
                    raise SystemExit(f"{kind} {name} already exists; choose another --id")

    def cleanup(self):
        if self.started:
            result = self.dc("down", "-v", "--remove-orphans", timeout=300)
            if result.returncode:
                self.diagnostics.append("owned stack cleanup failed: " + result.stderr[-400:])
            for s in BUILT:
                name = f"{self.project}-{s}"
                if run(["docker", "image", "inspect", name]).returncode == 0:
                    result = run(["docker", "rmi", name])
                    if result.returncode:
                        self.diagnostics.append(f"owned image cleanup failed for {s}: " + result.stderr[-400:])
        self.cleanup_work()

    def cleanup_work(self):
        target = self.work.resolve()
        if target.parent != Path(tempfile.gettempdir()).resolve() or not target.name.startswith("gw2-cmeasure."):
            raise RuntimeError("unexpected temporary cleanup path")
        shutil.rmtree(target)

    def container_ids(self) -> dict[str, str]:
        ids = {}
        for s in SERVICES:
            cid = self.dc("ps", "-q", s).stdout.strip()
            if cid:
                ids[s] = cid
        return ids

    def collect_images(self, ids):
        for s, cid in ids.items():
            cp = run(["docker", "inspect", "-f", "{{.Image}}", cid])
            image_id = cp.stdout.strip()
            info = run(["docker", "image", "inspect", image_id])
            try:
                d = json.loads(info.stdout)[0]
                parts = [d["Id"], d["Size"], d["Architecture"], d["Os"], d.get("RepoTags") or []]
            except (ValueError, KeyError, IndexError, TypeError):
                parts = []
            if info.returncode or len(parts) < 5:
                self.images[s] = {"id": image_id or None, "sizeBytes": None,
                                  "unavailableReason": "docker image inspect failed"}
                self.diagnostics.append(f"image metadata unavailable for {s}")
                continue
            self.images[s] = {"id": parts[0], "sizeBytes": int(parts[1]), "architecture": parts[2],
                              "os": parts[3], "tags": parts[4],
                              "role": "provenance (changes with code)" if s in BUILT else "runtime base"}
            if s in BUILT:
                # Each current runtime Dockerfile adds exactly two filesystem layers to its base:
                # backend user/directory setup + jar COPY; frontend template COPY + dist COPY.
                # Keep these application layers out of runtime-base compatibility checks.
                layers = d.get("RootFS", {}).get("Layers", [])
                self.images[s]["runtimeBaseLayers"] = layers[:-2] if len(layers) > 2 else None

    def timed_sampling(self, ids, label, seconds, traffic=None):
        """Sample docker stats until `seconds` elapse (each call blocks ~1-2 s); drive traffic if given."""
        series = {s: [] for s in ids}
        http = {"latency": [], "bytes": 0, "errors": 0, "requests": 0, "codes": {}}
        t0 = time.perf_counter()
        deadline = t0 + seconds
        next_sample = t0
        stop = threading.Event()
        driver_errors = []
        def drive():
            try:
                while not stop.is_set() and time.perf_counter() < deadline:
                    for url in traffic:
                        remaining = deadline - time.perf_counter()
                        if stop.is_set() or remaining <= 0:
                            return
                        # Stop admitting requests at the deadline, but let the last in-flight
                        # request use its normal timeout. A near-zero remaining window must not
                        # create an artificial HTTP failure. The measured wall time includes drain.
                        code, size, ms = http_get(url, timeout=10)
                        http["requests"] += 1
                        key = f"{url.split('/', 3)[-1] or '/'}={code}"
                        http["codes"][key] = http["codes"].get(key, 0) + 1
                        if code != 200:
                            http["errors"] += 1
                        else:
                            http["latency"].append(ms)
                            http["bytes"] += size
            except BaseException as error:
                driver_errors.append(error)
        driver = threading.Thread(target=drive, name="gw2-container-http", daemon=True) if traffic else None
        if driver:
            driver.start()
        try:
            while time.perf_counter() < deadline:
                if time.perf_counter() >= next_sample:
                    for svc, s in stats_sample(ids).items():
                        if s:
                            series[svc].append(s)
                    next_sample = time.perf_counter() + 1
                else:
                    stop.wait(min(0.2, max(0, deadline - time.perf_counter())))
        finally:
            stop.set()
            if driver:
                driver.join(timeout=12)
                if driver.is_alive():
                    raise RuntimeError("HTTP load driver did not stop")
        if driver_errors:
            raise RuntimeError("HTTP load driver failed") from driver_errors[0]
        wall = time.perf_counter() - t0
        return series, http, wall

    def workload(self, name, description, series, http, wall, limits):
        metrics = []
        for svc in SERVICES:
            sm = summarize(series.get(svc, []))
            if not sm["count"]:
                for m, u in (("cpu_peak", "percent_of_one_core"), ("memory_peak", "bytes")):
                    metrics.append(metric(f"{svc}_{m}", None, u, "docker-stats", "no usable docker stats sample"))
                self.diagnostics.append(f"{name}: no docker stats samples for {svc}")
                continue
            metrics += [
                metric(f"{svc}_cpu_peak", round(sm["cpu_peak"], 2), "percent_of_one_core", "docker-stats"),
                metric(f"{svc}_cpu_mean", round(sm["cpu_mean"], 2), "percent_of_one_core", "docker-stats"),
                metric(f"{svc}_memory_peak", int(sm["mem_peak"]), "bytes", "docker-stats"),
                metric(f"{svc}_samples", sm["count"], "count", "docker-stats"),
            ]
        if http is not None:
            ok = len(http["latency"])
            metrics += [
                metric("http_requests", http["requests"], "count", "client"),
                metric("http_errors", http["errors"], "count", "client"),
                metric("http_p95", round(p95(http["latency"]), 2) if ok else None, "ms", "client",
                       None if ok else "no successful request"),
                metric("http_throughput", round(http["requests"] / wall, 2) if wall else None, "requests_per_second", "client"),
                metric("http_body_bytes", http["bytes"], "bytes", "client"),
            ]
            if http["errors"]:
                self.diagnostics.append(f"{name}: {http['errors']} of {http['requests']} requests did not return 200 ({http['codes']})")
        metrics.append(metric("window", round(wall, 2), "seconds", "wall"))
        self.workloads.append({"name": name, "description": description, "metrics": metrics,
                               "limits": limits, "diagnostics": [d for d in self.diagnostics if d.startswith(name + ":")]})

    def db_observations(self):
        cp = self.dc("exec", "-T", "postgres", "psql", "-U", "gw2", "-d", "gw2", "-At", "-F", "|", "-c",
                     "select pg_database_size('gw2'), (select count(*) from pg_stat_activity where datname='gw2')")
        metrics = []
        parts = cp.stdout.strip().split("|")
        if cp.returncode == 0 and len(parts) == 2 and all(p.isdigit() for p in parts):
            metrics = [metric("db_size", int(parts[0]), "bytes", "pg_database_size"),
                       metric("db_connections_at_end", int(parts[1]), "count", "pg_stat_activity (point sample)")]
        else:
            self.diagnostics.append("database observations unavailable: psql query failed")
            metrics = [metric("db_size", None, "bytes", "pg_database_size", "psql query failed"),
                       metric("db_connections_at_end", None, "count", "pg_stat_activity", "psql query failed")]
        self.workloads.append({"name": "database_observations", "description": "Post-traffic PostgreSQL observations from inside the postgres container (database-wide, point-in-time).",
                               "metrics": metrics, "limits": ["Point samples only; no query-level or I/O counters."], "diagnostics": []})

    def environment(self):
        eng = run(["docker", "version", "-f", "{{.Server.Version}}|{{.Server.Os}}/{{.Server.Arch}}"]).stdout.strip()
        comp = run(["docker", "compose", "version", "--short"]).stdout.strip()
        info = run(["docker", "info", "-f", "{{.NCPU}}|{{.MemTotal}}|{{.OperatingSystem}}"]).stdout.strip().split("|")
        env = {"hostOs": platform.system(), "hostArchitecture": platform.machine(),
               "engineVersion": eng.split("|")[0] if eng else "unknown",
               "enginePlatform": eng.split("|")[1] if "|" in eng else "unknown",
               "composeVersion": comp or "unknown",
               "engineCpus": info[0] if len(info) == 3 else "unknown",
               "engineMemoryBytes": info[1] if len(info) == 3 else "unknown",
               "engineOs": info[2] if len(info) == 3 else "unknown",
               "runner": "GitHub Actions" if os.environ.get("GITHUB_ACTIONS") else "local",
               "idleSeconds": self.args.idle_seconds, "trafficSeconds": self.args.traffic_seconds,
               "trafficUrls": list(self.args.paths)}
        pg = self.images.get("postgres", {})
        env["postgresImageId"] = pg.get("id") or "unknown"
        for s in BUILT:
            env[f"{s}RuntimeBaseLayers"] = self.images.get(s, {}).get("runtimeBaseLayers") or "unknown"
        return env

    def go(self):
        self.preflight()
        t0 = time.perf_counter()
        # Preflight has ruled out existing resources. Own partial startup even if Compose times out.
        self.started = True
        cp = self.dc("up", "-d", "--build", "--wait", "--wait-timeout", str(self.args.timeout))
        up_ms = (time.perf_counter() - t0) * 1000
        ids = self.container_ids()
        if cp.returncode or set(ids) != set(SERVICES):
            self.diagnostics.append(f"stack did not become healthy: {(cp.stderr or cp.stdout)[-400:].strip()}")
            self.write(ids)
            return
        port = self.dc("port", "frontend", "8080").stdout.strip().rsplit(":", 1)[-1]
        self.base_url = f"http://127.0.0.1:{port}"
        self.collect_images(ids)
        self.startup(ids, up_ms)
        series, _, wall = self.timed_sampling(ids, "idle", self.args.idle_seconds)
        self.workload("idle", "Healthy stack with no requests, sampled with docker stats.", series, None, wall,
                      ["First samples may still include post-start work; peaks are of ~1 s point samples."])
        urls = [self.base_url + p for p in self.args.paths]
        series, http, wall = self.timed_sampling(ids, "http_global", self.args.traffic_seconds, urls)
        self.workload("http_global", "Sequential single-client GETs of the paths in environment.trafficUrls through the "
                      "frontend proxy to the backend and database: " + ", ".join(self.args.paths) + ".",
                      series, http, wall,
                      ["Global read traffic only; not a crafting, account-sync or live-upstream workload.",
                       "Client runs on the same host as the containers and shares its CPU.",
                       "Latency is client-side including body download; single client, so not a capacity figure."])
        self.db_observations()
        self.write(ids)

    def startup(self, ids, up_ms):
        metrics = [metric("up_to_healthy", round(up_ms), "ms", "docker compose up --build --wait (includes image build, so build cache affects it)")]
        self.workloads.append({"name": "startup", "description": "One cold `compose up --build --wait` of the owned project to all-services-healthy.",
                               "metrics": metrics, "limits": ["Per-service readiness times are not reported: Compose --wait gives only the aggregate."],
                               "diagnostics": []})

    def report(self, ids):
        return {"schemaVersion": SCHEMA_VERSION, "measurementScope": "container-stack",
                "workloadVersion": WORKLOAD_VERSION, "fixtureVersion": WORKLOAD_VERSION,
                "commit": self.args.commit, "timestamp": self.args.timestamp or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "project": self.project, "environment": self.environment() if self.images else {"note": "incomplete setup"},
                "images": self.images, "workloads": self.workloads, "diagnostics": self.diagnostics,
                "notMeasured": ["account synchronization, crafting profit/discovery calculation and live GW2 upstream "
                                "traffic are not measured in container mode (no GW2 key, no production proof bypass); "
                                "see the application-process report for those fixture workloads",
                                "network and block I/O counters, per-service readiness times"],
                "cpuUnit": "Docker-reported percent of one CPU core (can exceed 100 on multi-core); computed by Docker between its two internal reads"}

    def write(self, ids):
        out = Path(self.args.output_dir)
        out.mkdir(parents=True, exist_ok=True)
        rep = self.report(ids)
        (out / "container-resources.json").write_text(json.dumps(rep, indent=2), encoding="utf-8")
        (out / "container-resources.md").write_text(to_markdown(rep), encoding="utf-8")


def to_markdown(rep: dict) -> str:
    lines = [f"# Container measurements ({rep['measurementScope']})", "",
             f"Project `{rep['project']}`; workload `{rep['workloadVersion']}`; CPU unit: {rep['cpuUnit']}.", "",
             "## Images (actual Docker-reported)", "", "| Service | Image id | Size (bytes) | Role |", "|---|---|---|---|"]
    for s, i in rep["images"].items():
        lines.append(f"| {s} | `{i.get('id')}` | {i.get('sizeBytes') if i.get('sizeBytes') is not None else 'unavailable'} | {i.get('role', '')} |")
    for w in rep["workloads"]:
        lines += ["", f"## {w['name']}", "", w["description"], "", "| KPI | Value | Unit |", "|---|---|---|"]
        for m in w["metrics"]:
            lines.append(f"| {m['name']} | {m['value'] if m.get('value') is not None else 'unavailable: ' + m.get('unavailableReason', '')} | {m['unit']} |")
    lines += ["", "## Not measured", ""] + [f"- {n}" for n in rep["notMeasured"]]
    lines += ["", "## Diagnostics", ""] + ([f"- {d}" for d in rep["diagnostics"]] or ["None."])
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output-dir", default="container-perf-out")
    p.add_argument("--id")
    p.add_argument("--commit", default=os.environ.get("GITHUB_SHA", "unknown"))
    p.add_argument("--timestamp")
    p.add_argument("--idle-seconds", type=int, default=20)
    p.add_argument("--traffic-seconds", type=int, default=30)
    p.add_argument("--timeout", type=int, default=600)
    p.add_argument("--paths", nargs="*", default=["/", "/api/system/status", "/api/crafting/ingredient-search?query=wood"])
    p.add_argument("--strict", action="store_true", help="exit non-zero on failure (local debugging)")
    args = p.parse_args(argv)
    m = Measurement(args)
    failed = False
    try:
        m.go()
    except SystemExit:
        raise
    except BaseException as error:  # informational: record, then clean up
        m.diagnostics.append(f"measurement aborted: {error!r}")
        failed = True
        try:
            m.write({})
        except Exception:
            pass
    finally:
        try:
            m.cleanup()
        except Exception as error:
            m.diagnostics.append(f"cleanup failed: {error!r}")
        if m.diagnostics:
            m.write({})
    failed = failed or bool(m.diagnostics)
    print("diagnostics:", *(m.diagnostics or ["none"]), sep="\n  ")
    return 1 if (args.strict and failed) else 0


if __name__ == "__main__":
    sys.exit(main())
