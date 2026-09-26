"""What the Codex roles are given up front, and what it costs.

These are contract tests for the bounded context builders in
core/planning_context.py. The property that matters is not "smaller": it is
that nothing planning has to *decide* with is lost while finished history
becomes a pointer. Each test below therefore pins one thing the planner
would silently start getting wrong if the compaction dropped it --
deduplication, dependency ordering, queue priority, milestone coverage --
rather than pinning a character count.
"""
import tempfile
import unittest
from pathlib import Path

from agent.runtime.core import planning_context as context
from agent.runtime.runners.local_planner_runner import UsageTally


def story(**overrides) -> str:
    fields = {
        "Story ID": "STORY-DOM-001",
        "Title": "Stabilize the crafting fee rule",
        "Status": "DONE",
        "Milestone": "milestone-04",
        "Dependencies": "None.",
    }
    fields.update(overrides)

    return "\n\n".join(
        f"## {heading}\n\n{value}" for heading, value in fields.items()
    )


class StoryFactsTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def write(self, name, **overrides):
        path = self.root / name
        path.write_text(story(**overrides), encoding="utf-8")

        return path

    def test_facts_come_from_the_story_file_not_from_backlog_prose(self):
        path = self.write(
            "STORY-DOM-001-fee.md",
            Status="TODO -- blocked until STORY-DOM-002 reaches DONE",
            Dependencies="STORY-DOM-002 and UD-011 must land first.",
        )

        fact = context.story_facts([path])["STORY-DOM-001-fee.md"]

        self.assertEqual(fact["id"], "STORY-DOM-001")
        self.assertEqual(fact["status"], "BLOCKED")
        self.assertEqual(fact["milestone"], "milestone-04")
        self.assertEqual(fact["prerequisites"], ["STORY-DOM-002", "UD-011"])
        self.assertEqual(fact["title"], "Stabilize the crafting fee rule")

    def test_a_malformed_story_file_is_reported_not_skipped(self):
        path = self.root / "STORY-DOM-009-broken.md"
        path.write_text("# no sections here", encoding="utf-8")

        fact = context.story_facts([path])["STORY-DOM-009-broken.md"]

        self.assertEqual(fact["status"], "UNKNOWN")
        self.assertEqual(fact["id"], "")


NARRATIVE = (
    "DONE. " + "Implemented the whole thing with a long completion "
    "narrative that repeats the acceptance criteria. " * 40
)

BACKLOG = f"""# Story Backlog Index

Index only. No story content is duplicated here.

## Active

- `STORY-API-009-icons.md` — milestone-05: Implement persistent backend icon delivery.

## To Do

- `STORY-DOM-022-fees.md` — milestone-05: Establish a shared fee calculation.
- `STORY-WEB-011-controls.md` — milestone-05: Complete the Profit control grouping.

## Blocked

- `STORY-WEB-010-icons.md` — milestone-05: blocked until API-009 completes.

## Done

- `STORY-WEB-008-columns.md` — milestone-05: {NARRATIVE}
- `STORY-GONE-001-removed.md` — milestone-05: {NARRATIVE}

## Archived

### Milestone 04

- `STORY-API-001-boundary.md` — milestone-04: {NARRATIVE}

## Numbering Convention

`STORY-<AREA>-<NUMBER>-short-name.md`. Numbers are sequential per area.
"""


class BacklogIndexTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

        self.paths = [
            self.write("STORY-API-009-icons.md", **{
                "Story ID": "STORY-API-009", "Status": "UNFINISHED",
                "Milestone": "milestone-05", "Title": "Web item icon metadata",
                "Dependencies": "STORY-API-008, STORY-WEB-008",
            }),
            self.write("STORY-DOM-022-fees.md", **{
                "Story ID": "STORY-DOM-022", "Status": "TODO",
                "Milestone": "milestone-05", "Title": "Sale fee calculation",
                "Dependencies": (
                    "None. UD-011 blocks consumer integration only, and this "
                    "story receives an explicit sale basis instead, so it can "
                    "proceed on its own while that decision is still open, "
                    "which is the whole reason it was split out in the first "
                    "place during the previous planning pass.",
                ),
            }),
            self.write("STORY-WEB-011-controls.md", **{
                "Story ID": "STORY-WEB-011", "Status": "TODO",
                "Milestone": "milestone-05", "Title": "Profit control cleanup",
            }),
            self.write("STORY-WEB-010-icons.md", **{
                "Story ID": "STORY-WEB-010", "Status": "BLOCKED",
                "Milestone": "milestone-05", "Title": "Shared icon presentation",
                "Dependencies": "STORY-API-009",
            }),
            self.write("STORY-WEB-008-columns.md", **{
                "Story ID": "STORY-WEB-008", "Status": "DONE",
                "Milestone": "milestone-05", "Title": "Profit economic columns",
            }),
            self.write("STORY-API-001-boundary.md", **{
                "Story ID": "STORY-API-001", "Status": "DONE",
                "Milestone": "milestone-04", "Title": "Spring Boot HTTP boundary",
            }),
        ]
        self.facts = context.story_facts(self.paths)
        self.index = context.backlog_index(BACKLOG, self.facts)

    def write(self, name, **overrides):
        dependencies = overrides.get("Dependencies")

        if isinstance(dependencies, tuple):
            overrides["Dependencies"] = dependencies[0]

        path = self.root / name
        path.write_text(story(**overrides), encoding="utf-8")

        return path

    def test_queue_sections_keep_their_order_and_their_wording(self):
        order = [
            self.index.index(name) for name in
            ("STORY-DOM-022-fees.md", "STORY-WEB-011-controls.md")
        ]

        self.assertEqual(order, sorted(order))
        self.assertIn("## To Do", self.index)
        self.assertIn(
            "Establish a shared fee calculation", self.index
        )
        self.assertIn("blocked until API-009 completes", self.index)

    def test_every_story_id_and_filename_survives_for_deduplication(self):
        for fact in self.facts.values():
            self.assertIn(fact["id"], self.index)

        for path in self.paths:
            self.assertIn(path.name, self.index)

    def test_completed_narrative_is_replaced_by_one_indexed_line(self):
        self.assertNotIn("repeats the acceptance criteria", self.index)

        self.assertIn(
            "- STORY-API-001 | STORY-API-001-boundary.md | DONE | "
            "milestone-04 | Spring Boot HTTP boundary",
            self.index,
        )
        self.assertIn("### Milestone 04", self.index)
        self.assertLess(len(self.index), len(BACKLOG) / 2)

    def test_dependency_identifiers_are_listed_in_full(self):
        # The prose around them is summarized; the identifiers that decide
        # execution order are not, however long the section is.
        self.assertIn("deps: STORY-API-008, STORY-WEB-008", self.index)
        self.assertIn("deps: UD-011", self.index)
        self.assertIn("deps: None", self.index)

    def test_a_backlog_entry_without_a_story_file_is_reported(self):
        self.assertIn(
            "- STORY-GONE-001 | STORY-GONE-001-removed.md | FILE MISSING",
            self.index,
        )

    def test_milestone_coverage_is_derived_for_the_review(self):
        self.assertIn("- milestone-04: 1 DONE", self.index)
        self.assertIn(
            "- milestone-05: 1 BLOCKED, 1 DONE, 2 TODO, 1 UNFINISHED",
            self.index,
        )

    def test_convention_sections_are_kept_verbatim(self):
        self.assertIn("Numbers are sequential per area", self.index)


class DecisionIndexTest(unittest.TestCase):

    @staticmethod
    def decision(status, question, answer):
        return {
            "file": f"UD-011-{status.lower()}.md",
            "id": "UD-011",
            "status": status,
            "content": (
                f"## Status\n\n{status}\n\n"
                f"## Decision Needed\n\n{question}\n\n"
                f"## Blocks\n\nThe fee grouping work.\n\n"
                f"## Context\n\n" + "Background prose. " * 200 + "\n\n"
                f"## User Decision\n\n{answer}\n"
            ),
        }

    def test_an_open_decision_keeps_its_question_and_what_it_blocks(self):
        question = "Which fee basis applies to a partial sale?"

        rendered = context.decision_index(
            [self.decision("OPEN", question, "TODO")]
        )

        self.assertIn(question, rendered)
        self.assertIn("The fee grouping work.", rendered)
        self.assertIn("Answer: TODO", rendered)

    def test_a_resolved_decision_keeps_question_and_answer_only(self):
        question = "Which fee basis applies to a partial sale?"

        rendered = context.decision_index(
            [self.decision("RESOLVED", question, "Use the explicit basis.")]
        )

        # The question stays: it is what makes a duplicate recognizable.
        self.assertIn(question, rendered)
        self.assertIn("Use the explicit basis.", rendered)
        self.assertNotIn("Background prose.", rendered)
        self.assertNotIn("Blocks:", rendered)

    def test_no_decisions_is_stated_explicitly(self):
        self.assertEqual(context.decision_index([]), "(none)")


class ResolvedRequestIndexTest(unittest.TestCase):

    def test_receipt_is_summarized_with_the_artifacts_it_cites(self):
        rendered = context.resolved_request_index([{
            "file": "2026-09-20-columns.md",
            "content": (
                "## Status\n\nRESOLVED\n\n## Planner Resolution\n\n"
                "Covered by docs/DOMAIN_SPEC.md and "
                "agent/stories/STORY-WEB-008-columns.md. "
                + "Further explanation. " * 100
            ),
        }])

        self.assertIn("2026-09-20-columns.md | RESOLVED |", rendered)
        self.assertIn(
            "covered by: docs/DOMAIN_SPEC.md, "
            "agent/stories/STORY-WEB-008-columns.md",
            rendered,
        )
        self.assertNotIn("Further explanation. Further explanation. "
                         "Further explanation. Further explanation. "
                         "Further explanation. Further explanation. "
                         "Further explanation. Further explanation. "
                         "Further explanation. Further explanation. "
                         "Further explanation. Further explanation. ",
                         rendered)


class ArchitectRequestIndexTest(unittest.TestCase):

    @staticmethod
    def request(status, **overrides):
        content = (
            f"# AR-006 — Frontend boundary\n\n## Status\n\n{status}\n\n"
            "## Architecture Question\n\nWhich boundary owns the icon cache?\n\n"
            "## Architect Decision\n\nThe backend owns it. "
            + "Reasoning prose. " * 100 +
            "\n\n## Resolution\n\nRecorded in docs/TARGET_ARCHITECTURE.md.\n"
        )

        return dict({
            "file": "AR-006-frontend-boundary.md",
            "id": "AR-006",
            "title": "Frontend boundary",
            "status": status,
            "content": content,
        }, **overrides)

    def test_an_unresolved_request_is_supplied_in_full(self):
        rendered = context.architect_request_index([self.request("OPEN")])

        self.assertIn("--- BEGIN AR-006-frontend-boundary.md (OPEN) ---",
                      rendered)
        self.assertIn("Which boundary owns the icon cache?", rendered)

    def test_a_resolved_request_becomes_a_decision_line_and_a_reference(self):
        rendered = context.architect_request_index([self.request("RESOLVED")])

        self.assertIn("AR-006 (RESOLVED): Frontend boundary", rendered)
        self.assertIn("The backend owns it.", rendered)
        self.assertIn("Recorded in: docs/TARGET_ARCHITECTURE.md", rendered)
        self.assertLess(len(rendered), 700)

    def test_an_empty_inbox_is_stated_explicitly(self):
        self.assertIn("no files under agent/architect-requests/",
                      context.architect_request_index([]))


class ContextReportTest(unittest.TestCase):

    def test_report_prints_sizes_and_totals_but_never_contents(self):
        sections = {"backlog index": "x" * 100, "role instructions": "y" * 50}
        prompt = "z" * 200

        report = context.context_report("Planner", sections, prompt)

        self.assertIn("Planner context:", report)
        self.assertIn("  backlog index: 100 chars", report)
        self.assertIn("  role instructions: 50 chars", report)
        self.assertIn("  harness prompt scaffolding: 50 chars", report)
        self.assertIn("  total supplied context: 200 chars", report)
        self.assertNotIn("xxxx", report)


class UsageTallyTest(unittest.TestCase):
    """Every model request resends the prompt, so every one is counted."""

    @staticmethod
    def usage(gross, cached, output):
        return {"input_tokens": gross, "cached_input_tokens": cached,
                "output_tokens": output}

    def test_per_request_updates_are_counted_and_summed(self):
        tally = UsageTally()

        for gross in (65_667, 70_000, 89_045):
            tally.record({
                "type": "token_count",
                "info": {"last_token_usage": self.usage(gross, 60_000, 500)},
            })

        summary = tally.summary("planner")

        self.assertIn("model requests: 3", summary)
        self.assertIn("gross input tokens: 224,712", summary)
        self.assertIn("cached input tokens: 180,000", summary)
        self.assertIn("uncached input tokens: 44,712", summary)
        self.assertIn("output tokens: 1,500", summary)

    def test_turn_totals_are_reported_separately_from_request_sums(self):
        tally = UsageTally()

        tally.record({"type": "token_count",
                      "info": {"last_token_usage": self.usage(10, 4, 1)}})
        tally.record({"type": "turn.completed",
                      "usage": self.usage(100, 40, 10)})

        summary = tally.summary("architect")

        self.assertIn("[architect] turn totals", summary)
        self.assertIn("[architect] summed across requests", summary)
        self.assertIn("gross input tokens: 100", summary)
        self.assertIn("gross input tokens: 10", summary)

    def test_a_run_without_usage_events_says_so_instead_of_inventing_zero(self):
        self.assertIn("no token usage was reported",
                      UsageTally().summary("planner"))


if __name__ == "__main__":
    unittest.main()
