package io.github.pitrecki.dokubridge.core.domain.document;

import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentRevisionConflictException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class DocumentLifecycleTest {
    final UUID id = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");

    @Test
    void originalConstructorStartsAtRevisionZero() {
        assertThat(new BusinessDocument(id, owner, Map.of(), BusinessDocumentState.ACTIVE).revision()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void replacesTheWholeMetadataSnapshotAndAdvancesRevision(BusinessDocumentState state) {
        var original = document(state, 4);
        var source = new HashMap<String, Object>();
        source.put("replacement", true);
        var changed = original.updateMetadata(4, source);
        source.clear();
        assertThat(changed.metadata()).containsOnlyKeys("replacement").containsEntry("replacement", true);
        assertThat(changed.revision()).isEqualTo(5);
        assertThat(changed.id()).isEqualTo(id);
        assertThat(changed.owner()).isEqualTo(owner);
        assertThat(changed.state()).isEqualTo(state);
        assertThat(original.revision()).isEqualTo(4);
        assertThat(original.metadata()).containsEntry("original", true);
        assertThat(changed.updateMetadata(5, Map.of()).metadata()).isEmpty();
    }

    @Test
    void everyAcceptedReplacementAdvancesRevisionAndAnOldReplayConflicts() {
        var original = document(BusinessDocumentState.ACTIVE, 0);
        var changed = original.updateMetadata(0, original.metadata());
        assertThat(changed.revision()).isEqualTo(1);
        assertThatThrownBy(() -> changed.updateMetadata(0, original.metadata()))
                .isInstanceOf(DocumentRevisionConflictException.class);
    }

    @Test
    void archivePreservesMetadataAndRepeatedArchiveDoesNotAdvanceRevision() {
        var original = document(BusinessDocumentState.ACTIVE, 5);
        var archived = original.archive();
        assertThat(archived.state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(archived.revision()).isEqualTo(6);
        assertThat(archived.metadata()).isEqualTo(original.metadata());
        assertThat(archived.archive()).isSameAs(archived);
        assertThatThrownBy(() -> archived.updateMetadata(5, Map.of()))
                .isInstanceOf(DocumentRevisionConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void deletionPreservesIdentityAndMetadataAndCannotBeRepeatedAsAChange(BusinessDocumentState state) {
        var original = document(state, 2);
        var deleted = original.delete();
        assertThat(deleted.state()).isEqualTo(BusinessDocumentState.DELETED);
        assertThat(deleted.revision()).isEqualTo(3);
        assertThat(deleted.metadata()).isEqualTo(original.metadata());
        assertThat(deleted.owner()).isEqualTo(owner);
        assertThat(deleted.id()).isEqualTo(id);
        assertThat(deleted.delete()).isSameAs(deleted);
        assertThatThrownBy(deleted::archive).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> deleted.updateMetadata(3, Map.of())).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void rejectsInvalidAndExhaustedRevisionsWithoutChangingTheSnapshot() {
        assertThatIllegalArgumentException().isThrownBy(() -> document(BusinessDocumentState.ACTIVE, -1));
        var original = document(BusinessDocumentState.ACTIVE, Long.MAX_VALUE);
        assertThatIllegalArgumentException().isThrownBy(() -> original.updateMetadata(-1, Map.of()));
        assertThatThrownBy(original::archive).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(original::delete).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> original.updateMetadata(Long.MAX_VALUE, Map.of())).isInstanceOf(ArithmeticException.class);
        assertThat(original.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(original.state()).isEqualTo(BusinessDocumentState.ACTIVE);
    }

    private BusinessDocument document(BusinessDocumentState state, long revision) {
        return new BusinessDocument(id, owner, Map.of("original", true), state, revision);
    }
}
