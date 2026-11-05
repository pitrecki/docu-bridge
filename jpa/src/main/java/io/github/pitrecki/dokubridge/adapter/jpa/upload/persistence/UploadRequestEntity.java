package io.github.pitrecki.dokubridge.adapter.jpa.upload.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "dokubridge_upload_requests")
public class UploadRequestEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "version_id", nullable = false) private UUID versionId;
    @Column(name = "expected_sha256", length = 64) private String expectedSha256;

    public String expectedSha256() {
        return expectedSha256;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public UUID versionId() {
        return versionId;
    }

    protected UploadRequestEntity() {
    }
}
