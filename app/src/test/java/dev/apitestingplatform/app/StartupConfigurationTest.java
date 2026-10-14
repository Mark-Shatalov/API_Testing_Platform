package dev.apitestingplatform.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartupConfigurationTest {
    @Test
    void providesUsableDefaultWindowConfiguration() {
        StartupConfiguration configuration = StartupConfiguration.defaults();

        assertThat(configuration.windowTitle()).isEqualTo("API Testing Platform");
        assertThat(configuration.initialWidth()).isEqualTo(1400);
        assertThat(configuration.initialHeight()).isEqualTo(900);
        assertThat(configuration.initialWidth()).isGreaterThanOrEqualTo(configuration.minimumWidth());
        assertThat(configuration.initialHeight()).isGreaterThanOrEqualTo(configuration.minimumHeight());
    }
}

