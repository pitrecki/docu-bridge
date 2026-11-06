package io.github.pitrecki.dokubridge.sample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Timeout(150)
class PackagedSampleIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @TempDir
    Path directory;

    @Test
    void packagedApplicationVerifiesTheWorkflowAndRestartsWithoutReapplyingMigrations() throws Exception {
        Path jar = Path.of(System.getProperty("sample.jar")).toAbsolutePath();
        assertThat(jar).isRegularFile();
        for (int run = 0; run < 2; run++) {
            Path log = directory.resolve("sample-" + run + ".log");
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", jar.toString()).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            // Isolate the child from any host Spring configuration or database credentials.
            var environment = builder.environment();
            environment.clear();
            environment.put("LANG", "C.UTF-8");
            environment.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
            environment.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
            environment.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
            environment.put("DOKUBRIDGE_STORAGE_ROOT", directory.resolve("content").toString());
            var process = builder.start();
            try {
                assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("Sample exits within 60 seconds").isTrue();
                String output = Files.readString(log);
                assertThat(process.exitValue()).withFailMessage(output).isZero();
                assertThat(output).contains("Verified document ", "version history 1 and 2", "audited events",
                        "fluent API", "chain and callback", "owner lookup", "document lookup", "managed content reads", "pending discovery");
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
        assertThat(count("dokubridge_documents")).isEqualTo(2);
        assertThat(count("dokubridge_versions")).isEqualTo(4);
        assertThat(count("dokubridge_upload_requests")).isEqualTo(4);
        assertThat(count("dokubridge_audit")).isEqualTo(14);
        assertThat(count("dokubridge_flyway_history")).isEqualTo(3);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select count(*) from dokubridge_documents where state = 'DELETED' and revision = 3")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(2);
            }
            try (var result = statement.executeQuery("select count(*) from dokubridge_audit where version_id is null and request_id is null")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(6);
            }
        }
        try (var files = Files.list(directory.resolve("content"))) {
            assertThat(files).hasSize(4);
        }
    }

    private long count(String table) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var results = statement.executeQuery("select count(*) from " + table)) {
            results.next();
            return results.getLong(1);
        }
    }
}
