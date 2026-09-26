"""PO request lifecycle tests; no planner/model calls."""
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.human import product_owner_requests as requests


class RequestLifecycleTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.inbox = self.root / "requests"
        self.inbox.mkdir()
        self.decisions = self.root / "decisions"
        self.decisions.mkdir()
        (self.root / "docs").mkdir()
        (self.root / "docs/TARGET_ARCHITECTURE.md").write_text("Existing coverage")
        stack = ExitStack()
        self.addCleanup(stack.close)
        for name, value in [("PRODUCT_OWNER_REQUESTS_DIR", self.inbox),
                            ("REPO_ROOT", self.root),
                            ("USER_DECISIONS_DIR", self.decisions)]:
            stack.enter_context(patch.object(requests, name, value))

    def write(self, name="request.md", status="OPEN", resolution=""):
        text = (f"# Request\n\n## Requested Change\n\nKeep this intent.\n\n"
                f"## Status\n\n{status}\n\n## Planner Resolution\n\n{resolution}\n")
        (self.inbox / name).write_text(text, encoding="utf-8")
        return text

    def test_only_open_needs_user_and_legacy_requests_are_visible(self):
        self.write("open.md")
        self.write("waiting.md", "NEEDS_USER", "UD-001-choice.md")
        self.write("resolved.md", "RESOLVED", "Existing coverage")
        (self.inbox / "legacy.md").write_text("Plain request")
        (self.inbox / "README.md").write_text("Instructions")
        self.assertEqual({r["file"] for r in requests.read_requests()},
                         {"open.md", "waiting.md", "legacy.md"})
        self.assertEqual(len(requests.read_requests(include_resolved=True)), 4)

    def test_retained_resolution_with_evidence(self):
        before = {"request.md": self.write()}
        self.write(status="RESOLVED", resolution="Reused docs/TARGET_ARCHITECTURE.md; no action required.")
        self.assertEqual(requests.validate_request_updates(before, ["request.md"], "COMPLETE"), [])
        self.assertTrue((self.inbox / "request.md").exists())

    def test_independent_request_can_resolve_on_needs_user_pass(self):
        before = {"request.md": self.write()}
        self.write(status="RESOLVED", resolution="Reused docs/TARGET_ARCHITECTURE.md.")
        self.assertEqual(requests.validate_request_updates(before, ["request.md"], "NEEDS_USER"), [])

    def test_any_deletion_is_rejected_even_when_reported(self):
        before = {"request.md": self.write()}
        (self.inbox / "request.md").unlink()
        for processed in ([], ["request.md"]):
            self.assertIn("was deleted", " ".join(requests.validate_request_updates(before, processed, "COMPLETE")))

    def test_unreported_or_premature_resolution_is_rejected(self):
        before = {"request.md": self.write()}
        self.write(status="RESOLVED", resolution="Updated docs/TARGET_ARCHITECTURE.md.")
        for processed, status in [([], "COMPLETE"),
                                  (["request.md"], "FAILED")]:
            self.assertTrue(requests.validate_request_updates(before, processed, status))

    def test_resolution_requires_note_and_existing_evidence(self):
        before = {"request.md": self.write()}
        for resolution in ("", "Done", "Updated docs/missing.md"):
            self.write(status="RESOLVED", resolution=resolution)
            self.assertTrue(requests.validate_request_updates(before, ["request.md"], "COMPLETE"))

    def test_needs_user_requires_existing_ud_and_stays_visible(self):
        before = {"request.md": self.write()}
        self.write(status="NEEDS_USER", resolution="Blocked by UD-001-choice.md")
        self.assertTrue(requests.validate_request_updates(before, [], "NEEDS_USER"))
        (self.decisions / "UD-001-choice.md").write_text("## Status\n\nOPEN\n")
        self.assertEqual(requests.validate_request_updates(before, [], "NEEDS_USER"), [])
        self.assertEqual(len(requests.read_requests()), 1)
        (self.decisions / "UD-001-choice.md").write_text("## Status\n\nRESOLVED\n")
        self.assertEqual(len(requests.read_requests()), 1)

    def test_already_resolved_requests_are_ignored(self):
        before = {"request.md": self.write(status="RESOLVED", resolution="Existing coverage")}
        self.assertEqual(requests.validate_request_updates(before, [], "COMPLETE"), [])
        self.assertTrue(requests.validate_request_updates(before, ["request.md"], "COMPLETE"))
        self.write()
        self.assertTrue(requests.validate_request_updates(before, [], "COMPLETE"))

    def test_invalid_status_and_reporting_unresolved_are_rejected(self):
        before = {"request.md": self.write()}
        self.assertTrue(requests.validate_request_updates(before, ["request.md"], "COMPLETE"))
        self.write(status="DONE")
        self.assertTrue(requests.validate_request_updates(before, [], "COMPLETE"))


if __name__ == "__main__":
    unittest.main()
