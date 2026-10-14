package dev.apitestingplatform.app;

/** Immutable startup values kept independent from the JavaFX view layer. */
public record StartupConfiguration(
        String windowTitle,
        double initialWidth,
        double initialHeight,
        double minimumWidth,
        double minimumHeight) {

    public static StartupConfiguration defaults() {
        return new StartupConfiguration("API Testing Platform", 1400, 900, 1050, 700);
    }
}

