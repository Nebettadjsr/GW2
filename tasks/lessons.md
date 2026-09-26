# Lessons

Corrections received while working in this repository, recorded as rules so the
same mistake is not repeated. Append one entry per correction; keep entries
short and actionable.

## Answer the question that was asked, then stop

When asked a factual question about the repository, report the finding and
stop. Do not append adjacent concerns, risks, or implications that were not
asked about.

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
