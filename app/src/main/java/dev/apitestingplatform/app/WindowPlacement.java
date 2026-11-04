package dev.apitestingplatform.app;

/**
 * Initial main-window geometry for the screen it opens on. Sizes are outer window sizes in JavaFX
 * logical pixels, so a 1920x1080 display at 150 % scaling offers about 1280x690 after the taskbar.
 * The window never starts larger than that area, so its title bar with minimise, maximise and close
 * buttons is always visible; screens too small for the normal minimum open maximised instead.
 */
record WindowPlacement(double x, double y, double width, double height,
                       double minimumWidth, double minimumHeight, boolean maximized) {
    static final double MARGIN = 24;

    static WindowPlacement fit(StartupConfiguration configuration,
                               double areaX, double areaY, double areaWidth, double areaHeight) {
        double minimumWidth = Math.min(configuration.minimumWidth(), areaWidth);
        double minimumHeight = Math.min(configuration.minimumHeight(), areaHeight);
        boolean maximized = areaWidth < configuration.minimumWidth() + 2 * MARGIN
                || areaHeight < configuration.minimumHeight() + 2 * MARGIN;
        double width = clamp(configuration.initialWidth(), minimumWidth, Math.max(minimumWidth, areaWidth - 2 * MARGIN));
        double height = clamp(configuration.initialHeight(), minimumHeight, Math.max(minimumHeight, areaHeight - 2 * MARGIN));
        return new WindowPlacement(areaX + (areaWidth - width) / 2, areaY + (areaHeight - height) / 2,
                width, height, minimumWidth, minimumHeight, maximized);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
