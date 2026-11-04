package dev.apitestingplatform.app;

import dev.apitestingplatform.persistence.sqlite.DataDirectory;
import javafx.application.Application;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Entry point used by the installed launchers and {@code javafx:run}.
 * It runs before any JavaFX, JNA or SQLite native library is loaded, so their extraction caches can be
 * redirected from the user profile root and the system temporary folder into the per-user data directory.
 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains(AppUninstaller.ARGUMENT)) {
            // Runs from the installation folder that msiexec removes; keep native caches in the temp folder.
            RuntimeLocations.configureTemporary(Path.of(System.getProperty("java.io.tmpdir")));
            Application.launch(UninstallApplication.class);
            return;
        }
        if (Arrays.asList(args).contains(UserDataRemoval.ARGUMENT)) {
            // Removal must not load native caches from the directory it is about to delete.
            RuntimeLocations.configureTemporary(Path.of(System.getProperty("java.io.tmpdir")));
            Application.launch(UserDataRemovalApplication.class);
            return;
        }
        Path data = null;
        try { data = DataDirectory.database().getParent(); }
        catch (IllegalStateException unavailable) { /* The application reports missing LOCALAPPDATA during startup. */ }
        if (data != null) RuntimeLocations.configure(data, RuntimeLocations.applicationVersion());
        Application.launch(ApiTestingPlatformApplication.class, args);
    }
}
