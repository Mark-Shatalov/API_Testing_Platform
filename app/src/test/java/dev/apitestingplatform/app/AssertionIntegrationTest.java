package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.response.ResponseTestResult;
import dev.apitestingplatform.ui.theme.*;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.AssertionRow;
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
import static dev.apitestingplatform.domain.http.AssertionDefinition.Type.*;
import static dev.apitestingplatform.domain.http.AssertionResult.Outcome.*;
import static org.assertj.core.api.Assertions.*;

class AssertionIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static AssertionRow row(String name, AssertionDefinition.Type type, String target, String expected, boolean enabled) {
        return new AssertionRow(new AssertionDefinition(UUID.randomUUID(), name, type, target, expected, enabled));
    }

    @Test void editsChecksShowsMixedResultsAndPersistsOrderWithoutChangingAnInFlightSnapshot() throws Exception {
        UUID savedId; List<AssertionDefinition> savedChecks;
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start();
            fx(() -> { fixture.selectEditorTab("Tests"); return null; });
            waitFor(() -> fixture.editor().lookup("#assertion-add") != null);
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText(server.url("/checks").toString());
                ((Button) fixture.editor().lookup("#assertion-add")).fire(); return null;
            });
            waitFor(() -> fixture.editor().lookup("#assertion-name") != null);
            fx(() -> {
                ((TextField) fixture.editor().lookup("#assertion-name")).setText("Status is correct");
                var rules = fixture.controller.activeEditor().requestEditorModel().assertions;
                rules.add(row("JSON ok is true", JSON_PATH_EQUALS, "$.ok", "true", true));
                rules.add(row("Intentional failure", STATUS_EQUALS, "", "201", true));
                rules.add(row("Disabled draft", JSON_PATH_EQUALS, "not a path", "\"private-assertion-marker\"", false));
                AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "assertion-editor");
                fixture.selectResults(); return null;
            });
            savedChecks = fx(() -> fixture.controller.activeEditor().requestEditorModel().capture().assertions());
            var parent = await(fixture.repository.create(LibraryItem.Kind.COLLECTION, fixture.workspace, "Checks"));
            savedId = await(fixture.repository.createRequest(parent.id(), "Saved checks", fx(() -> fixture.controller.activeEditor().requestEditorModel().capture()))).item().id();
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"id\":2}").addHeader("Content-Type", "application/json").setBodyDelay(500, TimeUnit.MILLISECONDS));
            fx(() -> { fixture.send(); return null; });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            fx(() -> { fixture.controller.activeEditor().requestEditorModel().assertions.getFirst().expected.set("500"); return null; });
            waitFor(() -> fixture.results() != null && fixture.results().getItems().size() == 4);
            fx(() -> {
                assertThat(fixture.results().getItems()).extracting(ResponseTestResult::outcome).containsExactly(PASS, PASS, FAIL, DISABLED);
                assertThat(fixture.results().getItems().getFirst().expected()).isEqualTo("200");
                assertThat(((Label) fixture.editor().lookup("#response-test-summary")).getText()).contains("2 passed", "1 failed", "1 disabled");
                assertThat(fixture.controller.activeEditor().requestEditorModel().assertions.getFirst().expected.get()).isEqualTo("500");
                AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "assertion-results-graphite");
                fixture.themes.apply(new Appearance(Appearance.Theme.LIGHT, "#2563eb"));
                AppearanceIntegrationTest.snapshot(fixture.stage.getScene().getRoot(), "assertion-results-light"); return null;
            });
            var recovery = fx(() -> fixture.controller.activeEditor().requestEditorModel().capture());
            await(fixture.repository.putDraft(new RecoveryDraft(UUID.randomUUID(), savedId, fixture.workspace, "Check draft", recovery, Instant.now())));
        }
        try (Fixture restarted = new Fixture()) {
            assertThat(await(restarted.repository.load(savedId)).draft().assertions()).isEqualTo(savedChecks);
            assertThat(await(restarted.repository.recoverableDrafts())).anyMatch(draft -> !draft.draft().assertions().isEmpty() && draft.draft().assertions().getFirst().expected().equals("500"));
            fx(() -> { restarted.controller.openRequest(savedId); return null; });
            waitFor(() -> restarted.controller.activeEditor().requestEditorModel().assertions.size() == 4);
            fx(() -> { assertThat(restarted.controller.activeEditor().requestEditorModel().capture().assertions()).isEqualTo(savedChecks); return null; });
        }
        String database = new String(Files.readAllBytes(directory.resolve("platform.sqlite")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(database).doesNotContain("private-assertion-marker");
    }

    @Test void invalidCheckBlocksTrafficAndMarksItsRow() throws Exception {
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start();
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText(server.url("/checks").toString());
                fixture.controller.activeEditor().requestEditorModel().assertions.add(row("Invalid path", JSON_PATH_EXISTS, "$..id", "", true));
                fixture.selectResults(); fixture.send(); return null;
            });
            waitFor(() -> !fixture.controller.activeEditor().requestEditorModel().assertions.getFirst().error.get().isEmpty());
            assertThat(server.getRequestCount()).isZero();
            waitFor(() -> fixture.results() != null && fixture.results().getItems().size() == 1);
            fx(() -> { assertThat(fixture.results().getItems().getFirst().outcome()).isEqualTo(NOT_RUN); return null; });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"timeout", "cancel"})
    void timeoutAndCancellationShowNotRunInsteadOfPassingChecks(String action) throws Exception {
        try (MockWebServer server = new MockWebServer(); Fixture fixture = new Fixture()) {
            server.start(); server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText(server.url("/waiting").toString());
                var draft = fixture.controller.activeEditor().requestEditorModel(); draft.timeout.set(action.equals("timeout") ? "0.25" : "10");
                draft.assertions.add(row("Status", STATUS_EQUALS, "", "200", true)); fixture.selectResults(); fixture.send(); return null;
            });
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            if (action.equals("cancel")) fx(() -> { ((Button) fixture.editor().lookup(".cancel-button")).fire(); return null; });
            waitFor(() -> fixture.results() != null && fixture.results().getItems().size() == 1);
            fx(() -> { assertThat(fixture.results().getItems().getFirst().outcome()).isEqualTo(NOT_RUN);
                assertThat(fixture.results().getItems().getFirst().details()).contains(action.equals("timeout") ? "TIMEOUT" : "CANCELLED"); return null; });
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
            if (await(repository.list()).isEmpty()) await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Assertion demo"));
            workspace = await(repository.list()).stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).findFirst().orElseThrow().id();
            controller = fx(() -> new LibraryWindowController(engine, repository, false));
            stage = fx(() -> { Stage stage = new Stage(); stage.setScene(new Scene(controller.createView(), 1500, 1050)); return stage; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            fx(() -> { stage.show(); return null; }); waitFor(() -> controller.activeEditor() != null && editor().lookup(".url-input") != null);
        }
        Parent editor() { return (Parent) ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getContent(); }
        void selectEditorTab(String title) { TabPane pane = (TabPane) editor().lookup("#request-editor-tabs"); pane.getSelectionModel().select(pane.getTabs().stream().filter(tab -> tab.getText().equals(title)).findFirst().orElseThrow()); }
        void selectResults() { TabPane pane = (TabPane) editor().lookup("#response-tabs"); pane.getSelectionModel().selectLast(); }
        @SuppressWarnings("unchecked") TableView<ResponseTestResult> results() { return (TableView<ResponseTestResult>) editor().lookup("#response-test-results"); }
        void send() { ((Button) editor().lookup(".send-button")).fire(); }
        @Override public void close() throws Exception { fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown()); }
    }
}
