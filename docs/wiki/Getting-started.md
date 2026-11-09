# Getting started

DokuBridge embeds versioned documents into a Java application. Your application
owns business objects, authorization and its public API. DokuBridge stores document
metadata, versions and audit history in PostgreSQL, with immutable binary content
in a filesystem directory.

Requires Java 21, PostgreSQL and Spring Boot 3. The project builds against Boot
3.5.7. The artifacts are currently `0.1.0-SNAPSHOT`; install them locally before
using the starter. No published Maven Central release is assumed.

```sh
./mvnw install
```

The build needs Docker for its PostgreSQL integration tests. See
[Development](Development.md) for build, packaging and sample instructions.

## Add the starter

```xml
<dependency>
  <groupId>io.github.pitrecki.dokubridge</groupId>
  <artifactId>dokubridge-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure a PostgreSQL data source through normal Spring Boot properties and set
`dokubridge.storage.root` to a private application-controlled directory. Inject
`core.port.in.DocumentBridge` to use the library. The default repositories require
the selected entity manager factory's `JpaTransactionManager`; upload calls must
run outside host transactions.

| Property | Default | Behavior |
| --- | --- | --- |
| `dokubridge.enabled` | `true` | Enables library entity registration and default beans. |
| `dokubridge.storage.root` | unset | Required unless a `DocumentContentStore` bean is supplied. |
| `dokubridge.jpa.initialize-schema` | `false` | Runs the library Flyway migrations before JPA initialization. |

Host beans replace the corresponding default ports or facade. The entity scan
adds library entities while retaining the host's implicit scan or explicit
`@EntityScan`. Custom entity manager factories must include the library persistence
package themselves. Use a single or primary data source, entity manager factory
and transaction manager.

If the host already manages library migrations, leave schema initialization off.
Otherwise enable `dokubridge.jpa.initialize-schema=true`. The initializer waits for
Boot's host Flyway migrations, then uses its own `dokubridge_flyway_history` table.
For an existing host schema it creates a library-only baseline at version 0 before
running V1. It does not expose a competing Flyway bean or replace host history.

## Configure the host

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/app
    username: app
    password: ${DB_PASSWORD}
dokubridge:
  storage:
    root: /var/lib/my-app/documents
  jpa:
    initialize-schema: true
```

Use an application-controlled directory on a filesystem with hard-link support.
Choose schema initialization only when the host does not already run the library
migrations. Supply credentials through your application's configuration.

## Inject the entry point

```java
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import org.springframework.stereotype.Service;

@Service
final class InvoiceAttachments {
    private final DocumentBridge bridge;

    InvoiceAttachments(DocumentBridge bridge) {
        this.bridge = bridge;
    }

    void attach(byte[] pdf) throws java.io.IOException {
        bridge.upload(pdf)
            .toOwner("Invoice", "42")
            .metadata("category", "invoice")
            .save();
    }
}
```

The owner reference points to your existing business object; it does not create
or validate that object. Authorize access before calling the library. Uploads,
lifecycle changes and recovery must run outside host transactions. Ordinary reads
join an enclosing host transaction and obey its isolation.

Continue with the [Fluent API](Fluent-API.md),
[reading documents](Reading-documents.md) or
[persistence and storage](Persistence-and-storage.md).
