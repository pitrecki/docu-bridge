package io.github.pitrecki.dokubridge.testentities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "allowed_record")
public class AllowedRecord {
    @Id
    private Long id;

    protected AllowedRecord() {
    }
}
