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

`DocumentBridge` is the application-facing entry point. Its operation set covers `AttachDocument`, `AddDocumentVersion`, `UpdateMetadata`, `Archive`, `Delete`, `Get`, `GetContent`, `FindByOwner`, `GetVersions`, `GetAudit`, pending-upload discovery and explicit `ReconcileUpload`. Commands and value objects carry input validation. Public contracts must not expose JPA or filesystem implementations.

The main model consists of `BusinessDocument`, `DocumentVersion`, `DocumentOwnerRef(type, id)` and a metadata map (`Map<String, Object>`). Audit records describe meaningful actions and upload failures.

## Fluent API

The primary usage styles are `bridge.upload(bytes).toOwner(type, id).save().and()`
and `bridge.upload(bytes).toOwner(type, id).saveAndThen(document -> ...)`.
Each uploads one content version and continues on its document without extracting
an id. `bridge.document(id)` remains available for ids obtained elsewhere.
Attachment drafts require an owner before save is exposed;
metadata replacement requires `replaceMetadata(map).atRevision(revision).save()`.
These requirements are enforced by the returned step types, including compilation
checks against the core alone. Fluent types live in `core.port.in.upload` and
`core.port.in.document` and delegate to the existing command/query facade.

Byte-array upload drafts capture a private snapshot and calculate SHA-256
automatically. Metadata configuration takes a deep immutable JSON snapshot;
configuration returns new drafts rather than mutating earlier branches. The
attachment draft's `metadata(map)` replaces the whole map, while its key/value
overload adds or replaces an entry. Document metadata replacement retains its
existing full-replacement and revision rules.

Every unkeyed save allocates a new UUID request key at execution. An explicit
`requestId(key)` enables the existing idempotency contract, including replays of
pending/failed results. Streams retain their declared checksum and caller-owned
lifecycle: draft creation does not read them, and save does not close them.
Streams are not buffered or rewound. Byte snapshots can be saved repeatedly with
fresh internal streams; input streams remain stateful and require fresh input
for new uploads.

Configuring a draft or binding a document id performs no repository/storage I/O.
`save()` executes upload or metadata changes; document terminal reads and
archive/delete calls execute their named operations. Document contexts cache no
state. Fluent entry points add no new transaction, persistence, authorization or
recovery policy. Existing commands and facade implementations remain supported.

Fluent saves return `DocumentResult<T>`; `result()` exposes the immutable operation
snapshot and `and()` exposes its bound `DocumentHandle` without executing I/O.
Fluent archive returns the same result wrapper. Command-based facade methods keep
their original domain return types; existing fluent clients obtain those snapshots
with `save().result()` or `archive().result()`.

`findDocument(id)` returns `Optional<DocumentResult<BusinessDocument>>` by wrapping
one existing `get(id)` invocation. Active and archived documents are visible;
missing, deleted or foreign adapter snapshots are absent through the existing read
facade. Null ids are rejected before querying, and repository failures propagate.
The result retains its immutable query snapshot and a continuation bound to the
same facade. Accessing `result()` or `and()` causes no additional lookup or content
I/O. No content version is selected. Continuations use current state with normal
deletion and revision checks; there is no transaction spanning lookup and later
operations. Host authorization remains external. Existing `get` and `document`
methods retain their contracts; custom facades inherit this query through `get`.

`documentsForOwner(type, id)` and its `DocumentOwnerRef` overload return an immutable
list of `DocumentResult<BusinessDocument>`. They wrap one `findByOwner` invocation,
preserve its visible snapshots and order, and bind each continuation to that same
facade. Reading `result()` or obtaining `and()` performs no per-document lookup or
content I/O. Active and archived documents are visible; deleted documents are hidden.
An owner without documents has an empty list, while query errors propagate.
The query snapshots remain unchanged by later mutations. Continuations use current
state and preserve normal deletion and revision checks. They select no content
version. No transaction spans lookup and later operations. The overloads accepting
a limit and optional document-id cursor provide bounded pages as described below.
The host authorizes access. Existing custom facades inherit the unpaged operation
through their existing query implementation.

An upload continuation selects its resulting version for no-argument `content()`.
Metadata and archive continuations preserve that selection; another upload selects
its own version without changing earlier handles. Explicit `content(versionId)`
remains available. A handle created from just a document id has no selected version
and rejects no-argument content access rather than guessing the latest version.
Every content read still enforces current visibility and availability.

`DocumentHandle.readContent(reader)` reads the selected upload version, while
`readContent(versionId, reader)` selects a version explicitly. Both use the existing
content facade checks and invoke `DocumentContentReader<T>` once after a successful
open. The stream is borrowed for that call and closed before returning its result,
including on reader or stream failure. Readers can throw checked `IOException`;
close errors propagate, or are suppressed on an existing reader exception. If a
stream reuses the same exception for read and close, the original is propagated
unchanged without attempting self-suppression. A null reader fails before I/O.
Guava `Closer` provides resource cleanup and exception suppression inside the
private `DocumentHandle` implementation; Guava types do not appear in public
signatures. Domain types and outbound ports remain independent of this dependency.
The helper performs no buffering, adds no transaction or
audit and makes no state transition. The original caller-owned raw download and
upload stream contracts remain available. A reader must consume content within
its call and return parsed data rather than retain the borrowed stream.

`saveAndThen(action)` validates a non-null callback before saving, then calls
`save().andThen(action)`. The callback runs once whenever save returns normally,
including pending/failed keyed replays; it is skipped if save throws. Checked I/O
and runtime failures propagate unchanged. Already committed operations are retained,
and callback failure triggers no compensation or automatic retry. Each operation
uses its existing transaction boundary. The returned result is the original
operation snapshot even if the callback changes the document. Replaying an upload
also repeats its callback; request idempotency covers the upload alone.

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

The four code-bearing library modules (`core`, `filesystem`, `jpa`,
`autoconfigure`) attach source and Javadoc JARs during `package`. Plugin versions
and configuration are shared in the parent; each library explicitly enables the
attachments. Javadoc checks references and markup without requiring every type
to have a comment or fetching external API documentation. Normal `verify` checks
this packaging, and `install` makes the compiled/source/documentation artifacts
available to independent Maven consumers. Starter remains a dependency bundle;
sample packaging remains executable. These are build-time plugins only.

`integration-tests/starter-consumer` is an independent Maven test project outside
the library reactor. It consumes the installed starter through a host-owned Boot
BOM without inheriting the library parent, so missing transitive dependencies or
invalid installed POMs cannot be hidden by reactor resolution. CI runs `install`
first, including the full library and packaged-sample suite, then verifies this
consumer against PostgreSQL. Its fluent flow covers upload/replay, content and
audit reads, document/owner results, metadata revision checks, archive and deletion.

`scripts/verify-consumer.sh` evaluates the root project's effective version through
the Maven Help plugin and supplies it as `dokubridge.version` to the separate
consumer build. The consumer deliberately has no fallback library version. This
prevents an older artifact in the local Maven cache from passing the check after
a library version change. Maven settings and repository options passed to the
helper apply to both version evaluation and consumer verification. The helper
does not install artifacts; the library's successful `install` must precede it.
CI also verifies that the consumer rejects an unspecified version even after the
current library artifacts have been installed in the local repository.

## Engineering priorities

Correct state transitions and failure handling matter more than adding features. Prefer a small public API, explicit invariants, short transactions, deterministic tests and understandable code. Verify persistence with PostgreSQL, cover edge cases around storage failure and concurrent version creation, and keep docs restricted to decisions that matter to maintainers and consumers.

## Read facade

`core.port.in.DocumentBridge` is the inbound port. Its implementation,
`core.application.DefaultDocumentBridge`, uses `BusinessDocumentRepository`,
`DocumentVersionRepository` and `DocumentContentStore` for reads, with an optional
`DocumentAuditRepository` for audit history. Writes use the
additional `DocumentUploadRepository` and `DocumentLifecycleRepository` ports,
also defined in `core`. The `jpa` module implements
the repository ports outside the core. Domain types and errors live in
`core.domain.<feature>` and `core.domain.<feature>.exception`. Commands use
`core.port.in.document` or `core.port.in.upload`; outbound contracts use
`core.port.out.<feature>`. The filesystem module implements the content port.
No Spring, JPA or filesystem implementation is imported by core.

The central facade delegates upload/reconciliation orchestration to
`core.application.upload.DocumentUploads`, alongside package-private fingerprinting.
Document, version, upload, audit and content packages keep responsibilities visible
within each layer. See `docs/wiki/Architecture.md` for the public import migration.

`get(id)` returns an `Optional`; `findByOwner(owner)` returns an immutable list.
Both expose `ACTIVE` and `ARCHIVED` documents and hide `DELETED` documents.
`findByOwner(owner, limit)` and `findByOwner(owner, limit, afterDocumentId)`
return bounded immutable snapshots, also exposed by the fluent
`documentsForOwner(type, id, limit[, afterDocumentId])` and owner-reference
overloads. The limit is 1–1000; null starts the first page and the document UUID
cursor is exclusive. Order follows canonical UUID text, not creation time or
Java UUID's signed comparison. Each fluent result keeps its query snapshot and a
continuation without per-document lookups or a selected content version.

`BusinessDocumentRepository.findVisibleByOwner` filters owner, ACTIVE/ARCHIVED
state and cursor before applying the limit at the data source. The facade also
filters foreign/deleted snapshots defensively. The JPA projection avoids stale
managed entities and joins host read transactions with their existing isolation.
Pages are independent reads; concurrent inserts behind a cursor require a new
scan. Listing performs no content I/O, state transition or audit. Host
authorization remains external. Existing full-list repository/facade contracts
are unchanged; new paged port defaults are unsupported rather than loading and
slicing the full list. The starter uses the existing document port wiring. No
new dependency, configuration or migration is needed.

`getContent(documentId, versionId)` opens content only for a visible document and
an `AVAILABLE` version belonging to that document. A missing or deleted document
raises `DocumentNotFoundException`; an absent or foreign version raises
`DocumentVersionNotFoundException`. `PENDING` and `FAILED` versions raise
`DocumentContentUnavailableException` before the content store is called. Storage
I/O errors propagate without claiming a successful download. Callers close returned
streams. Null query arguments are rejected.

`getVersions(documentId)` returns an immutable list of all `PENDING`, `AVAILABLE`
and `FAILED` versions, ordered by number. Active and archived documents are
visible; a missing/deleted document raises `DocumentNotFoundException` before
version lookup. The facade filters foreign snapshots defensively and performs no
binary I/O. An empty history for a visible document is an empty list. Listing
versions does not grant content access: only `getContent` may open an available
version. The host remains responsible for authorization. This operation returns
the full history without an atomic snapshot spanning the document visibility check
and version query. The overloads `getVersions(documentId, limit)` and
`getVersions(documentId, limit, afterNumber)` expose bounded pages, also available
through `DocumentHandle.versions`. Limits are 1–1000; the exclusive number cursor
is nonnegative and defaults to 0. JPA applies scope, cursor, ordering and limit at
the data source through the existing document/number index. Pages are independent
reads and obey an enclosing host transaction's isolation. All state, visibility,
immutability and no-content-I/O rules remain the same.

`DocumentVersionRepository.findByDocument` provides scoped version snapshots,
including for retained deleted documents; the facade enforces visibility. Its
full-history and paged overload defaults throw `UnsupportedOperationException`, preserving existing single-version
implementations. Custom facades also inherit an unsupported default history method.
JPA uses query projections ordered by version number, including with retained host
entities. Existing constructors and Boot wiring enable history when the supplied
version port supports it; no new dependency, configuration or migration is needed.

`getAudit(documentId)` returns an immutable snapshot of every document/upload
event in repository insertion order. It preserves that order even when producer
timestamps move backwards. Active and archived documents are visible; a missing,
foreign or deleted document raises `DocumentNotFoundException` before the audit
port is called. Foreign audit snapshots are filtered defensively. A visible
document with no events has an empty list. Repository failures propagate rather
than becoming empty histories. Reading adds no event and performs no binary I/O.
The host authorizes access. There is no pagination or atomic snapshot spanning
the visibility check and history query.

`DocumentAuditRepository` remains the lower-level port for retained history,
including deleted documents. Application-level reads use the facade's visibility
checks. The audit-enabled read-only constructor takes `(documents, versions,
audits, content)` and does not enable writes. Full composition adds `audits` after
the existing five constructor arguments. Existing compositions remain usable with
audit disabled; existing custom facades inherit an unsupported default `getAudit`.
Boot supplies its selected audit port, including a host override. Audit reads need
no schema or configuration change.

These checks operate on repository snapshots. They do not lock a document during
streaming, revoke an already opened stream on deletion, or authorize a caller.
The host application remains responsible for authorization.

The JPA document, version and audit readers build snapshots from query
projections rather than managed entities. A retained host persistence context therefore cannot supply
an obsolete document state, metadata revision or upload outcome. These reads do
not clear or detach host entities. They still join an enclosing host transaction
and obey its database isolation; a repeatable-read transaction can retain an older
database snapshot. Deletion after a visibility check can still race an opened
stream as described above.

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
the facade. Use explicit reconciliation below for interrupted requests. There is no scheduler
or automatic orphan cleanup.

The original three-port `DefaultDocumentBridge` constructor remains read-only.
Use its four-port constructor with `DocumentUploadRepository` to enable uploads.
The five-port constructor additionally enables metadata and lifecycle mutations.
The six-port constructor also enables audit reads; the audit-only read composition
above remains available without write repositories. Default interface write
methods reject unsupported operations for existing external implementations.

## PostgreSQL persistence

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

## Document lifecycle

`UpdateMetadata(documentId, expectedRevision, metadata)` replaces the entire map;
an empty map clears it. Both active and archived documents permit replacement.
Every accepted replacement advances `BusinessDocument.revision`, including an
identical replacement. A stale expected revision raises
`DocumentRevisionConflictException` without a document change or audit. Retrying
the original command after success therefore conflicts; clients must reread the
snapshot before deciding whether another update is appropriate.

Revisions start at 0. The original four-argument `BusinessDocument` constructor
remains available and defaults to 0. Revision advances for metadata replacements
and actual lifecycle transitions; allocation/finalization of content versions does
not advance it. Negative revisions are invalid and exhaustion raises an error.
The field is a logical concurrency token, not a JPA `@Version` field.

`archive(Archive)` changes `ACTIVE` to `ARCHIVED`. Repeating archive returns the
current snapshot without advancing revision or adding another audit.
`delete(Delete)` changes active or archived documents to `DELETED`; repeating a
known deletion is a no-op. A missing document id raises `DocumentNotFoundException`.
Archive and metadata updates on deleted documents also report not found.

Deletion is logical: owner, metadata, version rows, binary content, upload request
keys and audit history remain retained. Reads hide the document. New upload
requests for it are rejected; a previously reserved upload may finish, but its
content stays hidden. Existing upload replays keep their durable-result semantics,
including after metadata changes or deletion; a replay never restores a document
or overwrites current metadata. There is no unarchive, physical purge or implicit
retention cleanup operation.

`DocumentLifecycleRepository` commits each change and its audit together. Its JPA
adapter locks the same document row used for version allocation and uses short
`READ COMMITTED` transactions. Concurrent metadata replacements with the same
expected revision have one winner. State-command duplicates produce one transition
and one audit. Write adapters explicitly refresh locked document/version entities
before checking conditions, so a retained host persistence context cannot bypass
revision checks, resurrect a deleted document, or refinalize a terminal version. Lifecycle calls reject an enclosing host transaction, consistent
with uploads; the port performs no binary I/O.

`METADATA_UPDATED`, `DOCUMENT_ARCHIVED` and `DOCUMENT_DELETED` audits have null
`versionId` and `requestId`; they describe the document, not an upload. Existing
upload audit actions still require both identifiers. Consumers should inspect the
action's `requiresUploadContext()` before reading these fields. Metadata values
are not copied into the audit. Revision checks, state transitions and audits roll
back together if any persistence step fails.

Migration V2 adds revision 0 to existing documents and relaxes audit identifiers
only for document actions using a database check constraint. V1 is unchanged.
The upgrade test migrates populated V1 data and verifies retained metadata,
version ids, request fingerprints and upload audit references. Boot's optional
library initializer performs the upgrades when enabled. Apply all migrations,
including V3 below, before running the current adapter with JPA schema validation.

## Spring Boot integration

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
Explicit reconciliation verifies published content as described below. Cleanup of
staging files and retained blobs remains an operator responsibility.

## Pending upload discovery

`pendingUploads(limit)` starts a scan; `pendingUploads(limit, afterRequestId)`
continues after an exclusive request-id cursor. Null starts the first page. Limits
are 1..1000. Results are immutable `PendingUpload(requestId, documentId, versionId)`
snapshots, ordered lexicographically by canonical request UUID. The semantic
`DocumentUploadRepository.findPendingUploads` port filters PENDING versions and
ACTIVE/ARCHIVED documents before limiting results. Deleted documents are hidden;
legacy pending requests without a checksum remain visible. Discovery does not
inspect blobs, allocate versions, mutate state or append audits.

The JPA adapter projects identifiers in one query using existing tables/indexes,
so retained host entities cannot supply stale states. It uses the existing short
independent read transaction and rejects an enclosing host transaction. The host
authorizes access to this global query. No schema or Boot wiring change is needed.
Existing upload-enabled compositions support discovery when their upload port
implements it; read-only compositions without that port remain unsupported.
Custom facades and upload repositories retain unsupported default methods.

Each page is a fresh snapshot. A listed upload may finish, disappear or be deleted
before reconciliation; the existing reconciliation operation rechecks durable
state. Concurrent inserts behind a cursor require restarting the scan to discover
them. There is no transaction spanning pages or discovery and reconciliation, and
no scheduler, automatic recovery or cleanup. Legacy reconciliation still requires
the original checksum and reports its existing safe error when unavailable.

## Explicit upload reconciliation

`reconcileUpload(ReconcileUpload(requestId, versionId))` checks the request/version
association, then returns an already terminal version without reading content.
For `PENDING`, it streams the published blob through SHA-256 and compares it with
the original declared checksum recorded in the reservation. Matching bytes become
`AVAILABLE`; different bytes become `FAILED`. The stream is closed before the
finalization transaction begins. A missing blob leaves the request pending and
returns a fresh durable snapshot, since an uploader may still be publishing it.
Other open/read/close errors propagate without requesting a state change.
Recovery uses Guava `Closer` to finish stream cleanup before finalization and
preserve the primary read error, including when read and close reuse one exception.

`DocumentUploadRepository.inspectForRecovery` returns a fresh committed snapshot
and its optional recorded checksum. `completeRecovery` locks/refreshes the version
and atomically finalizes it with `UPLOAD_RECOVERED` or `UPLOAD_RECOVERY_FAILED`.
Both audit actions require request/version ids. Audit failure rolls back the
transition. Matching finalization races add one terminal audit; a contradictory
outcome fails rather than overwriting a committed result. Database transactions
are closed during all content I/O, and enclosing host transactions are rejected.
Replays also refresh their version when a host retains its persistence context.

Recovery relies on the content store publishing only complete immutable blobs.
It can inspect an active upload safely: before publication it leaves `PENDING`;
after publication it may finalize before the original uploader. It never retries
`store`, resets `FAILED`, allocates versions, updates document revision, deletes
content, or makes a logically deleted document visible. The host authorizes this
operation. Pending-upload discovery above is explicit; there is no scheduler,
timeout or cleanup process.

Migration V3 adds nullable `expected_sha256` and the recovery audit actions; V1/V2
remain unchanged. New facade reservations record the checksum atomically with the
request. Existing fingerprints remain byte-for-byte compatible. Legacy requests
have no recorded checksum because a fingerprint cannot be reversed. Legacy
`PENDING` reconciliation raises `UploadChecksumUnavailableException` without
reading or changing content; the adapter never trusts a hash derived solely from
the stored blob. Legacy terminal versions still return normally. Replays do not
backfill this field. Resolving legacy pending uploads requires operator evidence
of their original intent outside this API.

Existing custom upload repositories remain source compatible through default
checksum overloads and unsupported recovery methods. To support recovery they
must persist the original checksum and implement inspection/finalization with the
same transaction and concurrency guarantees. The original reservation overloads
remain usable but create requests without recovery checksums in the JPA adapter.

## Open decisions

Physical deletion, retention policy, staging-file cleanup and automatic recovery
remain open. Metadata updates, archive, logical deletion and explicit upload
reconciliation are implemented. No framework or new module should be introduced
solely to pre-empt the remaining decisions.
