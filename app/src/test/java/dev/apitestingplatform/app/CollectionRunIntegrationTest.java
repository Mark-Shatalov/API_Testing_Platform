package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.theme.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class CollectionRunIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static RequestDraft draft(String path, Authentication auth, List<AssertionDefinition> checks, List<ExtractionDefinition> extractions) {
        var empty = RequestDraft.empty();
        return new RequestDraft(empty.method(), "{{baseUrl}}" + path, List.of(), List.of(), List.of(), empty.bodyType(), "", List.of(), List.of(),
                "10", empty.redirects(), empty.protocol(), List.of(), auth, checks, extractions);
    }
    private static RequestDraft plain(String path) { return draft(path, Authentication.none(), List.of(), List.of()); }
    private static AssertionDefinition status(int expected) { return new AssertionDefinition(UUID.randomUUID(), "Expected status", AssertionDefinition.Type.STATUS_EQUALS, "", Integer.toString(expected), true); }
    private static Authentication bearer() { return new Authentication(Authentication.Mode.BEARER, "{{token}}", "", "", "", "", Authentication.Placement.HEADER); }

    @Test void buttonRunsSavedFolderOrderAndFrozenEnvironmentWhileEditorAndCollectionChange() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var login = await(fixture.repository.createRequest(fixture.folder, "Login", draft("login", Authentication.none(),
                    List.of(new AssertionDefinition(UUID.randomUUID(), "Token exists", AssertionDefinition.Type.JSON_PATH_EXISTS, "$.access_token", "", true)),
                    List.of(new ExtractionDefinition(UUID.randomUUID(), "token", "$.access_token", true)))));
            var consumer = await(fixture.repository.createRequest(fixture.collection, "Protected", draft("protected", bearer(), List.of(status(201)), List.of())));
            await(fixture.repository.createRequest(fixture.collection, "Server error", draft("error", Authentication.none(), List.of(status(500)), List.of())));
            fixture.server.enqueue(new MockResponse().setBody("{\"access_token\":\"private-run-1\"}").setBodyDelay(600, TimeUnit.MILLISECONDS));
            fixture.server.enqueue(new MockResponse().setBody("{\"ok\":true}")); fixture.server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
            fixture.server.enqueue(new MockResponse().setBody("{\"access_token\":\"private-run-2\"}"));
            fixture.server.enqueue(new MockResponse().setBody("{\"ok\":true}")); fixture.server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
            fx(() -> {
                @SuppressWarnings("unchecked") TreeView<LibraryItem> tree = (TreeView<LibraryItem>) fixture.stage.getScene().getRoot().lookup("#library-tree");
                tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst());
                ((Button) fixture.stage.getScene().getRoot().lookup("#library-run-collection")).fire(); return null;
            });
            waitFor(() -> fixture.runner() != null);
            fx(() -> { fixture.spinner("collection-run-iterations").getValueFactory().setValue(2); fixture.spinner("collection-run-delay").getValueFactory().setValue(40); fixture.start(); return null; });
            RecordedRequest first = fixture.server.takeRequest(5, TimeUnit.SECONDS); assertThat(first.getPath()).isEqualTo("/login");
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText("http://localhost/unsaved-editor-change");
                assertThat(((Button) fixture.editor().lookup(".send-button")).isDisabled()).isFalse(); return null;
            });
            await(fixture.repository.save(consumer.item().id(), consumer.item().version(), plain("changed-after-start")));
            await(fixture.repository.reorder(consumer.item().id(), 0)); await(fixture.repository.delete(login.item().id()));
            await(fixture.repository.createRequest(fixture.collection, "New after start", plain("new-after-start")));
            var other = await(fixture.repository.createEnvironment(fixture.workspace, "Other environment", List.of(new Variable("baseUrl", "http://127.0.0.1:1/changed/", false))));
            await(fixture.repository.selectEnvironment(fixture.workspace, other.id()));
            List<RecordedRequest> sent = new ArrayList<>(); sent.add(first);
            for (int i = 1; i < 6; i++) { var received = fixture.server.takeRequest(5, TimeUnit.SECONDS); assertThat(received).isNotNull(); sent.add(received); }
            waitFor(() -> !fixture.button("collection-run-start").isDisabled() && fixture.results().getItems().size() == 6);
            assertThat(sent).extracting(RecordedRequest::getPath).containsExactly("/login", "/protected", "/error", "/login", "/protected", "/error");
            assertThat(sent.get(1).getHeader("Authorization")).isEqualTo("Bearer private-run-1"); assertThat(sent.get(4).getHeader("Authorization")).isEqualTo("Bearer private-run-2");
            fx(() -> {
                var rows = fixture.results().getItems();
                assertThat(rows).extracting(RunEntry::outcome).containsExactly(RunEntry.Outcome.SUCCESS, RunEntry.Outcome.SUCCESS, RunEntry.Outcome.HTTP_ERROR, RunEntry.Outcome.SUCCESS, RunEntry.Outcome.SUCCESS, RunEntry.Outcome.HTTP_ERROR);
                assertThat(rows).extracting(RunEntry::name).containsExactly("Login", "Protected", "Server error", "Login", "Protected", "Server error");
                assertThat(rows.get(1).failedChecks()).isEqualTo(1); assertThat(rows.get(2).assertions().getFirst().outcome()).isEqualTo(AssertionResult.Outcome.PASS);
                assertThat(rows.toString()).doesNotContain("private-run-1", "private-run-2");
                assertThat(((Label) fixture.runner().lookup("#collection-run-status")).getText()).contains("COMPLETED WITH FAILURES", "2 request failures", "2 failed checks");
                assertThat(((Label) fixture.runner().lookup("#collection-run-context")).getText()).contains("Environment: Selected environment");
                fixture.results().getSelectionModel().select(1);
                AppearanceIntegrationTest.snapshot(fixture.runner(), "collection-run-graphite");
                fixture.themes.apply(new Appearance(Appearance.Theme.LIGHT, "#2563eb"));
                AppearanceIntegrationTest.snapshot(fixture.runner(), "collection-run-light"); return null;
            });
            assertThat(await(fixture.repository.loadVariables(fixture.workspace)).selectedEnvironmentId()).isEqualTo(other.id());
            assertThat(await(fixture.repository.load(consumer.item().id())).draft().url()).endsWith("changed-after-start");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "close", "owner-close"})
    void cancellingOrClosingStopsActiveHttpAndPreventsLaterRequests(String action) throws Exception {
        try (Fixture fixture = new Fixture()) {
            await(fixture.repository.createRequest(fixture.collection, "Waiting", plain("waiting")));
            await(fixture.repository.createRequest(fixture.collection, "Never", plain("never")));
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            fixture.openRunner(); fx(() -> { fixture.start(); return null; }); assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS).getPath()).isEqualTo("/waiting");
            fx(() -> {
                if (action.equals("cancel")) fixture.button("collection-run-cancel").fire();
                else if (action.equals("close")) ((Stage) fixture.runner().getScene().getWindow()).close();
                else { fixture.controller.close(); fixture.stage.close(); }
                return null;
            });
            if (action.equals("cancel")) {
                waitFor(() -> fixture.results().getItems().size() == 2 && !fixture.button("collection-run-start").isDisabled());
                fx(() -> { assertThat(fixture.results().getItems()).extracting(RunEntry::outcome).containsExactly(RunEntry.Outcome.CANCELLED, RunEntry.Outcome.NOT_RUN);
                    assertThat(((Label) fixture.runner().lookup("#collection-run-status")).getText()).startsWith("CANCELLED"); return null;
                });
            } else waitFor(() -> fixture.runner() == null);
            assertThat(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS)).isNull(); assertThat(fixture.server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test void handlesEmptyCollectionLimitsAndCancellationBeforeSnapshotThenCanStartAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.openRunner(); fx(() -> { fixture.start(); return null; });
            waitFor(() -> !fixture.button("collection-run-start").isDisabled());
            fx(() -> { assertThat(((Label) fixture.runner().lookup("#collection-run-status")).getText()).contains("1,000"); return null; });
            assertThat(fixture.server.getRequestCount()).isZero();
            await(fixture.repository.createRequest(fixture.collection, "One", plain("one"))); await(fixture.repository.createRequest(fixture.collection, "Two", plain("two")));
            fx(() -> { fixture.spinner("collection-run-iterations").getValueFactory().setValue(1000); fixture.start(); return null; });
            waitFor(() -> !fixture.button("collection-run-start").isDisabled()); assertThat(fixture.server.getRequestCount()).isZero();
            fx(() -> { fixture.spinner("collection-run-iterations").getValueFactory().setValue(1); fixture.start(); fixture.button("collection-run-cancel").fire(); return null; });
            fx(() -> { assertThat(((Label) fixture.runner().lookup("#collection-run-status")).getText()).contains("Cancelled before"); return null; });
            assertThat(fixture.server.getRequestCount()).isZero();
            fixture.server.enqueue(new MockResponse().setBody("{}")); fixture.server.enqueue(new MockResponse().setBody("{}")); fx(() -> { fixture.start(); return null; });
            waitFor(() -> fixture.results().getItems().size() == 2 && !fixture.button("collection-run-start").isDisabled());
            assertThat(fixture.server.getRequestCount()).isEqualTo(2);
        }
    }

    private final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine();
        final SqliteLibraryRepository repository;
        final UUID workspace, collection, folder;
        final LibraryWindowController controller; final Stage stage; final ThemeManager themes;
        Fixture() throws Exception {
            server.start(); repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector()); await(repository.ready());
            workspace = await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Runner test")).id(); collection = await(repository.create(LibraryItem.Kind.COLLECTION, workspace, "Authentication flow")).id();
            folder = await(repository.create(LibraryItem.Kind.FOLDER, collection, "Login folder")).id();
            await(repository.saveWorkspaceVariables(workspace, 0, List.of(new Variable("baseUrl", "http://127.0.0.1:1/workspace/", false))));
            var environment = await(repository.createEnvironment(workspace, "Selected environment", List.of(new Variable("baseUrl", server.url("/").toString(), false)))); await(repository.selectEnvironment(workspace, environment.id()));
            controller = fx(() -> new LibraryWindowController(engine, repository, false));
            stage = fx(() -> { Stage value = new Stage(); value.setScene(new Scene(controller.createView(), 1500, 1050)); return value; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            fx(() -> { stage.show(); return null; }); waitFor(() -> controller.activeEditor() != null && editor().lookup(".url-input") != null);
        }
        Parent editor() { return (Parent) ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getContent(); }
        Parent runner() { return Window.getWindows().stream().map(Window::getScene).filter(Objects::nonNull).map(Scene::getRoot).filter(root -> "collection-run-window".equals(root.getId())).findFirst().orElse(null); }
        Button button(String id) { return (Button) runner().lookup("#" + id); }
        @SuppressWarnings("unchecked") Spinner<Integer> spinner(String id) { return (Spinner<Integer>) runner().lookup("#" + id); }
        @SuppressWarnings("unchecked") TableView<RunEntry> results() { return (TableView<RunEntry>) runner().lookup("#collection-run-results"); }
        void start() { button("collection-run-start").fire(); }
        void openRunner() throws Exception { fx(() -> { controller.showCollectionRun(collection); return null; }); waitFor(() -> runner() != null && runner().lookup("#collection-run-start") != null); }
        @Override public void close() throws Exception { fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown()); server.close(); }
    }
}
