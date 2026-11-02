package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.components.ResponsePanel;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.theme.*;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class AppearanceIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }

    @Test void previewThemesCancelAndSaveWithoutChangingRequestThenRestoreOnRestart() throws Exception {
        try (var fixture = new Fixture(false)) {
            waitFor(() -> fixture.controller.activeEditor() != null);
            fx(() -> {
                ((TextField) fixture.root().lookup(".url-input")).setText("https://example.com/unchanged");
                byte[] body = "{\"message\":\"Hello from your API\",\"id\":2,\"active\":true}".getBytes(StandardCharsets.UTF_8);
                ((ResponsePanel) fixture.root().lookup(".response-panel")).showResponse(new ExecutionResult(200,
                        Map.of("Content-Type", List.of("application/json")), body, 42, body.length, Optional.empty(), Instant.now(), Optional.of(HttpProtocol.HTTP_2)));
                return null;
            });
            waitFor(() -> ((ResponsePanel) fixture.root().lookup(".response-panel")).readyProperty().get());
            fx(() -> {
                assertPalette(fixture, true); snapshot(fixture.root(), "graphite");
                fixture.openSettings();
                assertThat(dialog().getStyleClass()).contains("theme-graphite");
                snapshot(dialog(), "appearance-dialog");
                selectTheme(Appearance.Theme.LIGHT); assertPalette(fixture, false);
                ((ColorPicker) dialog().lookup("#appearance-accent")).setValue(Color.web("#2060a0"));
                fixture.root().applyCss(); snapshot(fixture.root(), "light");
                ((Button) dialog().lookupButton(ButtonType.CANCEL)).fire();
                assertThat(fixture.themes.appearance()).isEqualTo(Appearance.defaults());
                assertPalette(fixture, true);
                fixture.openSettings(); selectTheme(Appearance.Theme.LIGHT);
                ((ColorPicker) dialog().lookup("#appearance-accent")).setValue(Color.web("#2060a0"));
                ((Button) dialog().lookup("#appearance-save")).fire();
                return null;
            });
            waitFor(() -> Window.getWindows().stream().noneMatch(window -> window.getScene().getRoot() instanceof DialogPane));
            assertThat(fixture.preferences.load()).isEqualTo(new Appearance(Appearance.Theme.LIGHT, "#2060a0"));
            String url = fx(() -> fixture.controller.activeEditor().requestEditorModel().capture().url());
            assertThat(url).isEqualTo("https://example.com/unchanged");
        }
        try (var restarted = new Fixture(false)) {
            fx(() -> { assertPalette(restarted, false); assertThat(restarted.themes.appearance().accent()).isEqualTo("#2060a0"); return null; });
        }
    }

    @Test void failedSaveKeepsDialogOpenAndCancelRestoresPreviousAppearance() throws Exception {
        try (var fixture = new Fixture(true)) {
            fx(() -> { fixture.openSettings(); selectTheme(Appearance.Theme.LIGHT);
                ((Button) dialog().lookup("#appearance-save")).fire(); return null; });
            waitFor(() -> !((Label) dialog().lookup("#appearance-error")).getText().isEmpty());
            fx(() -> {
                assertThat(dialog().getStyleClass()).contains("theme-light");
                ((Button) dialog().lookup("#appearance-reset")).fire();
                assertThat(fixture.themes.appearance()).isEqualTo(Appearance.defaults());
                ((Button) dialog().lookupButton(ButtonType.CANCEL)).fire();
                assertPalette(fixture, true); return null;
            });
            assertThat(fixture.preferences.load()).isEqualTo(Appearance.defaults());
        }
    }

    @SuppressWarnings("unchecked")
    private static void selectTheme(Appearance.Theme theme) { ((ComboBox<Appearance.Theme>) dialog().lookup("#appearance-theme")).setValue(theme); }
    private static DialogPane dialog() {
        return Window.getWindows().stream().map(Window::getScene).filter(Objects::nonNull).map(Scene::getRoot)
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
    }
    private static void assertPalette(Fixture fixture, boolean dark) {
        fixture.root().applyCss(); fixture.root().layout();
        Color background = (Color) ((Region) fixture.root().lookup(".library-toolbar")).getBackground().getFills().getFirst().getFill();
        Color text = (Color) ((Label) fixture.root().lookup("#library-status")).getTextFill();
        assertThat(background.getBrightness() < 0.5).isEqualTo(dark);
        assertThat(text.getBrightness() > 0.5).isEqualTo(dark);
    }
    /** Optional raw snapshots for visual QA; no AWT/Swing dependency is added to the application. */
    private static void snapshot(Parent root, String name) throws Exception {
        String output = System.getProperty("appearance.snapshots"); if (output == null) return;
        root.applyCss(); root.layout(); var image = root.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight(); byte[] pixels = new byte[width * height * 4];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getByteBgraInstance(), pixels, 0, width * 4);
        Path folder = Path.of(output); Files.createDirectories(folder);
        Files.write(folder.resolve(name + ".bgra"), pixels); Files.writeString(folder.resolve(name + ".size"), width + "," + height);
    }
    private final class Fixture implements AutoCloseable {
        final AppearancePreferences preferences = new AppearancePreferences(directory.resolve("appearance.properties"));
        final SqliteLibraryRepository repository;
        final LibraryWindowController controller;
        final Stage stage;
        final ThemeManager themes;
        Fixture(boolean failSave) throws Exception {
            repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector());
            repository.ready().toCompletableFuture().get(5, TimeUnit.SECONDS);
            controller = fx(() -> new LibraryWindowController(null, repository, false));
            stage = fx(() -> { Stage value = new Stage(); value.setTitle("Appearance preview"); value.setScene(new Scene(controller.createView(), 1400, 900)); return value; });
            themes = fx(() -> new ThemeManager(stage.getScene(), preferences.load(), value -> failSave
                    ? CompletableFuture.failedFuture(new java.io.IOException("Test failure")) : preferences.save(value)));
            fx(() -> { controller.setAppearanceAction(() -> themes.showSettings(stage)); stage.show(); return null; });
        }
        Parent root() { return stage.getScene().getRoot(); }
        void openSettings() { ((Button) root().lookup("#library-appearance")).fire(); }
        @Override public void close() throws Exception {
            fx(() -> { themes.close(); controller.close(); stage.close(); return null; });
            repository.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS); preferences.close();
        }
    }
}
