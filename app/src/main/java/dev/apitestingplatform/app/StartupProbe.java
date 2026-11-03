package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.controller.LibraryWindowController;
import javafx.application.Platform;
import javafx.stage.Stage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.Properties;

/** Opt-in benchmark hook. No production file access or timing work when the property is absent. */
final class StartupProbe {
    private StartupProbe() { }
    static void ready(Stage stage, LibraryWindowController controller) {
        String destination = System.getProperty("platform.startup.measurement");
        if (destination == null) return;
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        Properties result = new Properties();
        result.setProperty("jvmToReadyMs", Long.toString(ManagementFactory.getRuntimeMXBean().getUptime()));
        result.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        result.setProperty("usedHeapMiB", Long.toString((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024));
        Thread.ofPlatform().daemon().name("startup-measurement").start(() -> {
            try {
                try (var output = Files.newOutputStream(Path.of(destination))) { result.store(output, "Fresh application JVM, through library load and CSS/layout; Maven excluded"); }
                if (Boolean.getBoolean("platform.startup.autoExit")) { Thread.sleep(4000); Platform.runLater(() -> controller.requestWindowClose(stage::hide)); }
            } catch (Exception ignored) { /* An optional benchmark cannot affect normal use. */ }
        });
    }
}
