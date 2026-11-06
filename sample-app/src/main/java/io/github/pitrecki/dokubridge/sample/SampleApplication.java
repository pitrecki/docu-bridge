package io.github.pitrecki.dokubridge.sample;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
        byte[] initial = "first revision".getBytes(StandardCharsets.UTF_8);
        var upload = bridge.upload(initial)
                .toOwner("Invoice", "sample-42")
                .metadata("category", "invoice")
                .requestId(UUID.randomUUID());
        var first = upload.save();
        var replay = upload.save();
        if (!first.equals(replay)) throw new IllegalStateException("Idempotent replay returned another version");
        var document = bridge.document(first.documentId());
        byte[] revised = "second revision".getBytes(StandardCharsets.UTF_8);
        var second = document.upload(revised).save();
        try (var stream = document.content(second.id())) {
            if (!Arrays.equals(stream.readAllBytes(), revised)) throw new IOException("Downloaded content differs");
        }
        if (first.state() != DocumentVersionState.AVAILABLE || second.number() != 2
                || document.get().isEmpty()
                || bridge.findByOwner("Invoice", "sample-42").stream().noneMatch(candidate -> candidate.id().equals(first.documentId()))) {
            throw new IllegalStateException("Document workflow failed");
        }
        var history = document.versions();
        if (!history.equals(List.of(first, second))) throw new IllegalStateException("Version history differs");
        if (document.audit().size() != 4) throw new IllegalStateException("Active audit differs");
        var current = document.get().orElseThrow();
        var updated = document.replaceMetadata(Map.of("category", "revised"))
                .atRevision(current.revision())
                .save();
        var archived = document.archive();
        if (archived.revision() != updated.revision() + 1) throw new IllegalStateException("Archive revision differs");
        if (!document.versions().equals(history)) throw new IllegalStateException("Archived history differs");
        var actions = document.audit().stream().map(DocumentAuditEntry::action).toList();
        if (!actions.equals(List.of(DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.METADATA_UPDATED, DocumentAuditAction.DOCUMENT_ARCHIVED))) {
            throw new IllegalStateException("Archived audit differs");
        }
        document.delete();
        document.delete();
        if (document.get().isPresent()
                || bridge.findByOwner("Invoice", "sample-42").stream().anyMatch(candidate -> candidate.id().equals(first.documentId()))) {
            throw new IllegalStateException("Deleted document is still visible");
        }
        System.out.println("Verified document " + first.documentId() + " through fluent API with version history 1 and 2, audited events, metadata, archive and logical deletion");
    }
}
