"""Offline contract/flow tests for reviewing past local milestone blockers.

Scripted planner outputs test the harness contract, not live model reasoning.
"""
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

from agent.runtime.core import project_planner as planner, orchestrator as loop, story_state, story_archive
from agent.runtime.human import user_decisions as uds, architect_requests as ars


def review(area, outcome, *refs):
    return {"area": area, "outcome": outcome, "references": list(refs)}


class MilestonePlanningTest(unittest.TestCase):
    def setUp(self):
        instructions = (planner.REPO_ROOT / "agent/PLANNER_INSTRUCTIONS.md").read_text(encoding="utf-8")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        paths = {
            "REPO_ROOT": self.root,
            "STORIES_DIR": self.root / "agent/stories",
            "USER_DECISIONS_DIR": self.root / "agent/user-decisions",
            "ARCHITECT_REQUESTS_DIR": self.root / "agent/architect-requests",
            "BACKLOG_FILE": self.root / "agent/stories/BACKLOG.md",
            "CURRENT_STORY_FILE": self.root / "agent/CURRENT_STORY.md",
            "PROJECT_STATE_FILE": self.root / "agent/PROJECT_STATE.md",
            "PLANNER_INSTRUCTIONS_FILE": self.root / "agent/PLANNER_INSTRUCTIONS.md",
            "ROADMAP_FILE": self.root / "docs/ROADMAP.md",
            "TARGET_ARCHITECTURE_FILE": self.root / "docs/TARGET_ARCHITECTURE.md",
            "PRODUCT_OWNER_REQUESTS_DIR": self.root / "agent/product-owner-requests",
            "ADR_DIR": self.root / "docs/architecture/decisions",
            "ARCHIVE_DIR": self.root / "agent/stories/archive",
        }
        for name, value in paths.items():
            if value.suffix:
                value.parent.mkdir(parents=True, exist_ok=True)
            else:
                value.mkdir(parents=True, exist_ok=True)
            for module in (planner, loop, uds, ars, story_state, story_archive):
                if hasattr(module, name):
                    self.stack.enter_context(patch.object(module, name, value))
        (self.root / "agent/PLANNER_INSTRUCTIONS.md").write_text(instructions, encoding="utf-8")
        planner.PROJECT_STATE_FILE.write_text("Current phase: Phase 0")
        planner.ROADMAP_FILE.write_text("## Phase 0\n\nA, B, C, D, E remain.\n\n## Phase 1\n\nLater.")
        self.stories = []
        self.backlog()
        self.stack.enter_context(patch.object(planner, "_git_dirty_src_lines", return_value=set()))
        self.stack.enter_context(patch.object(planner, "read_requests", return_value=[]))
        self.stack.enter_context(patch.object(planner, "validate_request_updates", return_value=[]))
        self.stack.enter_context(patch.object(loop, "log_line"))
        self.claude, self.codex = Mock(), Mock()
        self.claude.available.return_value = False
        self.codex.available.return_value = True
        self.scheduler = loop.CapacityScheduler(self.claude, self.codex, cache_file=self.root / "cache.json")

    def backlog(self):
        planner.BACKLOG_FILE.write_text("## Active\n\n## To Do\n\n" + "\n".join(
            f"- `{name}`" for name in self.stories) + "\n\n## Blocked\n\n## Done\n\n## Archived\n")

    def decision(self, number, question=None):
        fields = {heading: "Context for this area." for heading in uds.REQUIRED_DECISION_HEADINGS}
        fields.update({"Status": "OPEN", "Decision Needed": question or f"Choose behavior for area {number}.",
                       "User Decision": "TODO", "Resolution": "TODO"})
        path = planner.USER_DECISIONS_DIR / f"UD-{number:03d}-choice.md"
        path.write_text("\n\n".join(f"## {key}\n\n{value}" for key, value in fields.items()))
        return path

    def request(self, number):
        fields = {"Status": "OPEN", "Architecture Question": f"Which boundary for area {number}?",
                  "Context and Constraints": "Current phase.", "Authoritative References": "docs/TARGET_ARCHITECTURE.md",
                  "Blocked Work": f"Area {number}", "Blocking User Decision": "None.",
                  "Architect Decision": "TODO", "Resolution": "TODO"}
        name = f"AR-{number:03d}-boundary.md"
        (planner.ARCHITECT_REQUESTS_DIR / name).write_text(f"# AR-{number:03d}\n\n" + "\n\n".join(
            f"## {key}\n\n{value}" for key, value in fields.items()))
        return name

    def story(self, number, dependencies="None.", status="TODO"):
        name = f"STORY-TEST-{number:03d}-area.md"
        fields = {heading: "None." for heading in planner.REQUIRED_STORY_HEADINGS}
        fields.update({"Story ID": f"STORY-TEST-{number:03d}", "Milestone": "milestone-00",
                       "Status": status, "Dependencies": dependencies})
        (planner.STORIES_DIR / name).write_text("\n\n".join(f"## {key}\n\n{value}" for key, value in fields.items()))
        if name not in self.stories:
            self.stories.append(name)
        self.backlog()
        return name

    def before(self):
        return (planner._existing_story_ids(), {path.name for path in planner._existing_story_files()},
                uds.list_decisions(), {item["file"]: item["content"] for item in ars.list_requests()})

    def validate(self, before, **overrides):
        result = {"status": "COMPLETE", "story_files_created": [], "user_decision_ids": [],
                  "architect_requests_created": [], "independent_work_remaining": False,
                  "phase_review": [review("Covered", "COVERED", "docs/ROADMAP.md")]}
        result.update(overrides)
        ids, names, decisions, requests = before
        return planner.validate_planning_result(result, None, set(), ids, names, decisions, {}, requests, {})

    def test_one_ud_and_independent_ready_work_is_complete(self):
        before = self.before()
        self.decision(10)
        story = self.story(1)
        result = self.validate(before, story_files_created=[story], user_decision_ids=["UD-010"],
            independent_work_remaining=True, phase_review=[review("A", "USER_DECISION", "UD-010"),
                review("D", "PLANNED", story), review("E", "READY", "docs/ROADMAP.md")])
        self.assertEqual(result["status"], "COMPLETE", result)

    def test_multiple_uds_and_ars_and_executable_stories_in_one_pass(self):
        before = self.before()
        self.decision(10)
        self.decision(11)
        requests = [self.request(6), self.request(7)]
        stories = [self.story(1), self.story(2)]
        result = self.validate(before, story_files_created=stories, user_decision_ids=["UD-010", "UD-011"],
            architect_requests_created=requests, phase_review=[review("A", "USER_DECISION", "UD-010"),
                review("B", "USER_DECISION", "UD-011"), review("C", "ARCHITECT_REQUEST", "AR-006", "AR-007"),
                review("D/E", "PLANNED", *stories)])
        self.assertEqual(result["status"], "COMPLETE", result)
        self.assertEqual(len(story_state.get_selectable_story_candidates()), 2)

    def test_idle_batches_discover_both_uds_before_any_answer_then_wait_locally(self):
        calls = []
        def plan():
            before = self.before()
            calls.append(len(calls) + 1)
            if len(calls) == 1:
                self.decision(10)
                self.decision(11)
                story = self.story(1)
                return self.validate(before, story_files_created=[story], user_decision_ids=["UD-010", "UD-011"],
                    independent_work_remaining=True, phase_review=[review("A", "USER_DECISION", "UD-010"),
                        review("B", "USER_DECISION", "UD-011"), review("D", "PLANNED", story),
                        review("E", "READY", "docs/ROADMAP.md")])
            story = self.story(len(calls))
            ids = [item["id"] for item in uds.list_decisions() if item["status"] == "OPEN"]
            return self.validate(before, status="NEEDS_USER", story_files_created=[story], user_decision_ids=ids,
                phase_review=[review("Blocked areas", "USER_DECISION", *ids), review("Independent area", "PLANNED", story)])
        with patch.object(loop, "run_planning_pass", side_effect=plan) as model:
            self.assertTrue(self.scheduler.plan_if_useful(idle=True))
            self.assertEqual({item["id"] for item in uds.list_decisions()}, {"UD-010", "UD-011"})
            self.assertTrue(self.scheduler.plan_if_useful(idle=True))
            for _ in range(3):
                self.assertFalse(self.scheduler.plan_if_useful(idle=True))
            self.assertEqual(model.call_count, 2)
            second = (planner.USER_DECISIONS_DIR / "UD-011-choice.md").read_bytes()
            first = planner.USER_DECISIONS_DIR / "UD-010-choice.md"
            first.write_text(first.read_text().replace("OPEN", "RESOLVED").replace("TODO", "Human answer"))
            self.assertTrue(self.scheduler.plan_if_useful(idle=True))
            self.assertFalse(self.scheduler.plan_if_useful(idle=True))
            self.assertEqual(model.call_count, 3)
            self.assertEqual(len(uds.list_decisions()), 2)
            self.assertEqual((planner.USER_DECISIONS_DIR / "UD-011-choice.md").read_bytes(), second)

    def test_ready_work_cannot_claim_needs_user_or_request_prose_only_retries(self):
        self.decision(10)
        before = self.before()
        args = dict(user_decision_ids=["UD-010"], independent_work_remaining=True,
                    phase_review=[review("A", "USER_DECISION", "UD-010"), review("D", "READY", "docs/ROADMAP.md")])
        for status in ("NEEDS_USER", "COMPLETE"):
            result = self.validate(before, status=status, **args)
            self.assertEqual(result["status"], "FAILED", result)
        result = self.validate(before, status="NEEDS_USER", user_decision_ids=["UD-010"],
                               phase_review=[review("A", "USER_DECISION", "UD-010")])
        self.assertEqual(result["status"], "NEEDS_USER", result)

    def test_repeated_review_reuses_questions_and_rejects_duplicates(self):
        self.decision(10)
        request = self.request(6)
        before = self.before()
        args = dict(user_decision_ids=["UD-010"], phase_review=[review("A", "USER_DECISION", "UD-010"),
                                                             review("C", "ARCHITECT_REQUEST", "AR-006")])
        self.assertEqual(self.validate(before, **args)["status"], "COMPLETE")
        result = self.validate(before, architect_requests_created=[request], **args)
        self.assertEqual(result["status"], "FAILED")
        self.decision(12, "Choose behavior for area 10.")
        result = self.validate(before, **args)
        self.assertIn("Duplicate user-decision question", str(result["validation_problems"]))
        self.assertEqual(len(ars.list_requests()), 1)

    def test_mixed_story_ud_ar_dependencies_stay_local_and_all_must_resolve(self):
        self.decision(10)
        request = self.request(6)
        self.story(1, status="DONE")
        dependent = self.story(2, dependencies="STORY-TEST-001, UD-010, AR-006")
        independent = self.story(3)
        content = (planner.STORIES_DIR / dependent).read_text()
        self.assertEqual(len(story_state.get_unsatisfied_dependencies(content)), 2)
        self.assertEqual(len(story_state.get_selectable_story_candidates()), 1)
        first = planner.USER_DECISIONS_DIR / "UD-010-choice.md"
        first.write_text(first.read_text().replace("OPEN", "RESOLVED"))
        self.assertEqual(len(story_state.get_unsatisfied_dependencies(content)), 1)
        architecture = planner.ARCHITECT_REQUESTS_DIR / request
        architecture.write_text(architecture.read_text().replace("OPEN", "RESOLVED"))
        self.assertEqual(story_state.get_unsatisfied_dependencies(content), [])
        self.assertEqual(len(story_state.get_selectable_story_candidates()), 2)
        self.story(1, status="UNFINISHED")
        self.assertEqual(len(story_state.get_unsatisfied_dependencies(content)), 1)

    def test_new_story_cannot_be_reported_executable_with_open_ud_dependency(self):
        self.decision(10)
        before = self.before()
        name = self.story(1, dependencies="UD-010")
        result = self.validate(before, story_files_created=[name], user_decision_ids=["UD-010"],
                               phase_review=[review("A", "USER_DECISION", "UD-010")])
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("unresolved prerequisites", str(result["validation_problems"]))

    def test_prompt_indexes_completed_backlog_history_instead_of_quoting_it(self):
        # A fresh Codex session resends the whole prompt with every model
        # request, so completion narrative is paid for many times over.
        # What planning needs from a finished story is its ID, filename,
        # status and milestone -- the narrative stays in its own file.
        name = self.story(1, status="DONE")
        narrative = "Completion narrative that must not be resent. " * 40
        planner.BACKLOG_FILE.write_text(
            "## Active\n\n## To Do\n\n## Blocked\n\n"
            f"## Done\n\n- `{name}` - milestone-00: DONE. {narrative}\n\n"
            "## Archived\n",
            encoding="utf-8",
        )
        prompt = planner.build_planning_prompt()
        self.assertNotIn(narrative, prompt)
        self.assertIn(f"- STORY-TEST-001 | {name} | DONE | milestone-00", prompt)
        self.assertIn("BACKLOG INDEX", prompt)
        self.assertIn("Milestone Coverage", prompt)

    def test_prompt_forbids_rereading_what_it_already_supplies(self):
        prompt = planner.build_planning_prompt()
        self.assertIn("Do not read, reread or print any", prompt)
        self.assertIn("AGENTS.md", prompt)
        self.assertIn("agent/stories/BACKLOG.md, when a completed entry's", prompt)

    def test_unresolved_completed_story_findings_are_supplied_once_dispositioned(self):
        finding_story = self.story(50, status="DONE")
        path = planner.STORIES_DIR / finding_story
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\n## Follow-up Findings\n\nF001: The cache is not shared across replicas.\n"
            + "\n## Result\n\nImplementation complete.\n",
            encoding="utf-8",
        )

        prompt, sections = planner.build_planning_context()
        self.assertIn("[F001]: The cache is not shared across replicas.", prompt)
        self.assertIn(finding_story, sections["unresolved Claude follow-up findings"])

        path.write_text(
            path.read_text(encoding="utf-8")
            + "\n## Follow-up Findings Disposition\n\n"
            + "- F001: DEFERRED — docs/KNOWN_PROBLEMS.md; revisit in a later milestone.\n",
            encoding="utf-8",
        )
        prompt, sections = planner.build_planning_context()
        self.assertNotIn("[F001]: The cache is not shared across replicas.", prompt)
        self.assertEqual(
            sections["unresolved Claude follow-up findings"],
            "(no unresolved Claude implementation follow-up findings)",
        )

    def test_unfinished_story_findings_are_not_supplied(self):
        name = self.story(51, status="TODO")
        path = planner.STORIES_DIR / name
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\n## Follow-up Findings\n\nF001: Unverified implementation observation.\n",
            encoding="utf-8",
        )
        prompt = planner.build_planning_prompt()
        self.assertNotIn("Unverified implementation observation", prompt)

    def test_prompt_reports_its_own_supplied_section_sizes(self):
        prompt, sections = planner.build_planning_context()
        report = planner.planning_context.context_report("Planner", sections, prompt)
        self.assertIn("backlog index:", report)
        self.assertIn("role instructions:", report)
        self.assertIn(f"total supplied context: {len(prompt):,} chars", report)

    def test_prompt_requires_broad_discovery_and_includes_existing_answers(self):
        self.decision(10)
        prompt = planner.build_planning_prompt()
        self.assertIn("Discover all currently identifiable independent UDs and ARs in the same pass", prompt)
        self.assertIn("Choose behavior for area 10.", prompt)
        self.assertIn("phase_review", prompt)
        self.assertIn("independent_work_remaining", prompt)
        self.assertNotIn("appropriate OPEN user-decision file and return NEEDS_USER", prompt)
        self.assertNotIn("## Phase 1\n\nLater.", prompt)
