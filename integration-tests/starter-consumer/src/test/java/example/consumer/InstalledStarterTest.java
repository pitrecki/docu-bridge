package example.consumer;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Timeout(60)
class InstalledStarterTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @TempDir Path storage;

    @Test
    void consumesInstalledStarterOutsideTheLibraryReactor() throws Exception {
        try (var context = new SpringApplicationBuilder(ConsumerApplication.class)
                .web(WebApplicationType.NONE).logStartupInfo(false)
                // Command-line properties take precedence over host database/storage environment settings.
                .run("--spring.main.banner-mode=off", "--spring.flyway.enabled=false",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate", "--dokubridge.jpa.initialize-schema=true",
                        "--dokubridge.storage.root=" + storage)) {
            var bridge = context.getBean(DocumentBridge.class);
            byte[] bytes = "external consumer".getBytes(StandardCharsets.UTF_8);
            var upload = bridge.upload(bytes).toOwner("Invoice", "external-42")
                    .metadata("origin", "external").requestId(UUID.randomUUID());
            var saved = upload.saveAndThen(document -> {
                byte[] downloaded = document.readContent(InputStream::readAllBytes);
                assertThat(downloaded).containsExactly(bytes);
            });
            assertThat(saved.result().state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertThat(upload.save().result()).isEqualTo(saved.result());
            assertThat(saved.and().versions()).containsExactly(saved.result());
            assertThat(saved.and().audit()).extracting(DocumentAuditEntry::action)
                    .containsExactly(DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED);

            var found = bridge.documentsForOwner("Invoice", "external-42").getFirst();
            assertThat(found.result().id()).isEqualTo(saved.result().documentId());
            var updated = found.and().replaceMetadata(Map.of("origin", "fluent"))
                    .atRevision(found.result().revision()).save();
            assertThat(updated.result().revision()).isEqualTo(1);
            assertThat(updated.and().get().orElseThrow().metadata()).containsEntry("origin", "fluent");
            var loaded = bridge.findDocument(saved.result().documentId()).orElseThrow();
            assertThat(loaded.result()).isEqualTo(updated.result());
            byte[] downloaded = loaded.and().readContent(saved.result().id(), InputStream::readAllBytes);
            assertThat(downloaded).containsExactly(bytes);
            loaded.and().archive().and().delete();
            assertThat(saved.and().get()).isEmpty();
            assertThat(bridge.findDocument(saved.result().documentId())).isEmpty();
            assertThat(bridge.documentsForOwner("Invoice", "external-42")).isEmpty();
            assertThat(found.result().metadata()).containsEntry("origin", "external");
        }
    }
}
