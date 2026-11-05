package io.github.pitrecki.dokubridge.core.application.upload;

import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class UploadFingerprint {
    private final MessageDigest digest = sha256();

    private UploadFingerprint() {
        string("dokubridge-upload-v1");
    }

    static String attach(AttachDocument command) {
        var fingerprint = new UploadFingerprint();
        fingerprint.string("attach");
        fingerprint.string(command.owner().type());
        fingerprint.string(command.owner().id());
        fingerprint.value(command.metadata());
        fingerprint.string(command.checksum().value());
        return HexFormat.of().formatHex(fingerprint.digest.digest());
    }

    static String version(AddDocumentVersion command) {
        var fingerprint = new UploadFingerprint();
        fingerprint.string("version");
        fingerprint.string(command.documentId().toString());
        fingerprint.string(command.checksum().value());
        return HexFormat.of().formatHex(fingerprint.digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JVM does not provide SHA-256", unavailable);
        }
    }

    private void value(Object value) {
        if (value == null) {
            digest.update((byte) 0);
        } else if (value instanceof String text) {
            digest.update((byte) 1);
            string(text);
        } else if (value instanceof Boolean flag) {
            digest.update(flag ? (byte) 2 : (byte) 3);
        } else if (value instanceof Number number) {
            digest.update((byte) 4);
            string(new BigDecimal(number.toString()).stripTrailingZeros().toString());
        } else if (value instanceof Map<?, ?> map) {
            digest.update((byte) 5);
            integer(map.size());
            map.keySet().stream().map(String.class::cast).sorted().forEach(key -> {
                string(key);
                value(map.get(key));
            });
        } else if (value instanceof List<?> list) {
            digest.update((byte) 6);
            integer(list.size());
            list.forEach(this::value);
        } else {
            throw new IllegalArgumentException("Metadata must be a validated JSON snapshot");
        }
    }

    private void integer(int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private void string(String value) {
        integer(value.length());
        // Length-delimited UTF-16 code units avoid lossy replacement of unpaired surrogates.
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            digest.update((byte) (unit >>> 8));
            digest.update((byte) unit);
        }
    }
}
