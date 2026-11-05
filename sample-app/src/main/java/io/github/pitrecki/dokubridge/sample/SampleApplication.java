package io.github.pitrecki.dokubridge.sample;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.Delete;
import io.github.pitrecki.dokubridge.core.port.in.document.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
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
        var history = bridge.getVersions(first.documentId());
        if (!history.equals(List.of(first, second))) throw new IllegalStateException("Version history differs");
        var document = bridge.get(first.documentId()).orElseThrow();
        var updated = bridge.updateMetadata(new UpdateMetadata(document.id(), document.revision(), Map.of("category", "revised")));
        var archived = bridge.archive(new Archive(document.id()));
        if (archived.revision() != updated.revision() + 1) throw new IllegalStateException("Archive revision differs");
        if (!bridge.getVersions(document.id()).equals(history)) throw new IllegalStateException("Archived history differs");
        bridge.delete(new Delete(document.id()));
        bridge.delete(new Delete(document.id()));
        if (bridge.get(document.id()).isPresent()
                || bridge.findByOwner(owner).stream().anyMatch(candidate -> candidate.id().equals(document.id()))) {
            throw new IllegalStateException("Deleted document is still visible");
        }
        System.out.println("Verified document " + first.documentId() + " with version history 1 and 2, metadata, archive and logical deletion");
    }

    private static ContentSha256 checksum(byte[] bytes) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JVM does not provide SHA-256", unavailable);
        }
    }
}
