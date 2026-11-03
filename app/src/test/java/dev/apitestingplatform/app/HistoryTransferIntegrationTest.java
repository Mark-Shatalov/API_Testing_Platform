package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.importexport.*;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import dev.apitestingplatform.ui.theme.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class HistoryTransferIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    static RequestDraft draft(String path, Authentication auth, List<AssertionDefinition> checks, List<ExtractionDefinition> extractions) {
        var empty = RequestDraft.empty();
        return new RequestDraft(empty.method(), "{{baseUrl}}" + path, List.of(), List.of(), List.of(), empty.bodyType(), "", List.of(), List.of(), "10", empty.redirects(), empty.protocol(), List.of(), auth, checks, extractions);
    }
    static RequestDraft plain(String path) { return draft(path, Authentication.none(), List.of(), List.of()); }
    static Authentication bearer() { return new Authentication(Authentication.Mode.BEARER, "{{token}}", "", "", "", "", Authentication.Placement.HEADER); }
    static Parent window(String id) { return Window.getWindows().stream().map(Window::getScene).filter(Objects::nonNull).map(Scene::getRoot).filter(root -> id.equals(root.getId())).findFirst().orElse(null); }

    @Test void manualSendReopensSanitizedHistoryAfterRestartAndExportsReadableReports() throws Exception {
        UUID historyId;
        try (Fixture fixture = new Fixture()) {
            var saved = await(fixture.repository.createRequest(fixture.collection, "Get token", draft("token", Authentication.none(),
                    List.of(new AssertionDefinition(UUID.randomUUID(), "Token comparison", AssertionDefinition.Type.JSON_PATH_EQUALS, "$.access_token", "\"expected-private-token\"", true)),
                    List.of(new ExtractionDefinition(UUID.randomUUID(), "token", "$.access_token", true)))));
            fixture.open(saved.item().id()); fixture.server.enqueue(new MockResponse().setBody("{\"access_token\":\"response-private-token\"}"));
            fx(() -> { fixture.send(); return null; }); assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            var record = fixture.terminalHistory(1).getFirst(); historyId = record.id();
            assertThat(record.kind()).isEqualTo(HistoryRecord.Kind.REQUEST); assertThat(record.failedChecks()).isEqualTo(1); assertThat(record.steps().getFirst().entry().statusCode()).isEqualTo(200);
            assertThat(record.toString()).doesNotContain("response-private-token", "expected-private-token");
            fixture.openHistory(); waitFor(() -> fixture.historyRows().getItems().size() == 1 && fixture.historySteps().getItems().size() == 1);
            fx(() -> {
                assertThat(((TextArea) window("history-window").lookup("#history-details")).getText()).contains("FAIL").doesNotContain("response-private-token", "expected-private-token");
                AppearanceIntegrationTest.snapshot(window("history-window"), "history-graphite"); fixture.themes.apply(new Appearance(Appearance.Theme.LIGHT, "#2563eb"));
                AppearanceIntegrationTest.snapshot(window("history-window"), "history-light"); return null;
            });
            Path report = directory.resolve("readable-report.html");
            try (var files = new TransferFiles()) { await(files.report(report, record, true)); }
            assertThat(Files.readString(report)).contains("Get token", "COMPLETED_WITH_FAILURES").doesNotContain("response-private-token", "expected-private-token");
        }
        try (Fixture restarted = new Fixture()) {
            assertThat(await(restarted.repository.loadHistory(historyId)).failedChecks()).isEqualTo(1);
            restarted.openHistory(); waitFor(() -> restarted.historySteps().getItems().size() == 1);
            fx(() -> { assertThat(restarted.historyRows().getItems().getFirst().id()).isEqualTo(historyId); return null; });
        }
    }

    @Test void collectionHistorySurvivesClosingRunnerAndNormalWindowCloseSavesCancellation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            await(fixture.repository.createRequest(fixture.collection, "Login", draft("login", Authentication.none(), List.of(), List.of(new ExtractionDefinition(UUID.randomUUID(), "token", "$.access_token", true)))));
            await(fixture.repository.createRequest(fixture.collection, "Protected", draft("protected", bearer(), List.of(), List.of())));
            fixture.server.enqueue(new MockResponse().setBody("{\"access_token\":\"run-private-token\"}")); fixture.server.enqueue(new MockResponse().setBody("{}"));
            fx(() -> { fixture.controller.showCollectionRun(fixture.collection); return null; }); waitFor(() -> window("collection-run-window") != null);
            fx(() -> { ((Button) window("collection-run-window").lookup("#collection-run-start")).fire(); return null; });
            assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS)).isNotNull(); assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer run-private-token");
            var completed = fixture.terminalHistory(1).getFirst(); assertThat(completed.kind()).isEqualTo(HistoryRecord.Kind.COLLECTION); assertThat(completed.steps()).hasSize(2);
            fx(() -> { ((Stage) window("collection-run-window").getScene().getWindow()).close(); return null; });
            fixture.openHistory(); waitFor(() -> fixture.historySteps().getItems().size() == 2);
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            fx(() -> { fixture.controller.showCollectionRun(fixture.collection); return null; }); waitFor(() -> window("collection-run-window") != null);
            fx(() -> { ((Button) window("collection-run-window").lookup("#collection-run-start")).fire(); return null; }); assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            CompletableFuture<Void> closed = new CompletableFuture<>();
            fx(() -> { fixture.controller.requestWindowClose(() -> { fixture.stage.close(); closed.complete(null); }); return null; }); closed.get(10, TimeUnit.SECONDS);
            var records = fixture.terminalHistory(2); assertThat(records).anyMatch(record -> record.state() == HistoryRecord.State.CANCELLED && record.steps().size() == 2);
            var cancelled = records.stream().filter(record -> record.state() == HistoryRecord.State.CANCELLED).findFirst().orElseThrow();
            assertThat(cancelled.steps()).extracting(step -> step.entry().outcome()).containsExactly(RunEntry.Outcome.CANCELLED, RunEntry.Outcome.NOT_RUN);
            assertThat(await(fixture.repository.listHistory(100, 0)).stream().filter(row -> row.id().equals(cancelled.id())).findFirst().orElseThrow().completed()).isEqualTo(1);
        }
        try (Fixture restarted = new Fixture()) { assertThat(restarted.terminalHistory(2)).anyMatch(record -> record.state() == HistoryRecord.State.CANCELLED); }
    }

    @ParameterizedTest @ValueSource(strings = {"timeout", "cancel", "tab-close"})
    void manualFailureAndCancellationHaveDurableOutcomes(String action) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            fx(() -> {
                ((TextField) fixture.editor().lookup(".url-input")).setText(fixture.server.url("/waiting").toString()); fixture.controller.activeEditor().requestEditorModel().timeout.set(action.equals("timeout") ? "0.2" : "10"); fixture.send(); return null;
            });
            assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            if (!action.equals("timeout")) fx(() -> { if (action.equals("tab-close")) fixture.controller.activeEditor().close(); else ((Button) fixture.editor().lookup(".cancel-button")).fire(); return null; });
            var record = fixture.terminalHistory(1).getFirst(); assertThat(record.state()).isEqualTo(action.equals("timeout") ? HistoryRecord.State.COMPLETED_WITH_FAILURES : HistoryRecord.State.CANCELLED);
            assertThat(record.steps().getFirst().entry().outcome()).isEqualTo(action.equals("timeout") ? RunEntry.Outcome.NETWORK_ERROR : RunEntry.Outcome.CANCELLED);
        }
    }

    @Test void workspaceFileRoundTripCopiesDataAndInvalidFileLeavesOriginalAndOpenDraftUntouched() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var saved = await(fixture.repository.createRequest(fixture.collection, "Get token", draft("token", Authentication.none(), List.of(), List.of(new ExtractionDefinition(UUID.randomUUID(), "token", "$.access_token", true)))));
            Path safeFile = directory.resolve("workspace-safe.json"), fullFile = directory.resolve("workspace-full.json");
            await(fixture.controller.exportWorkspace(fixture.workspace, safeFile, false)); await(fixture.controller.exportWorkspace(fixture.workspace, fullFile, true));
            assertThat(Files.readString(safeFile)).doesNotContain("environment-private-marker"); assertThat(Files.readString(fullFile)).contains("environment-private-marker");
            var before = await(fixture.repository.list()); UUID imported = await(fixture.controller.importWorkspace(fullFile)); assertThat(imported).isNotEqualTo(fixture.workspace);
            var copied = await(fixture.repository.exportWorkspace(imported)); assertThat(copied.environments().getFirst().variables()).contains(new Variable("token", "environment-private-marker", true));
            var copiedRequest = copied.nodes().stream().filter(node -> node.kind() == LibraryItem.Kind.REQUEST).findFirst().orElseThrow(); assertThat(copiedRequest.request()).isEqualTo(saved.draft());
            assertThat(await(fixture.repository.list())).containsAll(before); assertThat(fixture.server.getRequestCount()).isZero();
            fixture.open(saved.item().id());
            fx(() -> { ((TextField) fixture.editor().lookup(".url-input")).setText("https://example.test/unsaved-draft"); return null; });
            var snapshot = await(fixture.repository.list()); Path invalid = directory.resolve("invalid.json"); Files.writeString(invalid, Files.readString(fullFile).replace("\"version\" : 1", "\"version\" : 999"));
            assertThatThrownBy(() -> await(fixture.controller.importWorkspace(invalid))).hasCauseInstanceOf(java.io.IOException.class); assertThat(await(fixture.repository.list())).isEqualTo(snapshot);
            fx(() -> { assertThat(((TextField) fixture.editor().lookup(".url-input")).getText()).endsWith("unsaved-draft"); assertThat(fixture.controller.activeEditor().hasUnsavedEdits()).isTrue(); return null; });
            await(fixture.controller.importWorkspace(safeFile)); assertThat(fixture.server.getRequestCount()).isZero();
        }
    }

    @Test void failedHistoryWriteKeepsResponseAndRetryButtonPersistsTheFinalRecord() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + fixture.repository.databasePath()); var sql = db.createStatement()) { sql.execute("CREATE TRIGGER reject_history BEFORE INSERT ON execution_history BEGIN SELECT RAISE(ABORT,'History fixture'); END"); }
            fixture.server.enqueue(new MockResponse().setBody("{}")); fx(() -> { ((TextField) fixture.editor().lookup(".url-input")).setText(fixture.server.url("/okay").toString()); fixture.send(); return null; });
            assertThat(fixture.server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            waitFor(() -> ((Label) fixture.editor().lookup("#request-history-status")).getText().contains("failed"));
            fx(() -> { assertThat(((Label) fixture.editor().lookup("#response-state")).getText()).contains("Response received"); return null; });
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + fixture.repository.databasePath()); var sql = db.createStatement()) { sql.execute("DROP TRIGGER reject_history"); }
            fixture.openHistory(); fx(() -> { ((Button) window("history-window").lookup("#history-retry")).fire(); return null; });
            var record = fixture.terminalHistory(1).getFirst(); assertThat(record.state()).isEqualTo(HistoryRecord.State.COMPLETED); assertThat(record.steps().getFirst().entry().statusCode()).isEqualTo(200);
        }
    }

    @Test void exportDialogStartsWithSecretInclusionOffAndCanBeCancelledBeforeFileSelection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fx(() -> { Platform.runLater(() -> ((Button) fixture.stage.getScene().getRoot().lookup("#library-export-workspace")).fire()); return null; });
            waitFor(() -> dialog() != null && dialog().lookup("#transfer-include-secrets") != null);
            fx(() -> { assertThat(((CheckBox) dialog().lookup("#transfer-include-secrets")).isSelected()).isFalse(); AppearanceIntegrationTest.snapshot(dialog(), "export-options"); ((Button) dialog().lookupButton(ButtonType.CANCEL)).fire(); return null; });
            assertThat(fixture.server.getRequestCount()).isZero();
        }
    }
    static DialogPane dialog() { return Window.getWindows().stream().map(Window::getScene).filter(Objects::nonNull).map(Scene::getRoot).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElse(null); }

    final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer(); final JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine();
        final SqliteLibraryRepository repository; final UUID workspace, collection; final LibraryWindowController controller; final Stage stage; final ThemeManager themes;
        Fixture() throws Exception {
            server.start(); repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector()); await(repository.ready());
            if (await(repository.list()).isEmpty()) {
                var ws = await(repository.create(LibraryItem.Kind.WORKSPACE, null, "History test")); await(repository.create(LibraryItem.Kind.COLLECTION, ws.id(), "Collection"));
                var environment = await(repository.createEnvironment(ws.id(), "Test", List.of(new Variable("baseUrl", server.url("/").toString(), false), new Variable("token", "environment-private-marker", true)))); await(repository.selectEnvironment(ws.id(), environment.id()));
            }
            var items = await(repository.list()); workspace = items.stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).findFirst().orElseThrow().id(); collection = items.stream().filter(item -> item.kind() == LibraryItem.Kind.COLLECTION && item.parentId().equals(workspace)).findFirst().orElseThrow().id();
            controller = fx(() -> new LibraryWindowController(engine, repository, false));
            stage = fx(() -> { Stage result = new Stage(); result.setScene(new Scene(controller.createView(), 1500, 1050)); return result; });
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            fx(() -> { stage.show(); return null; }); waitFor(() -> controller.activeEditor() != null && editor().lookup(".url-input") != null);
        }
        Parent editor() { return (Parent) ((TabPane) stage.getScene().getRoot().lookup("#library-request-tabs")).getSelectionModel().getSelectedItem().getContent(); }
        void send() { ((Button) editor().lookup(".send-button")).fire(); }
        void open(UUID id) throws Exception { var saved = await(repository.load(id)); fx(() -> { controller.openRequest(id); return null; }); waitFor(() -> ((TextField) editor().lookup(".url-input")).getText().equals(saved.draft().url())); }
        void openHistory() throws Exception { fx(() -> { ((Button) stage.getScene().getRoot().lookup("#library-history")).fire(); return null; }); waitFor(() -> window("history-window") != null); }
        @SuppressWarnings("unchecked") TableView<HistorySummary> historyRows() { return (TableView<HistorySummary>) window("history-window").lookup("#history-records"); }
        @SuppressWarnings("unchecked") TableView<HistoryRecord.Step> historySteps() { return (TableView<HistoryRecord.Step>) window("history-window").lookup("#history-steps"); }
        List<HistoryRecord> terminalHistory(int expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                var rows = await(repository.listHistory(100, 0)).stream().filter(row -> row.state() != HistoryRecord.State.RUNNING).toList();
                if (rows.size() >= expected) { List<HistoryRecord> reports = new ArrayList<>(); for (var row : rows) reports.add(await(repository.loadHistory(row.id()))); return reports; }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("History did not reach the expected terminal state");
        }
        @Override public void close() throws Exception { fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown()); server.close(); }
    }
}
