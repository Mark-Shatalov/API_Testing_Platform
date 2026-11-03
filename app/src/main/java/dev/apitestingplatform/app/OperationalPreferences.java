package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.operations.OperationalSettings;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Called on init/operations worker, never on JavaFX. Invalid input remains available and falls back safely. */
final class OperationalPreferences {
    record Loaded(OperationalSettings settings, String notice) { }
    private final Path file;
    OperationalPreferences(Path file) { this.file = file.toAbsolutePath().normalize(); }
    Loaded load() {
        if (!Files.exists(file)) return new Loaded(OperationalSettings.defaults(), "");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1); if (bytes.length > 256 * 1024) throw new IOException();
            Properties values = new Properties(); values.load(new ByteArrayInputStream(bytes));
            if (!"1".equals(values.getProperty("version"))) throw new IOException();
            var settings = new OperationalSettings(OperationalSettings.ProxyMode.valueOf(values.getProperty("proxyMode")), values.getProperty("proxyHost", ""),
                    Integer.parseInt(values.getProperty("proxyPort")), values.getProperty("additionalCaFile", ""), Integer.parseInt(values.getProperty("responseLimitKiB")));
            return new Loaded(settings, "");
        } catch (IOException | RuntimeException invalid) { return new Loaded(OperationalSettings.defaults(), "Operational settings could not be read. Safe defaults are active; the original file was kept. Save settings to replace it."); }
    }
    void save(OperationalSettings settings) throws IOException {
        Files.createDirectories(file.getParent()); Properties values = new Properties(); values.setProperty("version", "1"); values.setProperty("proxyMode", settings.proxyMode().name());
        values.setProperty("proxyHost", settings.proxyHost()); values.setProperty("proxyPort", Integer.toString(settings.proxyPort())); values.setProperty("additionalCaFile", settings.additionalCaFile()); values.setProperty("responseLimitKiB", Integer.toString(settings.responseLimitKiB()));
        Path temporary = Files.createTempFile(file.getParent(), "operations-", ".tmp");
        try { try (var output = Files.newOutputStream(temporary)) { values.store(output, "API Testing Platform operational settings"); } Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
}
