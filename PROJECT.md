# DokuBridge

DokuBridge is a small embeddable library for attaching documents to business entities in Java/Spring applications. It stores document metadata and version history, delegates binary content to a storage adapter, and keeps an audit trail. The host application retains ownership of its business model, API and authorization.

## MVP

- Attach a document to a business object identified by `DocumentOwnerRef(type, id)`.
- Add new versions, update metadata, retrieve documents/content, and find documents by owner.
- Archive and delete documents through explicit domain operations.
- Track each content upload and audit relevant operations and failures.
- Offer filesystem storage, JPA persistence, Spring Boot auto-configuration, a starter and a working sample application.

**Not in scope:** a standalone DMS/ECM, folders, fine-grained access control, workflows, OCR, full-text search, cloud storage, UI or a reusable REST/web module. Demo endpoints may exist inside `sample-app`.

## Public boundary

`DocumentBridge` is the application-facing entry point. Its operation set covers `AttachDocument`, `AddDocumentVersion`, `UpdateMetadata`, `Archive`, `Delete`, `Get`, `GetContent` and `FindByOwner`. Commands and value objects carry input validation. Public contracts must not expose JPA or filesystem implementations.

The main model consists of `BusinessDocument`, `DocumentVersion`, `DocumentOwnerRef(type, id)` and a metadata map (`Map<String, Object>`). Audit records describe meaningful actions and upload failures.

## Lifecycle and consistency

| Entity | States |
| --- | --- |
| `BusinessDocument` | `ACTIVE`, `ARCHIVED`, `DELETED` |
| `DocumentVersion` | `PENDING`, `AVAILABLE`, `FAILED` |

For a new version:

1. Persist the new version as `PENDING`.
2. Store its binary content through `DocumentContentStore`.
3. Persist `AVAILABLE` on success or `FAILED` on storage failure; capture a relevant audit entry.
4. Return downloadable content only for `AVAILABLE` versions.

The database-first sequence is intentional. Database transactions do not encompass binary storage. A failure after successful file storage but before status finalization may leave orphaned content or a pending record; recovery and cleanup behavior must be defined for any implementation that can encounter it. Do not pretend the sequence is atomic.

## Module responsibilities

| Module | Responsibility |
| --- | --- |
| `core` | Domain model, commands, `DocumentBridge`, `DefaultDocumentBridge`, storage and repository ports |
| Filesystem adapter | Local binary storage via `DocumentContentStore` |
| JPA adapter | Persistence and audit adapters backed by JPA |
| Auto-configuration | Spring wiring and configuration defaults |
| Starter | Dependencies needed by host applications |
| `sample-app` | Usage example and optional demonstration REST endpoints |

`DefaultDocumentBridge` remains technology-neutral. JPA implements repository ports; it is not a second document facade. Avoid a generic transaction runner or database-specific assumptions in the core.

## Engineering priorities

Correct state transitions and failure handling matter more than adding features. Prefer a small public API, explicit invariants, short transactions, deterministic tests and understandable code. Verify persistence with PostgreSQL, cover edge cases around storage failure and concurrent version creation, and keep docs restricted to decisions that matter to maintainers and consumers.

## Open decisions

The precise concurrency strategy for assigning version numbers, duplicate-request semantics, cleanup/reconciliation after partially completed uploads and physical deletion/retention policy must be resolved against the actual implementation before treating them as guarantees. No framework or new module should be introduced solely to pre-empt these decisions.
