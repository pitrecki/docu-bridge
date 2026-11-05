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

## Read facade

`core.port.in.DocumentBridge` is the inbound port. Its implementation,
`core.application.DefaultDocumentBridge`, depends
only on `BusinessDocumentRepository`, `DocumentVersionRepository` and
`DocumentContentStore`, all defined in `core`. The `jpa` module implements
the repository ports outside the core. Types live in `core.domain` and
`core.domain.exception`; infrastructure contracts live in `core.port.out`. The
`filesystem` module implements
the content port. No Spring, JPA or filesystem implementation is imported by core.

`get(id)` returns an `Optional`; `findByOwner(owner)` returns an immutable list.
Both expose `ACTIVE` and `ARCHIVED` documents and hide `DELETED` documents.
`getContent(documentId, versionId)` opens content only for a visible document and
an `AVAILABLE` version belonging to that document. A missing or deleted document
raises `DocumentNotFoundException`; an absent or foreign version raises
`DocumentVersionNotFoundException`. `PENDING` and `FAILED` versions raise
`DocumentContentUnavailableException` before the content store is called. Storage
I/O errors propagate without claiming a successful download. Callers close returned
streams. Null query arguments are rejected.

These checks operate on repository snapshots. They do not lock a document during
streaming, revoke an already opened stream on deletion, or authorize a caller.
The host application remains responsible for authorization.

`BusinessDocument` snapshots include an immutable `Map<String,Object>` of JSON
metadata. Accepted values are strings, booleans, nulls, finite numbers, nested maps
with string keys and lists. Supported number classes are `Byte`, `Short`, `Integer`,
`Long`, `Float`, `Double`, `BigInteger` and `BigDecimal`; values and numeric types
are preserved. Containers are copied recursively and made unmodifiable. Null map
keys, cycles, non-finite numbers and other object types are rejected. Shared
acyclic containers are valid. Request fingerprints compare these values using the JSON semantics below.

## Request idempotency

Upload commands will carry a globally unique request idempotency key, as selected
by the project owner. A matching replay returns the existing version without
creating another version; different content or metadata with the same key is a
conflict. Replaying a `PENDING` request reports that it is in progress. A `FAILED`
request remains failed; a new attempt requires a new key. The core facade and JPA
adapter implement these rules; the content store itself does not implement request
idempotency. Request keys are UUIDs and are retained without automatic expiry.

`attach(AttachDocument)` and `addVersion(AddDocumentVersion)` return a
`DocumentVersion`. Commands carry a required declared `ContentSha256` (64 hex
digits, case-insensitive at input), request UUID and caller-owned content stream.
Attach also carries the owner and an immutable metadata snapshot. Add-version
carries the target document UUID and leaves metadata unchanged. Replays return the
current version state without consuming or closing the supplied stream; they compare
the declared checksum, not newly transmitted bytes. The caller closes the stream
on success, failure, replay and conflict.

Fingerprints include operation, declared content SHA-256 and either attachment
owner/metadata or the add-version target. Map key order is ignored; list order is
significant; numerically equal JSON numbers such as `1` and `1.0` compare equal.
Null, missing fields and different scalar types remain distinct. The private v1
encoding is length-delimited, type-tagged and SHA-256 hashed; its compatibility
vector is tested because persisted request keys depend on it.

Only an acquired reservation writes content. The facade streams through a digest,
verifies the actual checksum, then finalizes `AVAILABLE`. Content I/O or checksum
failure finalizes `FAILED` and rethrows the original error. If failure finalization
also fails, that error is suppressed on the original cause. If successful storage
is followed by a failed database finalization, the facade reports that failure,
does not guess a failed outcome, and never writes again automatically. Durable
state remains authoritative; an uncommitted finalization leaves `PENDING`.
Checksum mismatch can leave a stored blob, but it remains undownloadable through
the facade. Reconciliation and orphan cleanup require explicit operator action;
no recovery scheduler or reconciliation API is provided yet.

The original three-port `DefaultDocumentBridge` constructor remains read-only.
Use its four-port constructor with `DocumentUploadRepository` to enable uploads.
Default interface write methods reject unsupported uploads for existing external
read-only implementations.

## PostgreSQL persistence

The `jpa` module contains `JpaBusinessDocumentRepository`,
`JpaDocumentVersionRepository`, `JpaDocumentAuditRepository` and
`JpaDocumentUploadRepository`. Its runtime dependencies provide Spring ORM
transaction boundaries, Hibernate JPA, Jackson JSON encoding, PostgreSQL JDBC
and Flyway migrations. The core has no new runtime dependencies. Adapters live in `adapter.jpa`, with
entities and JSON mapping in `adapter.jpa.persistence`; the filesystem adapter
lives in `adapter.filesystem`. ArchUnit enforces the dependency direction.

The Flyway migration is opt-in at `classpath:db/dokubridge`; use the separate
history table `dokubridge_flyway_history`. It is outside Flyway's default
`db/migration` location to avoid running library migrations implicitly or colliding
with host V1 migrations. Host applications supply the data source, entity manager
factory (scanning `io.github.pitrecki.dokubridge.adapter.jpa`) and a shared `EntityManager`.
Run migrations before Hibernate schema validation. See the PostgreSQL integration
test for a working bootstrap without Spring Boot auto-configuration.

Metadata is stored as JSONB. The adapter decodes integers as `BigInteger` and
fractional numbers as `BigDecimal`, preserving precision without configuring the
host's global JSON mapper. Java numeric wrapper classes and JSON object key order
are not preserved across database round trips. Domain snapshots remain deeply
immutable.

`DocumentUploadRepository` is a semantic transaction port. Each reservation claims
a globally unique UUID request key and its caller-supplied SHA-256 fingerprint,
allocates a `PENDING` version and appends its reservation audit in one transaction.
Attachment creates the document and version 1. Further reservations lock the
document row and increment its version counter; a unique database constraint also
protects `(document_id, version_number)`. Failed uploads consume their reserved
version number. An uncommitted reservation rolls back all these records.

A matching existing fingerprint returns `acquired=false` and the current version,
including `PENDING` or `FAILED`; a different fingerprint raises
`UploadRequestConflictException`. Replays add neither versions nor audit entries.
The adapter accepts opaque fingerprints from the application service; it does not
compute request identity or consume content. Hosts must not substitute an arbitrary
hash and treat this port as full facade-level idempotency.

Finalization locks the version and permits only `PENDING` to `AVAILABLE`/`FAILED`
for the owning request. It appends `UPLOAD_SUCCEEDED`/`UPLOAD_FAILED` in that same
transaction. An audit failure rolls back finalization, leaving the version pending.
A connection failure with an ambiguous commit outcome requires inspecting durable
state; the library does not retry an uncertain outcome automatically.

Write transactions use PostgreSQL `READ COMMITTED` and `REQUIRES_NEW`. Calls inside
an existing host transaction are rejected, so later content I/O cannot inherit an
outer transaction. The port never performs content I/O. The facade performs content I/O only after
reservation commits and before finalization begins.

Both `ACTIVE` and `ARCHIVED` documents accept new versions. `DELETED` documents
reject new requests as missing. A matching replay of an existing request returns
its already reserved version without allocating another one, even after a document
state change; download visibility is still enforced separately by the read facade.

## Content storage contract

`DocumentContentStore` streams immutable bytes under a version UUID. `store` rejects
an existing key; it does not compare content or implement request idempotency.
The caller owns the upload stream and must close streams returned by `open`.
Storage failures are reported as `IOException`; missing content and duplicate keys
have specific exception types in `core`. The store knows neither document state
nor authorization; the facade must allow downloads only for `AVAILABLE` versions.
Physical deletion is not exposed in this increment.

The `filesystem` module implements this port without additional runtime libraries.
Its root must be a private application-controlled directory; external processes
must not replace the root or modify its entries. Keys are UUIDs, never filenames.
The adapter rejects symbolic links as the root and refuses to read symbolic links
or non-regular content entries. Host-level access and retention policy remain the
host application's responsibility.

Writes stream to a temporary file in the same root, close the output, then publish
using an atomic hard link. This requires filesystem hard-link support; unsupported
filesystems fail rather than falling back to an overwrite-prone move. Concurrent
writes of one key have exactly one winner, across adapter instances as well.
Readers never see a partially copied file under its final key. This is publication
atomicity, not a guarantee of durability across power loss.

Ordinary upload failures clean up staging files, preserving the original cause
and any cleanup failure. A process interruption can leave `.upload-*.tmp` files;
these are not downloadable by UUID. Failure after publication (including staging
cleanup failure) can leave a complete blob despite the call reporting failure.
The upload implementation must keep that version unavailable until database finalization
or explicit reconciliation succeeds; blob existence alone is insufficient.
Reconciliation and safe cleanup of interrupted uploads are not implemented yet.

## Open decisions

Cleanup/reconciliation after partially completed uploads and physical deletion/retention policy remain open. Metadata updates, archive/delete operations and their transaction/audit contracts remain to be implemented. No framework or new module should be introduced solely to pre-empt these decisions.
