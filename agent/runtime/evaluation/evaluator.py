import json
import re

from agent.runtime.support.config import EVALUATOR_RESULT_FILE, REPO_ROOT
from agent.runtime.support.files import write_json
from agent.runtime.runners.local_planner_runner import run_evaluator
from agent.runtime.core.story_state import classify_story_status, extract_status_section
from agent.runtime.support.daily_log import log_line


# ============================================================
# Who evaluates, and why it changed
#
# This was a local Hermes (hermes3:8b through Ollama) judging the story
# text against Claude's own written report. Two things were wrong with
# that. The model was too weak for the judgment -- it produced empty
# RETRYs that normalize_evaluation() had to rescue deterministically --
# and, more fundamentally, its only evidence was the implementer's
# account of its own work. It could confirm that a report claimed every
# criterion was met; it could not see whether the repository agreed.
#
# Codex evaluates instead, in its own read-only sandbox, with the working
# tree in front of it. That makes the interesting question answerable:
# not only "is every listed criterion ticked" but "does the change that
# was actually made accomplish what this story set out to accomplish".
# A change can satisfy every literal criterion and still miss the point.
#
# The division of labour is unchanged: the model classifies, Python
# records. Nothing here lets the evaluator write to the repository, move
# a story, or decide what happens next -- and the read-only sandbox is
# what enforces that, not a promise in a prompt. It matters here more
# than for the other roles, because evaluation runs while the story's
# implementation is still uncommitted in the working tree.
# ============================================================


# ============================================================
# Schema
#
# RETRY must carry structured, actionable deficiencies -- never a bare
# decision enum plus free-text reason. The list fields are the
# deterministic evidence normalize_evaluation() below acts on; "reason"
# is context for a human/Claude, never itself a source of truth.
#
# unmet_intent is the field the old evaluator had no way to fill: a
# deficiency in what the work achieves rather than in a checklist item.
# It is deliberately separate from unmet_acceptance_criteria, because a
# story whose criteria are all literally satisfied and whose purpose is
# still unmet is exactly the case that used to pass.
# ============================================================

EVALUATOR_SCHEMA = {
    "type": "object",
    "properties": {
        "decision": {
            "type": "string",
            "enum": [
                "COMPLETE",
                "RETRY",
                "BLOCKED",
                "NEEDS_USER"
            ]
        },
        "reason": {
            "type": "string"
        },
        "intent_achieved": {
            "type": "boolean"
        },
        "evidence": {
            "type": "array",
            "items": {
                "type": "string"
            }
        },
        "unmet_intent": {
            "type": "array",
            "items": {
                "type": "string"
            }
        },
        "unmet_acceptance_criteria": {
            "type": "array",
            "items": {
                "type": "string"
            }
        },
        "unmet_definition_of_done": {
            "type": "array",
            "items": {
                "type": "string"
            }
        },
        "actionable_retry_items": {
            "type": "array",
            "items": {
                "type": "string"
            }
        }
    },
    "required": [
        "decision",
        "reason",
        "intent_achieved",
        "evidence",
        "unmet_intent",
        "unmet_acceptance_criteria",
        "unmet_definition_of_done",
        "actionable_retry_items"
    ]
}


# ============================================================
# Evaluation prompt
# ============================================================

def build_evaluation_prompt(
    story_content: str,
    result_content: str,
    claude_exit_code: int,
    result_was_updated: bool,
) -> str:

    return f"""STORY EVALUATION MODE

You are the evaluator. You judge ONE finished implementation attempt on ONE
story. You are not the coding agent, not the project planner, not the
architect, and not the story selector.

You are running in a READ-ONLY sandbox. You cannot change anything, and you
must not try: you report a verdict and Python records the consequence.

THE TWO QUESTIONS
=================

1. Were this story's own Acceptance Criteria and its own Definition of Done
   satisfied, as written in the story file?

2. Did the work actually achieve what this story set out to achieve?

The second question is the one that needs your judgement, and it is not
answered by the first. A change can tick every criterion literally and still
fail the story's purpose. Look for exactly that:

- the criterion is satisfied somewhere other than where it has to hold (one
  call path, one component, one test fixture) while the behaviour a user or
  caller actually reaches is unchanged;
- the behaviour is implemented but unreachable -- not wired up, behind a flag
  that is never set, on a branch nothing takes;
- a test asserts the stub, the mock or the new code's own restatement of
  itself rather than the behaviour the story is about, so it would pass with
  the feature removed;
- the general rule the story asked for was special-cased to the examples the
  story happened to name;
- the change was made in a layer the story's goal does not live in, leaving
  the real rule stated in two places that can now disagree;
- something the story explicitly required be preserved was quietly changed,
  or something it required be removed is still reachable.

If the story's purpose is achieved, say so. Do not invent a shortfall to look
rigorous, and do not treat a different-but-equivalent implementation choice as
a shortfall.

HOW TO GROUND THE VERDICT
=========================

Claude's own report is supplied below. It is a claim, not evidence. Verify the
claims that decide your verdict against the repository itself:

- `git status --porcelain` and `git diff` for what actually changed (the work
  is still uncommitted; `git diff HEAD` shows it);
- read the changed files, or the relevant sections of them, where the verdict
  depends on what they now do;
- read the test that is supposed to cover the behaviour and check what it
  actually asserts.

You cannot run the build or the test suite, and you are not being asked to:
the full regression run is GitHub Actions' job, after you accept the story.
Judge what the code does, not whether it compiles on your machine.

Record in `evidence` the concrete things you actually inspected -- file paths,
diff hunks, test names. Never list something you did not look at.

SCOPE BOUNDARY
==============

You must NOT require any of the following, even where they look true or
incomplete in the repository:

- completing a roadmap phase or milestone, or resolving its exit criteria;
- planning, describing or scoping future work;
- changing project-wide planning files (PROJECT_STATE.md, ROADMAP.md,
  BACKLOG.md) beyond what this story's own Definition of Done requires;
- resolving an unrelated open user decision;
- implementing, selecting or preparing a subsequent story;
- anything a different story owns, however obviously worth doing.

Those are PROJECT PLANNING MODE concerns. Ignore them entirely unless this
story's own text names them as a requirement of THIS story. You may use
project documentation only where the story itself cites it; never introduce a
requirement from a document the story does not reference.

DECISIONS
=========

COMPLETE
  Every acceptance criterion and Definition of Done item for THIS story is
  satisfied, AND the story's purpose is achieved in the repository.
  unmet_intent, unmet_acceptance_criteria, unmet_definition_of_done and
  actionable_retry_items must all be empty, and intent_achieved must be true.

RETRY
  There is a concrete unresolved deficiency that (a) is in this story's own
  scope and (b) Claude can fix by continuing this same story with the tools it
  already has. This is the decision for "the purpose was not achieved" --
  name the shortfall in unmet_intent and the fix in actionable_retry_items.
  You MUST list at least one concrete item in actionable_retry_items; a RETRY
  with an empty list is rejected and costs a human's attention.
  Never use RETRY for anything in the scope boundary above, and never for a
  missing capability re-running Claude cannot conjure (see NEEDS_USER).

BLOCKED
  The story cannot currently be completed because of a concrete technical or
  dependency blocker. Name it.

NEEDS_USER
  Claude cannot make further progress without an external human action: a
  permission or capability that must be granted, a tool that must be
  installed, credentials or environment access, or a manual verification only
  a human can perform. This is NOT for product/domain decisions (a separate
  process owns those) and NOT for ordinary technical uncertainty Claude could
  resolve by investigating further. Name the concrete external action.

FIELDS
======

- intent_achieved: true only if question 2 is genuinely answered yes.
- evidence: what you actually inspected. Non-empty.
- unmet_intent: ways the delivered work fails to achieve what the story set
  out to achieve, even where the literal criteria are met. Empty if none.
- unmet_acceptance_criteria: criteria from this story's own "Acceptance
  Criteria" section that are demonstrably not met. Empty if none.
- unmet_definition_of_done: items from this story's own "Definition of Done"
  section that are demonstrably not met. Empty if none.
- actionable_retry_items: concrete, in-scope tasks for the next attempt, each
  mapping back to an entry in one of the three lists above. Empty unless the
  decision is RETRY.
- reason: short context for a human. The structured fields, not this prose,
  determine what happens next.

Do not invent new requirements. Do not propose implementation details beyond
naming what is missing. Do not select another story.

SUPPLIED CONTEXT
================

Repository root: {REPO_ROOT.as_posix()}

--- BEGIN STORY ---
{story_content}
--- END STORY ---

Claude's own report of the attempt (a claim to be verified, not evidence):

--- BEGIN RESULT ---
{result_content}
--- END RESULT ---

Claude process exit code: {claude_exit_code}
CLAUDE_RESULT.md changed during this run: {result_was_updated}

REQUIRED OUTPUT
===============

Your final message must contain exactly one fenced JSON block and nothing
else outside it:

```json
{{
  "decision": "COMPLETE" | "RETRY" | "BLOCKED" | "NEEDS_USER",
  "reason": "<short context>",
  "intent_achieved": true | false,
  "evidence": ["<what you inspected>", ...],
  "unmet_intent": ["<purpose not achieved>", ...],
  "unmet_acceptance_criteria": ["<criterion>", ...],
  "unmet_definition_of_done": ["<item>", ...],
  "actionable_retry_items": ["<concrete task>", ...]
}}
```

Write no files. Stop after that message.
"""


# ============================================================
# Verdict extraction
#
# A read-only role cannot write its result to an artifact, so the verdict
# comes back in Codex's final message. Anything unparseable is an
# evaluation failure, never a guess: the orchestrator retries the
# evaluation (without re-running Claude) and escalates if it keeps
# failing, which is strictly better than acting on a verdict this code
# had to infer.
# ============================================================

FENCED_JSON = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)

REQUIRED_LIST_FIELDS = (
    "evidence",
    "unmet_intent",
    "unmet_acceptance_criteria",
    "unmet_definition_of_done",
    "actionable_retry_items",
)


def _candidate_payloads(messages: list[str]):
    """Every JSON object the messages offer, last message first."""

    for message in reversed(messages):
        for match in reversed(FENCED_JSON.findall(message)):
            yield match

        stripped = message.strip()

        if stripped.startswith("{") and stripped.endswith("}"):
            yield stripped


def parse_evaluator_verdict(messages: list[str]) -> dict:
    """The verdict from Codex's messages, or ValueError naming what was wrong."""

    if not messages:
        raise ValueError(
            "The Codex evaluator produced no agent message, so it reported "
            "no verdict."
        )

    failures = []

    for payload in _candidate_payloads(messages):
        try:
            parsed = json.loads(payload)
        except ValueError as exc:
            failures.append(f"not valid JSON ({exc})")
            continue

        if not isinstance(parsed, dict):
            failures.append("JSON payload is not an object")
            continue

        if parsed.get("decision") not in (
                "COMPLETE", "RETRY", "BLOCKED", "NEEDS_USER"
        ):
            failures.append(
                f"unknown decision {parsed.get('decision')!r}"
            )
            continue

        for field in REQUIRED_LIST_FIELDS:
            if field in parsed and not isinstance(parsed[field], list):
                failures.append(f"{field} is not a list")
                break
        else:
            return parsed

    raise ValueError(
        "The Codex evaluator's final message carried no usable verdict ("
        + "; ".join(failures or ["no JSON object found"])
        + ")"
    )


# ============================================================
# Evaluator
# ============================================================

def evaluate_story(
    story_content: str,
    result_content: str,
    claude_exit_code: int,
    result_was_updated: bool
) -> dict:

    prompt = build_evaluation_prompt(
        story_content,
        result_content,
        claude_exit_code,
        result_was_updated,
    )

    messages: list[str] = []

    log_line("Evaluator started (Codex, read-only)")

    # ModelCapacityUnavailable deliberately propagates: Codex usage
    # exhaustion is a scheduling event the orchestrator waits out, never a
    # failed evaluation that burns a retry.
    evaluator_exit_code = run_evaluator(prompt, messages)

    verdict = parse_evaluator_verdict(messages)

    if evaluator_exit_code != 0:
        # A usable verdict with a non-zero exit is still a verdict, but the
        # run is not clean, so it is recorded rather than smoothed over.
        log_line(
            f"Codex evaluator exited {evaluator_exit_code} but reported a "
            "usable verdict; proceeding on the verdict."
        )

    result = normalize_evaluation(
        verdict,
        story_content
    )

    result["evaluator_exit_code"] = evaluator_exit_code

    write_json(
        EVALUATOR_RESULT_FILE,
        result
    )

    return result


# ============================================================
# Deterministic normalization
#
# The model's "decision" enum is not trusted on its own. The structured
# deficiency lists (unmet_intent / unmet_acceptance_criteria /
# unmet_definition_of_done / actionable_retry_items) are the deterministic
# evidence; free-text "reason" is never the primary signal -- it is only
# carried through for human/Claude context.
#
# A RETRY with zero structured deficiencies is invalid by construction
# (an "empty RETRY"), regardless of what its reason text claims or
# denies. It never triggers another Claude run. What it normalizes to
# depends on the one piece of deterministic repository evidence
# available at this layer -- the story file's own canonical
# "## Status" section:
#   - Status: DONE            -> COMPLETE (the story itself already
#                                declares completion, and the evaluator
#                                supplied no concrete reason to doubt
#                                it)
#   - anything else           -> NEEDS_USER (an empty RETRY on a story
#                                that isn't canonically DONE is an
#                                evaluator failure, not actionable work
#                                -- stop and ask rather than loop)
#
# A concrete unmet acceptance criterion, DoD item or intent shortfall, if
# present, always wins over a bare "DONE" status -- DONE never
# unconditionally overrides real evidence of a gap (see CLAUDE.md "Never
# invent or reinterpret a domain rule to make an implementation easier" --
# the analogous rule here is "never let a status label override concrete
# evidence").
#
# Symmetrically, a COMPLETE decision that nonetheless carries concrete
# unmet items is also contradictory -- concrete evidence wins there too,
# so it is normalized down to RETRY using that same evidence. An
# intent_achieved of false is treated as exactly such a contradiction:
# the work not achieving the story's purpose is a retry, whatever label
# the evaluator attached to it.
# ============================================================

def _as_list(value) -> list[str]:
    if not value:
        return []

    return [str(item) for item in value if str(item).strip()]


def normalize_evaluation(
    evaluation: dict,
    story_content: str
) -> dict:

    decision = evaluation.get("decision")
    reason = evaluation.get("reason", "")

    unmet_intent = _as_list(
        evaluation.get("unmet_intent")
    )
    unmet_acceptance_criteria = _as_list(
        evaluation.get("unmet_acceptance_criteria")
    )
    unmet_definition_of_done = _as_list(
        evaluation.get("unmet_definition_of_done")
    )
    actionable_retry_items = _as_list(
        evaluation.get("actionable_retry_items")
    )

    unmet_items = (
        unmet_intent
        + unmet_acceptance_criteria
        + unmet_definition_of_done
    )

    has_concrete_deficiency = bool(
        unmet_items or actionable_retry_items
    )

    # Absent (an older payload, or a model that omitted it) must not read
    # as "purpose not achieved" -- only an explicit false does.
    intent_achieved = evaluation.get("intent_achieved")
    intent_denied = intent_achieved is False

    normalized = {
        "decision": decision,
        "reason": reason,
        "intent_achieved": intent_achieved,
        "evidence": _as_list(evaluation.get("evidence")),
        "unmet_intent": unmet_intent,
        "unmet_acceptance_criteria": unmet_acceptance_criteria,
        "unmet_definition_of_done": unmet_definition_of_done,
        "actionable_retry_items": actionable_retry_items,
        "raw_decision": decision,
        "normalized": False
    }

    if decision in ("RETRY", "COMPLETE") and not has_concrete_deficiency:
        # Nothing concrete either way. An intent_achieved of false with no
        # named shortfall is the same shape of evaluator failure as an
        # empty RETRY: it contradicts itself and offers nothing to act on.
        if decision == "COMPLETE" and not intent_denied:
            return normalized

        status_text = extract_status_section(
            story_content
        )

        story_status = classify_story_status(
            status_text
        )

        if intent_denied:
            normalized["decision"] = "NEEDS_USER"
            normalized["reason"] = (
                f"Normalized {decision} -> NEEDS_USER: the evaluator "
                "reported that the story's purpose was not achieved "
                "(intent_achieved=false) but named no concrete shortfall in "
                "unmet_intent, unmet_acceptance_criteria, "
                "unmet_definition_of_done or actionable_retry_items, so "
                "there is nothing for another attempt to fix. Original "
                f"reason: {reason!r}"
            )
        elif story_status == "DONE":
            normalized["decision"] = "COMPLETE"
            normalized["reason"] = (
                "Normalized RETRY -> COMPLETE: the evaluator returned "
                "RETRY with no concrete, structured deficiency "
                "(unmet_intent, unmet_acceptance_criteria, "
                "unmet_definition_of_done, and actionable_retry_items were "
                "all empty), and the story's own canonical Status is DONE. "
                f"Original reason: {reason!r}"
            )
        else:
            normalized["decision"] = "NEEDS_USER"
            normalized["reason"] = (
                "Normalized RETRY -> NEEDS_USER: the evaluator returned "
                "RETRY with no concrete, structured deficiency, so "
                "there is nothing actionable to retry and the story's "
                "own canonical Status is not DONE. Original reason: "
                f"{reason!r}"
            )

        normalized["normalized"] = True

        return normalized

    if decision == "COMPLETE" and (unmet_items or intent_denied):
        merged_items = actionable_retry_items or unmet_items

        normalized["decision"] = "RETRY"
        normalized["actionable_retry_items"] = merged_items
        normalized["reason"] = (
            "Normalized COMPLETE -> RETRY: the evaluator returned "
            "COMPLETE but also reported concrete unmet work -- an "
            "unachieved story purpose, or unmet acceptance criteria or "
            "Definition of Done items -- which is contradictory; the "
            "concrete evidence takes precedence over the decision label. "
            f"Original reason: {reason!r}"
        )
        normalized["normalized"] = True

        return normalized

    if decision == "RETRY" and not actionable_retry_items:
        # has_concrete_deficiency is true here only via the unmet_* lists;
        # actionable_retry_items must itself be non-empty for a valid
        # RETRY (it is what becomes the retry prompt), so backfill it
        # from the unmet lists rather than sending Claude an empty
        # deficiency list.
        normalized["actionable_retry_items"] = unmet_items
        normalized["normalized"] = True

    return normalized
