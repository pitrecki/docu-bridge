# Uploads and recovery

## Upload sequence

```mermaid
sequenceDiagram
    participant Host
    participant Core as DocumentBridge / core
    participant DB as JPA / PostgreSQL
    participant Files as Filesystem adapter
    Host->>Core: upload(bytes).toOwner(...).save()
    Core->>DB: Reserve request, PENDING version and audit
    DB-->>Core: Commit reservation
    Core->>Files: Stream immutable content; verify SHA-256
    Files-->>Core: Publish complete file
    Core->>DB: Finalize AVAILABLE and audit
    DB-->>Core: Commit finalization
    Core-->>Host: Result snapshot and continuation
```

File I/O happens between database transactions. A failure after publishing content
can leave a `PENDING` version; an existing file alone never grants download access.

## Request idempotency and failure handling

Upload commands carry a globally unique UUID request idempotency key. A matching
replay returns the existing version without
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

See [architecture](Architecture.md#composition) for manual facade composition.

## Pending upload discovery

Discover uploads to inspect without knowing their request/version ids beforehand:

```java
var page = bridge.pendingUploads(100);
if (!page.isEmpty()) {
    var nextPage = bridge.pendingUploads(100, page.getLast().requestId());
}
```

Each `PendingUpload` exposes `requestId()`, `documentId()` and `versionId()`. Pass a
chosen entry's request/version ids to `reconcileUpload` when you want to verify it.
Listing itself reads no files, changes no state and writes no audit. It can include
uploads still in progress. Legacy pending uploads without a recorded checksum are
included; their reconciliation retains the `UploadChecksumUnavailableException`
behavior described under [interrupted uploads](#interrupted-uploads).

Pages are immutable, with a size from 1 to 1000, ordered lexicographically by the
canonical request UUID. The cursor is exclusive; null starts the first page.
Only `PENDING` uploads of active or archived documents are returned; deleted
documents and terminal versions are excluded before applying the limit. An empty
page ends the current scan. Pages are independent snapshots: concurrent inserts
behind the cursor require a new scan from the start, and an entry may finish
before you reconcile it.

The host authorizes this global query and invokes it outside host transactions,
like reconciliation. The existing upload repository composition enables discovery;
read-only compositions without that port remain unsupported. Custom repositories
and facades inherit unsupported defaults until they implement discovery. JPA uses
the existing tables and indexes, with no migration or new dependency.

## Interrupted uploads

```java
var result = bridge.reconcileUpload(requestId, versionId);
```

For `PENDING`, reconciliation verifies the existing immutable blob against the
checksum recorded at reservation: matching content becomes `AVAILABLE`, mismatched
content becomes `FAILED`, and missing content stays `PENDING`. Storage errors
propagate without finalization. Terminal versions return unchanged. Recovery and
its audit commit together; concurrent matching finalizations add one audit.
Reconciliation performs no content writes or deletion and does not expose deleted
documents. The host authorizes calls and must invoke them outside its transactions.

Apply migration V3 before using the current JPA adapter. Older requests lack the
original checksum: legacy pending requests raise `UploadChecksumUnavailableException`
without changing state, while legacy terminal results remain readable. A replay
never backfills the checksum from caller input. There is no recovery scheduler or
cleanup process.

## Recovery guarantees

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
operation. Pending-upload discovery is explicit; there is no scheduler,
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
