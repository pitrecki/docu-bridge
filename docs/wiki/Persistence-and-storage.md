# Persistence and storage

## PostgreSQL

The `jpa` module contains `JpaBusinessDocumentRepository`,
`JpaDocumentVersionRepository`, `JpaDocumentAuditRepository`,
`JpaDocumentUploadRepository` and `JpaDocumentLifecycleRepository`. Its runtime dependencies provide Spring ORM
transaction boundaries, Hibernate JPA, Jackson JSON encoding, PostgreSQL JDBC
and Flyway migrations. Core's managed content reads use Guava `Closer`. Repositories live
in `adapter.jpa.document`, `adapter.jpa.version`, `adapter.jpa.upload` and
`adapter.jpa.audit`, with entities and JSON mapping in each feature's `persistence`
subpackage. The filesystem adapter lives in `adapter.filesystem.content`.
ArchUnit enforces both feature placement and dependency direction.

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

The private metadata mapper accepts JSONB's full numeric width: up to 131072 digits
before the decimal point and 16383 after it, including negative values. Its number
length limit accounts for the sign and decimal point. This prevents a successful
upload from becoming unreadable when a stored number exceeds Jackson's default
1000-character limit. The host's mapper configuration and other Jackson parsing
constraints are unchanged. Values outside PostgreSQL's numeric range remain
subject to database rejection.

`DocumentUploadRepository` is a semantic transaction port. Each reservation claims
a globally unique UUID request key and its caller-supplied SHA-256 fingerprint,
records the declared content checksum, allocates a `PENDING` version and appends
its reservation audit in one transaction.
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
transaction. Repeating the same terminal outcome returns the current snapshot
without another audit; a contradictory terminal outcome is rejected. This permits
an uploader and reconciliation to finish the same version safely. An audit failure
rolls back finalization, leaving the version pending.
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

## Spring Boot composition

`autoconfigure` is the composition root. `DokuBridgeAutoConfiguration` registers
default adapters and `DocumentBridge` for a Boot-managed JPA application; each
port/facade bean backs off independently when the host provides that type. The
configuration uses a shared entity manager and verifies that the selected
`JpaTransactionManager` belongs to the same factory. Entity registration preserves
the implicit host scan or its explicit `@EntityScan` without expanding the latter.
A custom entity manager factory remains responsible for including library entities.

`dokubridge.enabled=false` disables this integration. Filesystem storage requires
an explicit `dokubridge.storage.root`, unless the host supplies its own content
store. Library migration remains opt-in via `dokubridge.jpa.initialize-schema`.
When enabled, migration runs after Boot's host Flyway initializers and before JPA
initialization, in a separate library history table. Existing nonempty schemas
receive a library baseline at 0; host migrations and history are retained. A
library initializer is not a Flyway bean, so Boot can still configure host Flyway.

`starter` bundles the Boot JPA and adapter dependencies without application logic.
`sample-app` is a console client using the inbound port; its packaged JAR is tested
in two fresh JVMs against the same PostgreSQL instance and content directory. It
provides no web module. Spring configuration classes live in `autoconfigure`,
separate from domain, ports, application services and adapters.

## Filesystem content store

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
Explicit reconciliation verifies published content as described in [uploads and recovery](Uploads-and-recovery.md). Cleanup of
staging files and retained blobs remains an operator responsibility.

## Schema upgrades

| Migration | Change |
| --- | --- |
| V1 | Documents, version allocation, upload requests and upload audits. |
| V2 | Document revisions and document-only lifecycle audits. |
| V3 | Original upload SHA-256 and recovery audit actions. |

Apply all migrations before starting the current adapter with schema validation.
Existing migration files remain unchanged; upgrades preserve earlier data and
request fingerprints. Legacy pending uploads without an original checksum need
operator evidence before they can be recovered; see
[uploads and recovery](Uploads-and-recovery.md).
