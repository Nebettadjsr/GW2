# Lessons for application work

The complete pre-migration lessons log is preserved at the `pre-github-flow`
tag. These are the lessons that still apply to GW2 product changes and tests.

## Verify the command actually ran

Use the repository Maven wrapper. Confirm Maven reports tests run or `BUILD
SUCCESS`; a shell pipeline's exit code can hide a missing command. For any
check, name the commit or working tree revision whose behavior it exercised.

## Prove the local server is yours

Before browser smoke tests, verify the server answering the chosen address and
port is the one the test started. Use `127.0.0.1` consistently so an IPv6
`localhost` listener cannot shadow an IPv4 stub. After stopping a wrapper,
probe the port and stop only the child processes started by that test.

## Keep test doubles aligned with callers

When a production caller moves to a new overload, search for test doubles
overriding the old signature. A stale override may compile but never run,
making the test fail for an unrelated-looking reason.

## Assert the right browser element

Check `data-test` hooks against the component. Match a node through its own
label element; a parent element also contains every descendant's text. Pair a
positive assertion with a sibling or other negative case when that distinction
matters.

## Revisit assertions of absence

When a feature introduces new requests, elements, or state, search for old
tests asserting their absence. Replace overly broad exact request lists with
filters for the behavior the test actually needs to constrain.

## Handle optional browser waits

A Playwright `waitForResponse` on a path that may not occur can reject after
the browser closes and hide the original failure. Handle the pending promise
when created and report the caught assertion error before cleanup.

## Exercise the shipped path and boundary

A passing stand-in does not prove the real application route. For timing or
security checks, sample at the boundary after every required condition is
satisfied. Report the actual output or behavior the criterion asks for, along
with measurements that support it.
