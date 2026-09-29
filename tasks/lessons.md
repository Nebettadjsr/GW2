# Lessons

Corrections received while working in this repository, recorded as rules so the
same mistake is not repeated. Append one entry per correction; keep entries
short and actionable.

## Answer the question that was asked, then stop

When asked a factual question about the repository, report the finding and
stop. Do not append adjacent concerns, risks, or implications that were not
asked about.

## Restart the runtime before trusting integration evidence

After substantial backend/frontend, DTO, route or configuration changes, build the current
source, stop stale backend and frontend processes, start the current runtime, and verify the
expected process owns the ports before browser or integration checks. A browser refresh does
not update an already-running Java process, and an unidentified listener can make an old
contract look like a current defect. Trivial edits that cannot affect served behavior do not
require a restart.

**Why:** Asked which model the orchestrator launches, the answer volunteered an
unrelated warning about the interactive chat model affecting the RepoMap
token-usage comparison. The chat model and the orchestrator-launched model are
independent; the aside was both unsolicited and wrong.

**How to apply:** Separate the two Claude contexts explicitly. The model running
this conversation has no bearing on what `agent/runtime` spawns, on measurements
taken by the orchestrator, or on the RepoMap experiment. If a genuine related
risk exists, offer it in one line, not as a recommendation section.

## Build with `./mvnw`, and never read exit code 0 alone as "tests passed"

`mvn` is not on the Bash tool's PATH in this environment. Piping it through
`| tail`/`| grep` makes the pipeline's exit status 0, so a run that never
executed anything looks like a successful build.

**Why:** A `mvn -q test-compile 2>&1 | tail -30` call reported exit 0 and was
briefly taken as a passing compile; the real output was
`mvn: command not found`. A test claim built on that would have been false.

**How to apply:** Use `./mvnw` (the wrapper in the repo root). Confirm a run by
the presence of real evidence — a `Tests run: N, Failures: 0` line or
`BUILD SUCCESS` — not by exit status, and never report a suite as passing
without that line.

## Do not invent model identifiers

If a requested model version does not exist, say so and use the correct nearest
identifier rather than constructing a plausible-looking one.

**Why:** A request for "opus 5.5" named a version that does not exist. Writing
`claude-opus-5-5` into config would have produced an orchestrator that fails at
launch, long after the mistake was made.

**How to apply:** Model ids are exact strings, never assembled from a version
number the user said aloud. Verify against the known model list, name the
substitution made, and keep the value in one named constant so it can be
corrected in a single edit.

## A scheduling gate must never fail closed and silent

When a queue decides whether work gets dispatched, an input it cannot parse
must be reported loudly, not skipped. Pair every "never guess" rule with a
"always say so" rule.

**Why:** `agent/architect-requests/` only recognized `OPEN`/`NEEDS_USER`/
`RESOLVED`. A request re-queued by hand as `TODO` — the convention every story
file uses — became invisible to `get_actionable_requests()`, so the architect
was never dispatched while the planner kept reporting itself blocked by that
same question. Correct-but-silent looks exactly like a broken orchestrator.

**How to apply:** Accept the obvious synonyms a human or model will actually
write, and for anything still unrecognized emit a concrete log line naming the
file and the reason (`undispatchable_requests()` /
`_report_undispatchable_architect_requests()`). Never leave "nothing happened"
as the only externally visible symptom of malformed workflow state.

## Verify a test guard applied before running the suite

Tests in this repository can reach live model runners. After adding or editing
a patch that prevents that, confirm it is in place in every affected fixture
before running anything.

**Why:** A scripted multi-line replacement silently failed on two fixtures that
call `orchestrator.main()`. The suite then ran the real Codex architect twice
against the live repository, spending subscription capacity and writing
workflow files.

**How to apply:** `agent/runtime/tests/__init__.py` now makes `find_claude`,
`find_codex` and `run_codex` raise for the whole package, so a missing fixture
patch fails the test instead of spending capacity. Keep that guard intact; when
a test needs a real runner path, patch it locally in that test.

## A local check must prove it is talking to its own server, not just to a port

Before driving a browser at `localhost:<port>`, establish that the process answering there is the
one the check started. A bind that succeeded is not that proof.

**Why:** `npm run smoke:sync` binds a stub origin on 5174 and promises "no real synchronization
ran". A leftover Vite dev server was listening on `[::1]:5174` only, so the stub's IPv4 bind
succeeded while Chrome resolved `localhost` to `::1` and loaded the dev server instead — which
proxies `/api` to the real backend. The check clicked all four synchronization triggers against
the user's live database and the GW2 API, and reported only a step timeout.

**How to apply:** Bind and navigate to `127.0.0.1` explicitly, never the name `localhost`, so one
address family cannot shadow the other; fail the run on a `listen` error instead of ignoring it;
and assert the stub actually served the page (`servedByStub > 0`) before touching any control. Any
check that claims "nothing real was touched" needs a positive identity assertion, not the absence
of an error. Before starting a backend for evidence, check what is already listening — an
interrupted attempt leaves processes behind.

## Killing a wrapper is not killing the server, and a silent tool is not evidence

Stopping a background `npm run …`/`npx …` task kills the wrapper; the `node`/`vite` process it
spawned keeps listening. Confirm a port is released by probing it, not by the stop command's success.

**Why:** After the live checks for `STORY-WEB-004`, `TaskStop` reported the background `npx vite
--port 5191` task stopped — but `curl` still got HTTP 200 from it. `netstat -ano` was then used to
find the owner and printed **no** matching listener, which read as "already gone". `netstat` returns
**zero** LISTENING rows at all in this sandbox, so its empty output was a broken tool, not a clean
port. Trusting it would have left a dev server proxying to the live backend running indefinitely.

**How to apply:** After stopping a server, `curl` the port and require a connection failure before
calling it released. Never infer "nothing is listening" from a diagnostic that printed nothing —
sanity-check the tool first (here, the total LISTENING count). To find a process this sandbox can
actually see, match its command line via
`/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process …"`,
then `/c/Windows/System32/taskkill.exe /PID <pid> /T /F` with `MSYS_NO_PATHCONV=1` so Git Bash does
not rewrite `/PID` into a path. **`WMIC` returns nothing at all here** — like `netstat`, it is a
second broken diagnostic, and `taskkill`/`powershell.exe` are not on the Bash tool's PATH, so both
need their absolute `/c/Windows/System32/…` path. Kill only the PIDs whose command line you recognize as yours — this repository normally has
several pre-existing `npm run dev` servers that must survive.

## A new overload silently orphans the test double that overrides the old one

Adding a parameter by introducing an overload keeps production callers compiling, but a test stub
that overrides the *old* signature still compiles too — and is never called again. Its captured
fields stay null and the failure surfaces as an unrelated `NullPointerException`.

**Why:** `STORY-DOM-021` added a ninth `MaterialTradeability` parameter to
`CraftingPlanner.evaluateAllCoordinated` as an overload. `CraftingProfitService` moved to the new
entry point; `CraftingProfitServiceTest.RecordingCraftingPlanner` still overrode the eight-argument
one, so five tests failed with `"planner.capturedRoster" is null` — nothing in the message named the
overload.

**How to apply:** after adding an overload that existing callers are migrated to, `grep` for
subclasses and `@Override`s of the old signature and move each one, or make the old signature
`final`/delegating so an orphaned override cannot compile. When an existing test suddenly NPEs on a
field a stub was supposed to fill, check which overload the production code now calls before
debugging the test's own logic.

## Verify a browser/DOM test hook against the component, not from memory

A `data-test` name that does not exist makes `findAll` return nothing, and `expect(undefined?.…)`
can pass or fail for the wrong reason. Matching an element by `text()` is worse: a parent contains
every descendant's text, so `find(el => el.text().includes(name))` returns the ancestor.

**Why:** the same story's new test looked for `[data-test="resolution-node"]` — the real hook is
`tree-node` — and then found the tree *root* when matching "Account Bound Scrap", so it asserted the
root's own blocked reason and would have passed with the child unmarked.

**How to apply:** grep the component for the hook before asserting on it, and match a node by its own
label element (`el.find('[data-test="node-name"]').text()`), not by the subtree's text. Pair every
"this node is marked" assertion with a sibling that must *not* be.

## A check that asserts an absence expires the day the feature arrives

When a story adds the thing an existing check asserted was missing, that check fails for the right
reason in the wrong place. Search for assertions of absence before running anything, and convert them
into "only this, and here is the count" rather than deleting them.

**Why:** `STORY-WEB-010` made the browser render item images. `account-browser-smoke.mjs` had already
been updated for the new `<img>` assertions, but a *different* step still demanded a bank reload produce
exactly `['/api/account/bank']` — and it now legitimately produces image requests too, so the first real
run against live data failed after three steps. The neighbouring step had been fixed; this one had not
been looked for.

**How to apply:** grep the checks for the old negative claim (`no <img>`, `imageCount > 0`, "requests
nothing") and for exact-equality assertions on request lists, and rewrite each one as a filter plus a
reported count. Two more from the same session: a comment naming a script (`profit-page-perf.mjs`) does
not create it — verify a referenced harness exists before trusting an acceptance criterion is covered;
and `page.reload()` does not revalidate a *fresh* subresource, so asserting 304s after a reload asserts
on an empty list. Use the browser's own `fetch(url, {cache: 'no-cache'})` when the point is the stored
validator.

## An unawaited `waitForResponse` deletes the error message you actually need

A Playwright wait created on a path the run might not take is an unhandled rejection the moment the
browser closes. Node reports *that* and exits, so the real assertion failure — which already
triggered the `finally` that closed the browser — is never printed.

**Why:** `discovery-live-smoke.mjs` creates `nextResponse(discoveryPath)` and
`nextResponse(detailPath)` before a reload, but skips the second when the reloaded calculation no
longer offers the selected recipe. The first run failed with
`page.waitForResponse: Target page, context or browser has been closed` pointing at the *setup* line,
which says nothing at all. The actual cause was a wrong assumption three steps earlier — the check
read the page before the selector had answered — and it took a second run to see it.

**How to apply:** mark every created wait handled at creation (`pending.catch(() => undefined)`;
this leaves `await pending` still throwing the original error) and wrap the script body in
`try/catch/finally` that prints the caught error itself rather than leaving the process to report
whichever promise rejected last. A script whose failure mode is "an error from a line that cannot
fail" is unusable as evidence.

## Patching one module's paths leaves its siblings reading the real repository

A test that redirects `orchestrator.BACKLOG_FILE`/`STORIES_DIR` to a temp root does **not**
redirect `story_state`'s copies of those names. Anything the code under test reaches through the
sibling module still sees the live repo, so the test's verdict moves with the repository's own
state — and a branch that is really dead code can look covered.

**Why:** `PlanningHoldTest.setUp` patches `loop.*` only, so `plan_if_useful`'s
`should_trigger_planning(len(get_selectable_story_candidates()))` read the actual backlog.
`test_true_flag_continues_bounded_pass_then_false_holds` had been passing purely because the queue
was short; the day the backlog held three ready stories against a threshold of 2 it failed in CI,
on a commit that changed only frontend files. The genuine defect it had been hiding: the bounded
follow-up branch for a held `independent_work_remaining: True` sat *after* the
`queue_low or changed_since_hold` gate, so from a held state it was unreachable unless the queue
happened to be low.

**How to apply:** patch every module that binds the name (`for module in (planner, loop, uds, ars,
story_state, …)`, as `test_milestone_planning` does), or patch the *function* the code calls
(`patch.object(loop, "get_selectable_story_candidates", return_value=[…])`) so the input is stated
by the test. Prefer the variant that keeps the assertion dependent on the behavior under test —
a stocked queue here, not an empty one that would have passed with the bug still in place. When a
test in a temp-root fixture fails only after an unrelated commit, look for the input it never
actually controlled before suspecting the commit.

## A model-written status field is not a record of the harness's own progress

When a pipeline has steps after the model stops — evaluate, commit, push,
verify — none of them may be inferred from a field the model writes. Record the
harness's own position durably, next to the artifacts, and resume from that.

**Why:** `execute_active_story()` returned `COMPLETE` for any active story whose
own `## Status` said `DONE`, and `_active_is_executable()` classified the same
story as not executable. On 27.09.2026 Claude fixed a CI failure for
`STORY-WEB-015` and wrote `DONE`; Hermes was then unreachable and the evaluation
loop — unbounded, unlike every other escalation in the orchestrator — spun from
21:02 until the run was interrupted at 21:50. The next morning the restarted
orchestrator read `DONE`, skipped the story, selected `STORY-SYNC-004`, and left
the finished fix unevaluated, uncommitted and unpushed, where the next story's
`git add --all` would have absorbed it.

**How to apply:** `artifacts/ATTEMPT_STATE.json` names the outstanding step
(`AWAITING_EVALUATION` / `AWAITING_CI`) and carries the retry budgets, so a
restart resumes the missing step instead of re-invoking Claude or skipping the
story; every terminal outcome and every new attempt clears it. Ask the
repository, not a status label, whether work was published — a commit named
`implemented <STORY-ID>` plus something still outstanding — and require both
halves, since "tree is dirty" is true seconds after every commit
(`agent/logs/<date>.log` is tracked and appended to continuously). When a
retry loop exists because propagating the error "would re-invoke the expensive
model", fix the state instead of making the loop infinite.

## A verdict that changes nothing re-runs forever

Every branch that ends an attempt must leave the workflow in a state the next
cycle reads differently. A `return` that records nothing is an infinite loop
with extra steps.

**Why:** the evaluator's `BLOCKED` verdict just did `return "BLOCKED"` — no
`set_story_blocked`, no backlog move, unlike the neighbouring `NEEDS_USER`
path. The story kept whatever Status Claude wrote, its bullet stayed under
`## Active`, `CURRENT_STORY.md` still pointed at it, so the next cycle
re-invoked Claude on the same story with the same prompt, against no budget
(`BLOCKED` consumes neither `MAX_RETRIES_PER_STORY` nor
`MAX_CI_FIX_ATTEMPTS`) and with no escalation.

**How to apply:** for each terminal branch, state which file the next cycle
will read differently, and assert it in a test. The same rule caught the
evaluator-`RETRY` path leaving `Status: DONE` in place after the verdict
rejected that claim — which `normalize_evaluation()` would later have read as
grounds to turn an empty RETRY into COMPLETE.

## Two parsers for one format means the writers only learned one

When a format grows a second shape, every reader *and every writer* has to
learn it. Grep for the old shape's literal, not just for the parse function.

**Why:** `parse_backlog_section()` understood both the legacy `` `file.md` ``
bullet and the compact `STORY-ID | file.md | STATUS | summary` row, but
`move_backlog_entry_to_active()` and `_pull_bullet_from_backlog_section()`
matched only `f"`{filename}`" in line`. So activating a compact-row story could
not pull its row out of `## To Do`: it synthesized a bare bullet under
`## Active` and left the original in place. `STORY-SYNC-004` was listed under
both headings at once, and blocking such a story left a stale To Do row behind.

**How to apply:** one shared `bullet_names_story()` predicate, used by every
mover. And note the detection existed the whole time —
`validate_backlog_consistency()` reports exactly this — but no running code ever
called it, only a test. A written-and-never-called consistency check is not a
safety net; `_report_backlog_inconsistencies()` now logs each distinct problem
once per process, loudly and without gating anything.

## A test guard has to cover the committed files too, not only the models

`tests/__init__.py` stopped tests spawning models, pushing and calling the
GitHub API, but not writing to `agent/logs/` — a committed historical record.

**Why:** only `start_console_logging()` was kept out of the tests' reach;
`log_line()` was not. `agent/logs/2026-09-27.log` therefore carries a block of
invented lines a test run wrote *in the middle of the real incident above*
("Evaluator timeout … attempt 1/2" against `EVALUATION_ATTEMPTS = 3`, "Planning
resumed for changed inputs", "Decision: wait"), which is exactly the noise that
makes an incident log unusable as evidence.

**How to apply:** guard the single writer both entry points share
(`daily_log._write`), and drop writes only while `LOGS_DIR` still points at the
committed directory — so `test_daily_log.py`, which redirects it to a temporary
one, keeps exercising the real implementation.

The same guard has to cover live runtime state, not only committed files. Adding
`clear_attempt_state()` to every terminal path of `execute_active_story()` meant
the existing suites — which drive those paths against a temp repository but
resolve `ATTEMPT_STATE_FILE` through `support/config.py` — silently deleted the
real `agent/runtime/artifacts/ATTEMPT_STATE.json`, i.e. the live position of an
actual story attempt. "It lives in the gitignored artifacts directory" is not
the test: the test is whether the file can be regenerated. Before adding a
delete to a production path, ask which fixtures already reach it, and redirect
the path at package level (`tests/__init__.py`) rather than trusting each
fixture to patch it.

## An upsert that creates a full row must also complete a half-empty one

When a write creates rows with a full canonical field set but its `ON CONFLICT` branch touches one
column, the conflicting row stays incomplete forever. Decide per field whether "existing" means
"populated"; only a populated value is worth preserving, a NULL is a gap the same response can close.

**Why:** `STORY-SYNC-004` made `IconSync`'s upsert create missing `items` rows with
`name`/`type`/`rarity`/`vendor_value`/`icon_url`, but `DO UPDATE SET icon_url = …` only. A row that
already existed with those fields NULL kept them NULL even though upstream had just returned them, so
the story's goal — complete metadata coverage — was unmet for exactly the case the evaluator checked.
Its test asserted the *preservation* half ("populated fields unchanged") and never the *completion*
half, so nothing failed.

**How to apply:** write the conflict branch as `COALESCE(<table>.<col>, EXCLUDED.<col>)` per field
(stored side wins) and widen the statement's `WHERE` so the write also fires when a stored field is
NULL and the response can fill it — otherwise the filled values are computed and then discarded. Pair
every "this value survived" assertion with a "that missing value got filled" assertion on the same
row, and confirm the new test fails against the old statement before believing it covers anything.

## The prompt outranks the contract it embeds — keep both in step

When a role's behavior is set by a Markdown contract *and* by the harness
prompt that wraps it, the prompt's imperative wording wins. Changing the
contract alone does not change behavior.

**Why:** `agent/ARCHITECT_INSTRUCTIONS.md` was rewritten so the architect
decides technology choices itself, but `build_architect_prompt()` still listed
"a Class C decision with materially different viable alternatives ... including
any technology still marked TBD" as a NEEDS_USER outcome. The architect
escalated and said so in its own reasoning: "this invocation's outcome rules
require escalation".

**How to apply:** After any change to a role contract, re-read the prompt
builder that embeds it (`core/architect.py`, `core/project_planner.py`) and
reconcile the outcome rules, then assert the key wording in a test so the two
cannot drift apart silently.

## Prove a negative branch from a throwaway copy, never by editing the file you ship

A control that works by temporarily breaking the checked-in script depends on a
revert that nothing verifies. Run the control from a copy instead, and state in
the result which file the passing run was made on.

**Why:** `STORY-WEB-019`'s first attempt proved both halves of its new coverage
guard by editing `AREAS` in `frontend/scripts/layout-browser-smoke.mjs` — and the
`{ id: 'nowhere' }` entry was committed, while the story text asserted "the final
file contains neither". `npm run smoke:layout` then failed at step 1 on the very
tree the story claimed a 13-step pass for, so the leak also destroyed the
evidence for every criterion after it.

**How to apply:** generate the broken variants next to the script
(`scripts/.tmp-control-*.mjs`), run those, delete them, and confirm with
`git status` that nothing untracked remains. Never write a result sentence about
a revert without a `git diff` in the same breath — and when re-verifying a story
whose Result already reads DONE, check the tree before the prose: a status field
and a written-up result are claims, not evidence.

## "My story changed no source file" is a claim about the story, not about the commit

The harness commits the whole tree. Any maintainer edit sitting uncommitted when a
story finishes ships *inside that story's commit* and is gated by CI as if the
story had written it. Before deciding which local tests a story needs, ask
`git status` what else is in the tree — not what the story touched.

**Why:** `STORY-WEB-019` changed only `layout-browser-smoke.mjs` and concluded
"no Vitest, Java or TestFX suite was run, because no source file changed". The
maintainer's restructure of `EctoSalvageScreen.vue` was in the working tree at
the time; commit `41a7a6a` carried both, and CI failed with six Vitest failures
in files the story never opened. The story text even *named* that concurrent edit
and still did not run the suite covering it.

**How to apply:** when a `git status` at session start shows modified product
files you did not write, run the narrow suites that cover them before declaring
done, and say in the Result which of them the commit will carry. When a CI report
names failures in files the story never touched, diff the commit first
(`git show <sha> --stat`) — the cause is usually a swept-in edit, and it is
maintainer work to integrate, never to revert.

Retarget such tests, don't rewrite their claims: a restructure that moves a value
from `.result-conclusion` into a labelled `.result-summary__item` leaves every
asserted number identical, so the fix is which region is queried. Prefer the
narrowest container that still names the value (a `summaryItem(open, 'Effective
cost')` helper over the enclosing block), so the next restyling fails by name
instead of passing on a substring found somewhere else.

And re-run right before reporting, not only right after editing: on this same
story the maintainer reworded those sentences again ("Crystalline Dust" → "Dust")
*between* the green run and the write-up, turning two passing assertions red. A
`git status` at the end that lists a product file you did not touch means the
evidence above it is older than the tree.

The second half of the same story: a fixture is only as current as the screen it
answers. Its `ecto` entry waited on `[data-test="ecto-scenario-table"]` and
stubbed `/api/ecto/salvage`, both of which the maintainer's rewrite had removed —
the page's own unit test asserts that route is never called. Before writing a
stub answer, grep the component for the hook and the route it actually loads, and
prefer a ready selector that only appears once the data-driven region rendered,
so a stale fixture fails the wait instead of measuring a degraded page.
