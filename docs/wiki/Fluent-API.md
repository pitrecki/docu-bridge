# Fluent API

```java
var saved = bridge.upload(bytes)
    .toOwner("Invoice", "42")
    .metadata("category", "invoice")
    .save();

var version = saved.result();
var document = saved.and();
byte[] downloaded = document.readContent(java.io.InputStream::readAllBytes);
document.archive();
```

This uploads one version, reads that version and archives its document. Archive is
logical; archived documents remain readable and accept further versions.

## Drafts, results and continuations

Uploads start with content, followed by a required owner. Only a completed draft
exposes `save()`. Metadata replacement similarly requires `atRevision(...)` before
`save()` becomes available. Creating/configuring drafts performs no repository
or stream I/O; upload and metadata writes happen at `save()`.

For an upload that can be retried, select the request key before the first save:

```java
var upload = bridge.upload(bytes)
    .toOwner("Invoice", "42")
    .metadata("category", "invoice")
    .requestId(requestId);

var first = upload.save();
var replay = upload.save();
assert first.result().equals(replay.result());
```

`save()` returns `DocumentResult<T>`: `result()` exposes the immutable operation
snapshot and `and()` continues on the same document without another lookup or
upload. For example, after saving one file:

```java
var saved = upload.save();
var version = saved.result();
var document = saved.and();
var history = document.versions();
var audit = document.audit();
try (var input = document.content()) {
    // Read the version selected by this upload; close the stream here.
}
```

`document.upload(revisedBytes).save()` adds the next content version. Its result
selects that new version for `content()`; an earlier result still selects its
original version. Metadata and archive continuations preserve that selection.
No context caches document state or bypasses download visibility checks.

`saveAndThen(action)` is equivalent to `save().andThen(action)` and returns the
original operation result after the callback. Callbacks accept document operations
and checked `IOException`, including reads through `content()`. They run once per
normally returning save, including keyed replays returning `PENDING` or `FAILED`.
An upload exception skips the callback. A callback exception propagates unchanged;
previously committed operations remain committed. The chain is sequential and uses
the existing transaction boundary of each operation. Retrying a keyed upload runs
its callback again, so upload idempotency does not make arbitrary callbacks
idempotent.

The fluent return types have changed: use `save().result()` to obtain the former
`DocumentVersion` or `BusinessDocument` snapshot, and `archive().result()` for an
archive snapshot. Command-based facade return types are unchanged.

Every unkeyed `save()` creates a new request, including repeated saves of one
draft. A keyed save follows the existing replay/conflict rules. Draft configuration
is immutable: byte arrays and JSON metadata are copied when supplied, so branching
a draft or modifying the original input does not change another draft.
`metadata(map)` replaces the attachment draft's complete map;
`metadata(key, value)` adds/replaces one entry, including JSON null. Maps such as
`Map<String, String>` are accepted without casts.

For a document id obtained elsewhere, `bridge.findDocument(id)` reads a snapshot
with a continuation; see [reading documents](Reading-documents.md).
`bridge.document(id)` binds the id without loading or caching its state. Its
terminal reads are `get()`, `versions()`, `audit()` and `content(versionId)`;
callers close returned content streams. `upload(...)` starts a version draft,
while `archive()` and `delete()` execute those operations directly. Metadata uses
`replaceMetadata(map).atRevision(revision).save()`; see
[metadata and lifecycle](Metadata-and-lifecycle.md). Owner lookup
also accepts `bridge.findByOwner("Invoice", "42")`; explicit recovery accepts
`bridge.reconcileUpload(requestId, versionId)`.

Fluent operations call the existing command/query facade. Visibility, authorization
by the host, version numbering, revision conflicts, audit and transaction behavior
remain the same. Existing command-based calls and custom facade implementations
remain supported; no additional Spring bean or storage adapter is needed.

## Callback form

```java
bridge.upload(bytes)
    .toOwner("Invoice", "42")
    .saveAndThen(document -> document.archive());
```

Both styles perform one upload. Use `document.upload(nextBytes).save()` only when
you want another version. Neither the chain nor callback creates a transaction
spanning operations.

## Streaming uploads

```java
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import java.nio.file.Files;

try (var input = Files.newInputStream(path)) {
    bridge.upload(input, new ContentSha256(expectedSha256))
        .toOwner("Invoice", "42")
        .requestId(requestId)
        .save();
}
```

`expectedSha256` is the previously established SHA-256 hex string; the overload
also accepts the hex string directly. `ContentSha256.of(bytes)` computes a checksum
when needed. The library verifies newly uploaded content against the declared
checksum. Stream drafts do not read, buffer, rewind or close the source, including
on failure and replay. Supply a fresh stream for a new upload; large content can
use this path without the byte-array snapshot. See [uploads and recovery](Uploads-and-recovery.md)
for replay and failure rules, and [reading documents](Reading-documents.md)
for managed downloads and history.
