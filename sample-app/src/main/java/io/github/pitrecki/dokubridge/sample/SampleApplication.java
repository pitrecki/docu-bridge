package io.github.pitrecki.dokubridge.sample;

import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SampleApplication {
    public static void main(String[] args) throws IOException {
        var application = new SpringApplication(SampleApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run(args)) {
            demonstrate(context.getBean(DocumentBridge.class));
        }
    }

    private static void demonstrate(DocumentBridge bridge) throws IOException {
        var owner = new DocumentOwnerRef("Invoice", "sample-42");
        byte[] initial = "first revision".getBytes(StandardCharsets.UTF_8);
        UUID requestId = UUID.randomUUID();
        var metadata = Map.<String, Object>of("category", "invoice");
        DocumentVersion first;
        try (var source = new ByteArrayInputStream(initial)) {
            first = bridge.attach(new AttachDocument(requestId, owner, metadata, checksum(initial), source));
        }
        DocumentVersion replay;
        try (var source = new ByteArrayInputStream(initial)) {
            replay = bridge.attach(new AttachDocument(requestId, owner, metadata, checksum(initial), source));
        }
        if (!first.equals(replay)) throw new IllegalStateException("Idempotent replay returned another version");
        byte[] revised = "second revision".getBytes(StandardCharsets.UTF_8);
        DocumentVersion second;
        try (var source = new ByteArrayInputStream(revised)) {
            second = bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), first.documentId(), checksum(revised), source));
        }
        try (var stream = bridge.getContent(first.documentId(), second.id())) {
            if (!Arrays.equals(stream.readAllBytes(), revised)) throw new IOException("Downloaded content differs");
        }
        if (first.state() != DocumentVersionState.AVAILABLE || second.number() != 2
                || bridge.get(first.documentId()).isEmpty()
                || bridge.findByOwner(owner).stream().noneMatch(document -> document.id().equals(first.documentId()))) {
            throw new IllegalStateException("Document workflow failed");
        }
        System.out.println("Verified document " + first.documentId() + " with versions 1 and 2");
    }

    private static ContentSha256 checksum(byte[] bytes) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JVM does not provide SHA-256", unavailable);
        }
    }
}
