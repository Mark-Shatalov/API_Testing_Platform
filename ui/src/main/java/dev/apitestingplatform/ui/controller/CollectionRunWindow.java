package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.application.run.CollectionRunner;
import dev.apitestingplatform.application.storage.LibraryRepository;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.ui.MainView;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.*;
import java.util.function.Function;

/** Non-modal runner: the saved collection is captured at Start while the main editor stays usable. */
public final class CollectionRunWindow implements AutoCloseable {
    private final LibraryRepository repository;
    private final CollectionRunner runner;
    private final UUID collectionId;
    private final Stage stage = new Stage();
    private final Spinner<Integer> iterations = new Spinner<>(1, 1000, 1);
    private final Spinner<Integer> delay = new Spinner<>(0, 60000, 0, 100);
    private final Button start = new Button("Start"), cancel = new Button("Cancel run");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label("Ready. The selected environment and saved request order will be captured at Start.");
    private final Label context = new Label();
    private final TableView<RunEntry> results = new TableView<>();
    private final TextArea details = new TextArea("Select a result to inspect checks and extraction.");
    private CollectionRunner.Execution active;
    private boolean closed, preparing;
    private long ticket;

    public CollectionRunWindow(Window owner, LibraryRepository repository, CollectionRunner runner, UUID collectionId, String name) {
        this.repository = repository; this.runner = runner; this.collectionId = collectionId;
        stage.initOwner(owner); stage.setTitle("Run collection — " + name); stage.setMinWidth(850); stage.setMinHeight(570);
        context.setText("Collection: " + name); context.setId("collection-run-context"); context.setWrapText(true);
        iterations.setId("collection-run-iterations"); delay.setId("collection-run-delay"); iterations.setPrefWidth(105); delay.setPrefWidth(130);
        iterations.setAccessibleText("Iteration count"); delay.setAccessibleText("Delay between requests in milliseconds");
        start.setId("collection-run-start"); cancel.setId("collection-run-cancel"); cancel.setDisable(true);
        start.setOnAction(event -> start()); cancel.setOnAction(event -> cancel());
        Label hint = new Label("Runs saved requests in tree order, including folders. Save edits before Start. Each iteration has fresh temporary variables; failures are recorded and later requests continue. Closing this window cancels the run. Runs are saved in History; compared values and response bodies are omitted."); hint.setWrapText(true);
        FlowPane controls = new FlowPane(10, 8, new Label("Iterations"), iterations, new Label("Delay (ms)"), delay, start, cancel);
        progress.setMaxWidth(Double.MAX_VALUE); progress.setId("collection-run-progress");
        status.setId("collection-run-status"); status.setWrapText(true);
        results.setId("collection-run-results"); results.setPlaceholder(new Label("Press Start to run the saved collection."));
        results.getColumns().addAll(List.of(column("#", row -> Integer.toString(row.position()), 45), column("Iteration", row -> Integer.toString(row.iteration()), 70),
                column("Request", RunEntry::name, 170), column("Request result", row -> row.outcome().name().replace('_', ' '), 140),
                column("HTTP", row -> row.statusCode() == 0 ? "—" : Integer.toString(row.statusCode()), 60),
                column("Time (ms)", row -> Long.toString(row.durationMillis()), 80), column("Bytes", row -> Long.toString(row.sizeBytes()), 75),
                column("Checks", CollectionRunWindow::checks, 180), column("Extraction", CollectionRunWindow::extraction, 180)));
        details.setEditable(false); details.setWrapText(true); details.setId("collection-run-details"); details.setPrefRowCount(6);
        results.getSelectionModel().selectedItemProperty().addListener((value, before, selected) -> showDetails(selected));
        SplitPane split = new SplitPane(results, details); split.setOrientation(javafx.geometry.Orientation.VERTICAL); split.setDividerPositions(0.7); VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(10, context, hint, controls, progress, status, split); root.setPadding(new Insets(14)); root.getStyleClass().add("app-root"); root.setId("collection-run-window");
        root.getStylesheets().add(Objects.requireNonNull(MainView.class.getResource("main.css")).toExternalForm());
        stage.setScene(new Scene(root, 1160, 780)); stage.setOnHidden(event -> close());
    }
    private static TableColumn<RunEntry, String> column(String title, Function<RunEntry, String> value, double width) {
        TableColumn<RunEntry, String> column = new TableColumn<>(title); column.setPrefWidth(width); column.setSortable(false);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue()))); return column;
    }
    private static String checks(RunEntry entry) {
        if (entry.assertions().isEmpty()) return "—";
        long passed = entry.assertions().stream().filter(row -> row.outcome() == dev.apitestingplatform.domain.http.AssertionResult.Outcome.PASS).count();
        long notRun = entry.assertions().stream().filter(row -> row.outcome() == dev.apitestingplatform.domain.http.AssertionResult.Outcome.NOT_RUN).count();
        return passed + " pass / " + entry.failedChecks() + " fail" + (notRun == 0 ? "" : " / " + notRun + " not run");
    }
    private static String extraction(RunEntry entry) {
        if (entry.extractions().isEmpty()) return "—";
        long extracted = entry.extractions().stream().filter(row -> row.outcome() == dev.apitestingplatform.domain.http.ExtractionResult.Outcome.EXTRACTED).count();
        return extracted + " extracted / " + entry.extractionProblems() + " problems";
    }
    private void showDetails(RunEntry entry) {
        if (entry == null) { details.setText("Select a result to inspect checks and extraction."); return; }
        StringBuilder text = new StringBuilder(entry.details());
        for (var check : entry.assertions()) text.append("\n\nCheck: ").append(check.name()).append(" — ").append(check.outcome()).append("\nExpected: ").append(check.expected())
                .append("\nActual: ").append(check.actual()).append("\n").append(check.reason());
        for (var extraction : entry.extractions()) text.append("\n\nExtraction: ").append(extraction.variable()).append(" — ").append(extraction.outcome()).append("\n").append(extraction.details());
        details.setText(text.toString());
    }
    public void show() { if (!closed) { stage.show(); stage.toFront(); } }
    public boolean isShowing() { return !closed && stage.isShowing(); }
    private void busy(boolean value) { start.setDisable(value); iterations.setDisable(value); delay.setDisable(value); cancel.setDisable(!value); }
    private void start() {
        if (closed || active != null || preparing) return;
        final RunOptions options = new RunOptions(iterations.getValue(), delay.getValue());
        long version = ++ticket; preparing = true; busy(true); progress.setProgress(-1); status.setText("Capturing saved collection and selected environment…");
        results.getItems().clear(); showDetails(null);
        repository.snapshotCollection(collectionId).whenComplete((snapshot, failure) -> Platform.runLater(() -> {
            if (closed || version != ticket) return; preparing = false;
            if (failure != null) { busy(false); progress.setProgress(0); status.setText("Collection could not be loaded. Refresh the library and try again."); return; }
            try {
                int total = options.total(snapshot.requests().size());
                context.setText("Collection: " + snapshot.name() + " · Environment: " + snapshot.environmentName() + " · " + snapshot.requests().size() + " saved requests · " + total + " executions");
                progress.setProgress(0); status.setText("Starting collection…");
                active = runner.start(snapshot, options, update -> Platform.runLater(() -> {
                    if (closed || version != ticket) return;
                    progress.setProgress((double) update.completed() / update.total());
                    status.setText(update.phase() + " · " + update.completed() + "/" + update.total() + " processed · iteration " + update.iteration());
                }), row -> Platform.runLater(() -> {
                    if (closed || version != ticket) return; results.getItems().add(row);
                    if (results.getSelectionModel().getSelectedItem() == null) results.getSelectionModel().selectFirst();
                }));
                active.result().whenComplete((report, error) -> Platform.runLater(() -> {
                    if (closed || version != ticket) return; active = null; busy(false);
                    if (error != null) { status.setText("Run could not finish. Results already received remain visible."); return; }
                    results.getItems().setAll(report.entries());
                    long processed = report.entries().stream().filter(row -> row.outcome() != RunEntry.Outcome.NOT_RUN).count();
                    progress.setProgress((double) processed / report.entries().size());
                    status.setText(report.status().name().replace('_', ' ') + " · " + processed + "/" + report.entries().size() + " processed · " + report.requestFailures() + " request failures · "
                            + report.failedChecks() + " failed checks · " + report.extractionProblems() + " extraction problems" + (report.historyWarning().isEmpty() ? " · history saved" : " · " + report.historyWarning()));
                    if (results.getSelectionModel().getSelectedItem() == null) results.getSelectionModel().selectFirst();
                }));
            } catch (IllegalArgumentException | IllegalStateException unavailable) { busy(false); progress.setProgress(0); status.setText(unavailable.getMessage()); }
        }));
    }
    private void cancel() {
        if (preparing) { ticket++; preparing = false; busy(false); progress.setProgress(0); status.setText("Cancelled before the run started; no requests were sent."); }
        else if (active != null && active.cancel()) { status.setText("Cancelling the active request; later requests will not start…"); cancel.setDisable(true); }
    }
    @Override public void close() { if (closed) return; closed = true; ticket++; if (active != null) active.cancel(); stage.hide(); }
}
