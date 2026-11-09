package io.github.pitrecki.dokubridge.sample;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.IOException;
import java.io.InputStream;
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
        var saved = upload.saveAndThen(document -> {
            if (document.versions().size() != 1 || document.audit().size() != 2) {
                throw new IllegalStateException("Upload callback differs");
            }
        });
        var first = saved.result();
        var replay = upload.save().result();
        if (!first.equals(replay)) throw new IllegalStateException("Idempotent replay returned another version");
        var document = saved.and();
        byte[] revised = "second revision".getBytes(StandardCharsets.UTF_8);
        var next = document.upload(revised).save();
        var second = next.result();
        byte[] downloaded = next.and().readContent(InputStream::readAllBytes);
        if (!Arrays.equals(downloaded, revised)) throw new IOException("Downloaded content differs");
        var found = bridge.documentsForOwner("Invoice", "sample-42").stream()
                .filter(candidate -> candidate.result().id().equals(first.documentId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Owner lookup omitted the document"));
        byte[] original = found.and().readContent(first.id(), InputStream::readAllBytes);
        if (!Arrays.equals(original, initial)) throw new IOException("Original content differs");
        if (first.state() != DocumentVersionState.AVAILABLE || second.number() != 2
                || document.get().isEmpty()
                || bridge.pendingUploads(1000).stream().anyMatch(pending -> pending.documentId().equals(first.documentId()))) {
            throw new IllegalStateException("Document workflow failed");
        }
        var history = document.versions();
        if (!history.equals(List.of(first, second))) throw new IllegalStateException("Version history differs");
        if (!document.versions(1).equals(List.of(first))
                || !document.versions(1, first.number()).equals(List.of(second))
                || !document.versions(1, second.number()).isEmpty()) {
            throw new IllegalStateException("Paged version history differs");
        }
        if (document.audit().size() != 4) throw new IllegalStateException("Active audit differs");
        var updated = found.and().replaceMetadata(Map.of("category", "revised"))
                .atRevision(found.result().revision())
                .save();
        var archived = updated.and().archive();
        if (archived.result().revision() != updated.result().revision() + 1) throw new IllegalStateException("Archive revision differs");
        if (!document.versions().equals(history)) throw new IllegalStateException("Archived history differs");
        var actions = document.audit().stream().map(DocumentAuditEntry::action).toList();
        if (!actions.equals(List.of(DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.METADATA_UPDATED, DocumentAuditAction.DOCUMENT_ARCHIVED))) {
            throw new IllegalStateException("Archived audit differs");
        }
        var loaded = bridge.findDocument(first.documentId()).orElseThrow();
        if (!loaded.result().equals(archived.result())) throw new IllegalStateException("Document lookup snapshot differs");
        loaded.and().delete();
        document.delete();
        if (document.get().isPresent()
                || bridge.findDocument(first.documentId()).isPresent()
                || bridge.documentsForOwner("Invoice", "sample-42").stream().anyMatch(candidate -> candidate.result().id().equals(first.documentId()))) {
            throw new IllegalStateException("Deleted document is still visible");
        }
        System.out.println("Verified document " + first.documentId() + " through fluent API (chain and callback) with pending discovery, managed content reads, document lookup, owner lookup, version history 1 and 2, paged history, audited events, metadata, archive and logical deletion");
    }
}
