package dev.apitestingplatform.ui;

import dev.apitestingplatform.ui.controller.MainWindowController;
import javafx.scene.Parent;
import javafx.scene.text.Font;
import java.io.InputStream;
import java.io.IOException;
import java.util.Objects;

/** Entry point for constructing the JavaFX presentation layer. */
public final class MainView {
    private MainView() {
    }

    public static Parent create() {
        return new MainWindowController().createView();
    }

    /** Register the bundled static font faces once, before any application scene is styled. */
    public static String stylesheet() { return Resources.STYLESHEET; }

    private static final class Resources {
        private static final String STYLESHEET = load();
        private static String load() {
            for (String face : new String[]{"Regular", "Medium", "SemiBold"}) {
                try (InputStream data = MainView.class.getResourceAsStream("fonts/Geist-" + face + ".ttf")) {
                    if (data != null) Font.loadFont(data, 13);
                } catch (IOException unavailable) { /* System typeface remains a usable fallback. */ }
            }
            return Objects.requireNonNull(MainView.class.getResource("main.css"), "UI stylesheet is missing").toExternalForm();
        }
    }
}

