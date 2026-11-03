package dev.apitestingplatform.application.operations;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Export/backup destinations must never replace the running database, settings, logs or recovery files. */
public final class ManagedFiles {
    private ManagedFiles() { }
    public static Path output(Path database, Path destination, boolean backup) throws IOException {
        Path requested = destination.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested)) throw new IOException("Choose an output file rather than a symbolic link.");
        // Diagnostics must still work if startup could not create the data directory.
        Path directory = database.toAbsolutePath().normalize().getParent();
        if (Files.exists(directory)) directory = directory.toRealPath();
        Path db = directory.resolve(database.getFileName()), target = requested.getParent().toRealPath().resolve(requested.getFileName());
        List<Path> exact = List.of(db, db.resolveSibling(db.getFileName() + "-wal"), db.resolveSibling(db.getFileName() + "-shm"), db.resolveSibling(db.getFileName() + ".lock"),
                directory.resolve("appearance.properties"), directory.resolve("operations.properties"), directory.resolve("restore-pending.sqlite"), directory.resolve("restore-pending.properties"), directory.resolve("restore-in-progress.properties"));
        if (exact.contains(target)) throw new IOException("Choose an output file that does not replace managed application data.");
        for (String name : List.of("logs", "migration-backups", "recovery-backups")) if (target.startsWith(directory.resolve(name))) throw new IOException("Choose an output outside logs and automatic recovery backups.");
        if (!backup && target.startsWith(directory.resolve("backups"))) throw new IOException("Choose an export file outside the database backups folder.");
        if (Files.exists(target) && (Files.isSymbolicLink(target) || Files.exists(db) && Files.isSameFile(target, db))) throw new IOException("The output aliases a managed file. Choose a different destination.");
        return target;
    }
}
