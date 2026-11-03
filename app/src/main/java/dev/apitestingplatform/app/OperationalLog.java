package dev.apitestingplatform.app;

import dev.apitestingplatform.application.operations.EventLog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Rotating structured-only log: no user strings or Throwable objects cross this boundary. */
final class OperationalLog implements EventLog, AutoCloseable {
    private static final int FILE_LIMIT = 512 * 1024;
    private final Path directory;
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("safe-log-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong dropped = new AtomicLong();
    private volatile String notice = "";
    OperationalLog(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    Path directory() { return directory; }
    String notice() { return notice; }
    long dropped() { return dropped.get(); }
    @Override public void record(Level level, Component component, Code code, UUID id, Map<Metric, Long> metrics) {
        try {
            Objects.requireNonNull(level); Objects.requireNonNull(component); Objects.requireNonNull(code); Objects.requireNonNull(id);
            TreeMap<Metric, Long> values = new TreeMap<>(metrics);
            if (values.size() > Metric.values().length || values.values().stream().anyMatch(value -> value == null || value < 0 || value > 1_000_000_000_000L)) throw new IllegalArgumentException();
            String numbers = String.join(";", values.entrySet().stream().map(entry -> entry.getKey().name() + "=" + entry.getValue()).toList());
            String line = Instant.now() + "|" + level + "|" + component + "|" + id + "|" + code + "|" + numbers + "\n";
            writer.execute(() -> {
                try { Files.createDirectories(directory); Path current = directory.resolve("operations.log");
                    if (Files.exists(current) && Files.size(current) + line.length() > FILE_LIMIT) {
                        Files.deleteIfExists(directory.resolve("operations.3.log"));
                        for (int index = 2; index >= 1; index--) { Path previous = directory.resolve("operations." + index + ".log"); if (Files.exists(previous)) Files.move(previous, directory.resolve("operations." + (index + 1) + ".log"), StandardCopyOption.REPLACE_EXISTING); }
                        Files.move(current, directory.resolve("operations.1.log"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.writeString(current, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); notice = "";
                } catch (IOException failure) { notice = "The log directory is not writable. Application data was kept; diagnostics can still export system information."; }
            });
        } catch (RejectedExecutionException full) { dropped.incrementAndGet(); }
        catch (RuntimeException invalid) { dropped.incrementAndGet(); }
    }
    CompletionStage<byte[]> snapshot() {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try { writer.execute(() -> {
            try { ByteArrayOutputStream output = new ByteArrayOutputStream();
                for (String name : List.of("operations.3.log", "operations.2.log", "operations.1.log", "operations.log")) {
                    Path file = directory.resolve(name); if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    byte[] bytes; try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(FILE_LIMIT + 1); }
                    if (bytes.length > FILE_LIMIT) continue;
                    for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) if (safe(line)) output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
                result.complete(output.toByteArray());
            } catch (IOException failure) { result.complete(new byte[0]); }
        }); } catch (RejectedExecutionException closed) { result.complete(new byte[0]); }
        return result.minimalCompletionStage();
    }
    private static boolean safe(String line) {
        if (line.length() > 1024) return false;
        try { String[] fields = line.split("\\|", -1); if (fields.length != 6) return false;
            Instant.parse(fields[0]); Level.valueOf(fields[1]); Component.valueOf(fields[2]); UUID.fromString(fields[3]); Code.valueOf(fields[4]);
            Set<Metric> keys = new HashSet<>();
            if (!fields[5].isEmpty()) for (String field : fields[5].split(";", -1)) { String[] pair = field.split("=", -1); if (pair.length != 2 || !keys.add(Metric.valueOf(pair[0])) || !pair[1].matches("[0-9]{1,13}") || Long.parseLong(pair[1]) > 1_000_000_000_000L) return false; }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    @Override public void close() { writer.shutdown(); }
}
