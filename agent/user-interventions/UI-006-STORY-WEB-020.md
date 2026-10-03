# User Intervention

## Status

RESOLVED

## Story

STORY-WEB-020

## Reason

QA preparation failed 2 times; no coding was started. QAPlanError: QA modified forbidden files; changes were restored: agent/logs/2026-10-03.log

## Claude Response

QA could not produce a validated plan. Inspect the QA artifacts and resume this story after resolving the cause.

## User Resolution

The QA file-protection failure was caused by legitimate orchestrator console and daily-log appends occurring while QA's workspace snapshot was checked. Daily log writes are now buffered for the guarded QA execution and flushed after the workspace comparison/restoration completes. Direct QA changes to log files remain forbidden and are still restored/rejected.

## Resolution Notes

Verified in `agent/runtime/support/daily_log.py` and `agent/runtime/qa/qa_agent.py`. Regression tests in `agent/runtime/tests/test_qa_agent.py` confirm legitimate log output is retained without a false protection failure, while a direct QA log edit is rejected and restored without losing buffered output. `python -m unittest agent.runtime.tests.test_qa_agent agent.runtime.tests.test_qa_flow -q` passed (24 tests). WEB-020 has no other unsatisfied dependencies; the existing intervention-requeue mechanism restored it to To Do.
