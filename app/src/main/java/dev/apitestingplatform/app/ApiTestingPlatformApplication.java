package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.persistence.sqlite.DataDirectory;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.application.http.HttpExecutionEngine;
import dev.apitestingplatform.application.operations.EventLog;
import dev.apitestingplatform.httpclient.NetworkConfiguration;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import dev.apitestingplatform.ui.theme.Appearance;
import dev.apitestingplatform.ui.theme.ThemeManager;

public final class ApiTestingPlatformApplication extends Application {
    private HttpExecutionEngine engine;
    private LibraryWindowController controller;
    private SqliteLibraryRepository repository;
    private AppearancePreferences preferences;
    private Appearance appearance;
    private ThemeManager themes;
    private OperationsService operations;
    private OperationalLog log;
    private Thread.UncaughtExceptionHandler previousHandler;
    private final java.util.UUID session = java.util.UUID.randomUUID();

    @Override public void init() {
        // JavaFX calls init on the launcher thread, before the FX application thread starts.
        // Must precede the first JVM default ProxySelector lookup.
        System.setProperty("java.net.useSystemProxies", "true");
        var file = DataDirectory.database();
        log = new OperationalLog(file.resolveSibling("logs"));
        var operationalPreferences = new OperationalPreferences(file.resolveSibling("operations.properties"));
        var loaded = operationalPreferences.load();
        String notice = loaded.notice();
        try { engine = NetworkConfiguration.create(loaded.settings()); }
        catch (java.io.IOException invalidCa) {
            var settings = loaded.settings();
            try { engine = NetworkConfiguration.create(new dev.apitestingplatform.domain.operations.OperationalSettings(settings.proxyMode(), settings.proxyHost(), settings.proxyPort(), "", settings.responseLimitKiB())); }
            catch (java.io.IOException impossible) { throw new IllegalStateException("Default TLS configuration is unavailable."); }
            notice += " The additional CA file is unavailable or invalid. JVM trust is active. Review Network settings.";
        }
        engine = new LoggedHttpEngine(engine, log);
        repository = new SqliteLibraryRepository(DataDirectory.database(), new DpapiPayloadProtector());
        repository.setEventLog(log);
        repository.ready().whenComplete((ignored, failure) -> {
            if (failure != null) log.record(EventLog.Level.ERROR, EventLog.Component.STORAGE, EventLog.Code.STORAGE_FAILED, session);
            else if (!repository.startupNotice().isEmpty()) {
                boolean applied = repository.startupNotice().startsWith("Database restored") || repository.startupNotice().startsWith("The database restore was applied");
                log.record(applied ? EventLog.Level.INFO : EventLog.Level.WARN, EventLog.Component.BACKUP, applied ? EventLog.Code.RESTORE_APPLIED : EventLog.Code.RESTORE_REJECTED, session);
            }
        });
        operations = new OperationsService(repository, operationalPreferences, log, notice);
        log.record(EventLog.Level.INFO, EventLog.Component.STARTUP, EventLog.Code.APP_STARTED, session);
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            log.record(EventLog.Level.ERROR, EventLog.Component.UI, EventLog.Code.UNEXPECTED_ERROR, session);
            javafx.application.Platform.runLater(() -> { if (controller != null) controller.reportUnexpectedFailure(); });
        });
        preferences = new AppearancePreferences(DataDirectory.database().resolveSibling("appearance.properties"));
        appearance = preferences.load();
    }
    @Override
    public void start(Stage stage) {
        StartupConfiguration configuration = StartupConfiguration.defaults();

        // An ordinary Windows window: title bar with minimise, maximise and close, sized to the screen it opens on.
        stage.initStyle(javafx.stage.StageStyle.DECORATED);
        stage.setTitle(configuration.windowTitle());
        addIcons(stage);
        javafx.geometry.Rectangle2D area = startupScreen().getVisualBounds();
        WindowPlacement placement = WindowPlacement.fit(configuration, area.getMinX(), area.getMinY(), area.getWidth(), area.getHeight());
        stage.setMinWidth(placement.minimumWidth());
        stage.setMinHeight(placement.minimumHeight());
        stage.setX(placement.x()); stage.setY(placement.y());
        stage.setWidth(placement.width()); stage.setHeight(placement.height());
        stage.setMaximized(placement.maximized());
        controller = new LibraryWindowController(engine, repository);
        controller.setOperations(operations);
        controller.setEventLog(log);
        controller.setOnReady(() -> StartupProbe.ready(stage, controller));
        controller.setAppearanceAction(() -> themes.showSettings(stage));
        stage.setScene(new Scene(controller.createView()));
        enableFullScreenToggle(stage);
        themes = new ThemeManager(stage.getScene(), appearance, preferences::save);
        stage.setOnCloseRequest(event -> {
            event.consume(); controller.requestWindowClose(stage::hide);
        });
        stage.show();
    }

    /**
     * F11 switches between the normal window and full screen, as in browsers. Escape keeps its existing
     * meaning in the app (for example clearing the library search), so it does not leave full screen.
     */
    static void enableFullScreenToggle(Stage stage) {
        stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("Press F11 to leave full screen");
        stage.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.F11 && !event.isShortcutDown() && !event.isAltDown()) {
                stage.setFullScreen(!stage.isFullScreen());
                event.consume();
            }
        });
    }

    /** The screen under the mouse pointer, so the window opens where the user launched it. */
    private static javafx.stage.Screen startupScreen() {
        try {
            javafx.geometry.Point2D pointer = new javafx.scene.robot.Robot().getMousePosition();
            var screens = javafx.stage.Screen.getScreensForRectangle(pointer.getX(), pointer.getY(), 1, 1);
            if (!screens.isEmpty()) return screens.getFirst();
        } catch (RuntimeException unavailable) { /* fall back to the primary screen */ }
        return javafx.stage.Screen.getPrimary();
    }

    static void addIcons(Stage stage) {
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            try (var icon = ApiTestingPlatformApplication.class.getResourceAsStream("icon-" + size + ".png")) {
                if (icon != null) stage.getIcons().add(new javafx.scene.image.Image(icon));
            } catch (java.io.IOException ignored) { /* the default icon remains */ }
        }
    }

    @Override public void stop() {
        if (themes != null) themes.close();
        if (preferences != null) preferences.close();
        if (controller != null) controller.close();
        if (engine != null) engine.close();
        if (repository != null) repository.close();
        if (operations != null) operations.close();
        if (log != null) { log.record(EventLog.Level.INFO, EventLog.Component.STARTUP, EventLog.Code.APP_STOPPED, session); log.close(); }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    public static void main(String[] args) {
        launch(args);
    }
}

