package dev.apitestingplatform.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLocationsTest {
    private static final List<String> PROPERTIES = List.of("javafx.cachedir", "jna.tmpdir", "org.sqlite.tmpdir", "jpackage.app-version");
    private final Map<String, String> saved = new HashMap<>();
    @TempDir Path data;

    RuntimeLocationsTest() { PROPERTIES.forEach(name -> saved.put(name, System.getProperty(name))); }

    @AfterEach void restore() {
        saved.forEach((name, value) -> { if (value == null) System.clearProperty(name); else System.setProperty(name, value); });
    }

    @Test void nativeCachesLiveInTheVersionedDataFolderAndOlderVersionsAreRemoved() throws Exception {
        PROPERTIES.forEach(System::clearProperty);
        Path old = Files.createDirectories(data.resolve("cache").resolve("0.0.9").resolve("openjfx"));
        Files.writeString(old.resolve("glass.dll"), "old");
        Path unrelated = Files.writeString(data.resolve("platform.sqlite"), "db");

        RuntimeLocations.configure(data, "0.1.0");

        Path root = data.resolve("cache").resolve("0.1.0");
        assertThat(System.getProperty("javafx.cachedir")).isEqualTo(root.resolve("openjfx").toString());
        assertThat(System.getProperty("jna.tmpdir")).isEqualTo(root.resolve("jna").toString());
        assertThat(System.getProperty("org.sqlite.tmpdir")).isEqualTo(root.resolve("sqlite").toString());
        assertThat(root.resolve("openjfx")).isDirectory();
        assertThat(data.resolve("cache").resolve("0.0.9")).doesNotExist();
        assertThat(unrelated).hasContent("db");
    }

    @Test void explicitOverridesAreKept() {
        System.setProperty("javafx.cachedir", "C:/custom");
        RuntimeLocations.configure(data, "dev");
        assertThat(System.getProperty("javafx.cachedir")).isEqualTo("C:/custom");
    }

    @Test void versionComesFromTheInstalledLauncherAndIsSanitised() {
        System.clearProperty("jpackage.app-version");
        assertThat(RuntimeLocations.applicationVersion()).isEqualTo("dev");
        System.setProperty("jpackage.app-version", "0.2.0");
        assertThat(RuntimeLocations.applicationVersion()).isEqualTo("0.2.0");
        System.setProperty("jpackage.app-version", "..\\..\\escape");
        assertThat(RuntimeLocations.applicationVersion()).isEqualTo("dev");
    }
}
