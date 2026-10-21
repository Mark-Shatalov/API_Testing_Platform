package dev.apitestingplatform.persistence.sqlite;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apitestingplatform.application.storage.PayloadProtector;
import dev.apitestingplatform.domain.storage.RequestDraft;
import java.util.Arrays;
import java.util.Base64;

/** Versioned JSON envelope in TEXT; payload uses current-user DPAPI in production. */
final class ProtectedDraftCodec {
    private static final int LIMIT = 32 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final PayloadProtector protector;
    ProtectedDraftCodec(PayloadProtector protector) {
        this.protector = protector;
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(LIMIT).build());
    }
    String encode(RequestDraft draft) throws Exception {
        var output = new java.io.ByteArrayOutputStream() {
            void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
            @Override public synchronized void write(byte[] data, int offset, int length) {
                if (length > LIMIT - count) throw new IllegalArgumentException("Draft too large");
                super.write(data, offset, length);
            }
            @Override public synchronized void write(int value) {
                if (count >= LIMIT) throw new IllegalArgumentException("Draft too large"); super.write(value);
            }
        };
        byte[] plain = null;
        try {
            json.writeValue(output, draft); plain = output.toByteArray();
            return "{\"version\":1,\"protection\":\"current-user\",\"payload\":\"" + Base64.getEncoder().encodeToString(protector.protect(plain)) + "\"}";
        } finally { if (plain != null) Arrays.fill(plain, (byte) 0); output.wipe(); }
    }
    RequestDraft decode(String envelope) throws Exception {
        if (envelope.length() > LIMIT * 2) throw new IllegalArgumentException("Payload too large");
        var tree = json.readTree(envelope);
        if (tree.path("version").asInt() != 1 || !tree.path("protection").asText().equals("current-user"))
            throw new IllegalArgumentException("Unsupported payload version");
        byte[] plain = protector.unprotect(Base64.getDecoder().decode(tree.path("payload").asText()));
        try {
            if (plain.length > LIMIT) throw new IllegalArgumentException("Payload too large");
            return json.readValue(plain, RequestDraft.class);
        } finally { Arrays.fill(plain, (byte) 0); }
    }
}
