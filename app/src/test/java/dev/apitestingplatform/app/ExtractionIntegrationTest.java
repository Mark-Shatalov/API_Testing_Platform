package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.response.ResponseTestResult;
import dev.apitestingplatform.ui.theme.*;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.ExtractionRow;
import javafx.scene.*;
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
import static dev.apitestingplatform.domain.http.ExtractionResult.Outcome.*;
import static org.assertj.core.api.Assertions.*;

class ExtractionIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static RequestDraft draft(String url, Authentication auth, List<ExtractionDefinition> extraction, List<AssertionDefinition> checks) {
        var empty = RequestDraft.empty();
        return new RequestDraft(empty.method(), url, empty.params(), empty.headers(), empty.cookies(), empty.bodyType(), empty.bodyText(), empty.form(), empty.multipart(),
                empty.timeout(), empty.redirects(), empty.protocol(), List.of(), auth, checks, extraction);
    }
    private static Authentication bearer() { return new Authentication(Authentication.Mode.BEARER, "{{token}}", "", "", "", "", Authentication.Placement.HEADER); }

    @Test void savedRequestsExchangeMaskedTokenWithinCollectionWithoutSavingItAndKeepInFlightDefinitions() throws Exception {
        UUID loginId, consumerId; RequestDraft savedLogin;
        String secret = "response-only-private-token";
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start();
            var environment = await(fixture.repository.createEnvironment(fixture.workspace, "Test", List.of(new Variable("token", "saved-environment", true))));
            await(fixture.repository.selectEnvironment(fixture.workspace, environment.id()));
            fx(() -> { fixture.selectEditor("Extract"); return null; });
            waitFor(() -> fixture.editor().lookup("#extraction-add") != null);
            fx(() -> {
                fixture.url(server.url("/login").toString()); ((Button) fixture.editor().lookup("#extraction-add")).fire(); return null;
            });
            waitFor(() -> fixture.editor().lookup("#extraction-variable") != null);
            fx(() -> {
                ((TextField) fixture.editor().lookup("#extraction-variable")).setText("token");
                ((TextField) fixture.editor().lookup("#extraction-path")).setText("$.access_token");
                fixture.controller.activeEditor().requestEditorModel().assertions.add(new dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.AssertionRow(
                        new AssertionDefinition(UUID.randomUUID(), "Token comparison", AssertionDefinition.Type.JSON_PATH_EQUALS, "$.access_token", "\"different\"", true)));
                fixture.controller.activeEditor().requestEditorModel().extractions.add(new ExtractionRow(new ExtractionDefinition(UUID.randomUUID(), "disabled", "private-extraction-config-marker", false)));
                return null;
            });
            savedLogin = fx(() -> fixture.controller.activeEditor().requestEditorModel().capture());
            loginId = await(fixture.repository.createRequest(fixture.folder, "Login", savedLogin)).item().id();
            consumerId = await(fixture.repository.createRequest(fixture.collection, "Protected", draft(server.url("/protected").toString(), bearer(), List.of(), List.of()))).item().id();
            UUID other = await(fixture.repository.create(LibraryItem.Kind.COLLECTION, fixture.workspace, "Other collection")).id();
            UUID isolated = await(fixture.repository.createRequest(other, "Isolated", draft(server.url("/isolated").toString(), bearer(), List.of(), List.of()))).item().id();
            fixture.refreshUntil(isolated); fixture.open(loginId); fx(() -> { fixture.selectEditor("Extract"); fixture.selectResults("Extraction Results"); return null; });
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"" + secret + "\"}").setBodyDelay(300, TimeUnit.MILLISECONDS));
            fx(() -> { fixture.send(); return null; }); assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            fx(() -> { fixture.controller.activeEditor().requestEditorModel().extractions.getFirst().path.set("$.new_unsent_path"); return null; });
            waitFor(() -> fixture.results() != null && fixture.results().getItems().size() == 2);
            fx(() -> {
                assertThat(fixture.results().getItems()).extracting(ExtractionResult::outcome).containsExactly(EXTRACTED, DISABLED);
                assertThat(fixture.results().getItems().toString()).doesNotContain(secret);
                AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "extraction-editor-graphite");
                fixture.selectResults("Test Results"); return null;
            });
            fx(() -> {
                @SuppressWarnings("unchecked") var checks = (TableView<ResponseTestResult>) fixture.editor().lookup("#response-test-results");
                assertThat(checks.getItems().getFirst().actual()).contains("[secret]").doesNotContain(secret);
                fixture.selectResults("Extraction Results"); fixture.themes.apply(new Appearance(Appearance.Theme.LIGHT, "#2563eb"));
                AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "extraction-results-light"); return null;
            });
            var recovery = fx(() -> fixture.controller.activeEditor().requestEditorModel().capture());
            await(fixture.repository.putDraft(new RecoveryDraft(UUID.randomUUID(), loginId, fixture.workspace, "Extract draft", recovery, Instant.now())));
            fixture.open(consumerId); server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
            fx(() -> { fixture.send(); return null; });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer " + secret);
            fixture.waitDone();
            fixture.open(isolated); server.enqueue(new MockResponse().setBody("{}")); fx(() -> { fixture.send(); return null; });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer saved-environment"); fixture.waitDone();
            assertThat(await(fixture.repository.loadVariables(fixture.workspace)).selectedVariables().getFirst().value()).isEqualTo("saved-environment");
            assertThat(await(fixture.repository.load(loginId)).draft()).isEqualTo(savedLogin);
            fixture.open(consumerId); fx(() -> { ((Button) fixture.stage.getScene().getRoot().lookup("#library-clear-temporary")).fire(); return null; });
            server.enqueue(new MockResponse().setBody("{}")); fx(() -> { fixture.send(); return null; });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer saved-environment"); fixture.waitDone();
        }
        try (MockWebServer server = new MockWebServer(); Fixture restarted = new Fixture()) {
            server.start();
            assertThat(await(restarted.repository.load(loginId)).draft()).isEqualTo(savedLogin);
            assertThat(await(restarted.repository.recoverableDrafts())).anyMatch(value -> value.draft().extractions().stream().anyMatch(rule -> rule.path().equals("$.new_unsent_path")));
            restarted.open(consumerId); fx(() -> { restarted.url(server.url("/after-restart").toString()); return null; });
            server.enqueue(new MockResponse().setBody("{}")); fx(() -> { restarted.send(); return null; });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer saved-environment"); restarted.waitDone();
        }
        for (String name : List.of("platform.sqlite", "platform.sqlite-wal")) if (Files.exists(directory.resolve(name))) {
            assertThat(new String(Files.readAllBytes(directory.resolve(name)), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret, "private-extraction-config-marker");
        }
    }

    @Test void invalidEnabledRuleBlocksHttpAndMarksEditorRow() throws Exception {
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start(); fx(() -> {
                fixture.url(server.url("/blocked").toString());
                fixture.controller.activeEditor().requestEditorModel().extractions.add(new ExtractionRow(new ExtractionDefinition(UUID.randomUUID(), "token", "$..token", true)));
                fixture.selectEditor("Extract"); fixture.selectResults("Extraction Results"); fixture.send(); return null;
            });
            waitFor(() -> !fixture.controller.activeEditor().requestEditorModel().extractions.getFirst().error.get().isBlank());
            assertThat(server.getRequestCount()).isZero();
            fx(() -> { assertThat(fixture.results().getItems().getFirst().outcome()).isEqualTo(NOT_RUN); return null; });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "multiple", "non-json", "timeout", "cancel", "clear"})
    void failedOrCancelledRefreshNeverReusesAnOldToken(String action) throws Exception {
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start(); server.enqueue(new MockResponse().setBody("{\"access_token\":\"old-token\"}"));
            fx(() -> {
                fixture.url(server.url("/token").toString()); fixture.controller.activeEditor().requestEditorModel().extractions.add(new ExtractionRow());
                fixture.selectResults("Extraction Results"); fixture.send(); return null;
            });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull(); waitFor(() -> fixture.results() != null && fixture.results().getItems().size() == 1); fixture.waitDone();
            String body = action.equals("missing") ? "{}" : action.equals("multiple") ? "{\"items\":[1,2]}" : action.equals("non-json") ? "<html>Error</html>" : "{\"access_token\":\"late-token\"}";
            var response = new MockResponse().setBody(body);
            if (action.equals("timeout") || action.equals("cancel")) response.setSocketPolicy(SocketPolicy.NO_RESPONSE);
            if (action.equals("clear")) response.setBodyDelay(400, TimeUnit.MILLISECONDS);
            server.enqueue(response);
            fx(() -> {
                var model = fixture.controller.activeEditor().requestEditorModel(); model.timeout.set(action.equals("timeout") ? "0.2" : "10");
                if (action.equals("multiple")) model.extractions.getFirst().path.set("$.items[*]"); fixture.send(); return null;
            });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            if (action.equals("cancel")) fx(() -> { ((Button) fixture.editor().lookup(".cancel-button")).fire(); return null; });
            if (action.equals("clear")) fx(() -> { ((Button) fixture.editor().lookup("#request-clear-temporary")).fire(); return null; });
            waitFor(() -> fixture.results() != null && fixture.results().getItems().size() == 1); fixture.waitDone();
            fx(() -> { assertThat(fixture.results().getItems().getFirst().outcome()).isIn(FAILED, NOT_RUN);
                var model = fixture.controller.activeEditor().requestEditorModel(); model.extractions.clear(); model.authMode.set(Authentication.Mode.BEARER); model.bearerToken.set("{{token}}");
                fixture.url(server.url("/must-not-send").toString()); fixture.send(); return null;
            });
            waitFor(() -> !fixture.controller.activeEditor().requestEditorModel().authError.get().isBlank());
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    private final class Fixture implements AutoCloseable {
        final SqliteLibraryRepository repository;
        final JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine();
        final LibraryWindowController controller; final Stage stage; final ThemeManager themes;
        final UUID workspace, collection, folder;
        Fixture() throws Exception {
            repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector()); await(repository.ready());
            if (await(repository.list()).isEmpty()) {
                var ws = await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Extraction test"));
                var col = await(repository.create(LibraryItem.Kind.COLLECTION, ws.id(), "Data flow")); await(repository.create(LibraryItem.Kind.FOLDER, col.id(), "Login folder"));
            }
            var items = await(repository.list()); workspace = items.stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).findFirst().orElseThrow().id();
            collection = items.stream().filter(item -> item.kind() == LibraryItem.Kind.COLLECTION).findFirst().orElseThrow().id();
            folder = items.stream().filter(item -> item.kind() == LibraryItem.Kind.FOLDER).findFirst().orElseThrow().id();
            controller = fx(() -> new LibraryWindowController(engine, repository, false));
            stage = fx(() -> { Stage value = new Stage(); value.setScene(new Scene(controller.createView(), 1500, 1050)); return value; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            fx(() -> { stage.show(); return null; }); waitFor(() -> controller.activeEditor() != null && editor().lookup(".url-input") != null);
        }
        Parent editor() { return (Parent) ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getContent(); }
        void selectEditor(String name) { select((TabPane) editor().lookup("#request-editor-tabs"), name); }
        void selectResults(String name) { select((TabPane) editor().lookup("#response-tabs"), name); }
        void select(TabPane pane, String name) { pane.getSelectionModel().select(pane.getTabs().stream().filter(tab -> tab.getText().equals(name)).findFirst().orElseThrow()); }
        @SuppressWarnings("unchecked") TableView<ExtractionResult> results() { return (TableView<ExtractionResult>) editor().lookup("#response-extraction-results"); }
        void url(String url) { ((TextField) editor().lookup(".url-input")).setText(url); }
        void send() { ((Button) editor().lookup(".send-button")).fire(); }
        void waitDone() throws Exception { waitFor(() -> !((Button) editor().lookup(".send-button")).isDisabled()); }
        void open(UUID id) throws Exception {
            var saved = await(repository.load(id)); String url = saved.draft().url(); fx(() -> { controller.openRequest(id); return null; });
            waitFor(() -> ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getText().startsWith(saved.item().name())
                    && ((TextField) editor().lookup(".url-input")).getText().equals(url));
        }
        void refreshUntil(UUID id) throws Exception {
            fx(() -> { controller.refresh(); return null; });
            waitFor(() -> { @SuppressWarnings("unchecked") TreeView<LibraryItem> tree = (TreeView<LibraryItem>) stage.getScene().getRoot().lookup("#library-tree"); return contains(tree.getRoot(), id); });
        }
        boolean contains(TreeItem<LibraryItem> node, UUID id) { return node.getValue() != null && node.getValue().id().equals(id) || node.getChildren().stream().anyMatch(child -> contains(child, id)); }
        @Override public void close() throws Exception { fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown()); }
    }
}
