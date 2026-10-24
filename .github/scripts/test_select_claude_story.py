import unittest

from select_claude_story import dependencies, select_story


REPO = "https://api.github.com/repos/Nebettadjsr/GW2"


def issue(number, state="open", labels=(), deps="None", body_prefix=""):
    return {
        "number": number,
        "title": f"STORY-WEB-{number:03d}: Test story",
        "state": state,
        "labels": [{"name": label} for label in labels],
        "body": f"{body_prefix}\n## Dependencies\n\n{deps}\n\n## Definition of Done\n",
        "repository_url": REPO,
    }


class StorySelectionTest(unittest.TestCase):
    def test_oldest_ready_auto_story_wins(self):
        stories = [
            issue(1, labels=["pairing"]),
            issue(2, labels=["auto"], deps="STORY-WEB-001"),
            issue(3, labels=["auto"]),
            issue(4, labels=["auto"]),
        ]
        chosen, linked, reasons = select_story(stories, [])
        self.assertEqual(chosen["number"], 3)
        self.assertIsNone(linked)
        self.assertIn("#2: dependency lacks merged completion", reasons[0])

    def test_closed_dependency_needs_merged_pr(self):
        stories = [issue(1, state="closed"), issue(2, labels=["auto"], deps="STORY-WEB-001")]
        self.assertEqual(dependencies(stories[1]), {"STORY-WEB-001"})
        self.assertIsNone(select_story(stories, [])[0])
        merged = {
            "state": "closed", "merged_at": "2026-10-24T12:00:00Z",
            "head": {"repo": {"full_name": "Nebettadjsr/GW2"}, "ref": "story/1-legacy"},
            "base": {"repo": {"full_name": "Nebettadjsr/GW2"}, "ref": "master"},
            "body": "Closes #1",
        }
        self.assertEqual(select_story(stories, [merged])[0]["number"], 2)
        merged["base"]["ref"] = "integration"
        self.assertIsNone(select_story(stories, [merged])[0])

    def test_existing_pr_and_decision_prevent_repeat(self):
        stories = [issue(2, labels=["auto"]), issue(3, labels=["auto", "needs-decision"])]
        pull = {
            "state": "open",
            "head": {"repo": {"full_name": "Nebettadjsr/GW2"}, "ref": "story/2-old-format"},
            "base": {"ref": "master"},
            "body": "Closes #2",
        }
        chosen, linked, reasons = select_story(stories, [pull])
        self.assertIsNone(chosen)
        self.assertIsNone(linked)
        self.assertEqual(len(reasons), 2)

    def test_review_rework_precedes_new_story(self):
        stories = [issue(2, labels=["auto"]), issue(3, labels=["auto", "needs-rework"])]
        pull = {
            "state": "open",
            "head": {"repo": {"full_name": "Nebettadjsr/GW2"}, "ref": "fix/review-feedback"},
            "base": {"ref": "master"},
            "body": "Closes #3",
        }
        selected, linked, _ = select_story(stories, [pull])
        self.assertEqual(selected["number"], 3)
        self.assertEqual(linked["head"]["ref"], "fix/review-feedback")
        pull["base"]["ref"] = "integration"
        next_story, _, reasons = select_story(stories, [pull])
        self.assertEqual(next_story["number"], 2)
        self.assertIn("does not target master", reasons[0])

    def test_fork_pr_blocks_duplicate_run(self):
        stories = [issue(2, labels=["auto", "needs-rework"])]
        pull = {
            "state": "open",
            "head": {"repo": {"full_name": "someone/GW2"}, "ref": "fix/account-ui"},
            "base": {"ref": "master"},
            "body": "Closes #2",
        }
        selected, linked, reasons = select_story(stories, [pull])
        self.assertIsNone(selected)
        self.assertIsNone(linked)
        self.assertIn("from a fork", reasons[0])


if __name__ == "__main__":
    unittest.main()
