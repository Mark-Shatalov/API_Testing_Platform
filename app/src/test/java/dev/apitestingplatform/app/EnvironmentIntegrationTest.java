package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.*;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.theme.*;
import dev.apitestingplatform.ui.viewmodel.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class EnvironmentIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static DialogPane dialog() {
        return Window.getWindows().stream().map(Window::getScene).filter(Objects::nonNull).map(Scene::getRoot)
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
    }

    @Test void createAndEditEnvironmentThroughMaskedUiThenKeepSelectionOnRestart() throws Exception {
        UUID created;
        try (Fixture fixture = new Fixture()) {
            fx(() -> { fixture.button("environment-new").fire();
                ((TextField) dialog().lookup("#environment-name")).setText("Local test");
                ((Button) dialog().lookup("#variables-add")).fire();
                ((TextField) dialog().lookup("#variable-name")).setText("token");
                ((TextField) dialog().lookup("#variable-value")).setText("encrypted-ui-marker");
                ((CheckBox) dialog().lookup("#variable-secret")).setSelected(true);
                assertThat(dialog().lookup("#variable-secret-value").isVisible()).isTrue();
                assertThat(dialog().lookup("#variable-value").isVisible()).isFalse();
                AppearanceIntegrationTest.snapshot(dialog(), "environment-editor");
                ((Button) dialog().lookup("#environment-save")).fire(); return null;
            });
            waitFor(() -> fixture.selector().getItems().size() == 2);
            created = await(fixture.repository.loadVariables(fixture.workspace)).environments().getFirst().id();
            UUID selected = created;
            fx(() -> { fixture.select(selected); return null; });
            waitFor(() -> !fixture.selector().isDisabled());
            fx(() -> {
                fixture.button("environment-edit").fire();
                assertThat(((PasswordField) dialog().lookup("#variable-secret-value")).getText()).isEqualTo("encrypted-ui-marker");
                ((TextField) dialog().lookup("#environment-name")).setText("Renamed local");
                ((Button) dialog().lookup("#environment-save")).fire(); return null;
            });
            waitFor(() -> fixture.selector().getValue() != null && fixture.selector().getValue().name().equals("Renamed local"));
            fx(() -> { fixture.button("workspace-variables").fire();
                ((Button) dialog().lookup("#variables-add")).fire();
                ((TextField) dialog().lookup("#variable-name")).setText("id");
                ((TextField) dialog().lookup("#variable-value")).setText("5");
                ((Button) dialog().lookup("#environment-save")).fire(); return null;
            });
            waitFor(() -> !fixture.button("workspace-variables").isDisabled() && Window.getWindows().stream().noneMatch(window -> window.getScene().getRoot() instanceof DialogPane));
        }
        try (Fixture restarted = new Fixture()) {
            assertThat(await(restarted.repository.loadVariables(restarted.workspace)).selectedEnvironmentId()).isEqualTo(created);
            assertThat(await(restarted.repository.loadVariables(restarted.workspace)).variables().getFirst().value()).isEqualTo("5");
            fx(() -> { assertThat(restarted.selector().getValue().name()).isEqualTo("Renamed local"); return null; });
        }
        String database = new String(Files.readAllBytes(directory.resolve("platform.sqlite")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(database).doesNotContain("encrypted-ui-marker");
    }

    @Test void switchEnvironmentsSendResolvedRequestsAndNeverMutateSavedTemplate() throws Exception {
        try (MockWebServer first = new MockWebServer(); MockWebServer second = new MockWebServer(); Fixture fixture = new Fixture()) {
            first.start(); second.start();
            await(fixture.repository.saveWorkspaceVariables(fixture.workspace, 0, List.of(new Variable("id", "workspace", false))));
            var a = await(fixture.repository.createEnvironment(fixture.workspace, "Local A", List.of(new Variable("baseUrl", first.url("/").toString(), false), new Variable("id", "a", false), new Variable("token", "private-token-marker", true))));
            var b = await(fixture.repository.createEnvironment(fixture.workspace, "Local B", List.of(new Variable("baseUrl", second.url("/").toString(), false), new Variable("id", "b", false), new Variable("token", "other-token-marker", true))));
            await(fixture.repository.selectEnvironment(fixture.workspace, a.id()));
            var parent = await(fixture.repository.create(LibraryItem.Kind.COLLECTION, fixture.workspace, "Tests"));
            var draft = new RequestDraft(HttpMethod.POST, "{{baseUrl}}items/{{id}}", List.of(new RequestField("key", "{{token}}")),
                    List.of(new RequestField("X-Token", "{{token}}")), List.of(), RequestBody.Type.JSON, "{\"id\":\"{{id}}\"}", List.of(), List.of(),
                    "5", RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1, List.of(new Variable("id", "request", false)));
            var saved = await(fixture.repository.createRequest(parent.id(), "Template", draft));
            fx(() -> { fixture.controller.refresh(); return null; });
            waitFor(() -> fixture.selector().getItems().size() == 3);
            fx(() -> { fixture.controller.openRequest(saved.item().id()); return null; });
            waitFor(() -> fixture.controller.activeEditor().requestEditorModel().variables.size() == 1);
            fx(() -> { fixture.button("request-preview-target").fire(); return null; });
            waitFor(() -> fixture.label("request-resolved-target").getText().startsWith("Target:"));
            fx(() -> {
                assertThat(fixture.label("request-resolved-target").getText()).contains("items/request").doesNotContain("private-token-marker");
                AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "environment-workspace"); return null;
            });
            assertThat(first.getRequestCount()).isZero(); first.enqueue(new MockResponse().setBody("{\"ok\":true}").addHeader("Content-Type", "application/json"));
            fx(() -> { ((Button) fixture.editorRoot().lookup(".send-button")).fire(); return null; });
            var received = first.takeRequest(5, TimeUnit.SECONDS); assertThat(received).isNotNull();
            assertThat(received.getPath()).isEqualTo("/items/request?key=private-token-marker");
            assertThat(received.getHeader("X-Token")).isEqualTo("private-token-marker"); assertThat(received.getBody().readUtf8()).contains("request");
            waitFor(() -> !((Button) fixture.editorRoot().lookup(".send-button")).isDisabled());
            fx(() -> { fixture.select(b.id()); fixture.controller.activeEditor().requestEditorModel().variables.clear(); return null; });
            waitFor(() -> !fixture.selector().isDisabled());
            second.enqueue(new MockResponse().setBody("ok"));
            fx(() -> { ((Button) fixture.editorRoot().lookup(".send-button")).fire(); return null; });
            var next = second.takeRequest(5, TimeUnit.SECONDS); assertThat(next).isNotNull(); assertThat(next.getPath()).startsWith("/items/b?");
            assertThat(await(fixture.repository.load(saved.item().id())).draft()).isEqualTo(draft);
        }
    }

    @Test void missingVariableBlocksNetworkAndMarksTheUrl() throws Exception {
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start();
            fx(() -> {
                ((TextField) fixture.stage.getScene().getRoot().lookup(".url-input")).setText("http://localhost:" + server.getPort() + "/{{missing}}");
                ((Button) fixture.stage.getScene().getRoot().lookup(".send-button")).fire(); return null;
            });
            waitFor(() -> !fixture.label("request-url-error").getText().isEmpty());
            assertThat(server.getRequestCount()).isZero();
            fx(() -> { assertThat(fixture.label("request-url-error").getText()).contains("missing"); return null; });
        }
    }
    @Test void workspaceAndRequestTabsKeepTheirOwnVariablesAndUnsavedEdits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var other = await(fixture.repository.create(LibraryItem.Kind.WORKSPACE, null, "Other workspace"));
            var first = await(fixture.repository.createEnvironment(fixture.workspace, "First environment", List.of()));
            var second = await(fixture.repository.createEnvironment(other.id(), "Second environment", List.of()));
            await(fixture.repository.selectEnvironment(fixture.workspace, first.id())); await(fixture.repository.selectEnvironment(other.id(), second.id()));
            fx(() -> { ((TextField) fixture.editorRoot().lookup(".url-input")).setText("https://example.com/first"); fixture.controller.refresh(); return null; });
            waitFor(() -> fixture.selector().getValue() != null);
            fx(() -> {
                @SuppressWarnings("unchecked") ComboBox<LibraryItem> workspaces = (ComboBox<LibraryItem>) fixture.stage.getScene().getRoot().lookup("#library-workspaces");
                workspaces.setValue(workspaces.getItems().stream().filter(value -> value.id().equals(other.id())).findFirst().orElseThrow());
                return null;
            });
            waitFor(() -> fixture.selector().getValue() != null && fixture.selector().getValue().id().equals(second.id()));
            waitFor(() -> fixture.editorRoot().lookup(".url-input") != null);
            fx(() -> { ((TextField) fixture.editorRoot().lookup(".url-input")).setText("https://example.com/second"); return null; });
            fx(() -> { ((TabPane) fixture.stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().selectFirst(); return null; });
            waitFor(() -> fixture.selector().getValue() != null && fixture.selector().getValue().id().equals(first.id()));
            fx(() -> { assertThat(fixture.controller.activeEditor().requestEditorModel().capture().url()).isEqualTo("https://example.com/first");
                assertThat(fixture.controller.activeEditor().hasUnsavedEdits()).isTrue(); return null; });
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
            if (await(repository.list()).isEmpty()) await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Environment demo"));
            workspace = await(repository.list()).stream().filter(value -> value.kind() == LibraryItem.Kind.WORKSPACE).findFirst().orElseThrow().id();
            controller = fx(() -> new LibraryWindowController(engine, repository, false));
            stage = fx(() -> { Stage value = new Stage(); value.setScene(new Scene(controller.createView(), 1400, 1000)); return value; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            fx(() -> { stage.show(); return null; });
            waitFor(() -> controller.activeEditor() != null && !selector().isDisabled());
        }
        javafx.scene.Parent editorRoot() { return (javafx.scene.Parent) ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getContent(); }
        Button button(String id) { return (Button) (id.startsWith("request-") ? editorRoot() : stage.getScene().getRoot()).lookup("#" + id); }
        Label label(String id) { return (Label) (id.startsWith("request-") ? editorRoot() : stage.getScene().getRoot()).lookup("#" + id); }
        @SuppressWarnings("unchecked") ComboBox<Environment> selector() { return (ComboBox<Environment>) stage.getScene().getRoot().lookup("#environment-selector"); }
        void select(UUID id) { selector().setValue(selector().getItems().stream().filter(value -> value != null && value.id().equals(id)).findFirst().orElseThrow()); }
        @Override public void close() throws Exception {
            fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown());
        }
    }
}
