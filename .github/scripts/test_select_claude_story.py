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
        chosen, reasons = select_story(stories, [])
        self.assertEqual(chosen["number"], 3)
        self.assertIn("#2: dependency open", reasons[0])

    def test_closed_dependency_allows_story(self):
        stories = [issue(1, state="closed"), issue(2, labels=["auto"], deps="STORY-WEB-001")]
        self.assertEqual(dependencies(stories[1]), {"STORY-WEB-001"})
        self.assertEqual(select_story(stories, [])[0]["number"], 2)

    def test_existing_pr_and_decision_prevent_repeat(self):
        stories = [issue(2, labels=["auto"]), issue(3, labels=["auto", "needs-decision"])]
        pull = {
            "state": "open",
            "head": {"repo": {"full_name": "Nebettadjsr/GW2"}, "ref": "story/2-old-format"},
            "body": "Closes #2",
        }
        chosen, reasons = select_story(stories, [pull])
        self.assertIsNone(chosen)
        self.assertEqual(len(reasons), 2)

    def test_review_rework_precedes_new_story(self):
        stories = [issue(2, labels=["auto"]), issue(3, labels=["auto", "needs-rework"])]
        pull = {
            "state": "open",
            "head": {"repo": {"full_name": "Nebettadjsr/GW2"}, "ref": "story/3-old-format"},
            "body": "Closes #3",
        }
        self.assertEqual(select_story(stories, [pull])[0]["number"], 3)


if __name__ == "__main__":
    unittest.main()
