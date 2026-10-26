# DokuBridge

Embeddable document versioning for Java applications. See [PROJECT.md](PROJECT.md)
for scope and [AGENTS.md](AGENTS.md) for engineering rules.

## Build

Requires JDK 21 and network access to Maven Central on the first build.

```sh
./mvnw verify
```

The current implementation is the technology-independent `core` foundation:
owner references, document/version states and immutable version snapshots with
explicit upload finalization. It has no runtime dependencies.

`DocumentVersion` transitions only from `PENDING` to `AVAILABLE` or `FAILED`.
Finalization returns a new snapshot; it does not persist state or store bytes.
Repeated finalization of a terminal snapshot is rejected. This local invariant
does not implement request idempotency or concurrency control in persistence.

The facade, idempotent commands, repository ports, content storage, JPA adapters,
Spring Boot integration and sample application are not implemented yet.
