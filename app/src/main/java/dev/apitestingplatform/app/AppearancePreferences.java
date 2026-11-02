package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.theme.Appearance;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;

/** Local preferences: startup reads and serialized background writes, outside the install folder. */
final class AppearancePreferences implements AutoCloseable {
    private final Path file;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("appearance-save-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    AppearancePreferences(Path file) { this.file = file.toAbsolutePath().normalize(); }
    Appearance load() {
        try (var input = Files.newInputStream(file)) {
            Properties properties = new Properties(); properties.load(input);
            return new Appearance(Appearance.Theme.valueOf(properties.getProperty("theme", "GRAPHITE")),
                    properties.getProperty("accent", Appearance.defaults().accent()));
        } catch (IOException | IllegalArgumentException failure) { return Appearance.defaults(); }
    }
    CompletionStage<Void> save(Appearance appearance) {
        try {
            return CompletableFuture.runAsync(() -> {
                Path temporary = null;
                try {
                    Files.createDirectories(file.getParent());
                    temporary = Files.createTempFile(file.getParent(), "appearance-", ".tmp");
                    Properties properties = new Properties();
                    properties.setProperty("theme", appearance.theme().name()); properties.setProperty("accent", appearance.accent());
                    try (var output = Files.newOutputStream(temporary)) { properties.store(output, "API Testing Platform appearance"); }
                    try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                } catch (IOException failure) { throw new CompletionException(failure); }
                finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
            }, writer);
        } catch (RejectedExecutionException unavailable) { return CompletableFuture.failedFuture(unavailable); }
    }
    @Override public void close() { writer.shutdown(); }
}
