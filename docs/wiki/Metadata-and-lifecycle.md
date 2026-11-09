# Metadata and lifecycle

```java
var found = bridge.findDocument(documentId).orElseThrow();
var updated = found.and().replaceMetadata(Map.of("category", "revised"))
    .atRevision(found.result().revision())
    .save();
updated.and().archive().and().delete();
```

Metadata is replaced completely, including on archived documents. `updated.result()`
carries the next revision. A stale `expectedRevision` conflicts; an empty
map clears metadata. Content-version allocation does not advance document revision.

Repeated archive/delete produces no additional change or audit. Deleted documents
are hidden, while binary files, versions and history remain retained. A reserved
upload can finish after deletion without making content visible. Document-only
audit events have null version/request ids; upload audits retain both identifiers.

## Metadata values

`BusinessDocument` snapshots include an immutable `Map<String,Object>` of JSON
metadata. Accepted values are strings, booleans, nulls, finite numbers, nested maps
with string keys and lists. Supported number classes are `Byte`, `Short`, `Integer`,
`Long`, `Float`, `Double`, `BigInteger` and `BigDecimal`; values and numeric types
are preserved. Containers are copied recursively and made unmodifiable. Null map
keys, cycles, non-finite numbers and other object types are rejected. Shared
acyclic containers are valid. Request fingerprints use
[JSON value semantics](Uploads-and-recovery.md#request-idempotency-and-failure-handling).
Java numeric wrapper types are not preserved across PostgreSQL JSONB round trips;
the adapter decodes integers as `BigInteger` and fractional numbers as `BigDecimal`.

## Revisions, transitions and concurrency

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
including V3, before running the current adapter with JPA schema validation. See
[persistence and storage](Persistence-and-storage.md#schema-upgrades).
