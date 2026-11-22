"""Offline checks for the rework context renderer."""

import unittest

import collect_rework_context as ctx


class ReworkContextTest(unittest.TestCase):
    def test_failing_checks_show_their_annotation_messages(self):
        text = ctx.render(
            392, "0cc9a7a",
            [{"id": 1, "name": "Backend tests (Maven)", "conclusion": "failure"},
             {"id": 2, "name": "Workflow checks", "conclusion": "success"}],
            {"1": [{"path": "backend/src/test/java/sync/SyncFetchPublicationTest.java",
                    "title": "tpSync_batch404FallsBackToSingleFetchForEveryId",
                    "message": "expected: <7:7/70/8/75,9:9/90/10/95> but was: <7:...,8:-/-/-/-,9:...>"}]},
            [], [])
        self.assertIn("### Backend tests (Maven) (failure)", text)
        self.assertIn("tpSync_batch404FallsBackToSingleFetchForEveryId", text)
        self.assertNotIn("Workflow checks", text)
        self.assertIn("search the tests for assertions of the OLD behavior", text)

    def test_missing_annotations_point_to_the_log(self):
        text = ctx.render(1, "abc", [{"id": 9, "name": "X", "conclusion": "timed_out"}], {}, [], [])
        self.assertIn("No annotations were published", text)

    def test_unresolved_threads_and_notes_are_listed(self):
        text = ctx.render(1, "abc", [], {}, [{"path": "a/B.java", "body": "Flag this"}],
                          ["check runs unavailable: x"])
        self.assertIn("- a/B.java: Flag this", text)
        self.assertIn("None recorded for this head", text)
        self.assertIn("check runs unavailable: x", text)

    def test_long_text_is_clipped(self):
        self.assertLessEqual(len(ctx.clip("x" * 5000)), ctx.MAX_TEXT)

    def test_annotation_count_is_bounded(self):
        many = [{"path": "p", "title": "t", "message": str(i)} for i in range(40)]
        text = ctx.render(1, "abc", [{"id": 1, "name": "N", "conclusion": "failure"}], {"1": many}, [], [])
        self.assertIn("25 more annotations omitted", text)


if __name__ == "__main__":
    unittest.main()
