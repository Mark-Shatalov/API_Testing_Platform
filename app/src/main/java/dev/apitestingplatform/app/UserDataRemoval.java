package dev.apitestingplatform.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Explicit, user-requested removal of this Windows user's local data. Uninstalling never calls this:
 * the installer leaves {@code %LOCALAPPDATA%\API Testing Platform} untouched unless the user runs the
 * separate "Remove API Testing Platform Data" shortcut and confirms.
 */
final class UserDataRemoval {
    static final String ARGUMENT = "--remove-user-data";
    static final String FOLDER = "API Testing Platform";
    private static final String LOCK = "platform.sqlite.lock";

    enum Outcome { REMOVED, NOTHING_TO_REMOVE, IN_USE, REFUSED, INCOMPLETE }
    record Result(Outcome outcome, Path directory, int remainingEntries) { }

    private UserDataRemoval() { }

    /**
     * Deletes {@code localAppData\API Testing Platform} completely. Refuses any other location, refuses while
     * the application holds its single-instance lock, and never follows links or junctions out of the folder.
     */
    static Result remove(Path localAppData, Path dataDirectory) {
        Path base = localAppData.toAbsolutePath().normalize();
        Path directory = dataDirectory.toAbsolutePath().normalize();
        if (directory.getParent() == null || !directory.getParent().equals(base) || !directory.getFileName().toString().equals(FOLDER))
            return new Result(Outcome.REFUSED, directory, 0);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new Result(Outcome.NOTHING_TO_REMOVE, directory, 0);
        BasicFileAttributes root;
        try { root = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException unreadable) { return new Result(Outcome.INCOMPLETE, directory, 1); }
        if (!isPlainDirectory(root)) return new Result(Outcome.REFUSED, directory, 0);

        Path lockPath = directory.resolve(LOCK);
        List<Path> failures = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            final FileLock lock = tryLock(channel);
            if (lock == null) return new Result(Outcome.IN_USE, directory, 0);
            try (lock) { deleteChildren(directory, lockPath, failures); }
        } catch (IOException lockUnavailable) {
            return new Result(Outcome.IN_USE, directory, 0);
        }
        deleteEntry(lockPath, failures);
        if (failures.isEmpty()) deleteEntry(directory, failures);
        return failures.isEmpty() ? new Result(Outcome.REMOVED, directory, 0) : new Result(Outcome.INCOMPLETE, directory, failures.size());
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); } catch (OverlappingFileLockException sameProcess) { return null; }
    }

    private static void deleteChildren(Path directory, Path keep, List<Path> failures) {
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (child.equals(keep)) continue;
                deleteTree(child, failures);
            }
        } catch (IOException unreadable) { failures.add(directory); }
    }

    private static void deleteTree(Path path, List<Path> failures) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // A link or NTFS junction is removed itself; its target outside the data folder is never visited.
            if (isPlainDirectory(attributes)) deleteChildren(path, null, failures);
        } catch (IOException unreadable) { failures.add(path); return; }
        deleteEntry(path, failures);
    }

    private static void deleteEntry(Path path, List<Path> failures) {
        try { Files.deleteIfExists(path); } catch (IOException locked) { failures.add(path); }
    }

    /** On Windows a junction reports {@code isDirectory() && isOther()}; only ordinary folders are descended. */
    private static boolean isPlainDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }
}
