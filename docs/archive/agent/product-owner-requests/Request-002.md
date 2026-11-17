# Product Owner Request

## Status

RESOLVED

## Title

Coordinate Claude and Codex availability independently in the orchestrator

## Requested Change

The orchestrator should manage Claude Code and Codex planner availability independently.

Claude and Codex have separate usage/token limits and should not unnecessarily block each other.

### Desired behavior

If Claude has sufficient usage available:

- continue implementing executable stories from `BACKLOG.md -> To Do`;
- prefer consuming already-planned work before waiting for the planner.

If the selectable To Do queue drops to 2 or fewer stories:

- run the Codex planner if Codex usage is available;
- allow it to replenish planning work according to the existing planner rules.

If Codex usage is unavailable:

- do not stop Claude merely because planning cannot currently run;
- continue executing existing selectable To Do stories;
- allow the queue to shrink below the normal planning threshold, including to zero if necessary.

If Claude usage is unavailable:

- do not invoke Claude;
- if Codex usage is available, allow Codex to perform useful project-planning passes and create additional future executable work;
- continue planning until the planner reports that no additional useful current-scope work should be created.

If both Claude and Codex are unavailable:

- wait locally for usage to become available;
- do not repeatedly invoke either model while its usage is exhausted.

When one becomes available again, resume the appropriate flow.

## Concurrency / State Safety

Do not allow Claude-side orchestration changes and Codex planning changes to write shared workflow state concurrently.

Shared mutable orchestration state includes at least:

- `agent/stories/BACKLOG.md`
- `agent/CURRENT_STORY.md`
- story Status fields
- planner/evaluator/runtime result artifacts
- milestone/planning state

Token/usage checks may happen independently or concurrently.

Model work may also be prepared independently where safe.

However, commits to shared orchestration state must be serialized through the Python orchestrator.

Prefer a simple single-writer model over introducing unnecessary concurrent file locking.

The goal is:

- independent model availability;
- no unnecessary waiting;
- no race conditions;
- no lost BACKLOG/story-state updates.

## Waiting Behavior

Usage exhaustion is not a story failure and not a planning failure.

The orchestrator should enter a local wait state for the unavailable model.

Polling/checking usage must not consume model tokens where avoidable.

When capacity returns:

- Claude resumes the same unfinished active story if one exists;
- Codex resumes project planning when the planning trigger is still applicable.

## Scheduling Priority

Preferred scheduling:

1. Finish/resume an already active Claude story when Claude is available.
2. Execute existing selectable To Do work.
3. When To Do <= 2, replenish through Codex if available.
4. If Claude is unavailable but Codex is available, use that idle time for useful planning.
5. If neither can make progress, wait locally.

## Constraints

- Do not introduce parallel writes to BACKLOG or story-state files.
- Do not let Codex modify an active story being implemented by Claude.
- Do not let Claude select the next story.
- Preserve deterministic Python story selection.
- Reuse the existing Claude-usage waiting logic where possible.
- Add equivalent Codex usage detection/wait handling rather than building a second unrelated mechanism.
- Keep the orchestration understandable and deterministic.

## Why / Product Intent

Claude and Codex have independent usage limits.

The development pipeline should continue making progress whenever at least one of them can perform useful work, instead of stopping the entire system because one model temporarily has no capacity.

At the same time, shared repository planning state must remain consistent and free from race conditions.

## Planner Resolution

Confirmed and reused existing full coverage in `docs/TARGET_ARCHITECTURE.md` §36: deferred independent capacity, scheduling and waiting behavior, resume rules and Python single-writer state safety. No duplicate documentation or executable story was needed. The request remains represented as future planned work outside Phase 1; no orchestration implementation or later-phase planning was performed.