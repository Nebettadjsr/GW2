# Independent audit of the AI development orchestrator — 2026-10-04

## Scope, evidence and limits

Read-only review of the orchestrator after the recent audit-and-stabilize work.
Evidence: `agent/logs/2026-10-03.log`, Git history `8bb8126..675fddb`, artifact
bytes and modification times, the current backlog/story/intervention state, and
the runtime source. No model was invoked. No repository file or execution-state
artifact was modified by this audit; this report is the only file it adds.

**The test suite was deliberately not run.** Running it mutates live execution
state under `agent/runtime/artifacts/` — which is itself finding §3.1. Test
quality below is therefore assessed by reading tests, not by observing failures.

**Disclosure — this is not a fully independent review.** A substantial part of
the audited system was written by Claude in the session immediately before this
one, not by Codex:

- `agent/runtime/core/backlog_writer.py`, `agent/runtime/core/planning_check.py`
- `finalize_completed_story()` and the `FINALIZING` attempt phase
- `config.DOCUMENT_OWNERSHIP` / `ownership_table()` / `protected_paths_for()`
- the follow-up-finding separator fix in `core/follow_up_findings.py`
- `agent/runtime/tests/test_story_completion.py`

Codex's contribution is the layer above: the QA agent (`agent/runtime/qa/`,
`agent/QA_INSTRUCTIONS.md`), scoped publication, hash-bound verdict recovery,
and the CI-scope classifier. **The most expensive regression found in the
audited period was Claude's own (§3.2)**; it is reported as prominently as any
other finding. Weigh the self-assessed parts accordingly.

Findings are labelled **CONFIRMED DEFECT** (reproduced from code or artifacts),
**CONFIRMED RISK** (established by code inspection, not yet observed in
production) or **PREFERENCE** (architectural judgment).

---

## 1. Overall assessment

**Robustness: moderate and improving. Autonomy: real but expensive.
Maintainability: declining.**

The system genuinely delivered two verified stories autonomously on 2026-10-03
(`STORY-WEB-024`, `STORY-WEB-027`), both passing a real GitHub CI gate. The
recovery machinery is no longer aspirational: `ATTEMPT_STATE.json`, hash-bound
evaluator verdicts and scoped commits are real, deterministic mechanisms that
work as documented.

Cost per unit of delivered work is poor and complexity is compounding.
`core/orchestrator.py` is now **3,410 lines** (2,620 four days earlier) with a
seven-stage pipeline, four attempt phases, three nested retry budgets and five
distinct escalation paths. Delivering those two stories — one of which was
deleting a single file — consumed **~3.2M Codex gross input tokens and ~20% of
Claude's weekly capacity**. Three of five planning passes failed validation.

The pattern of the last four days is that each incident produces a new
mechanism rather than a simplification, and several mechanisms now overlap.

---

## 2. Strengths — preserve these

| Mechanism | Why it works | Evidence |
|---|---|---|
| Deterministic story selection | No model picks work. `selector.py:33-62` walks `## To Do` and takes the first entry `get_selectable_story_candidates()` confirms executable. An LLM previously "repeatedly selected nonexistent/invalid stories" (`selector.py:16-26`). | Queue consistent and selection correct in current state. |
| `ATTEMPT_STATE.json` as pipeline position | Separates "what Claude believes" from "which harness step still owes an answer". Rationale at `orchestrator.py:160-208` is the clearest design note in the codebase. | `test_pipeline_recovery.py`, 26 tests, drives each phase. |
| Hash-bound verdict reuse | `_load_current_evaluator_result()` reuses a persisted verdict only when `evaluated_story_sha256` **and** `evaluated_result_sha256` match (`orchestrator.py:627-640`). Identity, not phase state. | commit `d83e0c1`; `test_completion_recovery.py`. |
| Scoped publication | `commit_and_push(message, paths=...)` (`git_sync.py:198`) commits only validated planner writes plus observed coding changes, and refuses pre-staged unrelated files (`git_sync.py:227-234`). | Directly addresses the 35-path commit `3084e18`. |
| CI-scope classifier | `ci_failures_are_outside_story_scope()` (`orchestrator.py:801-832`) is deterministic, conservative (unknown job name ⇒ attribute to the story), and routes unrelated failures to a human **without invoking Claude** (`orchestrator.py:1899-1920`). | Mapping verified correct against all four current job names in `.github/workflows/ci.yml:33,108,148,194`. |
| Test process guard | `tests/__init__.py` makes `find_claude`, `run_codex`, `run_qa`, `commit_and_push`, `push_branch`, `fetch_json` raise. Written after a fixture "spent real Codex capacity on a real architect pass". | Correct and valuable, though incomplete — see §3.1. |
| Planning rollback | A failed pass restores the previous queue. | Verified: the 22:32 pass created `STORY-WEB-029-align-account-browser-smoke-contract.md`; the file is absent from `agent/stories/`. |
| QA failure classification | Technical QA failures (`_block_story_for_qa_failure`) are kept distinct from product questions (`_block_story_for_qa_decision`), so a tooling fault never becomes a Product Owner decision. | `qa_agent.py:529`; `test_qa_agent.py` technical-failure test. |

---

## 3. Weaknesses

### 3.1 CONFIRMED DEFECT — the test guard leaks into live execution state

`support/config.py:80,86` bind `SELECTOR_RESULT_FILE` and `QA_FAILURES_DIR` from
`ARTIFACTS_DIR` **at import time**. `tests/__init__.py` later reassigns
`config.ARTIFACTS_DIR`, which cannot retroactively rebind them. Demonstrated
directly:

```
before guard:
  ARTIFACTS_DIR    C:\...\GW2\agent\runtime\artifacts
  QA_FAILURES_DIR  C:\...\GW2\agent\runtime\artifacts\qa-failures
  SELECTOR_RESULT  C:\...\GW2\agent\runtime\artifacts\SELECTOR_RESULT.json
after guard:
  ARTIFACTS_DIR    C:\...\Temp\gw2-agent-tests\qa\artifacts
  QA_FAILURES_DIR  C:\...\GW2\agent\runtime\artifacts\qa-failures        <-- still live
  SELECTOR_RESULT  C:\...\GW2\agent\runtime\artifacts\SELECTOR_RESULT.json <-- still live
  selector.SELECTOR_RESULT_FILE  C:\...\GW2\agent\runtime\artifacts\SELECTOR_RESULT.json
```

Physical consequences present in the repository now:

- `agent/runtime/artifacts/SELECTOR_RESULT.json` (mtime **2026-10-04 07:49**)
  names `STORY-DOM-020-y.md`, a fixture defined at `test_orchestrator.py:687`
  and written by the unpatched real call at `test_orchestrator.py:723`.
  `selector.py:1` imports the constant by value and `test_orchestrator.py`
  never patches it.
- `agent/runtime/artifacts/qa-failures/` holds five records for fixture
  stories: `QA-STORY-DOM-001-active.json`, `QA-STORY-DOM-002-blocked.json`,
  `QA-STORY-DOM-099-test.json`, `QA-STORY-DOM-100-test.json`,
  `QA-STORY-UI-002-test.json`.

**Secondary effect on the findings process.** The 22:32 planner dispositioned
`STORY-WEB-027` F003 as `ALREADY COVERED — agent/runtime/tests/__init__.py
redirects orchestrator.EVALUATOR_RESULT_FILE to the isolated test QA directory`
(log line 1217). That is true for `EVALUATOR_RESULT_FILE` and false for the two
constants above, so a real finding was closed on a partially correct premise.
The disposition was rolled back with the failed pass, so F003 is still open.

### 3.2 CONFIRMED DEFECT (Claude's own) — repository-state invariants in the CI suite broke every publication

`test_story_completion.py`'s `QueueConsistencyTest` asserted over the **live
repository files**. The harness commits *before* finalizing, deliberately, so a
red gate can hand the story back as active work (`orchestrator.py:671-688`).
Every story-completion commit therefore contains Status `DONE` + an `## Active`
entry + a pointer naming the story — exactly what that test declared invalid.

CI consequently failed structurally on **every** story completion:

```
[19:15:15] GitHub CI FAILED for 61cd1a0 (STORY-WEB-024-repair-fresh-build-smoke-contracts.md)
[22:15:52] GitHub CI FAILED for 3084e18 (STORY-WEB-027-remove-tracked-smoke-control-copy.md)
[22:15:52] GitHub CI failed; returning the story to Claude (1/2).
```

Cost: two extra Claude runs (the 22:28 run alone was 756s and 22% of a session),
two extra evaluator rounds and two extra QA reviews — to repair a defect in the
test, not in the product.

**Fixed, with a caveat.** `validate_backlog_consistency(publishing=...)`
(`story_state.py:1056`) suspends the two rules for the single story being
published, and `story_awaiting_its_gate()` (`test_story_completion.py:929`)
derives that story from the committed tree. The docstring is candid about the
trade-off: *"A checkout cannot tell that window apart from a finalization that
never ran."* The fix therefore converts a guaranteed false positive into a
possible **false negative** — CI can no longer detect a genuinely missed
finalization. The deeper issue stands: **the CI-run suite asserts over
repository bookkeeping state at all.** Those assertions belong in a local
pre-commit check, not in the gate that evaluates the commit they inspect.

### 3.3 CONFIRMED DEFECT (Claude's own) — `planning_check` reports a false pass

`core/planning_check.py`'s module docstring claims *"It is the same validation
the harness applies afterwards, so a pass here means the result will be
accepted; there is no second, stricter gate to be surprised by."* **That is
false.** It validates only `status`, the `phase_review[].outcome` enum and
backlog entries. `validate_planning_result()` additionally enforces story
contract headings, dependency/BLOCKED coherence, decision and architect
cross-references, and the `independent_work_remaining` rule.

Both later planning failures passed the self-check and were then rejected:

```
[22:32:52] python -c "... from agent.runtime.core.planning_check import main; sys.exit(main())"
[22:32:52] [planner command done] exit 0          <-- self-check PASSED
[22:32:55] Planning result: FAILED (all changes rolled back)
[22:32:55] - independent_work_remaining must agree with READY areas in phase_review.
```

```
[20:58:38] Planning result: FAILED (all changes rolled back)
[20:58:38] - STORY-WEB-029-...md has unresolved prerequisites but is not BLOCKED
[20:58:38] - STORY-WEB-029-...md is missing required sections: Result
```

The second is the worse case: `Result` is a required story heading, trivially
checkable, and the self-check does not look. A planning pass costing 652,700
gross input tokens was discarded over a missing `## Result` line.

### 3.4 CONFIRMED RISK — QA's workspace guard can revert maintainer edits

`qa_agent.enforce_test_only_changes()` (`qa_agent.py:199-234`) takes two
full-repository snapshots around the QA model run. For a file it treats as a
violation:

```python
current = path.read_bytes() if path.is_file() else None
if current != after[name]:
    violations.append(f"{name} (preserved newer concurrent change; not restored)")
    continue
path.write_bytes(before[name])          # reverts to the pre-QA content
```

If the maintainer edits a file **during** the QA run but before the `after`
snapshot, then `current == after[name]` and the guard **silently reverts their
edit and blocks the story**. Protection exists only for edits landing in the
narrow window *after* the second snapshot.

`CLAUDE.md` states explicitly that *"The repository is also actively modified by
the human maintainer outside the agent workflow."* QA runs took 47s–14min in the
2026-10-03 log, and the snapshot covers every tracked and visible untracked file
(`qa_agent.py:169-189`).

`test_qa_agent.py:96` is named
`test_test_only_guard_preserves_a_concurrent_change_instead_of_overwriting_it`
but only exercises the post-snapshot case (`test_qa_agent.py:96-108`) — **the
test's name over-claims what it verifies.**

### 3.5 CONFIRMED RISK — deferred QA logs are lost on hard kill

The fix for the UI-006 incident is `with defer_log_writes():` around the QA run
(`qa_agent.py:496`). `support/daily_log.py:56-69` buffers appends **in memory**
and flushes in `finally`. A `SIGKILL` or power loss during a multi-minute QA
window loses all log evidence for that window — the one place a human would look
to understand what QA did. Excluding `agent/logs/` from `snapshot_files()` would
have resolved the same incident without that trade-off.

### 3.6 PREFERENCE — "never silently reinterpret" versus the new normalization

`REVIEW_OUTCOMES` deliberately rejects `DEFERRED` rather than mapping it, and the
planner prompt says so. The uncommitted working tree now **silently
reinterprets** a different field:

```python
if isinstance(remaining, bool) and remaining != ready:
    result["independent_work_remaining"] = ready     # overwritten
```

Both choices are individually defensible, but no stated principle distinguishes
them, so the next incident has no rule to appeal to. There is also a logic wart:
the subsequent `if remaining:` checks use the planner's **original** value rather
than the normalized one just written.

### 3.7 Fragile assumptions (risks, not defects)

- **CI job-name coupling.** `ci_failures_are_outside_story_scope` matches
  hardcoded substrings against GitHub job names. Correct today; a workflow
  rename degrades it silently to "always attribute to the story" — the direction
  that costs Claude capacity.
- **Unpinned models for three roles.** `QA_MODEL = "gpt-6-luna"` is explicit
  (`config.py:321`); planner, architect and evaluator pass no `--model`
  (`local_planner_runner.py:483`), so a Codex CLI default change silently alters
  their behaviour.
- **Full-repository double snapshot per QA preparation**, reading every tracked
  and untracked file's bytes twice.
- **Prompt-only mitigations.** The two incidents with no deterministic
  enforcement are the planner reversing accepted behaviour and QA over-scoping
  trivial stories.
- **No cross-process lock.** Nothing prevents two orchestrators, or an
  orchestrator plus a manual `python -m unittest` run, from writing
  `ATTEMPT_STATE.json` / `BACKLOG.md` concurrently — and §3.1 proves the test
  suite writes live artifacts.
- **Non-atomic writes.** Only `record_attempt_state` uses temp-file-then-
  `replace` (`orchestrator.py:240-246`). `BACKLOG.md`, story files and
  `CURRENT_STORY.md` use direct `write_text`; a kill mid-write truncates the
  queue index.

---

## 4. Incident verification

| Incident | Status | Evidence |
|---|---|---|
| Planner reversing intentional UI changes | **Partially fixed — prompt only** | `agent/PLANNER_INSTRUCTIONS.md` gained "Reconcile Requirements With Accepted Product Decisions" plus narrow implementation-file read permission (commit `3084e18`). No deterministic guard; inherently a judgment call, so the class can recur. |
| QA blocked by the orchestrator's own log file | **Fixed, unverified in operation** | Incident confirmed: `[21:00:00]` and `[21:01:02] QAPlanError: QA modified forbidden files; changes were restored: agent/logs/2026-10-03.log` → `UI-006-STORY-WEB-020.md`. Fix: `defer_log_writes()`. UI-006 is RESOLVED and WEB-020 is TODO back in the queue. Caveat §3.5. No QA run has occurred since the fix. |
| WEB-027 needed two QA preparations (invalid story identifier) | **Fixed** | `[21:58:33] QA returned an invalid plan (1/2): QA plan references a different story.` Fix: `validate_plan` accepts the exact ID minus the `STORY-` prefix, derived from the one canonical ID, with no fuzzy matching (`qa_agent.py:111-121`). |
| Unnecessarily broad QA requirements for trivial changes | **Not yet effective** | The WEB-027 plan — for deleting one file — required `npm run smoke:account` to exit 0; it failed for an unrelated pre-existing reason, forcing an evaluator round *and* a QA review to adjudicate the deviation. The proportionality paragraph in `QA_INSTRUCTIONS.md` is **uncommitted and was added after the incident**: `git show 3084e18:agent/QA_INSTRUCTIONS.md \| grep -c "Scale verification"` → `0`. Prompt-only, unenforceable deterministically. |
| Duplicate Evaluator and post-implementation QA reviews | **Unresolved by design** | WEB-027 ran evaluator + QA review at 22:13, then **again** at 22:28 after the CI fix: four Codex calls (~360k gross input tokens) for a one-file deletion. `review_required` fires on `qa_review_required` **or** `decision == "RETRY"` **or** `post_implementation_review_required` **or** any integrity finding (`orchestrator.py:641-646`), and re-fires on every CI-fix round. |
| Unrelated CI failures returned to Claude | **Fixed for the classifiable case** | `ci_failures_are_outside_story_scope` plus the hold path (`orchestrator.py:1899-1920`), which escalates to a human with `preserve_attempt=True` and does not invoke Claude. It could not have helped the actual 2026-10-03 failures: those commits *did* contain the failing suites' paths, because the publication scope was too broad (§3.2). Any story touching `agent/stories/*.md` also overlaps the "agent runtime" root and stays attributed to Claude. |
| Publication-state defect causing completed stories to fail CI | **Fixed — two independent causes** | Cause A, over-broad commit: `3084e18` carried 35 paths including `agent/logs/2026-10-03.log` (+469 lines), `docs/DOMAIN_SPEC.md`, `agent/PLANNER_INSTRUCTIONS.md`, `UI-006-STORY-WEB-020.md` and `frontend/scripts/profit-browser-smoke.mjs`. Fixed by scoped `commit_and_push`. Cause B: the live-state tests in §3.2. Fixed by `publishing=`. |
| Excessive planner invocations and invalid planning results | **Unresolved** | Five passes, **three failed**, 2,277,489 gross input tokens. The trigger is `scheduler.has_changed_planning_inputs()` (`orchestrator.py:3105`), and a completed story always changes planning inputs (story file, `BACKLOG.md`, `CURRENT_STORY.md`, `CLAUDE_RESULT.md`) — so planning runs after **every** story despite `PLANNING_TRIGGER_MAX_READY_STORIES = 0` and a stocked queue. |
| Follow-up findings generating unnecessary or duplicate stories | **Fixed at the mechanism level** | The disposition-separator bug is gone; four findings are legitimately open (WEB-024 F002, WEB-027 F001–F003) with none falsely re-supplied. The planner is now also instructed to re-verify that a finding's problem still exists before creating a story. |
| Excessive context consumption and repeated exploration | **Partially** | `core/planning_context.py` compaction is real and measured. Planner passes still reach 652k–794k gross input tokens, and RepoMap ran on 4 of 4 Claude attempts. |

---

## 5. Agent assessment

**Planner (Codex, unpinned model).** Clearest responsibilities, worst
reliability: 60% failure rate, largest context (up to 794,598 gross input
tokens), and it still hand-writes story Markdown through `python -` heredocs
(log 22:32:51) — only the *backlog* goes through `backlog_writer.add_to_do_entries`.
All three failures were schema/contract violations that a complete self-check
would catch for a fraction of the cost (§3.3).

**Architect (Codex, unpinned).** Invoked only while an actionable request exists
— correct and cheap. Not exercised on 2026-10-03; no evidence-based criticism.

**QA (Codex, `gpt-6-luna`, reasoning effort `medium`).** The most interesting
addition and the least proven. Genuine strengths: runs *before* coding; plans
are persisted, validated and protected from the coding agent; `NEEDS_USER`
routes to the standard `UD-*` process instead of letting QA answer product
questions; technical failures are classified separately from product decisions.
Weaknesses: proportionality is prompt-only and demonstrably failed once (§4); the
workspace guard can revert maintainer work (§3.4); 25 tests for a 623-line agent
holding repository-wide write-and-revert authority.

**Coding agent (Claude Opus 5).** Correctly the only implementer, with a sound
contract: the QA plan is injected via `implementation_contract()`, findings are
recorded rather than acted on, and backlog transitions are forbidden. **But
Claude was spent on infrastructure repair twice** — the 22:28 run fixed the
interaction between a harness test and the harness's own publication ordering.
That is the scarcest model in the system spending 756s and 22% of a session on
orchestrator internals rather than application functionality.

**Evaluator (Codex, unpinned).** Performs real independent verification rather
than repeating QA: at 22:13 it read the QA plan, diffed the tree, correctly
identified that the plan's exit-0 requirement was unmet and raised
`qa_review_required`. The problem is the *third* call — QA then re-reviews the
same deviation and approves it, in both rounds.

---

## 6. Recovery assessment

Handled correctly (verified by code inspection):

| Interruption | Behaviour |
|---|---|
| Kill after Claude finishes, before evaluation | `AWAITING_EVALUATION` → evaluator runs; Claude not re-invoked. |
| Kill after evaluation, before push | `AWAITING_CI` → persisted verdict reused **only if** story and result hashes match (`orchestrator.py:1527-1543`); otherwise re-evaluate, never back to Claude. |
| Kill during the conditional QA review | `AWAITING_QA_REVIEW` → saved verdict passed as `evaluation_override`; `not evaluation.get("qa_post_review")` makes the review idempotent. |
| Kill after CI passed, before bookkeeping | `FINALIZING` carries `ci_status`/`ci_sha`; replay is idempotent; no second gate and no second Claude run. |
| Codex capacity exhaustion mid-evaluation or mid-QA | Waits locally; explicitly does not consume a retry budget. |
| Planner crash, protected-file write, or failed validation | Full rollback. Verified: `STORY-WEB-029-...md` is absent from disk. |
| QA interrupted after generating tests | `QA_STATE.json` retains `owned_test_paths`; resume does not duplicate them. |

Where work or state can still be lost or repeated:

1. **Buffered QA logs on hard kill** (§3.5) — confirmed by code.
2. **Maintainer edits during QA** (§3.4) — confirmed by code, untested.
3. **A rolled-back planning pass discards genuine work.** The 22:32 pass
   verified a real mismatch, created `STORY-WEB-029` and dispositioned three
   findings; all were discarded over a derived boolean. Those findings are open
   again, so the next pass will likely repeat the work and risk the same
   rejection.
4. **No cross-process lock** (§3.7).
5. **Non-atomic queue writes** (§3.7).

---

## 7. Resource efficiency

One day (2026-10-03), two small stories delivered:

| Role | Calls | Gross input tokens | Notes |
|---|---|---|---|
| Planner | 5 | **2,277,489** | 3 failed → ~1.4M discarded |
| Evaluator | 5 | 573,737 | 2 were re-runs after CI fixes |
| QA | 5 | 344,092 | 2 preparations + 3 reviews |
| **Codex total** | **15** | **~3,195,000** | |
| Claude | 5 runs | 4,431s (74 min) | ~20% of weekly capacity |

Claude run detail (`Claude run measurement` lines): 752s, 382s, 1737s, 803s,
756s; session usage reached 100% once and 99% once.

Concrete waste:

1. **~1.4M tokens on failed planning**, at least 650k of it avoidable by a
   complete self-check (§3.3).
2. **Planning after every story** regardless of queue depth (§4).
3. **Two Claude runs consumed by harness defects** — the 19:12 run (1737s, 55%
   of a session) and the 22:28 run (756s) both ended in CI failures caused by
   harness publication behaviour, not product code.
4. **Triple adjudication of one deviation.** WEB-027's unmet smoke expectation
   was reasoned about by the evaluator, then QA, then both again after the CI
   fix.
5. **RepoMap on every Claude attempt** (4/4, ~15s and ~2,318 tokens each) with
   no recorded evidence that it changes outcomes.
6. **Two full-repository byte snapshots per QA preparation.**

---

## 8. Test quality

**416 tests across 23 files.** What they genuinely establish: selector
determinism (32), evaluator verdict parsing (30), architect dispatch (42), each
`ATTEMPT_STATE` resume path (26), CI gate behaviour against a throwaway
repository with a local bare remote (31 — `test_ci_verification.py` exercises
the *real* `commit_and_push` rather than mocking it, which is the right call),
and backlog entry/prose rejection (43).

Not verified:

- **Maintainer edit during QA** (§3.4) — the one test that sounds like it covers
  this does not.
- **Test-suite artifact isolation** — no test asserts that running the suite
  leaves `agent/runtime/artifacts/` untouched. Such a test would have caught
  §3.1.
- **Crash during a write** — every recovery test kills *between* operations,
  never mid-`write_text`. No torn-file test exists.
- **Concurrent orchestrators / absence of locking.**
- **`planning_check` ≡ `validate_planning_result`** — no test asserts the
  equivalence its docstring claims, which is why §3.3 survived.
- **QA proportionality** — not testable by construction; it is a judgment the
  prompt requests and nothing enforces.
- **Live-repository invariants are now self-exempting** — `story_awaiting_its_gate()`
  means CI cannot distinguish the publication window from a missed finalization.

Structural concern: three tests in the CI suite assert over the live working
tree, so **the gate's verdict depends on repository bookkeeping state and not
only on code**. That coupling caused §3.2 and should be removed rather than
parameterised.

---

## 9. Restart readiness

**Safe to restart. No completed work would be repeated.** Current state:

```
agent/CURRENT_STORY.md           : empty
ATTEMPT_STATE.json               : absent
BACKLOG ## Active                : empty
validate_backlog_consistency()   : no problems
To Do                            : STORY-WEB-021, STORY-WEB-028, STORY-WEB-020
STORY-WEB-024, STORY-WEB-027     : DONE, published (f2c3670, 675fddb), CI green
Open interventions               : none (UI-006 RESOLVED)
Unresolved follow-up findings     : 4 (WEB-024 F002, WEB-027 F001-F003)
PLANNING_RESULT.json             : FAILED, independent_work_remaining true
```

Exact sequence on restart:

1. `_active_is_executable()` → `False` (empty pointer); no story resumed.
2. `requeue_resolved_interventions()` — no-op; UI-006 already resolved and
   WEB-020 already requeued.
3. Three selectable candidates and a stocked queue would normally skip planning,
   **but** `has_changed_planning_inputs()` will almost certainly fire: the
   working tree has 18 modified files since the last pass and the previous
   result carries `independent_work_remaining: true`. Expect a planning pass
   first, costing 300k–800k input tokens, with a material chance of failing
   again — the four findings that triggered the last failure are still open.
4. Selection then activates **`STORY-WEB-021-ecto-content-test-hooks.md`**.
5. **QA preparation runs for WEB-021** (no plan exists under `agent/qa-plans/`)
   — one Codex call.
6. Claude then implements. Claude was at `weekly_used_percent=20` and
   `session_used_percent=99` at 22:33 on 2026-10-03; the session has since reset.

Three things to know before restarting:

- The 18 uncommitted files include the §3.1–§3.5 fixes. They are **unpublished**,
  so the next story's scoped commit will sweep whichever of them fall inside its
  path scope — partially publishing harness changes under a story's commit
  message, the pattern that produced `3084e18`.
- `SELECTOR_RESULT.json` is stale and names a nonexistent fixture story. It is
  write-only diagnostic output and will not misdirect selection, but it will
  mislead anyone reading it.
- `agent/runtime/artifacts/qa-failures/` holds five fixture records that a human
  triaging a real QA failure would have to disambiguate.

---

## 10. Prioritized recommendations

### P1 — Correctness and cost, low complexity

1. **Close the test-isolation leak** (§3.1). Rebind `config.QA_FAILURES_DIR`,
   `config.SELECTOR_RESULT_FILE` *and* `selector.SELECTOR_RESULT_FILE` in
   `tests/__init__.py`; better, derive artifact paths through a function so one
   reassignment covers all of them. Add a test asserting the suite leaves
   `agent/runtime/artifacts/` byte-identical. Delete the five stale fixture
   records and the stale selector result.
2. **Make `planning_check` genuinely equivalent** (§3.3). Have it call
   `validate_planning_result` with the same pre-snapshots, or delete it — a
   self-check that passes invalid output is worse than none, because the planner
   trusts it. Add a test pinning the equivalence.
3. **Remove live-repository assertions from the CI-run suite** (§3.2). Move
   `QueueConsistencyTest` to a local or pre-commit check and drop the
   `publishing=` exemption; the gate then tests code, and a genuinely missed
   finalization becomes detectable again.

### P2 — Prevent data loss, low complexity

4. **Exclude `agent/logs/` and `agent/runtime/artifacts/` from
   `snapshot_files()`** and remove `defer_log_writes()` (§3.5) — resolves the
   original incident without losing log evidence on a kill.
5. **Never revert a file whose on-disk bytes differ from the pre-QA snapshot**
   unless QA's own write is still present (§3.4). Report the violation and leave
   the file alone. Add the missing pre-snapshot-window test.
6. **Atomic writes for `BACKLOG.md`, story files and `CURRENT_STORY.md`** —
   reuse `record_attempt_state`'s temp-file-then-`replace` pattern.

### P3 — Efficiency, moderate complexity

7. **Gate planning on queue depth, not input change.** Require `not candidates`
   (or a Product Owner / architect inbox change) rather than any planning-input
   delta. Expected saving on the observed day: 3 of 5 planner passes, ~1.4M
   tokens.
8. **Narrow `review_required`** (`orchestrator.py:641-646`). Do not re-run the
   QA review after a CI-fix round when the plan's unmet item is unchanged and
   already adjudicated. Expected saving: ~2 Codex calls per CI-fix cycle.
9. **Pin planner, architect and evaluator models explicitly**, as QA already is.

### P4 — Structural, higher complexity and highest long-term value

10. **Stop spending Claude on harness repair.** Route CI failures whose failing
    suite is `agent runtime` to a human or a cheaper model by default. Claude's
    scarce capacity should implement application functionality.
11. **Reduce pipeline surface.** 3,410 lines with seven stages and four phases is
    at the edge of reviewability. Extracting the completion/CI/finalization state
    machine into its own module with an explicit transition table would make the
    interruption matrix checkable by inspection rather than by 26 scattered
    tests.
12. **State one principle for model-output handling** (§3.6): which fields the
    harness may normalize (derived, scheduler-facing) and which it must reject
    (semantic, vocabulary) — and apply it consistently.

**Candidate for removal:** RepoMap. Enabled on 4 of 4 Claude attempts, ~15s and
~2,318 tokens each, with no recorded evidence of improved outcomes and an
explicit "experimental" framing in the code. Measure it or drop it.

---

## Caveats on this review's limits

- The test suite was not run (§ Scope), so test-quality findings come from
  reading tests rather than observing failures.
- §3.4 and §3.5 are established by code inspection and have not been observed in
  production.
- Part of the audited system is Claude's own prior work (§ Scope); those sections
  are self-assessment, not independent review.
