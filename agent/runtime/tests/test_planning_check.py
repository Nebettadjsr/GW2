"""Offline regression coverage for the planner's output preflight."""

import json
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import planning_check
from agent.runtime.human import architect_requests


REQUEST = """# AR-007 - Cached results refresh contract

## Status

OPEN

## Architecture Question

How should a successful cached response expose source freshness and refresh progress?

## Context and Constraints

Cached results and asynchronous refresh are already selected by the Product Owner.

## Authoritative References

- docs/TARGET_ARCHITECTURE.md section 9

## Blocked Work

The cached-result HTTP contract.

## Blocking User Decision

None.

## Architect Decision

TODO

## Resolution

TODO
"""


class PlanningCheckTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.inbox = self.root / "architect-requests"
        self.inbox.mkdir()
        self.result = self.root / "PLANNING_RESULT.json"
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(planning_check, "PLANNING_RESULT_FILE", self.result))
        stack.enter_context(patch.object(planning_check, "BACKLOG_FILE", self.root / "BACKLOG.md"))
        stack.enter_context(patch.object(architect_requests, "ARCHITECT_REQUESTS_DIR", self.inbox))

    def write_result(self, name, reference="AR-007"):
        self.result.write_text(json.dumps({
            "status": "COMPLETE",
            "independent_work_remaining": False,
            "phase_review": [{"area": "Cached response contract", "outcome": "ARCHITECT_REQUEST",
                              "references": [reference]}],
            "user_decision_ids": [],
            "architect_requests_created": [name],
        }), encoding="utf-8")

    def test_both_rolled_back_ar007_outputs_are_rejected(self):
        for name in ("AR-007-cached-account-refresh-http-contract.md",
                     "AR-007-cached-results-refresh-contract.md"):
            with self.subTest(name=name):
                request = self.inbox / name
                request.write_text(REQUEST.split("\n", 1)[1], encoding="utf-8")
                self.write_result(name, "agent/architect-requests/" + name)
                problems = planning_check.check()
                self.assertIn(f"{name} has no readable 'AR-<NUMBER>' title.", problems)
                self.assertIn("ARCHITECT_REQUEST review entries must reference stable AR IDs.", problems)
                self.assertIn(f"New architect request {name} is missing from phase_review.", problems)
                request.unlink()

    def test_missing_title_is_rejected_even_with_stable_reference(self):
        name = "AR-007-cached-results-refresh-contract.md"
        (self.inbox / name).write_text(REQUEST.split("\n", 1)[1], encoding="utf-8")
        self.write_result(name)
        self.assertIn(f"{name} has no readable 'AR-<NUMBER>' title.", planning_check.check())

    def test_filename_reference_is_rejected_even_with_valid_request(self):
        name = "AR-007-cached-results-refresh-contract.md"
        (self.inbox / name).write_text(REQUEST, encoding="utf-8")
        self.write_result(name, "agent/architect-requests/" + name)
        self.assertIn("ARCHITECT_REQUEST review entries must reference stable AR IDs.",
                      planning_check.check())

    def test_corrected_request_passes_without_changing_files(self):
        name = "AR-007-cached-results-refresh-contract.md"
        (self.inbox / name).write_text(REQUEST, encoding="utf-8")
        self.write_result(name)
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual([], planning_check.check())
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_reported_request_must_exist(self):
        name = "AR-007-cached-results-refresh-contract.md"
        self.write_result(name)
        self.assertIn("Reported architect request does not exist under agent/architect-requests/: " + name,
                      planning_check.check())


if __name__ == "__main__":
    unittest.main()
