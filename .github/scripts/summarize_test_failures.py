"""Turn a failed CI job's test reports into a few compact GitHub annotations.

The orchestrator reads these annotations through the public checks API and
hands them to Claude as the failure report (agent/runtime/support/github_ci.py).
That is the whole reason this script exists: without it the only machine-readable
record of a failure is the raw job log, and feeding whole CI logs into a model
prompt is exactly what the new workflow is meant to avoid. Nothing here decides
whether the job failed -- the test command already did that.

Usage:
    summarize_test_failures.py --label "Backend (Maven)" --junit target/surefire-reports
    summarize_test_failures.py --label "Agent runtime" --unittest-log runtime-tests.log

Reads JUnit XML (Surefire and Vitest write the same shape) and/or a plain
unittest log, and prints one `::error::` workflow command per failing test.
"""
import argparse
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

# GitHub displays at most ten error annotations per step. Anything beyond that
# is counted in a final annotation instead of silently disappearing: a truncated
# report must say that it is truncated.
MAX_ANNOTATIONS = 10

# One failure must stay readable in a model prompt. The full output remains in
# the job log for a human.
MAX_MESSAGE_CHARS = 600

UNITTEST_FAILURE = re.compile(r"^(FAIL|ERROR):\s+(.+)$")


def escape_message(value: str) -> str:
    """Escaping for a workflow command's message.

    Only these three are decoded again by the runner. A real run showed why
    that distinction matters: escaping ':' and ',' here left literal "%3A"
    and "%2C" in the annotation the orchestrator reads back.
    """

    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(value: str) -> str:
    """Escaping for a property value such as `title=`, where ':' and ',' end it."""

    return escape_message(value).replace(":", "%3A").replace(",", "%2C")


def shorten(text: str) -> str:
    collapsed = "\n".join(
        line.rstrip() for line in (text or "").strip().splitlines() if line.strip()
    )

    if len(collapsed) <= MAX_MESSAGE_CHARS:
        return collapsed

    return collapsed[:MAX_MESSAGE_CHARS].rstrip() + " [...]"


def junit_failures(directories) -> list[tuple[str, str]]:
    failures = []

    for directory in directories:
        root = Path(directory)

        if not root.exists():
            continue

        for report in sorted(root.rglob("*.xml")):
            try:
                tree = ElementTree.parse(report)
            except ElementTree.ParseError:
                continue

            for case in tree.iter("testcase"):
                problems = list(case.findall("failure")) + list(case.findall("error"))

                if not problems:
                    continue

                name = ".".join(
                    part for part in (case.get("classname"), case.get("name")) if part
                )
                problem = problems[0]
                detail = (problem.get("message") or "").strip() or (
                    problem.text or ""
                ).strip()

                failures.append((name or report.stem, shorten(detail) or "(no message)"))

    return failures


def unittest_failures(log_path) -> list[tuple[str, str]]:
    path = Path(log_path)

    if not path.exists():
        return []

    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    failures = []

    for index, line in enumerate(lines):
        match = UNITTEST_FAILURE.match(line.strip())

        if not match:
            continue

        # The assertion itself is the last non-separator line of the block that
        # follows the header, which is what a reader actually needs.
        detail = []

        for following in lines[index + 1:index + 40]:
            stripped = following.strip()

            # unittest brackets each failure with rules of '=' or '-'. They
            # separate blocks; the second one ends this failure's output.
            if set(stripped) in ({"="}, {"-"}):
                if detail:
                    break
                continue

            if stripped:
                detail.append(stripped)

        failures.append((match.group(2), shorten("\n".join(detail[-6:]))))

    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--junit", action="append", default=[])
    parser.add_argument("--unittest-log")
    arguments = parser.parse_args()

    failures = junit_failures(arguments.junit)

    if arguments.unittest_log:
        failures += unittest_failures(arguments.unittest_log)

    if not failures:
        # The job failed before or outside the tests (dependency download,
        # compile error, missing service). Say exactly that instead of
        # implying the suite passed.
        print(
            f"::error title={escape_property(arguments.label)}::"
            + escape_message(
                "The job failed without producing test failures "
                "(build, setup or infrastructure error -- see the job log)."
            )
        )
        return 0

    for name, detail in failures[:MAX_ANNOTATIONS]:
        print(
            f"::error title={escape_property(arguments.label + ': ' + name)}::"
            + escape_message(detail)
        )

    remaining = len(failures) - MAX_ANNOTATIONS

    if remaining > 0:
        print(
            f"::error title={escape_property(arguments.label)}::"
            + escape_message(
                f"{remaining} further test failure(s) not listed here; "
                "see the job log."
            )
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
