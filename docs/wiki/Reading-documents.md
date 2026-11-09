# Reading documents

Use `findDocument` or `documentsForOwner` when you need a document snapshot and
a continuation. Use `bridge.document(id)` to bind a known id without a lookup.
Neither lookup selects a content version.

## Document by id

`findDocument(id)` returns `Optional<DocumentResult<BusinessDocument>>`. Read the
snapshot through `result()` and continue through `and()` without another lookup:

```java
bridge.findDocument(documentId).ifPresent(found -> {
    found.and().replaceMetadata(Map.of("category", "reviewed"))
        .atRevision(found.result().revision())
        .save();
});
```

Active and archived documents are visible; a missing or deleted document returns
an empty Optional. Query errors propagate. The snapshot stays unchanged by later
operations; continuations check current state, so stale metadata revisions can
conflict and deletion can make a subsequent operation fail. The query selects no
content version; use `content(versionId)` for downloads. The host authorizes access.
Lookup and subsequent operations retain their own transaction boundaries.
Existing `get(id)` still returns a domain snapshot, while `document(id)` binds a
context without a lookup. Custom facades inherit `findDocument` through their
existing `get` implementation.

## Documents by owner

`documentsForOwner(type, id)` returns immutable results with the same visible
documents and order as `findByOwner`. It also accepts a `DocumentOwnerRef`.
`result()` exposes the query snapshot without another lookup; `and()` continues
on that document. A lookup can return several documents for one owner.

To update the first result when one exists:

```java
bridge.documentsForOwner("Invoice", "42").stream().findFirst()
    .ifPresent(found -> {
        var snapshot = found.result();
        found.and().replaceMetadata(Map.of("category", "reviewed"))
            .atRevision(snapshot.revision())
            .save();
    });
```

`result()` supplies metadata and the observed revision from the original query.
`and()` binds subsequent operations to that document without extracting its UUID.
The first result does not imply the newest document; select by your application's
criteria when an owner has several documents.

To update every result:

```java
for (var found : bridge.documentsForOwner("Invoice", "42")) {
    found.and().replaceMetadata(Map.of("category", "reviewed"))
        .atRevision(found.result().revision())
        .save();
}
```

Active and archived documents are visible; deleted documents are hidden. An owner
without documents has an empty list. Results retain their original snapshots;
continuations use current state, so a later deletion hides the document and a
changed revision can conflict. The lookup selects no content version: use
`content(versionId)` or a subsequent upload's continuation for downloads.
The host authorizes access. No transaction spans the lookup and subsequent
operations. Existing custom facades inherit the unpaged method through their
`findByOwner` implementation.

### Paged owner lookup

```java
var page = bridge.documentsForOwner("Invoice", "42", 100);
if (!page.isEmpty()) {
    var nextPage = bridge.documentsForOwner("Invoice", "42", 100,
        page.getLast().result().id());
}
```

The overloads also accept `DocumentOwnerRef`. Pages retain immutable query
snapshots through `result()` and document continuations through `and()`, without
individual document lookups or content reads. No content version is selected.
Use the continuation to update metadata at the snapshot's revision, archive a
document or add its next content version.

Limits are 1–1000. Results are ordered by canonical UUID text, with an exclusive
document-id cursor; null starts the first page. UUID ordering does not indicate
creation time. JPA filters by both owner fields, visibility and cursor before
applying the limit in the database. Deleted documents cannot consume page slots.
An owner without visible documents has an empty page. The original overloads
without a limit still return the full list.

Pages are independent reads that obey an enclosing host transaction's isolation.
Concurrent inserts behind the cursor require starting another scan; metadata and
state can change between pages. A continuation still checks current visibility
and revision, while its original snapshot remains unchanged. The host authorizes
access. No content I/O, audit or state change occurs while listing.

The snapshot facade exposes `findByOwner(owner, limit)` and
`findByOwner(owner, limit, afterDocumentId)`. Custom facades implement its paged
query to enable fluent pages; the default is unsupported. Custom document
repositories implement `findVisibleByOwner(owner, limit, afterDocumentId)` and
must filter visibility and cursor before limiting at the data source. Existing
unpaged implementations remain usable without loading their full lists as a
paging fallback. The JPA adapter and starter need no new dependency,
configuration or schema migration.

## Version history

```java
var document = bridge.document(documentId);
var history = document.versions();       // Complete history.
var page = document.versions(100);        // First page.
if (!page.isEmpty()) {
    var nextPage = document.versions(100, page.getLast().number());
}
```

Both forms return immutable snapshots of `PENDING`, `AVAILABLE` and `FAILED`
versions, ordered by version number. Active and archived documents are visible;
missing or deleted documents raise `DocumentNotFoundException`. Listing reads no
files and does not make pending or failed content downloadable.

Page limits are 1–1000. The version-number cursor is exclusive and nonnegative;
0 starts from the beginning. An empty page ends the current scan. The facade also
exposes `getVersions(documentId, limit)` and
`getVersions(documentId, limit, afterNumber)`. Existing `versions()` and
`getVersions(documentId)` still return the full history.

JPA applies the cursor and limit in the database using the existing
`(document_id, version_number)` index. It does not fetch all versions to construct
a page. Pages are separate reads: states may change and later reservations may
appear between requests. An enclosing host transaction retains its database
isolation; no atomic snapshot spans the visibility check and version query.
The host authorizes access.

Custom version repositories implement `findByDocument(documentId)` for full
history and `findByDocument(documentId, limit, afterNumber)` for pages. Defaults
throw `UnsupportedOperationException`, preserving existing single-version
implementations. Custom facades likewise inherit unsupported history defaults.
The JPA adapter and starter support both forms without new configuration,
dependencies or migrations.

## Reading content with automatic close

Content is readable only for an `AVAILABLE` version belonging to a visible
document. These checks happen before the content store is called:

| Condition | Result |
| --- | --- |
| Missing or deleted document | `DocumentNotFoundException` |
| Missing version or version belonging to another document | `DocumentVersionNotFoundException` |
| `PENDING` or `FAILED` version | `DocumentContentUnavailableException` |
| Storage open/read/close failure | The original I/O failure propagates. |

`readContent(reader)` opens the version selected by an upload result, invokes the
reader once and closes the stream before returning its value. For a document
obtained by id or owner, pass the version explicitly:

```java
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

byte[] bytes = saved.and().readContent(InputStream::readAllBytes);

var found = bridge.findDocument(documentId).orElseThrow();
String text = found.and().readContent(versionId,
    input -> new String(input.readAllBytes(), StandardCharsets.UTF_8));
```

`DocumentContentReader<T>` accepts a checked `IOException` and can process content
incrementally; the helper adds no buffering or transaction. The borrowed stream
is valid during the reader call and is closed afterward, including on reader or
stream errors. Return parsed data rather than retaining or returning the stream.
Reader exceptions propagate; a simultaneous close failure is suppressed on the
original exception. If only close fails, that error propagates instead of returning
the reader's value. A null reader is rejected before I/O.

These reads enforce the same visibility, document/version association and
`AVAILABLE` checks as `content()`. An open failure skips the reader. Reading adds
no state change or audit. A context with no selected version requires the explicit
version overload. `content()` and `content(versionId)` continue to return raw
streams that the caller closes.

## Audit history

```java
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import java.util.List;

List<DocumentAuditEntry> audit = bridge.document(documentId).audit();
```

The facade returns an immutable list of document and upload events in database
insertion order. Timestamps may move backwards across producers and do not define
this order. Active and archived documents are visible; missing or deleted
documents raise `DocumentNotFoundException` before querying audit. No content is
opened and reading history adds no event. The host authorizes access. The operation
returns the full list without pagination, with the same snapshot/isolation limits
as other reads.

Upload actions carry version/request UUIDs; metadata, archive and delete actions
have null values for both. Check `entry.action().requiresUploadContext()` before
using those identifiers. Audit records contain no copied document metadata or
binary content. The lower-level `DocumentAuditRepository` retains all events,
including deletion history; its visibility is controlled by the host.

For manual composition, inject the existing audit port:

```java
var readOnly = new DefaultDocumentBridge(documents, versions, audits, content);
var full = new DefaultDocumentBridge(documents, versions, content, uploads, lifecycle, audits);
```

Repository arguments precede content in the audit-enabled read-only constructor,
so the existing four-argument upload constructor remains usable. The original
three-, four- and five-port compositions still work; they do not enable audit
history. Custom facades inherit an unsupported default `getAudit` method. Boot
passes the selected `DocumentAuditRepository` bean to the facade and respects a
host-provided replacement. No new property, dependency or migration is required.

## Read consistency

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

