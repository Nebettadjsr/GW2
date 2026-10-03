"""
Tests for the Codex Architect role: the request inbox
(agent/architect-requests/), ARCHITECTURE MODE's deterministic result
validation, the planner-side rules that keep the two roles separate,
and the orchestrator flow that connects them.

No architect, planner or capacity process is ever started here (the
package guard in tests/__init__.py enforces that), and no test touches
the real repository inbox.

Run with: python -m unittest agent.runtime.tests.test_architect_flow -v
(from the repository root).
"""

import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import architect, orchestrator, planning_context
from agent.runtime.human import architect_requests, user_decisions
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.tests.test_orchestration_flow import MainFlowTestCase


REQUEST_TEMPLATE = """# {request_id} — {title}

## Status

{status}

## Architecture Question

{question}

## Context and Constraints

Phase 5 migrates the frontend against the existing HTTP boundary.

## Authoritative References

- `docs/TARGET_ARCHITECTURE.md` §4.1

## Blocked Work

Frontend implementation planning.

## Blocking User Decision

{blocking}

## Architect Decision

{decision}

## Resolution

{resolution}
"""


DECISION_TEMPLATE = """# {decision_id} — {title}

## Status

{status}

## Decision Needed

Pick one.

## Why This Is Needed

Phase 5 needs it.

## Context

Both options are viable.

## Blocks

Frontend planning.

## External Input Possibly Required

Product Owner preference.

## User Decision

{answer}

## Resolution

{resolution}
"""


def request_text(
    request_id="AR-001",
    title="Frontend framework",
    status="OPEN",
    question="Which frontend framework should Phase 5 use?",
    blocking="None.",
    decision="TODO",
    resolution="TODO",
):
    return REQUEST_TEMPLATE.format(
        request_id=request_id,
        title=title,
        status=status,
        question=question,
        blocking=blocking,
        decision=decision,
        resolution=resolution,
    )


def decision_text(
    decision_id="UD-009",
    title="Frontend framework",
    status="OPEN",
    answer="TODO",
    resolution="TODO",
):
    return DECISION_TEMPLATE.format(
        decision_id=decision_id,
        title=title,
        status=status,
        answer=answer,
        resolution=resolution,
    )


class InboxTestCase(unittest.TestCase):
    """A temporary architect-request inbox and user-decision folder."""

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        root = Path(self._tmpdir.name).resolve()

        self.requests_dir = root / "architect-requests"
        self.requests_dir.mkdir()

        self.decisions_dir = root / "user-decisions"
        self.decisions_dir.mkdir()

        self._stack = ExitStack()

        for target, name, value in [
            (architect_requests, "ARCHITECT_REQUESTS_DIR", self.requests_dir),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions_dir),
            (architect, "USER_DECISIONS_DIR", self.decisions_dir),
            # _relative() reports paths against the repository root.
            (architect, "REPO_ROOT", root),
        ]:
            self._stack.enter_context(patch.object(target, name, value))

        self.addCleanup(self._stack.close)
        self.addCleanup(self._tmpdir.cleanup)

    def write_request(self, filename="AR-001-frontend.md", **kwargs) -> Path:
        path = self.requests_dir / filename
        path.write_text(request_text(**kwargs), encoding="utf-8")
        return path

    def write_decision(self, filename="UD-009-frontend.md", **kwargs) -> Path:
        path = self.decisions_dir / filename
        path.write_text(decision_text(**kwargs), encoding="utf-8")
        return path

    def request(self, filename="AR-001-frontend.md") -> dict:
        return next(
            item for item in architect_requests.list_requests()
            if item["file"] == filename
        )


class ActionableRequestTest(InboxTestCase):

    def test_empty_inbox_has_nothing_for_the_architect(self):
        self.assertEqual(architect_requests.get_actionable_requests(), [])

    def test_open_request_is_actionable(self):
        self.write_request()

        actionable = architect_requests.get_actionable_requests()

        self.assertEqual([item["id"] for item in actionable], ["AR-001"])

    def test_resolved_request_is_never_reprocessed(self):
        self.write_request(
            status="RESOLVED",
            decision="React with TypeScript, per UD-009.",
            resolution="docs/TARGET_ARCHITECTURE.md §4.1 updated.",
        )

        self.assertEqual(architect_requests.get_actionable_requests(), [])
        self.assertEqual(architect_requests.get_unresolved_requests(), [])

    def test_needs_user_waits_for_its_decision_and_resumes_when_resolved(self):
        self.write_request(status="NEEDS_USER", blocking="UD-009")
        self.write_decision(status="OPEN")

        self.assertEqual(architect_requests.get_actionable_requests(), [])
        self.assertEqual(
            architect_requests.get_blocking_decision_ids(), ["UD-009"]
        )

        # The human answers; nothing else about the request changes.
        self.write_decision(
            status="RESOLVED", answer="React with TypeScript.",
            resolution="Recorded.",
        )

        self.assertEqual(
            [item["id"] for item in architect_requests.get_actionable_requests()],
            ["AR-001"],
        )

    def test_needs_user_with_an_unusable_decision_reference_stays_blocked(self):
        # A missing decision file must never be assumed answered.
        self.write_request(status="NEEDS_USER", blocking="UD-404")

        self.assertEqual(architect_requests.get_actionable_requests(), [])

    def test_a_request_requeued_by_hand_as_todo_is_dispatched(self):
        # Observed: a human re-queued AR-001 for the architect by
        # writing TODO in its Status (the convention every story file
        # uses). The architect was then never invoked, while the planner
        # kept reporting itself blocked by that same question.
        for requeued in ("TODO", "To Do", "todo.", "  NEW  ", "PENDING"):
            with self.subTest(status=requeued):
                self.write_request(status=requeued)

                self.assertEqual(
                    [item["id"]
                     for item in architect_requests.get_actionable_requests()],
                    ["AR-001"],
                )
                self.assertEqual(
                    architect_requests.undispatchable_requests(), []
                )

    def test_unreadable_status_is_reported_rather_than_silently_skipped(self):
        self.write_request(status="PONDERING")

        self.assertEqual(architect_requests.get_actionable_requests(), [])

        stranded = architect_requests.undispatchable_requests()

        self.assertEqual([item["file"] for item in stranded],
                         ["AR-001-frontend.md"])
        self.assertIn("unreadable Status 'PONDERING'", stranded[0]["reason"])

    def test_needs_user_with_no_named_decision_is_reported(self):
        self.write_request(status="NEEDS_USER", blocking="None.")

        stranded = architect_requests.undispatchable_requests()

        self.assertEqual(len(stranded), 1)
        self.assertIn("no blocking UD-* is named", stranded[0]["reason"])

    def test_needs_user_with_a_missing_decision_file_is_reported(self):
        self.write_request(status="NEEDS_USER", blocking="UD-404")

        stranded = architect_requests.undispatchable_requests()

        self.assertEqual(len(stranded), 1)
        self.assertIn("UD-404 (no readable user-decision file)",
                      stranded[0]["reason"])

    def test_a_request_waiting_for_the_product_owner_is_not_reported(self):
        # A real human gate is not a defect and must not be reported as
        # one.
        self.write_request(status="NEEDS_USER", blocking="UD-009")
        self.write_decision(status="OPEN")

        self.assertEqual(architect_requests.undispatchable_requests(), [])


class PlannerInboxRulesTest(InboxTestCase):
    """
    The planner may only ever ADD a question (AGENTS.md's "No Implicit
    Role Switching"). Everything else about the inbox belongs to
    ARCHITECTURE MODE.
    """

    def before(self) -> dict:
        return {
            item["file"]: item["content"]
            for item in architect_requests.list_requests()
        }

    def test_creating_a_well_formed_request_is_accepted(self):
        before = self.before()
        self.write_request()

        self.assertEqual(
            architect_requests.validate_planner_updates(
                before, ["AR-001-frontend.md"], "COMPLETE"
            ),
            [],
        )

    def test_planner_answering_its_own_request_is_rejected(self):
        before = self.before()
        self.write_request(decision="Use React, obviously.")

        problems = architect_requests.validate_planner_updates(
            before, ["AR-001-frontend.md"], "COMPLETE"
        )

        self.assertTrue(
            any("must not answer its own architect request" in problem
                for problem in problems),
            problems,
        )

    def test_editing_an_existing_request_is_rejected(self):
        path = self.write_request()
        before = self.before()
        path.write_text(
            request_text(status="RESOLVED", decision="Decided by the planner."),
            encoding="utf-8",
        )

        problems = architect_requests.validate_planner_updates(
            before, [], "COMPLETE"
        )

        self.assertTrue(
            any("modified an existing architect request" in problem
                for problem in problems),
            problems,
        )

    def test_duplicate_question_for_an_unresolved_request_is_rejected(self):
        self.write_request()
        before = self.before()
        self.write_request(
            filename="AR-002-frontend-again.md",
            request_id="AR-002",
            # Same question, different wording of the file around it.
            question="  which FRONTEND framework should phase 5 use?  ",
        )

        problems = architect_requests.validate_planner_updates(
            before, ["AR-002-frontend-again.md"], "COMPLETE"
        )

        self.assertTrue(
            any("asks the same question as the existing unresolved request"
                in problem for problem in problems),
            problems,
        )

    def test_a_new_question_alongside_an_unresolved_one_is_allowed(self):
        self.write_request()
        before = self.before()
        self.write_request(
            filename="AR-002-task-status.md",
            request_id="AR-002",
            question="Where should long-running task status be stored?",
        )

        self.assertEqual(
            architect_requests.validate_planner_updates(
                before, ["AR-002-task-status.md"], "COMPLETE"
            ),
            [],
        )

    def test_silently_created_request_is_rejected(self):
        before = self.before()
        self.write_request()

        problems = architect_requests.validate_planner_updates(
            before, [], "COMPLETE"
        )

        self.assertTrue(
            any("not reported in architect_requests_created" in problem
                for problem in problems),
            problems,
        )

    def test_reused_id_is_rejected(self):
        self.write_request()
        before = self.before()
        self.write_request(filename="AR-001-other.md", request_id="AR-001",
                           question="Something else entirely?")

        problems = architect_requests.validate_planner_updates(
            before, ["AR-001-other.md"], "COMPLETE"
        )

        self.assertTrue(
            any("duplicates an existing architect request ID" in problem
                for problem in problems),
            problems,
        )


class ArchitectResultValidationTest(InboxTestCase):

    def setUp(self):
        super().setUp()
        self._stack.enter_context(
            patch.object(architect, "_git_dirty_src_lines", return_value=set())
        )
        self.path = self.write_request()
        self.dispatched = self.request()
        self.before = {self.path: self.path.read_bytes()}

    def validate(self, raw, before=None, after=None, pre_decisions=None):
        return architect.validate_architect_result(
            raw,
            self.dispatched,
            before if before is not None else self.before,
            after if after is not None else {self.path: self.path.read_bytes()},
            pre_decisions if pre_decisions is not None else [],
            set(),
        )

    def resolve_request(self):
        self.path.write_text(
            request_text(
                status="RESOLVED",
                decision="Answered from docs/TARGET_ARCHITECTURE.md §4.1.",
                resolution="No document change was needed.",
            ),
            encoding="utf-8",
        )

    def test_complete_with_a_resolved_request_is_accepted(self):
        self.resolve_request()

        result = self.validate({
            "status": "COMPLETE",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": [],
            "architecture_artifacts_changed": [],
            "reason": "Existing rule already answers it.",
        })

        self.assertEqual(result["status"], "COMPLETE")
        self.assertEqual(result["request_status"], "RESOLVED")
        self.assertNotIn("validation_problems", result)

    def test_complete_without_resolving_the_request_is_rejected(self):
        result = self.validate({
            "status": "COMPLETE",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": [],
            "architecture_artifacts_changed": [],
            "reason": "Says it is done.",
        })

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("not RESOLVED" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_resolved_request_with_a_placeholder_decision_is_rejected(self):
        self.path.write_text(request_text(status="RESOLVED"), encoding="utf-8")

        result = self.validate({
            "status": "COMPLETE",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": [],
            "architecture_artifacts_changed": [],
            "reason": "Empty answer.",
        })

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("still a placeholder" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_needs_user_escalates_through_the_existing_decision_folder(self):
        self.path.write_text(
            request_text(
                status="NEEDS_USER", blocking="UD-009",
                decision="Two viable options remain; see UD-009.",
            ),
            encoding="utf-8",
        )
        self.write_decision(status="OPEN")

        result = self.validate({
            "status": "NEEDS_USER",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": ["UD-009"],
            "architecture_artifacts_changed": [],
            "reason": "Genuine Product Owner choice.",
        })

        self.assertEqual(result["status"], "NEEDS_USER")
        self.assertEqual(result["user_decision_ids"], ["UD-009"])
        self.assertNotIn("validation_problems", result)

    def test_needs_user_without_a_recorded_blocker_is_rejected(self):
        # Without the ID in the request, the architect would never be
        # resumed once the human answers.
        self.path.write_text(
            request_text(status="NEEDS_USER", decision="See the decision."),
            encoding="utf-8",
        )
        self.write_decision(status="OPEN")

        result = self.validate({
            "status": "NEEDS_USER",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": ["UD-009"],
            "architecture_artifacts_changed": [],
            "reason": "Escalated.",
        })

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("would never be resumed" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_architect_answering_the_user_decision_itself_is_rejected(self):
        self.path.write_text(
            request_text(status="NEEDS_USER", blocking="UD-009",
                         decision="Analysis."),
            encoding="utf-8",
        )
        self.write_decision(status="OPEN", answer="React, decided by me.")

        result = self.validate({
            "status": "NEEDS_USER",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": ["UD-009"],
            "architecture_artifacts_changed": [],
            "reason": "Escalated.",
        })

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("must not invent the Product Owner's answer" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_resolving_an_existing_open_decision_is_rejected(self):
        self.write_decision(status="OPEN")
        pre_decisions = user_decisions.list_decisions()
        self.write_decision(status="RESOLVED", answer="React.",
                            resolution="Done.")
        self.resolve_request()

        result = self.validate(
            {
                "status": "COMPLETE",
                "architect_request": "AR-001-frontend.md",
                "user_decision_ids": [],
                "architecture_artifacts_changed": [],
                "reason": "Answered it myself.",
            },
            pre_decisions=pre_decisions,
        )

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("only the human Product Owner resolves" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_unreported_document_change_is_rejected(self):
        self.resolve_request()
        doc = self.requests_dir.parent / "TARGET_ARCHITECTURE.md"
        doc.write_text("after", encoding="utf-8")

        result = self.validate(
            {
                "status": "COMPLETE",
                "architect_request": "AR-001-frontend.md",
                "user_decision_ids": [],
                "architecture_artifacts_changed": [],
                "reason": "Quietly edited a document.",
            },
            before={**self.before, doc: b"before"},
            after={self.path: self.path.read_bytes(), doc: doc.read_bytes()},
        )

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("was changed but not reported" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_reporting_a_file_that_did_not_change_is_rejected(self):
        self.resolve_request()

        result = self.validate({
            "status": "COMPLETE",
            "architect_request": "AR-001-frontend.md",
            "user_decision_ids": [],
            "architecture_artifacts_changed": ["docs/TARGET_ARCHITECTURE.md"],
            "reason": "Claimed a change that never happened.",
        })

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("did not change during this run" in problem
                for problem in result["validation_problems"]),
            result,
        )

    def test_answering_a_different_request_is_rejected(self):
        self.resolve_request()

        result = self.validate({
            "status": "COMPLETE",
            "architect_request": "AR-042-something-else.md",
            "user_decision_ids": [],
            "architecture_artifacts_changed": [],
            "reason": "Wrong question.",
        })

        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(
            any("dispatched request was" in problem
                for problem in result["validation_problems"]),
            result,
        )


class ArchitectSchedulingTest(MainFlowTestCase):
    """The orchestrator side: when the architect runs, and what follows."""

    def open_request(self, filename="AR-001-frontend.md"):
        return {
            "file": filename,
            "path": Path(filename),
            "id": "AR-001",
            "title": "Frontend framework",
            "status": "OPEN",
            "blocking_decision_ids": [],
            "content": request_text(),
        }

    def test_empty_inbox_never_invokes_the_architect(self):
        self.state["candidates"] = ["STORY-A.md"]

        self.run_main(expect_stop=True)

        self.assertFalse(
            any(event.startswith("architect") for event in self.events),
            self.events,
        )

    def test_open_request_runs_the_architect_before_planning(self):
        self.architect_requests = [self.open_request()]

        self.run_main(expect_stop=True)

        self.assertEqual(
            self.events[0], "architect:AR-001-frontend.md", self.events
        )
        # Architecture first, then planning resumes by itself.
        self.assertEqual(self.events[1], "plan", self.events)

    def test_resolved_architecture_lets_planning_create_and_run_work(self):
        self.architect_requests = [self.open_request()]
        created = ["STORY-NEW.md"]

        def plan():
            self.events.append("plan")

            if created:
                self.state["fingerprint"] += "+"
                self.state["candidates"] = [created.pop()]
                return {"status": "COMPLETE",
                        "story_files_created": ["STORY-NEW.md"]}

            return {"status": "COMPLETE", "story_files_created": []}

        with patch.object(orchestrator, "run_planning_pass", side_effect=plan):
            self.run_main(expect_stop=True)

        self.assertEqual(
            self.events,
            ["architect:AR-001-frontend.md", "plan", "select", "claude",
             "select"],
        )

    def test_architect_needs_user_waits_for_the_existing_decision_flow(self):
        self.architect_requests = [self.open_request()]
        self.architect_result = {
            "status": "NEEDS_USER",
            "request_status": "NEEDS_USER",
            "user_decision_ids": ["UD-009"],
        }
        self.decisions = [
            {"id": "UD-009", "status": "OPEN", "file": "UD-009-frontend.md"}
        ]

        self.run_main(expect_stop=True)

        # One architect pass, then the ordinary user-decision wait --
        # no second escalation mechanism.
        self.assertEqual(
            self.events,
            ["architect:AR-001-frontend.md", "plan",
             "decision-wait:['UD-009']", "plan", "select"],
        )

    def test_resolved_decision_makes_the_architect_resume_the_same_request(self):
        # The human answers; get_actionable_requests() hands the same
        # request back, and the orchestrator dispatches it again with no
        # extra state of its own.
        blocked = self.open_request()
        blocked["status"] = "NEEDS_USER"
        blocked["blocking_decision_ids"] = ["UD-009"]
        self.decisions = [
            {"id": "UD-009", "status": "OPEN", "file": "UD-009-frontend.md"}
        ]

        # Actionable only once the decision is RESOLVED, exactly as the
        # real inbox computes it.
        answered = []

        def actionable():
            resolved = {
                decision["id"] for decision in self.decisions
                if decision["status"] == "RESOLVED"
            }
            if "UD-009" not in resolved or answered:
                return []
            return [blocked]

        def architect_pass(request):
            answered.append(request["file"])
            return self.architect(request)

        with patch.object(orchestrator, "get_actionable_architect_requests",
                          side_effect=actionable),              patch.object(orchestrator, "run_architect_pass",
                          side_effect=architect_pass):
            self.run_main(expect_stop=True)

        self.assertEqual(
            self.events,
            ["plan", "decision-wait:['UD-009']",
             "architect:AR-001-frontend.md", "plan", "select"],
        )

    def test_codex_exhaustion_is_not_an_architecture_failure(self):
        self.architect_requests = [self.open_request()]
        self.state["candidates"] = ["STORY-A.md"]

        with patch.object(orchestrator, "run_architect_pass",
                          side_effect=ModelCapacityUnavailable("Codex")):
            self.run_main()

        # The request is untouched, Codex is deferred rather than
        # blamed, and Claude still drains the queue it already has.
        self.assertEqual(self.architect_requests[0]["status"], "OPEN")
        self.codex.defer.assert_called_once()
        self.assertIn("select", self.events)
        self.assertIn("claude", self.events)
        self.assertTrue(
            any("capacity exhausted during architecture work" in line
                for line in self.logged),
            self.logged,
        )
        self.assertFalse(
            any("failed" in line.lower() for line in self.logged), self.logged
        )

    def test_failed_architect_pass_does_not_stop_execution(self):
        self.architect_requests = [self.open_request()]
        self.state["candidates"] = ["STORY-A.md"]
        self.architect_result = {"status": "FAILED", "reason": "rejected"}

        self.run_main()

        self.codex.defer.assert_called_once()
        self.assertIn("claude", self.events)
        self.assertTrue(
            any("Architect pass for AR-001-frontend.md failed" in line
                for line in self.logged),
            self.logged,
        )


class UndispatchableReportingTest(MainFlowTestCase):

    def test_an_undispatchable_request_is_logged_once_and_named_on_stop(self):
        orchestrator._reported_inbox_problems.clear()
        self.addCleanup(orchestrator._reported_inbox_problems.clear)

        self.undispatchable_requests = [
            {
                "file": "AR-001-frontend.md",
                "reason": "unreadable Status 'TODO'; expected one of "
                          "OPEN, NEEDS_USER, RESOLVED",
            }
        ]

        self.run_main(expect_stop=True)

        reported = [line for line in self.logged
                    if line.startswith("Architect request cannot be dispatched")]

        # Logged once with the concrete reason, not once per cycle.
        self.assertEqual(len(reported), 1, self.logged)
        self.assertIn("unreadable Status 'TODO'", reported[0])
        self.assertTrue(
            any("unadvanceable architect request(s) need a human: "
                "AR-001-frontend.md" in line
                for line in self.decisions_logged()),
            self.decisions_logged(),
        )


class ArchitectPromptTest(InboxTestCase):
    """
    The prompt's outcome rules are what the model actually acts on. A
    real run escalated a frontend-framework choice and said so in its
    own words -- "this invocation's outcome rules require escalation" --
    because those rules told it to escalate a TBD technology with viable
    alternatives. They must keep telling it the opposite.
    """

    def setUp(self):
        super().setUp()
        self.write_request()
        self.prompt = architect.build_architect_prompt(self.request())

    def test_prompt_tells_the_architect_to_decide_technology_choices(self):
        for expected in [
            "Decide the question yourself whenever it is a technical one",
            "recorded, reasoned decision",
            '"Several options are viable" is not grounds for',
            "HOW TO DECIDE A TECHNOLOGY CHOICE",
        ]:
            self.assertIn(expected, self.prompt)

    def test_prompt_lists_the_trade_off_dimensions_to_weigh(self):
        for dimension in [
            "what specific problem each option solves",
            "benefits and downsides",
            "what size and shape of project",
            "ecosystem and maintenance",
            "reversibility",
        ]:
            self.assertIn(dimension, self.prompt.lower())

    def test_prompt_restricts_escalation_to_non_technical_choices(self):
        self.assertIn("the narrow exception", self.prompt)
        self.assertIn(
            "Escalate only when the remaining choice does not turn on technical",
            self.prompt,
        )
        self.assertIn("If you can settle it on technical grounds, you must",
                      self.prompt)
        self.assertIn("because a document says TBD", self.prompt)

    def test_prompt_still_carries_the_role_contract_and_result_rules(self):
        self.assertIn("AUTHORITATIVE ARCHITECT INSTRUCTIONS", self.prompt)
        self.assertIn("ARCHITECT_RESULT.json", self.prompt)
        self.assertIn("Never invent the Product Owner's answer", self.prompt)

    def test_prompt_forbids_rereading_the_contract_it_already_supplies(self):
        # A recorded run reread ARCHITECT_INSTRUCTIONS.md although the
        # prompt already contained it, following AGENTS.md's routing step
        # (agent/runtime/reports/2026-09-26-planner-usage.md). The prompt
        # outranks that file, so it has to say so.
        self.assertIn(
            "Do not read or\nreread agent/ARCHITECT_INSTRUCTIONS.md", self.prompt
        )
        self.assertIn("do not read AGENTS.md", self.prompt)

    def test_prompt_reports_its_own_supplied_section_sizes(self):
        prompt, sections = architect.build_architect_context(self.request())

        self.assertEqual(set(sections), {
            "dispatched request", "user decision index", "document ownership",
            "role instructions",
        })
        self.assertIn("role instructions:", planning_context.context_report(
            "Architect", sections, prompt))


class ProcessGuardTest(unittest.TestCase):

    def test_tests_cannot_start_a_real_model_process(self):
        from agent.runtime.runners import local_planner_runner

        with self.assertRaises(AssertionError):
            local_planner_runner.run_codex("plan")


if __name__ == "__main__":
    unittest.main()
