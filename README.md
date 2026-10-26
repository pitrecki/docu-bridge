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
explicit upload finalization, plus the `DocumentContentStore` streaming port.
The `filesystem` module provides immutable local content storage. Neither module
adds third-party runtime dependencies.

`DocumentVersion` transitions only from `PENDING` to `AVAILABLE` or `FAILED`.
Finalization returns a new snapshot; it does not persist state or store bytes.
Repeated finalization of a terminal snapshot is rejected. This local invariant
does not implement request idempotency or concurrency control in persistence.

The filesystem adapter requires a private application-controlled directory on a
filesystem with hard-link support. It publishes complete content under version
UUIDs, rejects duplicate keys and refuses to read symbolic links. See
[PROJECT.md](PROJECT.md#content-storage-contract) for failure and recovery limits.

Both modules build with the command above. To run just the adapter and its core
prerequisite:

```sh
./mvnw -pl filesystem -am verify
```

The facade, idempotent commands, repository ports, JPA adapters, Spring Boot
integration and sample application are not implemented yet. The agreed request
idempotency semantics in `PROJECT.md` apply to that future facade, not raw storage.
