package io.github.pitrecki.dokubridge.adapter.jpa.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "dokubridge_upload_requests")
public class UploadRequestEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "version_id", nullable = false) private UUID versionId;

    public String fingerprint() {
        return fingerprint;
    }

    public UUID versionId() {
        return versionId;
    }

    protected UploadRequestEntity() {
    }
}
