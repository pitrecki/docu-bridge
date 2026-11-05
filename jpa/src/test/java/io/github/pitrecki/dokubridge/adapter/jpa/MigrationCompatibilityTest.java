package io.github.pitrecki.dokubridge.adapter.jpa;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigrationCompatibilityTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    @Test
    void upgradesExistingV1DataWithoutChangingUploadIdentityOrHistory() throws Exception {
        var v1 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/dokubridge").table("dokubridge_flyway_history").target("1").load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID audit = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    insert into dokubridge_documents(id, owner_type, owner_id, metadata, state, last_version)
                    values (?, 'Invoice', 'legacy-42', '{"nullable":null,"amount":1234567890.12345}'::jsonb, 'ACTIVE', 1)
                    """)) {
                statement.setObject(1, document);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    insert into dokubridge_versions(id, document_id, version_number, state) values (?, ?, 1, 'AVAILABLE')
                    """)) {
                statement.setObject(1, version);
                statement.setObject(2, document);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    insert into dokubridge_upload_requests(id, fingerprint, version_id) values (?, ?, ?)
                    """)) {
                statement.setObject(1, request);
                statement.setString(2, fingerprint);
                statement.setObject(3, version);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    insert into dokubridge_audit(id, document_id, version_id, request_id, action, occurred_at)
                    values (?, ?, ?, ?, 'UPLOAD_SUCCEEDED', current_timestamp)
                    """)) {
                statement.setObject(1, audit);
                statement.setObject(2, document);
                statement.setObject(3, version);
                statement.setObject(4, request);
                statement.executeUpdate();
            }
            connection.commit();
        }
        var latest = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/dokubridge").table("dokubridge_flyway_history").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("select revision from dokubridge_documents")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isZero();
            }
            try (var row = statement.executeQuery("""
                    select d.id, d.metadata ->> 'amount', d.metadata ? 'nullable', d.state, d.last_version,
                           v.id, v.state, r.id, r.fingerprint, a.id, a.action
                    from dokubridge_documents d join dokubridge_versions v on v.document_id = d.id
                    join dokubridge_upload_requests r on r.version_id = v.id
                    join dokubridge_audit a on a.request_id = r.id
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1, UUID.class)).isEqualTo(document);
                assertThat(row.getString(2)).isEqualTo("1234567890.12345");
                assertThat(row.getBoolean(3)).isTrue();
                assertThat(row.getString(4)).isEqualTo("ACTIVE");
                assertThat(row.getInt(5)).isEqualTo(1);
                assertThat(row.getObject(6, UUID.class)).isEqualTo(version);
                assertThat(row.getString(7)).isEqualTo("AVAILABLE");
                assertThat(row.getObject(8, UUID.class)).isEqualTo(request);
                assertThat(row.getString(9)).isEqualTo(fingerprint);
                assertThat(row.getObject(10, UUID.class)).isEqualTo(audit);
                assertThat(row.getString(11)).isEqualTo("UPLOAD_SUCCEEDED");
                assertThat(row.next()).isFalse();
            }
        }
    }
}
