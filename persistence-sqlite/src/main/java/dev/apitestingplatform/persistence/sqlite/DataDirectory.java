package dev.apitestingplatform.persistence.sqlite;

import java.nio.file.Path;

public final class DataDirectory {
    private DataDirectory() { }
    public static Path database() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) throw new IllegalStateException("LOCALAPPDATA is unavailable");
        return Path.of(local, "API Testing Platform", "platform.sqlite").toAbsolutePath().normalize();
    }
}
