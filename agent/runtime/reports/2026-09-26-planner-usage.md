# Planner retry and Codex usage investigation — 2026-09-26

## Evidence and limits

Read-only evidence: `agent/logs/2026-09-26.log`, local Codex rollouts under
`C:/Users/Administrator/.codex/sessions/2026/09/26/`, artifact bytes/modification
times, and scheduler/runner code. No live model was invoked. Token counts are
recorded counters; character counts are not exact token attribution.

Relevant fresh codex_exec sessions (CLI 0.155.1, model gpt-6-astra):
- 12:54:09: `01a0dd59-dc1b-78e1-99a0-4d9d621bf5bd`.
- 12:59:19: `01a0dd5e-979c-7820-9f45-5b3c291f0a5a`.

## Repeated planning: byte-sensitive invalidation

The first pass ended at 12:58:12 with NEEDS_USER, independent_work_remaining=false,
UD-011 and DOM-022. The next started at 12:59:18. No intervening architect run or
story completion appears in the operational log.

Request-008's modification time is 12:59:12.748, between those passes. Reapplying
the first session's recorded Request-008 patch to its supplied original request
reproduces the current/second-pass request after newline normalization (and
stripping the prompt boundary's trailing newline). There is no new request wording
or answer. The current request is CRLF. The raw-byte fingerprint considered an
encoding/line-ending rewrite new planning input. Existing code already captured
post-pass state; the newly created UD/story alone was not the missing gate.

Evidence limitation: schema 1 retained only the latest aggregate digest, and the
second pass overwrote the first cache. Its exact old hash/byte sequence and the
process that saved Request-008 cannot be recovered from that cache. The record
supports formatting-only invalidation, not a claim about a particular editor.
Schema 2 records per-file hashes and logs triggering paths for future audits.

## Recorded token accounting

Each row is one independent planner invocation. Requests are distinct recorded
usage updates within its single agent turn. Cached input is included in gross
input. Their difference is arithmetic uncached input, not a billing/quota estimate.

| Planner start | Requests | Gross input | Cached input | Uncached input | Output |
|---|---:|---:|---:|---:|---:|
| 03:50:30 | 7 | 497,327 | 419,968 | 77,359 | 1,955 |
| 04:52:59 | 9 | 637,148 | 567,424 | 69,724 | 2,897 |
| 09:57:28 | 8 | 672,618 | 578,048 | 94,570 | 8,716 |
| 10:02:59 | 6 | 448,730 | 367,872 | 80,858 | 3,942 |
| 12:54:09 | 9 | 727,623 | 637,312 | 90,311 | 6,894 |
| 12:59:19 | 5 | 365,608 | 297,984 | 67,624 | 2,171 |

The 727,623-input run started at 65,667 input tokens in its first model request
and ended at 89,045 in its last. The repeat started at 66,388 and ended at 79,260.
Displayed totals accumulate repeated context across these requests. The runner
previously omitted cached_input_tokens from its display.

### Actual context contributors

The supplied task prompts were 220,114 and 223,554 characters. First-pass sections
(including headings/separators):

| Section | Characters |
|---|---:|
| Full BACKLOG | 139,095 |
| Planner instructions | 29,867 |
| UD coverage, all statuses | 15,473 |
| Resolved PO receipts | 10,751 |
| Open/NEEDS_USER PO inbox | 6,382 |
| Architect request coverage | 4,468 |
| Current roadmap phase | 1,920 |

BACKLOG alone is about 63% of the supplied prompt. Its Done section is 80,832
characters and Archived is 55,341: historical completion narrative dominates it.
Both are resent at the start of every fresh planning session.

The transcript also records 21,686 characters of base instructions, approximately
6,625 of injected developer skill/team instructions, and approximately 9,000 of
injected user/plugin/AGENTS/environment context. The planner contract is supplied
in addition to overlapping harness workflow directions. Tool schemas and service
framing also contribute; saved transcripts do not establish exact per-source token
attribution.

Recorded tool-return strings add about 71,950 characters across eight responses
in the first run and 53,350 across four in the second. Commands return targeted
domain/architecture sections, heading searches, story files and checks of new
artifacts. The second reads DOM-022 in a multi-story dump and again for its tail.
These latest passes mainly use section reads, not repeated full dumps of every
domain document. Full historical backlog injection is the larger confirmed source.

Disk reads alone do not consume model input; returned command output does.
Suppressing aggregated_output in the terminal does not remove it from Codex's
context. Tool results, generated edits and agent messages remain in the same run's
growing context for subsequent requests.

### Sessions, history and result injection

The runner uses `codex exec --json --sandbox workspace-write -`, without resume,
--last, a prior session ID or conversation-history loading. These sessions have
distinct IDs, one initial task each, no fork origin and no compaction event.
Persistence saves transcripts locally; it does not resume the previous planner
conversation. A cached prefix does not establish shared conversation history.

Neither task prompt embeds prior PLANNING_RESULT.json, logs or raw conversation
history. The latest transcripts show no read of a prior planner result. Planning
history is injected indirectly through BACKLOG, resolved PO receipts, UDs and AR
decisions. Useful evidence is mixed with a large volume of historical narrative.

### Architect evidence

The same runner handled these September 25 architect invocations:
- 03:26:56 (`01a0d62c-3445-7163-87ca-0233b0da8981`): 385,086 input, 336,640 cached,
  eight requests, 45,921-character initial role prompt.
- 20:02:52 (`01a0d9bc-00fe-7723-88f6-23914d7cf9ef`): 562,275 input, 488,064 cached,
  ten requests, 45,126-character initial role prompt.

The first reads TARGET_ARCHITECTURE and CURRENT_ARCHITECTURE in full, then returns
to sections. The latter rereads ARCHITECT_INSTRUCTIONS despite its inclusion in
the prompt, reads architecture/source sections, and performs web research. These
are concrete additional context sources; their necessity must be assessed against
the dispatched question, not removed indiscriminately.

## Implemented fixes

- Persist normalized post-pass per-file hashes, aggregate digest, final remaining-
  work flag and paths changed by that planner pass.
- Ignore UTF-8 BOM and line-ending conversions; preserve all other text.
- Exclude own result/cache/logs, tests and runner display code from triggers;
  retain planning/validation contract changes and implementation-result evidence.
- Hold unchanged no-work states across restarts. Real input changes and validated
  independent_work_remaining=true batches can advance.
- Do not clear a hold merely because an architect function returned.
- Display both roles' pre-run quota from the existing RPC, terminal-only. Keep
  wait heartbeats local, expose cached input, and label architect events accurately.

## Recommended reductions (not implemented here)

Recommendations 1-4 were implemented afterwards in
`agent/runtime/core/planning_context.py` and the two prompt builders; see
"Supplied context is indexed, not narrated" in `agent/runtime/README.md` for
what each one became and for the measured prompt-character result. The
measurements in this report remain the pre-change baseline.

1. Replace full historical BACKLOG narrative with a deterministic index of IDs,
   filenames, statuses, milestones and dependencies. Preserve active/ready/blocked
   contracts and relevant phase completion/exit evidence; retain source paths for
   targeted reads and complete IDs for deduplication.
2. Compact resolved UD/PO/AR coverage into topic/decision/reference entries; expand
   the authoritative answer or receipt when relevant to the current area.
3. Consolidate duplicate harness/role instructions. Resolve AGENTS routing versus
   already-supplied-contract guidance so architect runs do not reread the contract.
4. Batch independent targeted reads, retain results within a run and bound output.
   Every extra model request resends the prefix and prior results. Do not combine
   dependent reads or truncate needed evidence blindly.
5. Measure prompt sections, gross/cached input and useful outcomes before changing
   models/reasoning. No model downgrade, hard token cap, history resumption, prompt
   compression or source restriction was applied speculatively.

## Capacity behavior

Both scheduler role methods already gated execution through CapacityProbe. It
calls codex app-server initialize/initialized then account/rateLimits/read, with
no thread/start or turn/start. Both windows must be below 100%; explicit exhaustion,
invalid/missing quota or failures block invocation. The cooldown remembers
unavailable capacity. Positive checks previously discarded details, returning only
a boolean. During an active Claude wait, outer-cycle availability logging is not
repeated, and no pre-role quota display existed.

The gate now retains its reading for one terminal-only pre-role line. Display
causes no extra model or quota call. Absolute remaining-token counts are not supplied
by this endpoint; a positive check cannot reserve capacity for a whole future run.

Official references:
- [App Server rate limits](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt)
  defines percentage windows and account/rateLimits/read.
- [Non-interactive Codex](https://learn.chatgpt.com/docs/non-interactive-mode)
  documents cached input and explicit session resumption.
