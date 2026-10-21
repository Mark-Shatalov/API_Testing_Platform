package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.persistence.sqlite.DataDirectory;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ApiTestingPlatformApplication extends Application {
    private JavaHttpExecutionEngine engine;
    private LibraryWindowController controller;
    private SqliteLibraryRepository repository;

    @Override public void init() {
        // JavaFX calls init on the launcher thread, before the FX application thread starts.
        engine = new JavaHttpExecutionEngine();
        repository = new SqliteLibraryRepository(DataDirectory.database(), new DpapiPayloadProtector());
    }
    @Override
    public void start(Stage stage) {
        StartupConfiguration configuration = StartupConfiguration.defaults();

        stage.setTitle(configuration.windowTitle());
        stage.setMinWidth(configuration.minimumWidth());
        stage.setMinHeight(configuration.minimumHeight());
        controller = new LibraryWindowController(engine, repository);
        stage.setScene(new Scene(
                controller.createView(),
                configuration.initialWidth(),
                configuration.initialHeight()));
        stage.setOnCloseRequest(event -> {
            event.consume(); controller.requestWindowClose(stage::hide);
        });
        stage.show();
    }

    @Override public void stop() {
        if (controller != null) controller.close();
        if (engine != null) engine.close();
        if (repository != null) repository.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

