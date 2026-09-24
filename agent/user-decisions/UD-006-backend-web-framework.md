# UD-006 - Phase 4 backend web framework

## Status

RESOLVED.

## Decision Needed

Choose the Java backend web framework for Phase 4: Spring Boot, Quarkus, or another explicitly named framework.

## Why This Is Needed

The supplied Phase 4 roadmap requires an explicit framework decision before completion. `docs/TARGET_ARCHITECTURE.md` section 30 intentionally marks the backend web framework TBD, prefers Java, and lists Spring Boot / Quarkus / other as possibilities. The planner must not silently finalize this technology choice.

## Context

- Supplied `docs/ROADMAP.md` section 8, Phase 4 objective, exit criteria, and framework decision high-level story.
- `docs/TARGET_ARCHITECTURE.md` sections 9 and 30: HTTP routing, validation, DTO translation and responses wrap application use cases; controllers must not implement business rules.
- Phase 4 is additive: the JavaFX UI must continue calling application services in-process.
- Existing UD-001 through UD-005 do not decide the backend framework. No framework compatibility, dependency version, or implementation verification is claimed by this planning pass.
- Once answered, record the intended technology in its authoritative owner, `docs/TARGET_ARCHITECTURE.md` section 30, and plan a small milestone-04 batch.

## Blocks

Planning framework-dependent Phase 4 HTTP implementation stories and satisfying the explicit framework-decision exit criterion.

## External Input Possibly Required

Product Owner technology choice. If technical comparison is needed before choosing, request a bounded comparison; compatibility must be established during implementation rather than assumed here.

## User Decision

Use Spring Boot as the backend web framework for Phase 4.

Spring Boot should provide the HTTP/API runtime and infrastructure around the existing application-service layer, including routing, request validation, transport DTO handling, HTTP responses and appropriate runtime/operational facilities.

The framework must remain at the application boundary. Existing domain and application behavior must not be redesigned around Spring or become dependent on framework-specific APIs merely because Spring Boot is now present.

HTTP controllers should remain thin and delegate to the existing application services. Domain logic must remain independent from HTTP, Spring and transport DTOs as required by the target architecture.

Use a currently supported Spring Boot version compatible with the project's Java and Maven versions at implementation time rather than permanently pinning this architectural decision to a specific framework patch version.

The existing JavaFX application must continue to call the application services in-process during Phase 4. Adding the Spring Boot API is additive and must not require migrating JavaFX through HTTP.

## Resolution

RESOLVED.

Spring Boot is the selected Phase 4 backend web framework. Framework integration is limited to the backend/API boundary; existing application and domain boundaries remain authoritative.