package dev.apitestingplatform.ui;

import dev.apitestingplatform.ui.controller.MainWindowController;
import javafx.scene.Parent;

/** Entry point for constructing the JavaFX presentation layer. */
public final class MainView {
    private MainView() {
    }

    public static Parent create() {
        return new MainWindowController().createView();
    }
}

