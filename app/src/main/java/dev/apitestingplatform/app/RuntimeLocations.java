package dev.apitestingplatform.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Native-library extraction caches. JavaFX would otherwise use {@code %USERPROFILE%\.openjfx}, while JNA and
 * SQLite use the system temporary folder. Installed builds keep them below
 * {@code %LOCALAPPDATA%\API Testing Platform\cache\<version>}, never in the installation directory.
 */
final class RuntimeLocations {
    static final String CACHE = "cache";
    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,63}");
    private static final Map<String, String> PROPERTIES = Map.of(
            "javafx.cachedir", "openjfx",
            "jna.tmpdir", "jna",
            "org.sqlite.tmpdir", "sqlite");

    private RuntimeLocations() { }

    /** jpackage writes {@code jpackage.app-version}; development runs share one "dev" cache. */
    static String applicationVersion() {
        String version = System.getProperty("jpackage.app-version", "dev");
        return SAFE_VERSION.matcher(version).matches() ? version : "dev";
    }

    static Path cacheRoot(Path dataDirectory, String version) {
        return dataDirectory.resolve(CACHE).resolve(version);
    }

    /** Sets only properties the user has not overridden, creates the folders and removes caches of older versions. */
    static void configure(Path dataDirectory, String version) {
        Path root = cacheRoot(dataDirectory, version);
        assign(root);
        removeOtherVersions(dataDirectory.resolve(CACHE), version);
    }

    static void configureTemporary(Path temporaryDirectory) {
        assign(temporaryDirectory.resolve("api-testing-platform-maintenance"));
    }

    private static void assign(Path root) {
        PROPERTIES.forEach((property, folder) -> {
            if (System.getProperty(property) != null) return;
            Path target = root.resolve(folder);
            try { Files.createDirectories(target); System.setProperty(property, target.toString()); }
            catch (IOException | SecurityException unavailable) { /* Library defaults remain usable. */ }
        });
    }

    /** Best effort: a library still loaded by another running version stays locked and is skipped. */
    static void removeOtherVersions(Path caches, String current) {
        if (!Files.isDirectory(caches, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> versions = Files.list(caches)) {
            versions.filter(path -> !path.getFileName().toString().equals(current))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(RuntimeLocations::deleteQuietly);
        } catch (IOException ignored) { /* Cache cleanup never blocks startup. */ }
    }

    private static void deleteQuietly(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException | UncheckedIOException ignored) { }
    }
}
