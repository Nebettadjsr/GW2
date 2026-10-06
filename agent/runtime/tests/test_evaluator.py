"""
Deterministic tests for evaluator.py's normalization layer, its verdict
extraction, and orchestrator.py's retry-prompt scoping.

Run with: python -m unittest agent.runtime.tests.test_evaluator -v
(from the repository root).

These tests never run Codex -- they exercise normalize_evaluation() and
parse_evaluator_verdict() directly (pure functions, no process) and
build_retry_prompt() (pure string formatting). No application/domain Java
code is touched.
"""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.evaluation.evaluator import (
    build_evaluation_prompt,
    normalize_evaluation,
    parse_evaluator_verdict,
)
from agent.runtime.core.orchestrator import build_retry_prompt


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
    unmet_intent=None,
    intent_achieved=True,
    evidence=None,
):
    return {
        "decision": decision,
        "reason": reason,
        "intent_achieved": intent_achieved,
        "evidence": evidence or ["git diff HEAD"],
        "unmet_intent": unmet_intent or [],
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


class IntentEvaluationTests(unittest.TestCase):
    """
    The question the previous evaluator could not ask: not "is every
    criterion ticked" but "did the work achieve what the story set out to
    achieve". A shortfall there must send the coder back, not pass.
    """

    def test_unachieved_intent_turns_complete_into_retry(self):
        raw = make_raw(
            "COMPLETE",
            reason="Every criterion is literally satisfied.",
            intent_achieved=False,
            unmet_intent=[
                "The rule is applied only in the table view; the fresh "
                "resolution path a user actually reaches is unchanged."
            ],
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "RETRY")
        self.assertTrue(result["normalized"])
        # The concrete shortfall becomes the work, and survives into the
        # retry prompt as its own named section.
        self.assertEqual(
            result["actionable_retry_items"], result["unmet_intent"]
        )

    def test_unmet_intent_alone_keeps_retry_actionable(self):
        raw = make_raw(
            "RETRY",
            reason="Purpose not achieved.",
            intent_achieved=False,
            unmet_intent=["The new test passes with the feature removed."],
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(
            result["actionable_retry_items"],
            ["The new test passes with the feature removed."],
        )

    def test_denied_intent_without_a_named_shortfall_asks_a_human(self):
        # Contradicts itself and offers nothing to act on -- the same shape
        # of evaluator failure as an empty RETRY, and never a silent pass.
        raw = make_raw(
            "COMPLETE",
            reason="Something feels off but I cannot name it.",
            intent_achieved=False,
        )

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "NEEDS_USER")
        self.assertIn("intent_achieved=false", result["reason"])

    def test_achieved_intent_with_no_deficiency_still_completes(self):
        result = normalize_evaluation(make_raw("COMPLETE"), DONE_STORY)

        self.assertEqual(result["decision"], "COMPLETE")
        self.assertFalse(result["normalized"])

    def test_missing_intent_field_is_not_read_as_denial(self):
        # An older payload, or a model that omitted the field, must not be
        # treated as "purpose not achieved".
        raw = make_raw("COMPLETE")
        del raw["intent_achieved"]

        result = normalize_evaluation(raw, DONE_STORY)

        self.assertEqual(result["decision"], "COMPLETE")

    def test_prompt_demands_repository_evidence_not_the_self_report(self):
        prompt = build_evaluation_prompt(DONE_STORY, "I did everything.", 0, True)

        self.assertIn("git diff", prompt)
        self.assertIn("claim, not evidence", prompt)
        self.assertIn("READ-ONLY", prompt)
        # The scope boundary the previous evaluator contract had must not
        # have been lost in the rewrite.
        self.assertIn("roadmap phase or milestone", prompt)


class VerdictParsingTests(unittest.TestCase):
    """
    A read-only evaluator cannot write its result, so the verdict is read
    back out of its final message. Anything unusable must raise, never be
    guessed at: acting on an inferred verdict is worse than retrying.
    """

    def test_fenced_json_block_is_extracted(self):
        verdict = parse_evaluator_verdict([
            "Here is my assessment.\n\n```json\n"
            '{"decision": "COMPLETE", "reason": "ok", "evidence": ["diff"],\n'
            ' "unmet_intent": [], "unmet_acceptance_criteria": [],\n'
            ' "unmet_definition_of_done": [], "actionable_retry_items": [],\n'
            ' "intent_achieved": true}\n```\n'
        ])

        self.assertEqual(verdict["decision"], "COMPLETE")
        self.assertTrue(verdict["intent_achieved"])

    def test_the_last_message_wins(self):
        earlier = '```json\n{"decision": "RETRY", "reason": "draft"}\n```'
        later = '```json\n{"decision": "COMPLETE", "reason": "final"}\n```'

        self.assertEqual(
            parse_evaluator_verdict([earlier, later])["reason"], "final"
        )

    def test_bare_json_object_is_accepted(self):
        self.assertEqual(
            parse_evaluator_verdict(['{"decision": "BLOCKED", "reason": "x"}'])
            ["decision"],
            "BLOCKED",
        )

    def test_no_messages_raises(self):
        with self.assertRaises(ValueError):
            parse_evaluator_verdict([])

    def test_prose_without_a_verdict_raises(self):
        with self.assertRaises(ValueError) as caught:
            parse_evaluator_verdict(["The story looks complete to me."])

        self.assertIn("no usable verdict", str(caught.exception))

    def test_unknown_decision_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_evaluator_verdict(['{"decision": "LOOKS_FINE"}'])

    def test_a_malformed_block_does_not_hide_an_earlier_valid_one(self):
        with self.assertRaises(ValueError):
            parse_evaluator_verdict(['```json\n{"decision": "COMPLETE",\n```'])

        self.assertEqual(
            parse_evaluator_verdict([
                '```json\n{"decision": "COMPLETE", "reason": "ok"}\n```',
                "```json\n{not json\n```",
            ])["decision"],
            "COMPLETE",
        )

    def test_a_non_list_deficiency_field_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_evaluator_verdict([
                '{"decision": "RETRY", "actionable_retry_items": "do x"}'
            ])


class EvaluateStoryEndToEndTests(unittest.TestCase):
    """
    The whole path with only the Codex process replaced: prompt in,
    verdict out of the final message, normalization, recorded result.
    """

    def run_evaluation(self, message, exit_code=0):
        from agent.runtime.evaluation import evaluator

        with tempfile.TemporaryDirectory() as directory:
            result_file = Path(directory) / "EVALUATOR_RESULT.json"

            def fake_run(prompt, messages):
                self.assertIn("STORY EVALUATION MODE", prompt)
                messages.append(message)
                return exit_code

            with patch.object(evaluator, "run_evaluator", side_effect=fake_run), \
                 patch.object(evaluator, "EVALUATOR_RESULT_FILE", result_file):
                result = evaluator.evaluate_story(DONE_STORY, "report", 0, True)

            self.assertEqual(
                json.loads(result_file.read_text(encoding="utf-8"))["decision"],
                result["decision"],
            )

        return result

    def test_an_accepted_attempt_is_recorded_as_complete(self):
        result = self.run_evaluation(
            "```json\n" + json.dumps({
                "decision": "COMPLETE", "reason": "verified against the diff",
                "intent_achieved": True,
                "evidence": ["git diff HEAD", "SelectedResultDetail.vue"],
                "unmet_intent": [], "unmet_acceptance_criteria": [],
                "unmet_definition_of_done": [], "actionable_retry_items": [],
            }) + "\n```"
        )

        self.assertEqual(result["decision"], "COMPLETE")
        self.assertEqual(result["evaluator_exit_code"], 0)
        self.assertIn("git diff HEAD", result["evidence"])

    def test_bridge_can_defer_persistence_until_contract_revalidation(self):
        from agent.runtime.evaluation import evaluator

        with tempfile.TemporaryDirectory() as directory:
            result_file = Path(directory) / "EVALUATOR_RESULT.json"
            with patch.object(evaluator, "run_evaluator", return_value=0), \
                    patch.object(evaluator, "parse_evaluator_verdict", return_value={
                        "decision": "COMPLETE", "reason": "verified", "intent_achieved": True,
                        "evidence": ["fixture"], "unmet_intent": [],
                        "unmet_acceptance_criteria": [], "unmet_definition_of_done": [],
                        "actionable_retry_items": [],
                    }), \
                    patch.object(evaluator, "EVALUATOR_RESULT_FILE", result_file):
                result = evaluator.evaluate_story(DONE_STORY, "report", 0, True, persist=False)

            self.assertEqual("COMPLETE", result["decision"])
            self.assertFalse(result_file.exists())

    def test_an_intent_shortfall_comes_back_as_actionable_retry_work(self):
        result = self.run_evaluation(
            "```json\n" + json.dumps({
                "decision": "COMPLETE", "reason": "criteria tick, purpose does not",
                "intent_achieved": False,
                "evidence": ["git diff HEAD"],
                "unmet_intent": ["Only the table path applies the rule."],
                "unmet_acceptance_criteria": [],
                "unmet_definition_of_done": [], "actionable_retry_items": [],
            }) + "\n```"
        )

        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(
            result["actionable_retry_items"],
            ["Only the table path applies the rule."],
        )

    def test_an_unusable_final_message_raises_instead_of_guessing(self):
        with self.assertRaises(ValueError):
            self.run_evaluation("Looks good to me, ship it.")


class RetryPromptScopeTests(unittest.TestCase):

    def test_9_retry_prompt_contains_only_actionable_items(self):
        story_path = (
            Path(__file__).resolve().parents[2]
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

    def test_unmet_intent_gets_its_own_section_in_the_retry_prompt(self):
        story_path = (
            Path(__file__).resolve().parents[2]
            / "stories"
            / "STORY-DOM-011-bound-material-domain-rule.md"
        )

        prompt = build_retry_prompt(
            ["Apply the rule on the fresh resolution path too."],
            "reason text",
            story_path,
            unmet_intent=[
                "The rule holds only in the table view, not where a user "
                "reaches it."
            ],
        )

        self.assertIn("does not achieve what this story set out", prompt)
        self.assertIn("not where a user reaches it", prompt)
        self.assertIn("Fix the substance rather than restating", prompt)

    def test_retry_prompt_without_intent_findings_adds_no_intent_section(self):
        story_path = (
            Path(__file__).resolve().parents[2]
            / "stories"
            / "STORY-DOM-011-bound-material-domain-rule.md"
        )

        prompt = build_retry_prompt(["Fix the assertion."], "reason", story_path)

        self.assertNotIn("does not achieve what this story set out", prompt)


if __name__ == "__main__":
    unittest.main()
