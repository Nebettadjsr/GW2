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
