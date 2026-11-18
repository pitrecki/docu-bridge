package io.github.pitrecki.dokubridge.autoconfigure;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled Enable library-managed Spring wiring, default adapters and document entity registration.
 * @param storage Filesystem content storage settings.
 * @param jpa Library schema initialization settings.
 */
@ConfigurationProperties("dokubridge")
public record DokuBridgeProperties(@DefaultValue("true") boolean enabled, Storage storage, Jpa jpa) {
    public DokuBridgeProperties {
        storage = storage == null ? new Storage(null) : storage;
        jpa = jpa == null ? new Jpa(false) : jpa;
    }

    /**
     * @param root Private, application-controlled content directory on a filesystem supporting hard links;
     *             required unless a DocumentContentStore bean is supplied.
     */
    public record Storage(Path root) {
    }

    /**
     * @param initializeSchema Run library Flyway migrations before JPA initialization, after host migrations,
     *                         using a separate history table; enable only when the host does not run them itself.
     */
    public record Jpa(boolean initializeSchema) {
    }
}
