package dev.apitestingplatform.app;

import dev.apitestingplatform.application.run.CollectionRunner;
import dev.apitestingplatform.application.storage.HistoryService;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Optional measured workload, not a timing threshold tied to one CI machine. Uses only temporary data and loopback HTTP. */
@EnabledIfSystemProperty(named = "platform.performance", matches = "true")
class PerformanceRecoveryTest {
    @TempDir Path directory;
    static <T> T await(CompletionStage<T> operation) throws Exception { return operation.toCompletableFuture().get(60, TimeUnit.SECONDS); }
    static <T> T fx(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(60, TimeUnit.SECONDS); }
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    @Test void measuresTenThousandRequestNavigationOpeningAndFiveHundredRequestRun() throws Exception {
        Properties metrics = new Properties(); metrics.setProperty("variant", System.getProperty("platform.performance.variant", "current"));
        metrics.setProperty("java", System.getProperty("java.version")); metrics.setProperty("logicalProcessors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        metrics.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        try (var server = new MockWebServer(); var engine = new JavaHttpExecutionEngine()) {
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setBody("{\"ok\":true}"); } }); server.start();
            var repo = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector()); await(repo.ready());
            var workspace = await(repo.create(LibraryItem.Kind.WORKSPACE, null, "Performance fixture"));
            var navigation = await(repo.create(LibraryItem.Kind.COLLECTION, workspace.id(), "10,000 requests"));
            var runCollection = await(repo.create(LibraryItem.Kind.COLLECTION, workspace.id(), "500 requests"));
            var empty = RequestDraft.empty();
            var draft = new RequestDraft(empty.method(), server.url("/perf").toString(), List.of(), List.of(), List.of(), empty.bodyType(), "", List.of(), List.of(), "10", empty.redirects(), empty.protocol(), List.of(), Authentication.none(),
                    List.of(new AssertionDefinition(UUID.randomUUID(), "Status 200", AssertionDefinition.Type.STATUS_EQUALS, "", "200", true)), List.of());
            var template = await(repo.createRequest(navigation.id(), "Request 00000", draft)); List<UUID> ids = new ArrayList<>(); ids.add(template.item().id());
            String payload;
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + repo.databasePath()); var statement = db.prepareStatement("SELECT payload_json FROM requests WHERE id=?")) {
                statement.setString(1, template.item().id().toString()); try (var row = statement.executeQuery()) { row.next(); payload = row.getString(1); }
                db.setAutoCommit(false);
                try (var item = db.prepareStatement("INSERT INTO items VALUES(?,'REQUEST',?,?,?,0,?)"); var request = db.prepareStatement("INSERT INTO requests VALUES(?,?,NULL,?)")) {
                    for (int index = 1; index < 10500; index++) {
                        UUID id = UUID.randomUUID(), parent = index < 10000 ? navigation.id() : runCollection.id(); if (index < 10000) ids.add(id);
                        item.setString(1, id.toString()); item.setString(2, parent.toString()); item.setString(3, "Request " + String.format(Locale.ROOT, "%05d", index)); item.setInt(4, index < 10000 ? index : index - 10000); item.setString(5, Instant.now().toString()); item.addBatch();
                        request.setString(1, id.toString()); request.setString(2, parent.toString()); request.setString(3, payload); request.addBatch();
                    }
                    item.executeBatch(); request.executeBatch(); db.commit();
                } finally { db.setAutoCommit(true); }
            }
            long start = System.nanoTime(); var controller = fx(() -> new LibraryWindowController(engine, repo, false));
            Stage stage = fx(() -> { Stage result = new Stage(); result.setScene(new Scene(controller.createView(), 1400, 900)); result.show(); return result; });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!fx(() -> controller.activeEditor() != null)) { if (System.nanoTime() > deadline) fail("Library did not become ready"); Thread.sleep(10); }
                fx(() -> { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); return null; });
                metrics.setProperty("libraryReadyMs", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
                List<Long> opening = new ArrayList<>();
                for (int index = 1; index <= 8; index++) {
                    UUID id = ids.get(index * 1000); CompletableFuture<Long> opened = new CompletableFuture<>(); long began = System.nanoTime();
                    fx(() -> {
                        TabPane tabs = (TabPane) stage.getScene().getRoot().lookup("#library-request-tabs");
                        @SuppressWarnings("unchecked") ChangeListener<Tab>[] listener = new ChangeListener[1];
                        listener[0] = (value, before, after) -> { if (after != null && after.getText().startsWith("Request ")) { tabs.getSelectionModel().selectedItemProperty().removeListener(listener[0]); Platform.runLater(() -> {
                            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                            assertThat(stage.getScene().getRoot().lookup("#params-table")).isNotNull();
                            opened.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
                        }); } };
                        tabs.getSelectionModel().selectedItemProperty().addListener(listener[0]); controller.openRequest(id); return null;
                    });
                    opening.add(await(opened));
                }
                List<Long> sorted = opening.stream().sorted().toList(); metrics.setProperty("openSamplesMs", opening.toString()); metrics.setProperty("openMedianMs", Long.toString((sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2)); metrics.setProperty("openMaxMs", Long.toString(sorted.getLast()));
                long navigationStart = System.nanoTime();
                fx(() -> { @SuppressWarnings("unchecked") TreeView<LibraryItem> tree = (TreeView<LibraryItem>) stage.getScene().getRoot().lookup("#library-tree");
                    var collection = tree.getRoot().getChildren().stream().filter(child -> child.getValue().id().equals(navigation.id())).findFirst().orElseThrow();
                    for (int index : List.of(9999, 5000, 0)) { tree.getSelectionModel().select(collection.getChildren().get(index)); tree.scrollTo(index + 1); stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
                    assertThat(collection.getChildren()).hasSize(10000); return null;
                });
                metrics.setProperty("threeNavigationMovesMs", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - navigationStart)));
                long runStart = System.nanoTime(); var snapshot = await(repo.snapshotCollection(runCollection.id()));
                try (var runner = new CollectionRunner(engine, new HistoryService(repo))) {
                    var report = await(runner.start(snapshot, new RunOptions(1, 0), progress -> { }, entry -> { }).result());
                    assertThat(report.status()).isEqualTo(RunReport.Status.COMPLETED); assertThat(report.entries()).hasSize(500); assertThat(server.getRequestCount()).isEqualTo(500);
                }
                metrics.setProperty("run500Ms", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runStart)));
                System.gc(); Thread.sleep(100);
                metrics.setProperty("usedHeapMiB", Long.toString((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)));
                metrics.setProperty("navigationRequests", "10000"); metrics.setProperty("runRequests", "500");
            } finally { fx(() -> { controller.close(); stage.close(); return null; }); await(repo.shutdown()); }
        }
        Path output = Path.of(System.getProperty("platform.performance.output", "target/performance.properties")).toAbsolutePath(); Files.createDirectories(output.getParent());
        try (var file = Files.newOutputStream(output)) { metrics.store(file, "Measured performance workload; fixture creation excluded"); }
        System.out.println("PERFORMANCE " + metrics);
    }
}
