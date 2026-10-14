package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.controller.MainWindowController;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ApiTestingPlatformApplication extends Application {
    private JavaHttpExecutionEngine engine;
    private MainWindowController controller;

    @Override public void init() {
        // JavaFX calls init on the launcher thread, before the FX application thread starts.
        engine = new JavaHttpExecutionEngine();
    }
    @Override
    public void start(Stage stage) {
        StartupConfiguration configuration = StartupConfiguration.defaults();

        stage.setTitle(configuration.windowTitle());
        stage.setMinWidth(configuration.minimumWidth());
        stage.setMinHeight(configuration.minimumHeight());
        controller = new MainWindowController(engine);
        stage.setScene(new Scene(
                controller.createView(),
                configuration.initialWidth(),
                configuration.initialHeight()));
        stage.show();
    }

    @Override public void stop() {
        if (controller != null) controller.close();
        if (engine != null) engine.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

