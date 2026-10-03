#!/usr/bin/env python3
"""Combine Java, TypeScript, and agent-runtime coverage reports for CI."""

from __future__ import annotations

import argparse
import json
import os
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class Counts:
    lines_covered: int = 0
    lines_valid: int = 0
    branches_covered: int = 0
    branches_valid: int = 0

    def plus(self, other: "Counts") -> "Counts":
        return Counts(
            self.lines_covered + other.lines_covered,
            self.lines_valid + other.lines_valid,
            self.branches_covered + other.branches_covered,
            self.branches_valid + other.branches_valid,
        )

    def report(self) -> dict[str, int | float | None]:
        return {
            "lines_covered": self.lines_covered,
            "lines_valid": self.lines_valid,
            "line_percent": percent(self.lines_covered, self.lines_valid),
            "branches_covered": self.branches_covered,
            "branches_valid": self.branches_valid,
            "branch_percent": percent(self.branches_covered, self.branches_valid),
        }


def percent(covered: int, valid: int) -> float | None:
    return round(covered * 100 / valid, 2) if valid else None


def jacoco(path: Path) -> tuple[Counts, dict[str, Counts]]:
    root = ET.parse(path).getroot()
    packages: dict[str, Counts] = {}
    for node in root.findall("./package"):
        counters = {c.get("type"): c for c in node.findall("./counter")}
        package_counts = Counts(
            int(counters.get("LINE", {}).get("covered", 0)),
            int(counters.get("LINE", {}).get("covered", 0)) + int(counters.get("LINE", {}).get("missed", 0)),
            int(counters.get("BRANCH", {}).get("covered", 0)),
            int(counters.get("BRANCH", {}).get("covered", 0)) + int(counters.get("BRANCH", {}).get("missed", 0)),
        )
        if package_counts.lines_valid or package_counts.branches_valid:
            package_name = node.get("name", "unknown").replace("/", ".") or "(default package)"
            packages[package_name] = package_counts
    counters = {c.get("type"): c for c in root.findall("./counter")}
    total = Counts(
        int(counters.get("LINE", {}).get("covered", 0)),
        int(counters.get("LINE", {}).get("covered", 0)) + int(counters.get("LINE", {}).get("missed", 0)),
        int(counters.get("BRANCH", {}).get("covered", 0)),
        int(counters.get("BRANCH", {}).get("covered", 0)) + int(counters.get("BRANCH", {}).get("missed", 0)),
    )
    return total, packages


def cobertura(path: Path) -> tuple[Counts, dict[str, Counts]]:
    root = ET.parse(path).getroot()
    packages: dict[str, Counts] = {}
    for package in root.findall("./packages/package"):
        files: dict[str, Counts] = {}
        for source in package.findall("./classes/class"):
            filename = source.get("filename", "unknown")
            line_valid = line_covered = branch_valid = branch_covered = 0
            for line in source.findall("./lines/line"):
                line_valid += 1
                line_covered += int(line.get("hits", 0)) > 0
                condition = line.get("condition-coverage", "")
                if condition:
                    match = re.search(r"\((\d+)/(\d+)\)", condition)
                    if match:
                        branch_covered += int(match.group(1))
                        branch_valid += int(match.group(2))
            files[filename] = Counts(line_covered, line_valid, branch_covered, branch_valid)
        package_total = Counts()
        for counts in files.values():
            package_total = package_total.plus(counts)
        if package_total.lines_valid or package_total.branches_valid:
            packages[package.get("name", "unknown")] = package_total
    total = Counts()
    for counts in packages.values():
        total = total.plus(counts)
    return total, packages


def vitest(path: Path) -> tuple[Counts, dict[str, Counts]]:
    report = json.loads(path.read_text(encoding="utf-8"))
    packages: dict[str, Counts] = {}
    for filename, data in report.items():
        if filename == "total":
            continue
        source = Path(filename).as_posix()
        group = source.split("/src/", 1)[-1].split("/", 1)[0] if "/src/" in source else "other"
        count = Counts(
            data.get("lines", {}).get("covered", 0), data.get("lines", {}).get("total", 0),
            data.get("branches", {}).get("covered", 0), data.get("branches", {}).get("total", 0),
        )
        packages[group] = packages.get(group, Counts()).plus(count)
    total_data = report.get("total", {})
    total = Counts(
        total_data.get("lines", {}).get("covered", 0), total_data.get("lines", {}).get("total", 0),
        total_data.get("branches", {}).get("covered", 0), total_data.get("branches", {}).get("total", 0),
    )
    return total, packages


def read_module(name: str, path: Path, parser) -> dict:
    if not path.is_file():
        return {"name": name, "status": "unavailable", "reason": f"Coverage report not found: {path}", "overall": None, "packages": {}}
    try:
        total, packages = parser(path)
    except (ET.ParseError, OSError, ValueError, json.JSONDecodeError) as error:
        return {"name": name, "status": "unavailable", "reason": f"Coverage report could not be read: {error}", "overall": None, "packages": {}}
    return {"name": name, "status": "measured", "overall": total.report(), "packages": {key: value.report() for key, value in sorted(packages.items())}}


def markdown(data: dict) -> str:
    target = data["target_percent"]
    revision = data["source_revision"] or "not provided"
    rows = ["## Test coverage KPI", "", f"Source revision: `{revision}`", "", f"Long-term target: **{target}% line and branch coverage**. Below target is a warning only; it does not fail CI.", "", "| Module | Lines | Branches | Status |", "|---|---:|---:|---|"]
    for module in data["modules"]:
        overall = module["overall"]
        if overall is None:
            rows.append(f"| {module['name']} | unavailable | unavailable | unavailable |")
        else:
            warning = "warning: below target" if (overall["line_percent"] or 0) < target or (overall["branch_percent"] or 0) < target else "at target"
            rows.append(f"| {module['name']} | {fmt(overall['line_percent'])} | {fmt(overall['branch_percent'])} | {warning} |")
    overall = data["overall"]
    rows.append(f"| **Combined** | {fmt(overall['line_percent'])} | {fmt(overall['branch_percent'])} | measured sources only |")
    rows.extend(["", "### Package/module breakdown", "", "| Module/package | Lines | Branches | Status |", "|---|---:|---:|---|"])
    for module in data["modules"]:
        for name, values in module["packages"].items():
            warning = "warning: below target" if (values["line_percent"] or 0) < target or (values["branch_percent"] or 0) < target else "at target"
            rows.append(f"| {module['name']}: `{name}` | {fmt(values['line_percent'])} | {fmt(values['branch_percent'])} | {warning} |")
        if module["status"] == "unavailable":
            rows.append(f"| {module['name']} | unavailable: {module['reason']} | unavailable |")
    rows.extend(["", "Combined percentages weight measured executable lines/branches across modules. Missing reports are unavailable, never counted as zero. HTML and raw machine-readable reports are published as the `coverage-reports` CI artifact."])
    return "\n".join(rows) + "\n"


def fmt(value: float | None) -> str:
    return "unavailable" if value is None else f"{value:.2f}%"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", type=Path, default=Path("target/site/jacoco/jacoco.xml"))
    parser.add_argument("--frontend", type=Path, default=Path("frontend/coverage/coverage-summary.json"))
    parser.add_argument("--python", type=Path, default=Path("agent/runtime/coverage.xml"))
    parser.add_argument("--output", type=Path, default=Path("quality-reports"))
    parser.add_argument("--revision", default=os.environ.get("GITHUB_SHA"))
    args = parser.parse_args()

    modules = [
        read_module("Java backend", args.java, jacoco),
        read_module("Frontend TypeScript", args.frontend, vitest),
        read_module("Python agent runtime", args.python, cobertura),
    ]
    combined = Counts()
    for module in modules:
        if module["overall"]:
            values = module["overall"]
            combined = combined.plus(Counts(values["lines_covered"], values["lines_valid"], values["branches_covered"], values["branches_valid"]))
    data = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_revision": args.revision,
        "target_percent": 90,
        "policy": "warning_only",
        "modules": modules,
        "overall": combined.report(),
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "coverage-kpi.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    (args.output / "coverage-kpi.md").write_text(markdown(data), encoding="utf-8")
    print(markdown(data))


if __name__ == "__main__":
    main()
