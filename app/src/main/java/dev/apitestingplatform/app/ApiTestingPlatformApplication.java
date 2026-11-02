package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.persistence.sqlite.DataDirectory;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import dev.apitestingplatform.ui.theme.Appearance;
import dev.apitestingplatform.ui.theme.ThemeManager;

public final class ApiTestingPlatformApplication extends Application {
    private JavaHttpExecutionEngine engine;
    private LibraryWindowController controller;
    private SqliteLibraryRepository repository;
    private AppearancePreferences preferences;
    private Appearance appearance;
    private ThemeManager themes;

    @Override public void init() {
        // JavaFX calls init on the launcher thread, before the FX application thread starts.
        engine = new JavaHttpExecutionEngine();
        repository = new SqliteLibraryRepository(DataDirectory.database(), new DpapiPayloadProtector());
        preferences = new AppearancePreferences(DataDirectory.database().resolveSibling("appearance.properties"));
        appearance = preferences.load();
    }
    @Override
    public void start(Stage stage) {
        StartupConfiguration configuration = StartupConfiguration.defaults();

        stage.setTitle(configuration.windowTitle());
        stage.setMinWidth(configuration.minimumWidth());
        stage.setMinHeight(configuration.minimumHeight());
        controller = new LibraryWindowController(engine, repository);
        controller.setAppearanceAction(() -> themes.showSettings(stage));
        stage.setScene(new Scene(
                controller.createView(),
                configuration.initialWidth(),
                configuration.initialHeight()));
        themes = new ThemeManager(stage.getScene(), appearance, preferences::save);
        stage.setOnCloseRequest(event -> {
            event.consume(); controller.requestWindowClose(stage::hide);
        });
        stage.show();
    }

    @Override public void stop() {
        if (themes != null) themes.close();
        if (preferences != null) preferences.close();
        if (controller != null) controller.close();
        if (engine != null) engine.close();
        if (repository != null) repository.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

