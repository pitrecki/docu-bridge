# DokuBridge — engineering rules

Read `PROJECT.md` before changing code. These rules apply to the whole repository. Follow existing conventions where they do not conflict with the project's documented boundaries.

## Purpose and scope

DokuBridge is an embeddable Java library for attaching versioned documents, metadata and audit history to business objects. It is not a document management system. Keep the MVP small; do not add features merely because a DMS might need them.

- Stack: Java 21, Spring Boot 3, JPA, PostgreSQL, Flyway, JUnit 5.
- MVP modules: `core`, filesystem content store, JPA adapters, Spring Boot auto-configuration, starter and sample application.
- No Lombok, MapStruct, CQRS framework, generic workflow, ACL subsystem, OCR, search, cloud storage, UI or standalone web module.
- REST controllers, if needed for demonstration, belong in `sample-app` only.
- Do not introduce new runtime dependencies or modules without explaining the concrete need.
- Before implementing general-purpose operations, check the JDK and established libraries. Prefer a suitable library dependency over recreating standard functionality. Keep project-specific domain rules and compatibility requirements explicit.

## Architecture

- Keep `core` independent of Spring, JPA, HTTP and filesystem details.
- Group the model in `core.domain.<feature>` and errors in `core.domain.<feature>.exception`, using `document`, `version`, `upload`, `audit` and `content` as appropriate. Commands and fluent operation types belong in `core.port.in.document` or `core.port.in.upload`; keep only the central `DocumentBridge` in `core.port.in`. Outbound contracts belong in `core.port.out.<feature>`. Do not flatten these packages.
- Keep `DefaultDocumentBridge` as the central application facade. Put the upload pipeline and fingerprinting in `core.application.upload`; place other substantial use cases in their own feature packages when needed, without adding pass-through services.
- Keep filesystem content storage in `adapter.filesystem.content`. Group JPA repositories in `adapter.jpa.<feature>` and their entities/JSON mapping in `adapter.jpa.<feature>.persistence`. Adapters depend on domain types and outbound ports, not on application services or inbound ports.
- Use `autoconfigure` as the Spring composition root; it may wire application services and adapters. Keep sample usage in `sample` and call the inbound port from it.
- Run the ArchUnit checks with the normal test suite; keep dependency direction enforced when adding types.
- `DocumentBridge` is the public facade; `DefaultDocumentBridge` implements application-level behavior in `core`.
- Keep the content-first fluent API on the inbound side. Drafts are immutable and delegate to existing facade commands; owner and metadata-revision steps must precede save. Do not add persistence, authorization or automatic retry behavior to fluent helpers.
- Fluent results expose snapshots through `result()` and document continuations through `and()`. Preserve selected content versions across metadata/archive continuations. Callbacks run after a normally returning save, including replays; preserve original exceptions and existing per-operation transactions without rolling back earlier commits.
- `DocumentHandle` and `DocumentUploads` use Guava `Closer` privately for managed reads and recovery stream cleanup. Keep Guava out of domain types, outbound ports and public API signatures; ArchUnit permits these specific implementation dependencies.
- Persistence is provided through semantic ports and JPA adapters, including `JpaBusinessDocumentRepository`, `JpaDocumentVersionRepository` and `JpaDocumentAuditRepository`.
- File storage is accessed through `DocumentContentStore`. Do not create a `JpaDocumentBridge` or `TransactionRunner`.
- Keep persistence and storage concerns inside their adapters. Do not let JPA entities leak into the public API.
- Prefer explicit composition over inheritance, specific ports over generic repositories, and small cohesive types over utility classes.
- Do not add interfaces, factories, patterns or configuration for a hypothetical second implementation.

## Domain contracts

- A document has an owner identified by `DocumentOwnerRef(type, id)` and metadata represented by `Map<String, Object>`.
- `BusinessDocument` states: `ACTIVE`, `ARCHIVED`, `DELETED`.
- `DocumentVersion` states: `PENDING`, `AVAILABLE`, `FAILED`.
- Store a `PENDING` version in the database, write its content, then persist `AVAILABLE` or `FAILED` as appropriate.
- Serve document content only for `AVAILABLE` versions. Failed or pending versions must not appear downloadable.
- Preserve version history and record audits of attach, version addition, metadata changes, archive, delete and upload failures.
- Treat database and content storage as separate failure domains. Never assume one transaction covers both.
- Make state transitions explicit; reject invalid transitions rather than repairing them silently.
- Define observable behavior for retries, duplicate requests, concurrent version creation, storage failures and failed finalization before implementing affected paths.
- The host application owns authorization; do not silently implement an ACL system in `core`.

## Code quality

- Apply SOLID where it makes dependencies and responsibilities clearer. Favor KISS and YAGNI over theoretical extensibility.
- Use DRY for shared business knowledge, not to remove every repeated line. A small duplication is preferable to a misleading abstraction.
- Make invalid states hard to express: validate required values at boundaries and in records/value objects; keep immutable data immutable.
- Use domain-specific names. Avoid `Manager`, `Helper`, `Util`, `Base`, `Common` and vague abstractions unless the responsibility is unambiguous.
- Keep methods focused, control flow direct and side effects visible. Prefer guard clauses; avoid deep nesting and clever code.
- Do not use exceptions as normal control flow. Preserve causes and use meaningful errors; never swallow failures or return success after a partial failure.
- Keep transaction boundaries short and explicit. Do not hold database transactions open during file I/O.
- Design failure paths as carefully as the happy path. Pay attention to races, idempotency, orphaned blobs, retries, and recovery after process interruption.
- Avoid logging document contents, credentials or sensitive metadata. Log identifiers, transitions and failures with enough context to investigate incidents.
- Protect filesystem storage against path traversal; stored paths/keys must not be derived directly from untrusted filenames.
- Optimize only where data or a real bottleneck justifies it. Readability wins over speculative micro-optimization.
- Maintain backward compatibility for public APIs and persisted data unless a breaking change is explicitly approved.

## Tests and verification

- Use JUnit 5 and AssertJ. Tests should describe business behavior, not implementation details.
- Cover invariants, transitions, failure paths and boundary conditions; mock only genuine external boundaries.
- Test JPA mappings and queries against PostgreSQL, preferably using Testcontainers when the project is configured for it. Do not rely on H2 for PostgreSQL-specific behavior.
- Test content-store failures, retry/duplicate behavior, concurrent version allocation and access restrictions on unavailable versions.
- Every bug fix needs a regression test when practicable. Do not weaken or delete tests to make a patch pass.
- Run the relevant existing checks after a change, then the broader project suite when feasible. Use the repository's build wrapper if present.
- Report precisely what passed, failed or could not be run. Never claim verification without executing it.

## How to work

1. Inspect relevant code, tests and project rules before editing. State the change in one or two sentences; skip ceremonial plans for small tasks.
2. For every material ambiguity, generate a concrete question rather than inventing a requirement. Include the options, their trade-off and a recommended default. Ask before making decisions that change the public API, persistence model, domain semantics, security or MVP scope.
3. If nothing material is ambiguous, proceed without filler questions. Do not ask about facts already established in `PROJECT.md` or in the code.
4. Make the smallest complete change. Reuse established patterns, but improve them when they violate these rules; avoid unrelated refactoring.
5. Validate the result and review the diff for complexity, regressions, compatibility, unnecessary dependencies and missing tests.
6. Finish with a short summary: what changed, tests run, and any unresolved risk or question. No self-evaluation or promotional language.

## Writing and documentation

- Write code, identifiers, Markdown, and commit messages in clear English.
- Documentation must sound like an engineer wrote it: direct, factual and concise. No generic introductions, sales language, filler, emojis or AI-style conclusions.
- Explain decisions, constraints, invariants, public behavior and non-obvious failure modes. Do not narrate the code or repeat information obvious from names.
- Prefer a short paragraph, a compact example or a small table to long inventories of best practices.
- Keep README focused on purpose, a fluent example and the upload flow. Maintain full documentation in `docs/wiki` and publish it with `scripts/publish-wiki.sh` to GitHub Wiki.
- Update docs only when behavior, API, architecture, setup or an important decision changes. Do not generate new documentation files for routine edits.
- Comments explain **why**, never merely **what**. Avoid routine Javadoc on self-explanatory private methods.
- Document trade-offs only where an actual decision was made. Mark unsettled decisions as open; never present assumptions as settled facts.
