package io.github.pitrecki.dokubridge.core.domain.content;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class ContentSha256Test {
    @ParameterizedTest
    @CsvSource(value = {
            "'', e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc, ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    })
    void computesStandardSha256Vectors(String content, String expected) {
        assertThat(ContentSha256.of(content.getBytes(StandardCharsets.UTF_8)).value()).isEqualTo(expected);
    }

    @Test
    void rejectsMissingContent() {
        assertThatNullPointerException().isThrownBy(() -> ContentSha256.of(null));
    }
}
