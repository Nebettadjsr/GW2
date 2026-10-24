# UD-017 — QA clarification for STORY-APP-015

## Status

RESOLVED

## Decision Needed

The story’s product behavior is specified, but the new scoped repository/publication application interfaces are absent. QA cannot complete the required production-path tests without defining and fixing those interfaces in the test contract. This needs resolution before the coding attempt.

## Why This Is Needed

The independent QA review found that implementation requirements are insufficient to define a reliable expected result.

## Context

Story: STORY-APP-015

I prepared production-migration checks for account-key isolation, character-child ownership, and reset markers. The required application-level tests are not ready: the scoped repository and publication interfaces do not exist, and I have not defined test-facing production APIs for the coder to implement. The existing snapshot and roster tests use stand-in or legacy schemas, so they do not establish the story’s scoped production behavior. I cannot mark this QA plan READY without those checks.

## Blocks

Implementation of STORY-APP-015.

## External Input Possibly Required

Product Owner decision.

## User Decision

RESOLVED — Product Owner decision.

The product behavior of STORY-APP-015 is fully specified by the story; there is no open product
question. The scoped repository and publication interfaces are technical design, and QA owns that
design: define the classes, method signatures and test seams in `interface_contract` in line with
`docs/TARGET_ARCHITECTURE.md` and the ADRs, write the tests against them, and report the
pre-implementation run as NOT_RUN or EXPECTED_FAILURE with the missing-symbol evidence. The coder
then implements them verbatim. Do not escalate absent interfaces to the Product Owner.

## Resolution

RESOLVED — QA designs the interfaces itself.
