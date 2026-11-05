package io.github.pitrecki.dokubridge.autoconfigure;

import io.github.pitrecki.dokubridge.adapter.filesystem.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.JpaBusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentAuditRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentContentStore;
import io.github.pitrecki.dokubridge.testentities.AllowedRecord;
import io.github.pitrecki.dokubridge.testhost.ExplicitHostConfiguration;
import io.github.pitrecki.dokubridge.testhost.HostConfiguration;
import io.github.pitrecki.dokubridge.testhost.HostRecord;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Timeout(45)
class DokuBridgeAutoConfigurationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @TempDir
    Path directory;

    @BeforeEach
    void resetDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("drop schema public cascade");
            statement.execute("create schema public");
        }
    }

    private ApplicationContextRunner runner() {
        return infrastructure().withUserConfiguration(HostConfiguration.class);
    }

    private ApplicationContextRunner infrastructure() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class, FlywayAutoConfiguration.class, DokuBridgeAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.locations=classpath:db/host",
                        "dokubridge.storage.root=" + directory.resolve("content"),
                        "dokubridge.jpa.initialize-schema=true");
    }

    @Test
    void registersAdaptersAndRunsUploadsWhilePreservingHostEntitiesAndMigrations() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DocumentBridge.class)
                    .hasSingleBean(DocumentContentStore.class).hasSingleBean(BusinessDocumentRepository.class);
            assertThat(context.getBeansOfType(Flyway.class)).containsOnlyKeys("flyway");
            var factory = context.getBean(EntityManagerFactory.class);
            assertThat(factory.getMetamodel().entity(HostRecord.class)).isNotNull();
            var bridge = context.getBean(DocumentBridge.class);
            byte[] bytes = {42};
            var checksum = new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            var request = UUID.randomUUID();
            var command = new AttachDocument(request, new DocumentOwnerRef("Invoice", "42"), Map.of(),
                    checksum, new ByteArrayInputStream(bytes));
            var version = bridge.attach(command);
            assertThat(version.state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertThat(bridge.attach(command)).isEqualTo(version);
            try (var content = bridge.getContent(version.documentId(), version.id())) {
                assertThat(content.read()).isEqualTo(42);
            }
            assertThat(context.getBean(DocumentAuditRepository.class).findByDocument(version.documentId())).hasSize(2);
            assertThat(count("host_record")).isEqualTo(1);
            assertThat(count("flyway_schema_history")).isEqualTo(1);
            assertThat(count("dokubridge_flyway_history")).isEqualTo(2);
        });
    }

    @Test
    void respectsExplicitHostEntityScanWithoutAddingImplicitHostEntities() {
        infrastructure().withUserConfiguration(ExplicitHostConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            var managed = context.getBean(EntityManagerFactory.class).getMetamodel().getEntities().stream()
                    .<Class<?>>map(entity -> entity.getJavaType()).toList();
            assertThat(managed).contains(AllowedRecord.class).doesNotContain(HostRecord.class);
            assertThat(managed).anyMatch(type -> type.getPackageName().endsWith("adapter.jpa.persistence"));
        });
    }

    @Test
    void customContentStoreDoesNotRequireAStorageRoot() throws Exception {
        var custom = new FilesystemDocumentContentStore(directory.resolve("custom"));
        runner().withPropertyValues("dokubridge.storage.root=")
                .withBean(DocumentContentStore.class, () -> custom).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DocumentContentStore.class);
                    assertThat(context.getBean(DocumentContentStore.class)).isSameAs(custom);
                });
    }

    @Test
    void customDocumentFacadeIsPreserved() {
        var custom = new DocumentBridge() {
            public Optional<BusinessDocument> get(UUID id) { return Optional.empty(); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { return List.of(); }
            public InputStream getContent(UUID document, UUID version) { throw new UnsupportedOperationException(); }
        };
        runner().withBean(DocumentBridge.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DocumentBridge.class);
            assertThat(context.getBean(DocumentBridge.class)).isSameAs(custom);
        });
    }

    @Test
    void customRepositoryBacksOffOnlyItsDefaultAdapter() {
        var custom = new BusinessDocumentRepository() {
            public Optional<BusinessDocument> findById(UUID id) { return Optional.empty(); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { return List.of(); }
        };
        runner().withBean(BusinessDocumentRepository.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BusinessDocumentRepository.class)).isSameAs(custom);
            assertThat(context).doesNotHaveBean(JpaBusinessDocumentRepository.class);
            assertThat(context).hasSingleBean(DocumentAuditRepository.class);
        });
    }

    @Test
    void enabledFalseLeavesHostJpaAndFlywayRunningWithoutLibraryTables() {
        runner().withPropertyValues("dokubridge.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DocumentBridge.class)
                    .doesNotHaveBean(DocumentContentStore.class);
            assertThat(context.getBean(EntityManagerFactory.class).getMetamodel().getEntities())
                    .allMatch(entity -> entity.getJavaType().equals(HostRecord.class));
            assertThat(tableExists("dokubridge_documents")).isFalse();
            assertThat(count("host_record")).isEqualTo(1);
        });
    }

    @Test
    void schemaInitializationIsOptIn() {
        runner().withPropertyValues("dokubridge.jpa.initialize-schema=false", "spring.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean("dokubridgeSchemaInitializer");
                    assertThat(tableExists("dokubridge_documents")).isFalse();
                    assertThat(count("host_record")).isEqualTo(1);
                });
    }

    @Test
    void missingStorageRootFailsWithConfigurationError() {
        runner().withPropertyValues("dokubridge.storage.root=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("dokubridge.storage.root is required");
        });
    }

    @Test
    void restartingContextKeepsMigrationsAndHostDataIntact() {
        runner().run(context -> assertThat(context).hasNotFailed());
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(count("host_record")).isEqualTo(1);
            assertThat(count("dokubridge_flyway_history")).isEqualTo(2);
        });
    }

    private long count(String table) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
