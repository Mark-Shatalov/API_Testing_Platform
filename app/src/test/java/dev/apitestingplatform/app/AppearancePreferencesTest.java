package dev.apitestingplatform.app;

import dev.apitestingplatform.ui.theme.Appearance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class AppearancePreferencesTest {
    @TempDir Path directory;

    @Test void firstLaunchDefaultsToGraphiteAndSavedAppearanceSurvivesRestart() throws Exception {
        Path file = directory.resolve("settings/appearance.properties");
        Appearance selected = new Appearance(Appearance.Theme.LIGHT, "#2060a0");
        try (var preferences = new AppearancePreferences(file)) {
            assertThat(preferences.load()).isEqualTo(Appearance.defaults());
            preferences.save(selected).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        try (var restarted = new AppearancePreferences(file)) { assertThat(restarted.load()).isEqualTo(selected); }
        try (var files = Files.list(file.getParent())) { assertThat(files.toList()).containsExactly(file); }
    }

    @Test void malformedSettingsFallBackWithoutPreventingStartup() throws Exception {
        Path file = directory.resolve("appearance.properties");
        for (String malformed : new String[] {"theme=UNKNOWN\naccent=#818cf8", "theme=LIGHT\naccent=red; -fx-font-size:999px", "theme=\\uXXXX"}) {
            Files.writeString(file, malformed);
            try (var preferences = new AppearancePreferences(file)) { assertThat(preferences.load()).isEqualTo(Appearance.defaults()); }
        }
    }

    @Test void failedWriteIsReportedAndDoesNotReplaceExistingData() throws Exception {
        Path parent = directory.resolve("blocked"); Files.writeString(parent, "keep this file");
        try (var preferences = new AppearancePreferences(parent.resolve("appearance.properties"))) {
            assertThatThrownBy(() -> preferences.save(Appearance.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.io.IOException.class);
        }
        assertThat(Files.readString(parent)).isEqualTo("keep this file");
    }
}
