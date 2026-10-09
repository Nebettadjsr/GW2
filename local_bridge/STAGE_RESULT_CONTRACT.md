# Development Pipeline Stage Result Contract

Every entry in the parent pipeline's `pipeline_summary` uses the same normalized
schema. Stage-specific validators decide success from the authoritative child
result and current bridge contract; `normalizeStageResult` supplies consistent
representation and defaults. A missing required success proof remains a blocker.

## Normalized fields

| Field | Meaning |
| --- | --- |
| `stage` | Stable stage key: `selection`, `activation`, `qa_preparation`, `implementation`, `qa_review`, `evaluator`, `publication`, `final_ci`, or `finalization`. |
| `execution` | `RUN` for newly completed work, `REUSED` for a validated persisted result, `SKIPPED` when the valid result means the work is not needed, or `STOPPED` when the stage cannot safely continue. |
| `status`, `decision` | Child status and decision; decision falls back to status when the child has no separate decision. |
| `story_id` | Story identity returned by the child/current bridge. |
| `task_id` | Persisted task identity when applicable; otherwise an empty string. |
| `commit_sha` | Published implementation SHA, Final CI SHA, or finalization SHA when applicable; otherwise an empty string. |
| `error` | Actual child error, or `null` when absent/empty. A child blocker is carried in `reason`, not mislabeled as an execution error. |
| `reason` | Human-readable blocker or explanation; empty on ordinary success. |
| `outstanding_requirements` | Array of unresolved requirements/findings; defaults to an empty array. |
| `provenance` | Source marker such as authenticated bridge HTTP or existing active-story state. |
| `record_source` | Bridge persisted-task marker when supplied, otherwise `unknown`. |
| `submission_disposition` | `created`, `cached_result`, `idempotent_replay`, or `unknown` as returned by the child. |

Optional stage details such as `exit_code`, `push_status`, `authorized_paths`,
`recovery`, and `story` remain available without changing the common fields.
The parent gate reads `pipeline_continue`, which is derived from the current
stage's normalized `execution` (`STOPPED` is false; the other three values are
continuable).

## Child acceptance rules

| Stage | New success (`RUN`) | Persisted success (`REUSED`) | Stop conditions |
| --- | --- | --- | --- |
| Selection | Authenticated selector returns an eligible story. | Active-story inspection already supplies the current story (`RESUMED_ACTIVE`). | Empty/blocked queue, missing story ID, or unauthenticated/inconsistent result. |
| Activation | `activated` for the selected story from the bridge. | `already_active` for that same story. | Story mismatch or any other activation outcome. |
| QA preparation | `READY` with `created`; task completed with exit 0, selected story, matching initial/rechecked story-contract fingerprints, matching verified decision, implementation permitted, no error/findings. | The same validated proof with `cached_result` or `idempotent_replay` from a completed persisted task. | Stale/missing fingerprint, mismatch, error, nonterminal/failed task, `NEEDS_USER`, technical failure, or outstanding requirements. Valid `NO_TESTS_NEEDED` is `SKIPPED`. |
| Implementation | `completed`, exit 0, selected story and task ID; normal task metadata records local HEAD and matching origin branch tip before Claude starts. Publication later independently validates the persisted task's metadata. | Existing implementation task is bridge-validated against the current implementation contract. | Stale task/contract, failed/nonzero task, local HEAD differing from origin before Claude starts, wrong story, or child invocation error. |
| QA review | Completed exit-0 `APPROVE`, current story, no outstanding requirements. | Same validated approval with `cached_result` or `idempotent_replay`. | `RETRY`, `NEEDS_USER`, technical/failed result, stale story/contract, error, or findings. |
| Evaluator | Completed exit-0 `COMPLETE`, current story/evaluation contract, no unmet findings. | Same validated result with cached/idempotent persisted disposition. | Incomplete/stale contract, error, non-complete decision, or findings. |
| Publication | `PUBLISHED`, verified push, current story, full commit SHA and nonempty bridge-authorized paths. | `ALREADY_PUBLISHED` with the same verified proof. | `BLOCKED`, failed/interrupted/uncertain outcome, mismatched story/SHA, unverified push, absent authorized scope, baseline not equal to the origin branch tip, or unrelated staged/dirty paths. The bridge publication gate owns path authorization. |
| Final CI | Completed exit-0 `PASSED` for the exact published implementation SHA and current CI contract. | Same result for the exact SHA with cached/idempotent persisted disposition. | Any SHA mismatch, stale contract, failed/unverified result, error, or application check failure. |
| Finalization | Bridge returns `finalized` for the current story. | Bridge returns `already_finalized` or validated recovery completion. | Lifecycle mismatch, unexpected dirty lifecycle files, or any unverified result. |

The stage-specific proof requirements above are intentional. Normalization must
not turn an absent required proof into success, and a child error/blocker must
not be propagated as an error when a validated persisted success is being
reused.
