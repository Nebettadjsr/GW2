import unittest

from agent.runtime.core.follow_up_findings import planner_block, unresolved_findings


class FollowUpFindingsTest(unittest.TestCase):
    def test_dispositioned_findings_are_not_repeated(self):
        story = """## Follow-up Findings

- F001: Existing work has a scaling limitation.
- F002: Item icons are missing cache headers.

## Result

Done.

## Follow-up Findings Disposition

- F001: ALREADY COVERED — STORY-WEB-010
- F002: DEFERRED — docs/KNOWN_PROBLEMS.md; revisit in Phase 6.
"""
        self.assertEqual(unresolved_findings(story, "agent/stories/STORY-X.md"), [])

    def test_open_findings_are_explicitly_scoped_to_their_completed_story(self):
        story = """## Follow-up Findings

- F001: The cache is not shared across replicas.
- F002: A second issue.

## Result

Done.
"""
        findings = unresolved_findings(story, "agent/stories/STORY-X.md")
        self.assertEqual([item["id"] for item in findings], ["F001", "F002"])
        block = planner_block(findings)
        self.assertIn("STORY-X.md [F001]", block)
        self.assertIn("The cache is not shared across replicas", block)

    def test_no_findings_has_explicit_empty_context(self):
        self.assertEqual(planner_block([]),
                         "(no unresolved Claude implementation follow-up findings)")


if __name__ == "__main__":
    unittest.main()
