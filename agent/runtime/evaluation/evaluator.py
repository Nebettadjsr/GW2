from agent.runtime.support.config import EVALUATOR_RESULT_FILE
from agent.runtime.support.files import write_json
from agent.runtime.evaluation.hermes_client import call_ollama
from agent.runtime.core.story_state import classify_story_status, extract_status_section
from agent.runtime.support.daily_log import log_line


# ============================================================
# Schema
#
# RETRY must carry structured, actionable deficiencies -- never a bare
# decision enum plus free-text reason. The three list fields are the
# deterministic evidence normalize_evaluation() below acts on; "reason"
# is context for a human/Claude, never itself a source of truth.
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
        "unmet_acceptance_criteria",
        "unmet_definition_of_done",
        "actionable_retry_items"
    ]
}


# ============================================================
# Evaluator
# ============================================================

def evaluate_story(
    story_content: str,
    result_content: str,
    claude_exit_code: int,
    result_was_updated: bool
) -> dict:

    system_message = """
You evaluate whether Claude Code completed the ACTIVE STORY -- only this
one story, nothing else.

You are not the coding agent, not a project planner, and not a story
selector.

Your only question is:

    "Did this active story satisfy its own Acceptance Criteria and its
    own Definition of Done, as written in the story file?"

Scope boundary -- you must NOT require any of the following, even if
they appear true or incomplete in the repository:
- completing a roadmap phase or milestone
- resolving a milestone's exit criteria
- planning, describing, or scoping future work
- changing project-wide planning files (PROJECT_STATE.md, ROADMAP.md,
  BACKLOG.md) beyond what the active story's own Definition of Done
  explicitly requires
- resolving an unrelated open user decision
- implementing, selecting, or preparing a subsequent/next story

Those are PROJECT PLANNING MODE concerns, not story evaluation
concerns. Ignore them entirely unless the active story's own
Acceptance Criteria or Definition of Done text explicitly names them
as a requirement of THIS story.

You may consider project documentation (specs, roadmap, known
problems, etc.) only insofar as the active story's own Acceptance
Criteria or Definition of Done explicitly reference it. Never
introduce a requirement from documentation the story itself does not
cite.

Allowed decisions:

COMPLETE
Every acceptance criterion and every Definition of Done item for THIS
story is satisfied. unmet_acceptance_criteria, unmet_definition_of_done,
and actionable_retry_items must all be empty.

RETRY
The story is not complete, but Claude can reasonably continue from the
current repository state, within this story's own scope, without a
user decision or any capability Claude does not already have in this
session. RETRY is valid only when there is a concrete unresolved
deficiency that (a) belongs to the current story's own scope and (b)
Claude can actually fix by rerunning this same story with the tools it
already has. You MUST list at least one concrete item in
actionable_retry_items -- never return RETRY with an empty list. Do
not use RETRY for anything in the scope boundary list above, and never
use RETRY merely because Claude re-ran into the same missing
capability described under NEEDS_USER below -- re-running Claude
cannot make a denied permission, a missing tool, or a required manual
step appear.

BLOCKED
The story cannot currently be completed because of a technical or
dependency blocker. Identify the concrete blocker.

NEEDS_USER
Claude cannot make further meaningful implementation progress without
an external human action -- this is NOT a product/domain decision
(that belongs to a separate process and must never be classified
here); it is Claude lacking a capability only a human can supply in
this session. Use NEEDS_USER when Claude's own response says
something like: a permission or capability must be granted (e.g.
shell/tool access was denied); a missing tool/capability must be
installed or provided; a manual verification step only a human can
perform (e.g. interactive GUI behavior with no automation available);
credentials or environment access are required; or Claude explicitly
asks the user to choose between concrete runtime/tooling options it
cannot resolve itself. Identify the concrete external action required.

Do NOT use NEEDS_USER for ordinary technical uncertainty Claude could
resolve itself by investigating further (that is RETRY, if there is a
concrete actionable item, or BLOCKED otherwise). Do NOT use NEEDS_USER
merely because Claude's response contains a question mark or an
incidental conversational question that does not actually block
further progress.

Structured fields (all four required, use empty arrays when there is
nothing to report):
- unmet_acceptance_criteria: acceptance criteria copied/paraphrased
  from the story's own "Acceptance Criteria" section that are
  demonstrably not met. Empty if none.
- unmet_definition_of_done: Definition of Done items copied/paraphrased
  from the story's own "Definition of Done" section that are
  demonstrably not met. Empty if none.
- actionable_retry_items: concrete, in-scope tasks Claude should do on
  a retry to close the gap. Every item here must map back to something
  in unmet_acceptance_criteria or unmet_definition_of_done. Empty
  unless decision is RETRY.

Do not invent new requirements.
Do not propose implementation details.
Do not select another story.

Give a short "reason" for context, but the structured fields -- not the
prose in "reason" -- are what determines whether a retry happens.
"""

    user_message = f"""
ACTIVE STORY:

--- BEGIN STORY ---
{story_content}
--- END STORY ---

CLAUDE RESULT:

--- BEGIN RESULT ---
{result_content}
--- END RESULT ---

Claude process exit code:
{claude_exit_code}

CLAUDE_RESULT.md changed during this run:
{result_was_updated}
"""

    log_line("Evaluator started")
    raw_result = call_ollama(
        [
            {
                "role": "system",
                "content": system_message
            },
            {
                "role": "user",
                "content": user_message
            }
        ],
        EVALUATOR_SCHEMA
    )

    result = normalize_evaluation(
        raw_result,
        story_content
    )

    write_json(
        EVALUATOR_RESULT_FILE,
        result
    )

    return result


# ============================================================
# Deterministic normalization
#
# Hermes's "decision" enum is not trusted on its own. The structured
# deficiency lists (unmet_acceptance_criteria / unmet_definition_of_done
# / actionable_retry_items) are the deterministic evidence; free-text
# "reason" is never the primary signal -- it is only carried through for
# human/Claude context.
#
# A RETRY with zero structured deficiencies is invalid by construction
# (an "empty RETRY"), regardless of what its reason text claims or
# denies. It never triggers another Claude run. What it normalizes to
# depends on the one piece of deterministic repository evidence
# available at this layer -- the story file's own canonical
# "## Status" section:
#   - Status: DONE            -> COMPLETE (the story itself already
#                                declares completion, and Hermes
#                                supplied no concrete reason to doubt
#                                it)
#   - anything else           -> NEEDS_USER (an empty RETRY on a story
#                                that isn't canonically DONE is an
#                                evaluator failure, not actionable work
#                                -- stop and ask rather than loop)
#
# A concrete unmet acceptance criterion or DoD item, if present, always
# wins over a bare "DONE" status -- DONE never unconditionally overrides
# real evidence of a gap (see CLAUDE.md "Never invent or reinterpret a
# domain rule to make an implementation easier" -- the analogous rule
# here is "never let a status label override concrete evidence").
#
# Symmetrically, a COMPLETE decision that nonetheless carries concrete
# unmet items is also contradictory -- concrete evidence wins there too,
# so it is normalized down to RETRY using that same evidence.
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

    unmet_acceptance_criteria = _as_list(
        evaluation.get("unmet_acceptance_criteria")
    )
    unmet_definition_of_done = _as_list(
        evaluation.get("unmet_definition_of_done")
    )
    actionable_retry_items = _as_list(
        evaluation.get("actionable_retry_items")
    )

    has_concrete_deficiency = bool(
        unmet_acceptance_criteria
        or unmet_definition_of_done
        or actionable_retry_items
    )

    normalized = {
        "decision": decision,
        "reason": reason,
        "unmet_acceptance_criteria": unmet_acceptance_criteria,
        "unmet_definition_of_done": unmet_definition_of_done,
        "actionable_retry_items": actionable_retry_items,
        "raw_decision": decision,
        "normalized": False
    }

    if decision == "RETRY" and not has_concrete_deficiency:
        status_text = extract_status_section(
            story_content
        )

        story_status = classify_story_status(
            status_text
        )

        if story_status == "DONE":
            normalized["decision"] = "COMPLETE"
            normalized["reason"] = (
                "Normalized RETRY -> COMPLETE: the evaluator returned "
                "RETRY with no concrete, structured deficiency "
                "(unmet_acceptance_criteria, unmet_definition_of_done, "
                "and actionable_retry_items were all empty), and the "
                "story's own canonical Status is DONE. Original "
                f"reason: {reason!r}"
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

    if decision == "COMPLETE" and (
        unmet_acceptance_criteria or unmet_definition_of_done
    ):
        merged_items = actionable_retry_items or (
            unmet_acceptance_criteria + unmet_definition_of_done
        )

        normalized["decision"] = "RETRY"
        normalized["actionable_retry_items"] = merged_items
        normalized["reason"] = (
            "Normalized COMPLETE -> RETRY: the evaluator returned "
            "COMPLETE but also listed concrete unmet acceptance "
            "criteria or Definition of Done items, which is "
            "contradictory -- the concrete evidence takes precedence "
            f"over the decision label. Original reason: {reason!r}"
        )
        normalized["normalized"] = True

        return normalized

    if decision == "RETRY" and not actionable_retry_items:
        # has_concrete_deficiency is true here only via unmet_* lists;
        # actionable_retry_items must itself be non-empty for a valid
        # RETRY (it is what becomes the retry prompt), so backfill it
        # from the unmet lists rather than sending Claude an empty
        # deficiency list.
        normalized["actionable_retry_items"] = (
            unmet_acceptance_criteria + unmet_definition_of_done
        )
        normalized["normalized"] = True

    return normalized
