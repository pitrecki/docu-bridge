# Development

Clone the repository and run commands from its root. Build with JDK 21; Docker
must be running for PostgreSQL/Testcontainers and packaged-sample checks.

## Build and tests

Requires JDK 21 and network access to Maven Central on the first build.

```sh
./mvnw verify
```

All modules build with the command above. The PostgreSQL tests require a running
Docker daemon; Testcontainers starts and stops PostgreSQL 17.6 automatically. Tests fail
when Docker is unavailable rather than silently skipping persistence checks.
`verify` also packages the sample and runs its mandatory Failsafe integration test,
which launches the JAR twice in separate JVMs. The dependency-only starter has no
unit tests; sample verification runs after packaging rather than in `test`.
To run just the filesystem adapter and its core prerequisite:

```sh
./mvnw -pl filesystem -am verify
```

CI also checks a standalone host in `integration-tests/starter-consumer` after
installing the library. That project has its own Spring Boot BOM and no library
parent or reactor membership, so it resolves the installed starter and its
transitive dependencies. The test starts PostgreSQL through Testcontainers and
exercises fluent upload/replay, downloads, owner lookup, metadata and lifecycle
operations. Run the same checks locally from the repository root:

```sh
./mvnw install
./scripts/verify-consumer.sh
```

Use the same local Maven repository for both commands. This consumer check runs
separately from the normal reactor `verify`; it needs the artifacts installed by
the first command. The helper reads the root project's effective Maven version
and supplies it to the independent consumer as `dokubridge.version`. The consumer
has no default library version, so it cannot silently test a previously cached
release when the root version changes. The helper accepts Maven options such as
`-s settings.xml` and `-Dmaven.repo.local=/path/to/repository`; it forwards them to
both Maven invocations. Version evaluation uses the pinned Maven Help plugin.

## Library artifacts

`package` and `verify` attach `-sources.jar` and `-javadoc.jar` alongside the
compiled JAR for `core`, `filesystem`, `jpa` and `autoconfigure`. Outputs are in
each module's `target` directory; for example:

```text
core/target/dokubridge-core-0.1.0-SNAPSHOT.jar
core/target/dokubridge-core-0.1.0-SNAPSHOT-sources.jar
core/target/dokubridge-core-0.1.0-SNAPSHOT-javadoc.jar
```

Run `./mvnw install` to install all modules, their parent POM and attached
artifacts into the local Maven repository. Other projects can then depend on
`dokubridge-starter`; IDEs can resolve library source and API documentation through
the standard Maven classifiers. The host manages its Spring Boot version through
its own parent or BOM.

Javadoc validates references and markup during the normal build. Missing comments
are allowed; broken documentation fails the build. Documentation generation does
not fetch external API links. The dependency-only starter and executable sample
produce their existing JARs without source or Javadoc attachments.

## Run the sample

The sample requires these environment variables for a development PostgreSQL
instance and content directory:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `DOKUBRIDGE_STORAGE_ROOT`

After `./mvnw verify`:

```sh
java -jar sample-app/target/dokubridge-sample-app-0.1.0-SNAPSHOT.jar
```

The console application uses fluent continuations, callbacks and document/owner lookups, enables library schema initialization,
validates the JPA schema, attaches a document, replays the request, adds version 2 and verifies its
download, full and paged version history and audit through `DocumentBridge`. It then
replaces metadata, archives and logically deletes that example document, verifies read invisibility, prints its id
and exits. Each run creates another retained, logically deleted example document. It enables no HTTP endpoints and disables the
sample's separate host Flyway instance; these settings belong to the sample only.
The packaged integration test supplies an isolated PostgreSQL container and checks
two complete runs, retained data, migration history and stored files.

## Maintain this wiki

Documentation sources live in `docs/wiki` in the main repository. Review changes
alongside code, then publish them to the separate GitHub Wiki Git repository:

```sh
./scripts/publish-wiki.sh
```

The publisher reads page names and content directly from the commit at `HEAD`
when it starts. Commit documentation changes before publishing: staged, unstaged
and untracked files are not part of that snapshot. The wiki commit records that
same source revision even if the source checkout changes during publication.

Publishing requires push access to `pitrecki/docu-bridge.wiki.git`. If the wiki has
never been initialized, enable Wiki in repository settings and create its first
page in the GitHub UI before running the script. The publisher preserves unrelated
wiki pages and normalizes local Markdown navigation for GitHub Wiki. It commits
as Piotr Nowak <piotr_nowak@outlook.com>. To preview publication in a local Git
repository, override `DOKUBRIDGE_WIKI_REMOTE` with its path.

CI checks publication against temporary local Git repositories without GitHub
credentials or network access. Run those checks locally with:

```sh
./scripts/test-publish-wiki.sh
```
