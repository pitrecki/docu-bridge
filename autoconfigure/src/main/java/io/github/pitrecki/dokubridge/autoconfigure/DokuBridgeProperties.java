package io.github.pitrecki.dokubridge.autoconfigure;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dokubridge")
public record DokuBridgeProperties(@DefaultValue("true") boolean enabled, Storage storage, Jpa jpa) {
    public DokuBridgeProperties {
        storage = storage == null ? new Storage(null) : storage;
        jpa = jpa == null ? new Jpa(false) : jpa;
    }

    public record Storage(Path root) {
    }

    public record Jpa(boolean initializeSchema) {
    }
}
