# DokuBridge documentation

DokuBridge attaches versioned documents to business objects in Java applications.
It combines a content-first DSL, PostgreSQL persistence, immutable filesystem
content and an audit trail. The host owns authorization and its business API.

## Start here

1. [Getting started](Getting-started.md): add the starter, configure PostgreSQL
   and storage, and upload your first document.
2. [Fluent API](Fluent-API.md): drafts, results, continuations, callbacks,
   idempotent retries and streaming uploads.
3. [Reading documents](Reading-documents.md): document lookup, paged owner results
   and version history, managed downloads and audit history.

## Behavior and operations

- [Metadata and lifecycle](Metadata-and-lifecycle.md): JSON values, revisions,
  archive, logical deletion and concurrency.
- [Uploads and recovery](Uploads-and-recovery.md): transaction boundaries,
  checksums, failures, pending discovery and explicit reconciliation.
- [Persistence and storage](Persistence-and-storage.md): PostgreSQL, migrations,
  Spring Boot composition and filesystem requirements.

## Contributing

- [Architecture](Architecture.md): modules, feature packages, ports, adapters,
  composition and public-import migration.
- [Development](Development.md): build, test suites, installed consumer,
  packaged sample and wiki publishing.

Sources are maintained with the code in
[docs/wiki](https://github.com/pitrecki/docu-bridge/tree/main/docs/wiki).
The repository's [PROJECT.md](https://github.com/pitrecki/docu-bridge/blob/main/PROJECT.md)
records maintainer contracts; [AGENTS.md](https://github.com/pitrecki/docu-bridge/blob/main/AGENTS.md)
contains engineering rules.
