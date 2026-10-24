# User Intervention

## Status

RESOLVED

## Story

STORY-API-010

## Reason

Application tests still need changes after 2 QA correction rounds.

## Claude Response

# STORY-API-010 — Establish validated account sessions and access context

## Outcome

Implemented. Transient GW2 identity proof, bounded process-local sessions, the trusted
request-context boundary and centralized grant/keyed-operation checking are in place, wired, and
verified end-to-end over real HTTP against the offline fixture adapter. Enforcement for the existing
account routes stays default-off, which is the integration boundary this story deliberately does not
cross.

**This pass resumed an interrupted attempt.** The previous invocation ran out of capacity at 19:56
with the `application.access`, `infra.access` and `web.access` sources written but never compiled,
wired or executed, and with `CLAUDE_RESULT.md` still holding STORY-INFRA-004's result. Nothing
already finished was redone; the gap was verification, and running it found two real defects that
static review had not:

1. **The application could not start at all.** `AccountAccessWebConfig` injected a
   `com.fasterxml.jackson.databind.ObjectMapper` bean. Spring Boot 4.1.1 auto-configures **Jackson 3**
   (`tools.jackson.databind`), and this project uses Jackson 2 only for its own GW2 parsing, so no
   such bean exists: startup failed with `APPLICATION FAILED TO START … required a bean of type
   'com.fasterxml.jackson.databind.ObjectMapper' that could not be found`. The guard now takes the
   application's own auto-configured mapper, so a body it writes is shaped exactly like a
   controller's.
2. **The caller's `accountId` assertion was unreadable.** The interceptor read it from
   `getQueryString()` only, to avoid consuming a form-encoded body. That misses a parameter supplied
   any other way — including `MockMvc`'s `.param()`, which the QA suite uses and which populates
   parameters without a query string (verified in
   `AbstractMockHttpServletRequestBuilder.buildRequest`: only `queryParam` calls `setQueryString`).
   The query string is still read first; the parsed-parameter fallback is skipped for a form-encoded
   request, where asserting nothing is the safe outcome.

**The QA contract suite cannot compile in this environment** — one import that exists in no
artifact — so no automated test ran, mine or anyone's. That is reported under
*Interface Deviations* for QA, and the end-to-end verification below stands in its place. It is
evidence of the production behavior, not a substitute for the suite.

## What changed

Beyond the two fixes above, this pass added no new production behavior; the access sources written by
the interrupted attempt are unchanged apart from those edits.

**`application.access`** (no framework, no HTTP, no SQL)

- `AccountProofPort` / `AccountProof` — one transient key validated upstream, reduced to the account
  GUID plus the key's effective grants, URL restriction and expiry. Three deliberately distinct
  failures, because the boundary answers them oppositely: `InvalidCredentialException` (definitive,
  revokes), `ProofUnavailableException` (retryable, revokes nothing, clamps its own retry guidance),
  `MalformedProofException` (fails closed).
- `AccountSessions` — stores only the SHA-256 verifier of a 256-bit `SecureRandom` identifier, never
  the identifier and never the GW2 key. Expiry is computed once at proof as `min(now + 15m,
  credential expiry)` and only ever read; there is no touch and no sliding window. A
  `LinkedHashMap` bounded by `removeEldestEntry` evicts the eldest proof past capacity.
- `AccountContext` — the immutable resolved scope. `permits` checks grants *and* endpoint
  restrictions; `assertsSameAccount` makes a caller-supplied id agree-or-reject, never select.
- `AccountOperation` — per account source, the grants and upstream endpoints that source consumes.
- `AccountAccessService` — `establish`, `renew`, `resolve`, `logout`, `authorizeKeyedOperation`.
  Renewal issues a *new* session rather than extending the presented one, so the absolute lease can
  never be lengthened.

**`infra.access`** — `Gw2AccountProofAdapter` calls `/v2/account` and `/v2/tokeninfo` with the
caller's key (not `api.Gw2ApiClient`, which authenticates with the locally configured key and
collapses every non-200 into one exception); redirects are refused so the header cannot follow one to
another host. `Gw2ProofResponses` holds the shape/GUID/permission/expiry validation, shared with
`FixtureAccountProofAdapter` so the offline path cannot be more forgiving than the live one.

**`web.access`** — `AccountAccessHttp` (cookie shape, origin/CSRF, header names, all applied
identically by the routes and the guard), `AccountAccessApiController`
(`POST /api/session`, `/renew`, `/logout`), `AccountAccessInterceptor`, `RequestAccountContext`,
`AccountAccessWebConfig` (highest precedence, so the guard precedes the freshness interceptor).

**`web.dto.SessionAccessResponse`** — `accessContext`, `expiresAt`, `grants`. No GUID, no key.

**Wiring** (`Gw2ApiApplication`, `application.properties`) — one shared `AccountSessions`; the live
proof adapter by default; `gw2.account-access.enforce` (false), `.session-capacity` (500),
`.allowed-origins` (empty), `.test-fixtures` (false).

**`pom.xml`** — `spring-boot-webmvc-test` (test scope), which is where Spring Boot 4 moved MockMvc
auto-configuration.

**Docs** — `docs/CURRENT_ARCHITECTURE.md` gains "Validated account access" under section 7 (proof and
identity, sessions, transport contract, request context, grants and keyed operations, and the
integration boundary), and its section 7 claim that "the HTTP API has no application authentication"
is corrected, since that absence no longer holds. Section 8 names the new properties and section 10
the packages checked.

## Tests run

### Build

```
JAVA_HOME=…/openjdk-25.0.1 ./mvnw -o compile -Djavafx.platform=win
[INFO] BUILD SUCCESS
```

```
JAVA_HOME=…/openjdk-25.0.1 ./mvnw -o -q test-compile -Djavafx.platform=win
[ERROR] /…/src/test/java/web/AccountAccessContractTest.java:[5,63] Package
        org.springframework.boot.test.autoconfigure.web.servlet ist nicht vorhanden
[ERROR] /…/src/test/java/web/AccountAccessContractTest.java:[29,2] Symbol nicht gefunden
[ERROR]   Symbol: Klasse AutoConfigureMockMvc
```

**No automated test could be run.** The test module does not compile, so this blocks every existing
suite, not only the new one. See *Interface Deviations*.

### End-to-end HTTP verification

Because the suite could not run, the shipped routes were driven over real HTTP. The application was
started directly (`java -cp target/classes;src/test/resources;<runtime cp> web.Gw2ApiApplication`,
bypassing `spring-boot:run`, which forks the failing `test-compile`) with
`--gw2.account-access.test-fixtures=true --gw2.account-luck.schema-init=false
--gw2.global-refresh.initial-delay-ms=86400000` on port 8097. No credential reached ArenaNet and no
database was needed. The server's own log named the port and PID, so the listener was identified
rather than assumed.

`bash /tmp/api010-verify.sh`, captured verbatim:

```
===== AC3: proof cookie and cache policy =====
ok   [200] proof with a valid fixture key
ok     header Cache-Control: no-store
ok     header HttpOnly
ok     header SameSite=Strict
ok     header free of Domain=
ok     header free of fixture-key-a
ok     body free of fixture-key-a
ok     body free of C19467C6-F5AD-E211-8756-78E7D1936222
ok     body contains accessContext
===== AC3: origin / CSRF on every state-changing route =====
ok   [403] proof from a foreign origin
ok   [403] proof with no Origin at all
ok   [403] renew with no Origin
ok   [403] logout with no Origin
ok   [400] proof with no credential
ok     body contains CREDENTIAL_REQUIRED
===== AC1/AC4: credential outcomes map to safe statuses =====
ok   [401] unknown key is rejected
ok   [401] malformed response fails closed
ok     header Cache-Control: no-store
ok   [401] expired subtoken is rejected
ok   [503] upstream outage is retryable
ok     header Retry-After
ok   [503] rate limiting is retryable
ok     header Retry-After: 60
===== AC4: account work needs a live session and matching context =====
       session A context=16anon801JoD1Jt5b7QLvw   session B context=zqrkp55s5BAyTURYV3mXCA
ok   [401] no session at all
ok     body contains SESSION_REQUIRED
ok   [403] cookie without a context
ok   [403] A's cookie with B's context
ok     body contains ACCOUNT_ACCESS_DENIED
ok     body free of C19467C6-F5AD-E211-8756-78E7D1936222
ok     body free of D29467C6-F5AD-E211-8756-78E7D1936222
ok   [401] unknown cookie value
ok   [200] A's own cookie and context
ok     header Cache-Control: no-store
===== AC4: caller account ids are assertions only =====
ok   [403] accountId asserting another account
ok     body free of C19467C6-F5AD-E211-8756-78E7D1936222
ok     body free of D29467C6-F5AD-E211-8756-78E7D1936222
ok   [200] accountId agreeing with the session
===== AC1/AC5: a replacement key keeps only its own grants =====
ok   [200] restricted session reads luck
ok   [403] restricted session denied bank
ok   [403] restricted session denied materials
ok   [200] full session reads bank
===== AC2: reads do not extend the absolute lease =====
ok     expiry unchanged across reads (2026-10-18T20:57:08.734906Z)
===== AC7/AC2: logout needs the matching context =====
ok   [403] logout with a foreign context
ok   [200] A still usable after that
ok   [200] B still usable after that
ok   [204] logout with the right context
ok     header Max-Age=0
ok   [401] A is revoked
ok   [401] logging A out again
ok   [200] B is untouched
===== AC1/AC5: renewal revalidates the credential =====
ok   [401] renew without a session
ok   [403] renew with a foreign context
ok   [403] renew with another account's key
ok     body contains ACCOUNT_MISMATCH
ok   [503] renew during an outage
ok   [200] cached authorization survives that outage
ok     renewal rotated both the identifier and the context
ok   [200] renewed session authorizes
ok   [401] an invalid key revokes the renewed session
ok   [401] the revoked session no longer authorizes
===== AC6: a key is only ever read from its header =====
ok   [400] key in a query parameter is not a credential
ok   [400] key in a cookie is not a credential
ok   [400] key in a JSON body is not a credential

passed=63 failed=0
exit=0
```

One expectation in that script was initially wrong and was corrected before this capture: a key in a
query parameter with a valid `Origin` is answered `400 CREDENTIAL_REQUIRED` (the query value is
ignored, so no credential was presented), not `403`. The production behavior was correct; the script
had asked for the wrong status.

**Cookie policy outside localhost.** `Secure` is omitted only for plain HTTP on a loopback host, and
present otherwise — same listener, different `Host`/`Origin`:

```
--- loopback host (explicit localhost development) ---
Set-Cookie: GW2_SESSION=cI9-EKfgNwczj45H4mbcqeqjNG-n7r2XCsBq1R7V9g8; Path=/; HttpOnly; SameSite=Strict
--- non-loopback host name on the same plain-HTTP listener ---
Set-Cookie: GW2_SESSION=e3DWVawMC2FeolIjIj99l3DiuBcz3t9rGrBp5P8xXjU; Path=/; HttpOnly; SameSite=Strict; Secure
```

**Bounded storage and eviction** (second instance, `--gw2.account-access.session-capacity=2`):

```
capacity is configured to 2; proving three sessions in order
  session 1 proved
  session 2 proved
  after two proofs:  s1=200  s2=200
  session 3 proved
  after three proofs: s1=401  s2=200  s3=200
  (401 for s1 is the eldest-proof eviction; the two newest still authorize)
```

**Restart requires fresh proof.** That instance was stopped by the PID its own log reported, the port
confirmed released (`curl` exit 7), and the process restarted on the same port:

```
pre-restart cookie after restart: code=401  (expect 401)
fresh proof on the restarted instance: code=200  (expect 200)
```

**Rejection performs no account work.** `/api/account/luck`'s freshness check opens a database
connection before its controller. A third instance was started with `DATABASE_URL` pointing at a dead
port (process environment wins over `.env`), which makes any freshness execution observable:

```
DATABASE_URL points at a dead port for this instance.

A route whose freshness check opens a connection, and which the access guard does not cover:
  /api/crafting/selector-options -> 503
  body: {"error":"DATA_STORE_UNAVAILABLE","message":"The crafting data store is currently unavailable",…}

The same freshness-checked source reached through a guarded account route:
  /api/account/luck, no session           -> 401
  body: {"error":"SESSION_REQUIRED","message":"Prove a GW2 API key to continue",…}
  /api/account/luck, foreign context      -> 403
  /api/account/luck, authorized           -> 200
  body: {"accessContext":"hJ5kQp1icP9R5fZ2m0PNCg","expiresAt":"2026-10-18T20:59:53.985390400Z",…}
```

The unguarded route proves the database really is unreachable there; the guarded route answers
401/403/200 with no database error at all. So the guard precedes the freshness interceptor, and a
rejected request opens no connection and admits no refresh. The authorized `200` with the database
dead is also AC7's cached-authorization-during-outage case.

**No secret in the logs.** Across all 63 checks the backend log stayed at 21 lines (startup only),
with zero occurrences of any fixture key, either account GUID, any session identifier, and zero
exceptions or stack traces.

**Processes.** All three backends were stopped by the PIDs their own logs reported; ports 8097, 8098
and 8099 each confirmed released (`curl` exit 7). The only remaining `java.exe` was created
17.10.2026, a day before this session — the maintainer's, untouched.

## Coverage review

No coverage report could be produced: JaCoCo reports come from a test run, and the test module does
not compile. Against `docs/QUALITY_METRICS.md` this means the 90% line/branch warning target is
**unmeasured** for the new `application.access`, `infra.access` and `web.access` packages — not met,
not missed. No exclusion was changed and no baseline is claimed. Coverage assessment is owed once QA
has corrected the import and the suite runs.

## Interface Deviations

- `src/test/java/web/AccountAccessContractTest.java:5` imports
  `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`, which **does not
  exist in Spring Boot 4.1.1**. Spring Boot 4 moved the annotation to
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` in the
  `spring-boot-webmvc-test` module. Evidence: that module's jar contains
  `org/springframework/boot/webmvc/test/autoconfigure/AutoConfigureMockMvc.class`, and no jar under
  `~/.m2/repository/org/springframework` contains any `org/springframework/boot/test/autoconfigure/
  web/servlet/` entry at all, so no dependency can supply the old path. The required dependency is
  already declared in `pom.xml` (test scope) and resolves. **Only the import line is wrong**; every
  name the test uses from production code exists as written. I did not touch the test. This blocks
  `test-compile`, and therefore every suite in the project, so it needs QA's correction before the CI
  gate can pass.

- Also for QA, discovered while verifying rather than as a deviation: the suite's
  `accountAndGrantAssertionsAreRejected…` case asserts the `accountId` consistency check using
  `.param("accountId", …)`. `MockMvc` populates request parameters without a query string for
  `.param` (only `.queryParam` calls `setQueryString`), so a guard reading `getQueryString()` would
  never see it. Production now reads the parsed parameter as well, so the case passes as written and
  needs no change — recorded so the behavior is not mistaken for a leniency later.

## Verification gaps

Stated plainly, since passing checks are not completion. Each matches a gap QA's own plan already
reserved for post-implementation review:

- **Absolute 15-minute expiry** is verified as *issued* (`expiresAt` is exactly proof + 15 min) and as
  *never extended by reads*, but no session was observed actually expiring — that needs the
  controlled-clock test. The **earlier-credential-expiry cap** (`min(lease, credential expiry)`) is
  verified only by inspection: the one expired-subtoken fixture is rejected outright, and no fixture
  has a near-future expiry. I did not add one, as that is fixture design.
- **`authorizeKeyedOperation`** has no HTTP caller yet, so its revalidation path is unverified at
  runtime. Its grant half is covered through the guard (restricted session denied bank/materials), and
  definitive-versus-transient revocation is covered through renewal (an invalid key revoked the
  session; a 503 outage did not).
- **Concurrency** was not exercised; sessions proved successively, not in parallel.
- `AccountGrant` models ten permissions. Any permission ArenaNet adds is ignored rather than mapped,
  which is the safe direction but means an operation requiring an unmodelled grant could never be
  admitted.

## Follow-up Findings

F001: The project now carries two Jackson majors at once — Spring Boot 4.1.1 auto-configures Jackson 3
(`tools.jackson.databind`) for the web layer, while `api.*`, `repo.*`, `parser.*` and `infra.*` parse
with Jackson 2 (`com.fasterxml.jackson.databind`), and no Jackson 2 `ObjectMapper` bean exists. Any
future component injecting the `com.fasterxml` type fails at startup, as this story's guard did until
it was corrected. Pre-existing and outside this story; worth a deliberate decision on which major the
application standardizes on.

F002: `docs/CURRENT_ARCHITECTURE.md` section 3 enumerates source areas (`web.*`, `application.*`,
`craft.*`, `repo.*`, `sync.*`, `api.*`, frontend, JavaFX) but omits `infra.*` entirely, which already
held `infra.icons` before this story and now also holds `infra.access`. The list reads as complete and
is not.

## User Resolution

RESOLVED

## Resolution Notes

The QA contract suite no longer has a compile problem: `AccountAccessContractTest` now imports
`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, and `pom.xml` carries
`spring-boot-webmvc-test` (test scope). `test-compile` succeeds.

The one remaining red application test, `AccountAccessServiceKeyedOperationTest.rejectsFabricatedOrRevokedSessionContextBeforeProvingKey`,
was a production defect, not a test defect: `AccountAccessService.authorizeKeyedOperation` never checked
that the presented session was live and the context genuine before proving the key. The check now exists
(a fabricated, expired or revoked pair throws `IllegalStateException`; the stored scope is authoritative).
`application.access.*Test` and `AccountAccessContractTest` pass (17 tests).

Next: re-evaluate the story against its QA plan, publish and run CI. No test change is requested.
