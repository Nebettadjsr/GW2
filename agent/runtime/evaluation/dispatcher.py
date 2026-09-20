from pathlib import Path

from agent.runtime.support.config import DISPATCH_RESULT_FILE, REPO_ROOT
from agent.runtime.support.files import write_json
from agent.runtime.evaluation.hermes_client import call_ollama


# ============================================================
# Schema
# ============================================================

DISPATCH_SCHEMA = {
    "type": "object",
    "properties": {
        "story_id": {
            "type": "string",
            "minLength": 1
        },
        "goal": {
            "type": "string",
            "minLength": 1
        },
        "acceptance_criteria": {
            "type": "array",
            "items": {
                "type": "string",
                "minLength": 1
            },
            "minItems": 1
        },
        "references": {
            "type": "array",
            "items": {
                "type": "string",
                "minLength": 1
            },
            "minItems": 1
        },
        "status": {
            "type": "string",
            "enum": [
                "READY",
                "NEEDS_MORE_CONTEXT"
            ]
        },
        "reason": {
            "type": "string"
        }
    },
    "required": [
        "status"
    ]
}


# ============================================================
# Dispatcher result validation / normalization
# ============================================================

def validate_dispatch_result(
        result: dict
) -> dict:
    """
    Enforce the contract consumed by build_claude_prompt().

    A READY result must contain every field Claude's prompt builder
    requires, and references / acceptance criteria may not be empty.

    If Hermes claims READY but the work order is incomplete, downgrade
    it to NEEDS_MORE_CONTEXT instead of letting the orchestrator crash
    later with a KeyError.
    """

    if not isinstance(result, dict):
        return {
            "status": "NEEDS_MORE_CONTEXT",
            "reason": (
                "Dispatcher returned an invalid response type "
                f"({type(result).__name__}) instead of an object."
            )
        }

    status = result.get("status")

    if status == "READY":
        problems = []

        story_id = result.get("story_id")
        goal = result.get("goal")
        acceptance_criteria = result.get("acceptance_criteria")
        references = result.get("references")

        if not isinstance(story_id, str) or not story_id.strip():
            problems.append("story_id is missing or empty")

        if not isinstance(goal, str) or not goal.strip():
            problems.append("goal is missing or empty")

        if (
                not isinstance(acceptance_criteria, list)
                or not acceptance_criteria
                or not all(
            isinstance(item, str) and item.strip()
            for item in acceptance_criteria
        )
        ):
            problems.append(
                "acceptance_criteria is missing, empty, "
                "or contains an empty/non-string item"
            )

        if (
                not isinstance(references, list)
                or not references
                or not all(
            isinstance(item, str) and item.strip()
            for item in references
        )
        ):
            problems.append(
                "references is missing, empty, "
                "or contains an empty/non-string item"
            )

        if problems:
            return {
                "status": "NEEDS_MORE_CONTEXT",
                "reason": (
                        "Dispatcher claimed READY but produced an "
                        "incomplete work order: "
                        + "; ".join(problems)
                )
            }

        return result

    if status == "NEEDS_MORE_CONTEXT":
        reason = result.get("reason")

        if not isinstance(reason, str) or not reason.strip():
            result = dict(result)
            result["reason"] = (
                "Dispatcher reported NEEDS_MORE_CONTEXT "
                "without a usable reason."
            )

        return result

    return {
        "status": "NEEDS_MORE_CONTEXT",
        "reason": (
            "Dispatcher returned an invalid or missing status: "
            f"{status!r}"
        )
    }


# ============================================================
# Dispatcher
# ============================================================

def dispatch_story(
        story_content: str
) -> dict:

    system_message = """
You are a dispatcher for Claude Code.

You are not the coding agent and you do not decide implementation details.

Extract the supplied active story into a small structured work order.

Extract only:
- story_id
- goal
- acceptance_criteria
- references
- status
- reason, only when status is NEEDS_MORE_CONTEXT

Rules:
- do not invent requirements
- do not invent implementation details
- do not write code
- do not select another story
- do not expand scope
- acceptance criteria must come from the supplied story
- references must come from the supplied story
- references must contain at least one authoritative source from the story
- acceptance_criteria must contain at least one item from the story

If the story lacks enough information to execute safely, return:
- status = NEEDS_MORE_CONTEXT
- reason = short explanation

Otherwise return ALL of these fields:
- status = READY
- story_id
- goal
- acceptance_criteria
- references

A READY result is invalid if story_id, goal, acceptance_criteria,
or references is missing or empty.
"""

    user_message = f"""
ACTIVE STORY:

--- BEGIN ACTIVE STORY ---
{story_content}
--- END ACTIVE STORY ---
"""

    result = call_ollama(
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
        DISPATCH_SCHEMA
    )

    result = validate_dispatch_result(
        result
    )

    write_json(
        DISPATCH_RESULT_FILE,
        result
    )

    return result


# ============================================================
# Claude prompt
# ============================================================

def build_claude_prompt(
        plan: dict,
        story_path: Path,
        repo_map_context: str = "",
) -> str:

    criteria = "\n".join(
        f"- {item}"
        for item in plan[
            "acceptance_criteria"
        ]
    )

    references = "\n".join(
        f"- {item}"
        for item in plan[
            "references"
        ]
    )

    relative_story = story_path.relative_to(
        REPO_ROOT
    ).as_posix()

    # repo_map_context (see agent/runtime/support/repo_map.py) is
    # purely optional orientation context for Claude's implementation
    # prompt -- when empty (the default, and always the case for the
    # Codex planner/Hermes/selector, which never call this with one),
    # the prompt is byte-for-byte identical to before this parameter
    # existed.
    sections = [
        f"Execute {plan['story_id']}."
    ]

    if repo_map_context:
        sections.append(repo_map_context)

    sections.append(
        f"""Active story:
{relative_story}

Goal:
{plan["goal"]}

Acceptance criteria:
{criteria}

Relevant sources:
{references}

Use CLAUDE.md and the active story as the source of truth.

Stop after completing or blocking this story.
"""
    )

    return "\n\n".join(sections)