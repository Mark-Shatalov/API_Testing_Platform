package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.operations.OperationalSettings.ProxyMode;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.theme.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class OperationsIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    @Test @SuppressWarnings("unchecked") void settingsSaveSurvivesReopenAndErrorsPreserveDirtyEditor() throws Exception {
        try (var fixture = new Fixture()) {
            waitFor(() -> fixture.controller.activeEditor() != null
                    && fixture.stage.getScene().getRoot().lookup(".url-input") != null);
            fx(() -> {
                ((TextField) fixture.stage.getScene().getRoot().lookup(".url-input")).setText("https://example.com/unsaved");
                fireAction(fixture.stage.getScene().getRoot(), "#library-settings"); return null;
            });
            waitFor(() -> settings().lookup("#operations-database") instanceof TextField field && !field.getText().isEmpty());
            fx(() -> {
                assertThat(settings().getStyleClass()).contains("theme-graphite");
                ((ComboBox<ProxyMode>) settings().lookup("#operations-proxy-mode")).setValue(ProxyMode.MANUAL);
                ((TextField) settings().lookup("#operations-proxy-host")).setText("localhost");
                ((Spinner<Integer>) settings().lookup("#operations-proxy-port")).getValueFactory().setValue(8888);
                ((Spinner<Integer>) settings().lookup("#operations-response-limit")).getValueFactory().setValue(128);
                ((Button) settings().lookup("#operations-save")).fire(); return null;
            });
            waitFor(() -> ((Label) settings().lookup("#operations-status")).getText().startsWith("Network settings saved"));
            fx(() -> { AppearanceIntegrationTest.snapshot(settings(), "operations-graphite"); fixture.themes.apply(new Appearance(Appearance.Theme.LIGHT, "#2060a0")); AppearanceIntegrationTest.snapshot(settings(), "operations-light");
                ((TextField) settings().lookup("#operations-ca-file")).setText(directory.resolve("missing.pem").toString()); ((Button) settings().lookup("#operations-save")).fire(); return null; });
            waitFor(() -> !settings().lookup("#operations-save").isDisabled());
            fx(() -> {
                assertThat(fixture.controller.activeEditor().hasUnsavedEdits()).isTrue();
                fixture.controller.reportUnexpectedFailure();
                assertThat(fixture.controller.activeEditor().requestEditorModel().capture().url()).isEqualTo("https://example.com/unsaved");
                assertThat(((Label) fixture.stage.getScene().getRoot().lookup("#library-status")).getText()).contains("Editor changes were kept");
                ((Stage) settings().getScene().getWindow()).close(); fixture.controller.showSettings(); return null;
            });
            waitFor(() -> ((TextField) settings().lookup("#operations-proxy-host")).getText().equals("localhost"));
            fx(() -> { assertThat(((Spinner<Integer>) settings().lookup("#operations-response-limit")).getValue()).isEqualTo(128); assertThat(((TextField) settings().lookup("#operations-ca-file")).getText()).isEmpty(); return null; });
            assertThat(fixture.preferences.load().settings().proxyPort()).isEqualTo(8888);
        }
    }
    private Parent settings() { return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene).filter(java.util.Objects::nonNull).map(Scene::getRoot).filter(root -> "operations-window".equals(root.getId())).findFirst().orElseThrow(); }
    @Test void responseExportCannotOverwriteTheRunningDatabase() throws Exception {
        try (var fixture = new Fixture()) {
            waitFor(() -> fixture.controller.activeEditor() != null);
            waitFor(() -> fixture.stage.getScene().getRoot().lookup(".response-panel") != null);
            var panel = fx(() -> (dev.apitestingplatform.ui.components.ResponsePanel) fixture.stage.getScene().getRoot().lookup(".response-panel"));
            fx(() -> { byte[] body = "replace-database-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8); panel.showResponse(new dev.apitestingplatform.domain.http.ExecutionResult(200, java.util.Map.of(), body, 1, body.length, java.util.Optional.empty(), java.time.Instant.now(), java.util.Optional.empty())); return null; });
            waitFor(() -> panel.readyProperty().get());
            fx(() -> { panel.saveTo(fixture.repository.databasePath()); return null; });
            waitFor(() -> ((Label) panel.lookup("#response-file-status")).getText().startsWith("Unable"));
            assertThat(fixture.repository.list().toCompletableFuture().get(5, TimeUnit.SECONDS)).hasSize(1);
        }
    }
    final class Fixture implements AutoCloseable {
        final OperationalPreferences preferences = new OperationalPreferences(directory.resolve("operations.properties"));
        final SqliteLibraryRepository repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector());
        final OperationalLog log = new OperationalLog(directory.resolve("logs"));
        final OperationsService service = new OperationsService(repository, preferences, log, "");
        final LibraryWindowController controller; final Stage stage; final ThemeManager themes;
        Fixture() throws Exception {
            repository.ready().toCompletableFuture().get(5, TimeUnit.SECONDS);
            controller = fx(() -> { var value = new LibraryWindowController(null, repository, false); value.setOperations(service); return value; });
            stage = fx(() -> { Stage value = new Stage(); value.setScene(new Scene(controller.createView(), 1280, 850)); value.show(); return value; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> java.util.concurrent.CompletableFuture.completedFuture(null)));
        }
        public void close() throws Exception { fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); service.close(); log.snapshot().toCompletableFuture().get(5, TimeUnit.SECONDS); log.close(); repository.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS); }
    }
}
