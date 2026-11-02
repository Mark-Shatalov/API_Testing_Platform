package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.theme.*;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class AuthenticationIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }

    @ParameterizedTest @ValueSource(strings = {"BEARER", "BASIC", "HEADER", "QUERY"})
    void editsAuthPreviewsAndSendsCredentialsThenRestoresEncryptedSavedAndRecoveryDrafts(String choice) throws Exception {
        UUID savedId; Authentication expected;
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start(); await(fixture.repository.saveWorkspaceVariables(fixture.workspace, 0, List.of(new Variable("token", "bearer-private-marker", true))));
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText(server.url("/auth").toString());
                TabPane tabs = (TabPane) fixture.editor().lookup("#request-editor-tabs");
                tabs.getSelectionModel().select(tabs.getTabs().stream().filter(tab -> tab.getText().equals("Auth")).findFirst().orElseThrow());
                return null;
            });
            waitFor(() -> fixture.editor().lookup("#auth-mode") != null);
            fx(() -> {
                @SuppressWarnings("unchecked") ComboBox<Authentication.Mode> mode = (ComboBox<Authentication.Mode>) fixture.editor().lookup("#auth-mode");
                mode.setValue(choice.equals("BEARER") ? Authentication.Mode.BEARER : choice.equals("BASIC") ? Authentication.Mode.BASIC : Authentication.Mode.API_KEY);
                if (choice.equals("BEARER")) {
                    ((PasswordField) fixture.editor().lookup("#auth-bearer-token")).setText("{{token}}");
                    assertThat(fixture.editor().lookup("#auth-bearer-token-visible").isVisible()).isFalse();
                } else if (choice.equals("BASIC")) {
                    ((TextField) fixture.editor().lookup("#auth-username")).setText("alice");
                    ((PasswordField) fixture.editor().lookup("#auth-password")).setText("password-private-marker");
                } else {
                    ((TextField) fixture.editor().lookup("#auth-key-name")).setText(choice.equals("QUERY") ? "custom_key" : "X-Access");
                    ((PasswordField) fixture.editor().lookup("#auth-key-value")).setText("key-private-marker");
                    @SuppressWarnings("unchecked") ComboBox<Authentication.Placement> placement = (ComboBox<Authentication.Placement>) fixture.editor().lookup("#auth-key-placement");
                    placement.setValue(Authentication.Placement.valueOf(choice));
                }
                ((Button) fixture.editor().lookup("#request-preview-target")).fire(); return null;
            });
            waitFor(() -> ((Label) fixture.editor().lookup("#request-resolved-target")).getText().startsWith("Target:"));
            fx(() -> {
                assertThat(((Label) fixture.editor().lookup("#request-resolved-target")).getText()).doesNotContain("key-private-marker", "password-private-marker", "bearer-private-marker");
                if (choice.equals("BEARER")) AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "auth-graphite");
                if (choice.equals("QUERY")) { fixture.themes.apply(new Appearance(Appearance.Theme.LIGHT, "#2563eb")); AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "auth-light"); }
                return null;
            });
            assertThat(server.getRequestCount()).isZero();
            var captured = fx(() -> fixture.controller.activeEditor().requestEditorModel().capture()); expected = captured.authentication();
            var parent = await(fixture.repository.create(LibraryItem.Kind.COLLECTION, fixture.workspace, "Auth tests"));
            savedId = await(fixture.repository.createRequest(parent.id(), "Saved auth", captured)).item().id();
            await(fixture.repository.putDraft(new RecoveryDraft(UUID.randomUUID(), savedId, fixture.workspace, "Recovery auth", captured, Instant.now())));
            server.enqueue(new MockResponse().setBody("{\"ok\":true}").addHeader("Content-Type", "application/json"));
            fx(() -> { ((Button) fixture.editor().lookup(".send-button")).fire(); return null; });
            var received = server.takeRequest(5, TimeUnit.SECONDS); assertThat(received).isNotNull();
            switch (choice) {
                case "BEARER" -> assertThat(received.getHeader("Authorization")).isEqualTo("Bearer bearer-private-marker");
                case "BASIC" -> assertThat(received.getHeader("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("alice:password-private-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                case "HEADER" -> assertThat(received.getHeader("X-Access")).isEqualTo("key-private-marker");
                case "QUERY" -> assertThat(received.getPath()).isEqualTo("/auth?custom_key=key-private-marker");
            }
            assertThat(fx(() -> fixture.controller.activeEditor().requestEditorModel().authentication())).isEqualTo(expected);
        }
        try (Fixture restarted = new Fixture()) {
            assertThat(await(restarted.repository.load(savedId)).draft().authentication()).isEqualTo(expected);
            assertThat(await(restarted.repository.recoverableDrafts())).anyMatch(draft -> draft.draft().authentication().equals(expected));
            fx(() -> { restarted.controller.openRequest(savedId); return null; });
            waitFor(() -> restarted.controller.activeEditor().requestEditorModel().authentication().equals(expected));
        }
        String bytes = new String(Files.readAllBytes(directory.resolve("platform.sqlite")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(bytes).doesNotContain("bearer-private-marker", "password-private-marker", "key-private-marker");
    }

    @Test void conflictingAuthorizationStopsBeforeNetworkAndShowsAnAuthError() throws Exception {
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start();
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText(server.url("/auth").toString());
                var draft = fixture.controller.activeEditor().requestEditorModel(); draft.authMode.set(Authentication.Mode.BEARER); draft.bearerToken.set("token-marker");
                draft.headers.add(new dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.Row("Authorization", "manual-marker", true));
                ((Button) fixture.editor().lookup(".send-button")).fire(); return null;
            });
            waitFor(() -> !fixture.controller.activeEditor().requestEditorModel().authError.get().isEmpty());
            assertThat(server.getRequestCount()).isZero();
        }
    }
    private final class Fixture implements AutoCloseable {
        final SqliteLibraryRepository repository;
        final JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine();
        final LibraryWindowController controller;
        final Stage stage;
        final UUID workspace;
        final ThemeManager themes;
        Fixture() throws Exception {
            repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector()); await(repository.ready());
            if (await(repository.list()).isEmpty()) await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Authentication demo"));
            workspace = await(repository.list()).stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).findFirst().orElseThrow().id();
            controller = fx(() -> new LibraryWindowController(engine, repository, false));
            stage = fx(() -> { Stage value = new Stage(); value.setScene(new Scene(controller.createView(), 1400, 1000)); return value; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            fx(() -> { stage.show(); return null; }); waitFor(() -> controller.activeEditor() != null && editor().lookup(".url-input") != null);
        }
        Parent editor() { return (Parent) ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getContent(); }
        @Override public void close() throws Exception { fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown()); }
    }
}
