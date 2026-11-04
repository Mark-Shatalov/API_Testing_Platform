package dev.apitestingplatform.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WindowPlacementTest {
    private final StartupConfiguration defaults = StartupConfiguration.defaults();

    private static void assertInside(WindowPlacement placement, double x, double y, double width, double height) {
        assertThat(placement.x()).isGreaterThanOrEqualTo(x);
        assertThat(placement.y()).as("title bar stays on screen").isGreaterThanOrEqualTo(y);
        assertThat(placement.x() + placement.width()).isLessThanOrEqualTo(x + width);
        assertThat(placement.y() + placement.height()).isLessThanOrEqualTo(y + height);
        assertThat(placement.minimumWidth()).isLessThanOrEqualTo(width);
        assertThat(placement.minimumHeight()).isLessThanOrEqualTo(height);
    }

    @Test void fullHdAtOneHundredPercentUsesTheDesignedSizeCentred() {
        WindowPlacement placement = WindowPlacement.fit(defaults, 0, 0, 1920, 1032);
        assertThat(placement.width()).isEqualTo(1400);
        assertThat(placement.height()).isEqualTo(900);
        assertThat(placement.maximized()).isFalse();
        assertThat(placement.x()).isEqualTo(260);
        assertInside(placement, 0, 0, 1920, 1032);
    }

    @Test void laptopAtOneHundredFiftyPercentFitsInsteadOfOverflowing() {
        // 1920x1080 at 150 %: 1280x720 logical, about 688 high above the taskbar.
        WindowPlacement placement = WindowPlacement.fit(defaults, 0, 0, 1280, 688);
        assertInside(placement, 0, 0, 1280, 688);
        assertThat(placement.maximized()).as("smaller than the normal minimum plus margins").isTrue();
        assertThat(placement.minimumHeight()).isEqualTo(688);
    }

    @Test void fullHdAtOneHundredTwentyFivePercentGetsAFittedNonMaximisedWindow() {
        // 1920x1080 at 125 %: 1536x864 logical, about 816 high above the taskbar.
        WindowPlacement placement = WindowPlacement.fit(defaults, 0, 0, 1536, 816);
        assertInside(placement, 0, 0, 1536, 816);
        assertThat(placement.maximized()).isFalse();
        assertThat(placement.width()).isEqualTo(1400);
        assertThat(placement.height()).isEqualTo(816 - 2 * WindowPlacement.MARGIN);
    }

    @Test void secondaryMonitorWithNegativeOriginIsRespected() {
        WindowPlacement placement = WindowPlacement.fit(defaults, -2560, -200, 2560, 1400);
        assertInside(placement, -2560, -200, 2560, 1400);
        assertThat(placement.maximized()).isFalse();
    }

    @Test void verySmallScreenStillKeepsMinimumsWithinTheScreen() {
        WindowPlacement placement = WindowPlacement.fit(defaults, 0, 0, 1024, 560);
        assertInside(placement, 0, 0, 1024, 560);
        assertThat(placement.maximized()).isTrue();
    }
}
