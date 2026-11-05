package io.github.pitrecki.dokubridge.testhost;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "host_record")
public class HostRecord {
    @Id
    private Long id;
    private String label;

    protected HostRecord() {
    }

    public HostRecord(Long id, String label) {
        this.id = id;
        this.label = label;
    }
}
