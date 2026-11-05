package io.github.pitrecki.dokubridge.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DocumentOwnerRefTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingOwnerType(String type) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentOwnerRef(type, "42"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingOwnerId(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DocumentOwnerRef("Invoice", id));
    }

    @Test
    void identifiesOwnerByBothTypeAndExactId() {
        var owner = new DocumentOwnerRef("Invoice", " 0042 ");
        assertThat(owner).isEqualTo(new DocumentOwnerRef("Invoice", " 0042 "));
        assertThat(owner).isNotEqualTo(new DocumentOwnerRef("Order", " 0042 "));
        assertThat(owner).isNotEqualTo(new DocumentOwnerRef("Invoice", "0042"));
        assertThat(owner.id()).isEqualTo(" 0042 ");
    }
}
