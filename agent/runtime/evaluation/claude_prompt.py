from pathlib import Path

from agent.runtime.support.config import REPO_ROOT


def build_claude_prompt(story_path: Path, repo_map_context: str = "") -> str:
    """Build the implementation prompt from the active story's canonical path."""
    relative_story = story_path.relative_to(REPO_ROOT).as_posix()
    sections = [f"Implement the active story at {relative_story}."]

    if repo_map_context:
        sections.append(repo_map_context)

    sections.append(
        f"""Read and follow CLAUDE.md and the active story at {relative_story}.
They are the source of truth for this work. Do not create stories or edit the
backlog. If you discover a useful issue outside this story's scope, record it
under `## Follow-up Findings` in the story and in `CLAUDE_RESULT.md` as a concise
bullet `F001: <finding>`; increment the ID for additional findings. Use `None.`
when there are no findings. Do not report work already covered by this story.

Stop after completing or blocking this story.
"""
    )
    return "\n\n".join(sections)
