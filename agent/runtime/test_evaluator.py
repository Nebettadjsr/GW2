"""
Deterministic tests for evaluator.py's normalization layer and
orchestrator.py's retry-prompt scoping.

Run with: python -m unittest agent.runtime.test_evaluator -v
(from the repository root), or `python test_evaluator.py` from inside
agent/runtime/.

These tests do not call Hermes/Ollama -- they exercise
normalize_evaluation() directly (pure function, no network) and
build_retry_prompt() (pure string formatting), which is where PART 1-4
of the fix actually live. No application/domain Java code is touched.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evaluator import normalize_evaluation  # noqa: E402
from orchestrator import build_retry_prompt  # noqa: E402


DONE_STORY = """# STORY-TEST-DONE

## Status

DONE

## Acceptance Criteria

- Thing A is implemented.

## Definition of Done

- Tests pass.
"""

TODO_STORY = """# STORY-TEST-TODO

## Status

TODO

## Acceptance Criteria

- Thing A is implemented.

## Definition of Done

- Tests pass.
"""


def make_raw(
    decision,
    reason="",
    unmet_acceptance_criteria=None,
    unmet_definition_of_done=None,
    actionable_retry_items=None,
):
    return {
        "decision": decision,
        "reason": reason,
        "unmet_acceptance_criteria": unmet_acceptance_criteria or [],
        "unmet_definition_of_done": unmet_definition_of_done or [],
        "actionable_retry_items": actionable_retry_items or [],
    }


class NormalizeEvaluationTests(unittest.TestCase):

    def test_1_complete_with_no_deficiencies_stays_complete(self):
        raw = make_raw("COMPLETE", reason="All good.")

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "COMPLETE")
        self.assertFalse(result["actionable_retry_items"])

    def test_2_retry_with_concrete_unmet_criterion_stays_retry(self):
        raw = make_raw(
            "RETRY",
            reason="Thing A is missing.",
            unmet_acceptance_criteria=["Thing A is implemented."],
            actionable_retry_items=["Implement Thing A."],
        )

        result = normalize_evaluation(raw, TODO_STORY)

        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(
            result["actionable_retry_items"], ["Implement Thing A."]
        )

    def test_3_retry_with_empty_actionable_items_is_rejected(self):
        raw = make_raw("RETRY", reason="Not quite done.")

        result = normalize_evaluation(raw, TODO_STORY)

        self.assertNotEqual(result["decision"], "RETRY")
        # story is not canonically DONE, so there is nothing
        # deterministic to fall back to -- surface to a human instead
        # of silently declaring victory.
        self.assertEqual(result["decision"], "NEEDS_USER")

    def test_4_retry_reason_claims_complete_but_no_structured_gap(self):
        raw = make_raw(
            "RETRY",
            reason=(
                "The story was already finalized ... all six ... "
                "conflicts now satisfy the criterion"
            ),
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertNotEqual(result["decision"], "RETRY")
        self.assertEqual(result["decision"], "COMPLETE")

    def test_5_done_story_green_tests_no_deficiency_is_complete(self):
        raw = make_raw("RETRY", reason="Everything checks out.")

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "COMPLETE")
        self.assertTrue(result["normalized"])

    def test_6_done_story_with_concrete_unmet_criterion_can_retry(self):
        raw = make_raw(
            "RETRY",
            reason="One criterion still fails.",
            unmet_acceptance_criteria=["Thing A is implemented."],
            actionable_retry_items=["Fix Thing A."],
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(result["actionable_retry_items"], ["Fix Thing A."])

    def test_7_unrelated_roadmap_incompleteness_does_not_force_retry(self):
        raw = make_raw(
            "RETRY",
            reason="Phase 0 exit criteria are not all met yet.",
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertNotEqual(result["decision"], "RETRY")

    def test_8_next_story_work_does_not_force_retry(self):
        raw = make_raw(
            "RETRY",
            reason="The next story should now be selected and planned.",
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertNotEqual(result["decision"], "RETRY")

    def test_complete_with_contradictory_unmet_items_downgrades_to_retry(self):
        raw = make_raw(
            "COMPLETE",
            reason="Looks done.",
            unmet_acceptance_criteria=["Thing A is implemented."],
        )

        result = normalize_evaluation(raw, TODO_STORY)

        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(
            result["actionable_retry_items"], ["Thing A is implemented."]
        )

    def test_retry_backfills_actionable_items_from_unmet_lists(self):
        raw = make_raw(
            "RETRY",
            reason="DoD item missing.",
            unmet_definition_of_done=["Tests pass."],
        )

        result = normalize_evaluation(raw, TODO_STORY)

        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(result["actionable_retry_items"], ["Tests pass."])


class RetryPromptScopeTests(unittest.TestCase):

    def test_9_retry_prompt_contains_only_actionable_items(self):
        story_path = (
            Path(__file__).resolve().parent.parent
            / "stories"
            / "STORY-DOM-011-bound-material-domain-rule.md"
        )

        prompt = build_retry_prompt(
            ["Fix the flaky assertion in CraftingResolverBoundMaterialTest."],
            (
                "Unrelated roadmap commentary that must not leak into "
                "the retry scope, e.g. plan the next milestone."
            ),
            story_path,
        )

        self.assertIn(
            "Fix the flaky assertion in CraftingResolverBoundMaterialTest.",
            prompt,
        )
        self.assertIn(
            "do not touch roadmap/milestone planning, future/next",
            prompt,
        )


if __name__ == "__main__":
    unittest.main()
