package dev.apitestingplatform.persistence.sqlite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record Migration(int version, String sql, boolean incompatible) {
    public Migration {
        if (version < 1 || sql == null || sql.isBlank()) throw new IllegalArgumentException("Invalid migration");
        sql = sql.replace("\r\n", "\n").replace('\r', '\n');
    }
    public String checksum() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
