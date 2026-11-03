package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.importexport.TransferFiles;
import dev.apitestingplatform.ui.MainView;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/** Readable, paged history. Repository and file operations never block JavaFX. */
public final class HistoryWindow implements AutoCloseable {
    private final LibraryRepository repository; private final HistoryService history; private final TransferFiles files;
    private final Stage stage = new Stage();
    private final TableView<HistorySummary> records = new TableView<>();
    private final TableView<HistoryRecord.Step> steps = new TableView<>();
    private final TextArea details = new TextArea();
    private final Label summary = new Label("Select a record to reopen its report."), status = new Label(), page = new Label();
    private final Spinner<Integer> count = new Spinner<>(1, 1000, 100), days = new Spinner<>(1, 365, 30);
    private final Button export = new Button("Export report…"), previous = new Button("Previous"), next = new Button("Next"), saveRetention = new Button("Save retention");
    private HistoryRecord selected; private int offset; private long listVersion, loadVersion; private boolean closed;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    public HistoryWindow(Window owner, LibraryRepository repository, HistoryService history, TransferFiles files) {
        this.repository = repository; this.history = history; this.files = files;
        stage.initOwner(owner); stage.setTitle("Execution history"); stage.setMinWidth(900); stage.setMinHeight(650);
        records.setId("history-records"); records.setPlaceholder(new Label("No history yet. Send a request or run a collection, then Refresh."));
        records.getColumns().addAll(List.of(column("Started", row -> TIME.format(Instant.ofEpochMilli(row.startedMillis())), 165), column("Type", row -> row.kind().name(), 100),
                column("State", row -> row.state().name().replace('_', ' '), 190), column("Processed", row -> row.completed() + "/" + row.planned(), 85), column("Request failures", row -> Long.toString(row.requestFailures()), 110), column("Failed checks", row -> Long.toString(row.failedChecks()), 100)));
        records.getSelectionModel().selectedItemProperty().addListener((value, before, after) -> load(after));
        steps.setId("history-steps"); steps.setPlaceholder(new Label("No completed step checkpoint was saved for this record."));
        steps.getColumns().addAll(List.of(column("#", row -> Integer.toString(row.entry().position()), 40), column("Iteration", row -> Integer.toString(row.entry().iteration()), 65), column("Request", row -> row.entry().name(), 180),
                column("Outcome", row -> row.entry().outcome().name().replace('_', ' '), 140), column("HTTP", row -> Integer.toString(row.entry().statusCode()), 60),
                column("Time (ms)", row -> Long.toString(row.entry().durationMillis()), 80), column("Bytes", row -> Long.toString(row.entry().sizeBytes()), 80),
                column("Checks pass/fail/not run", row -> row.passed() + "/" + row.failed() + "/" + row.notRun(), 160)));
        steps.getSelectionModel().selectedItemProperty().addListener((value, before, after) -> details(after));
        details.setId("history-details"); details.setEditable(false); details.setWrapText(true); details.setPrefRowCount(6);
        summary.setId("history-summary"); summary.setWrapText(true); status.setId("history-status"); status.setWrapText(true);
        export.setId("history-export"); export.setDisable(true); export.setOnAction(event -> export());
        Button refresh = button("Refresh", "history-refresh", this::refresh), retry = button("Retry pending saves", "history-retry", () -> {
            status.setText("Retrying pending history saves…"); history.retry().whenComplete((ignored, failure) -> Platform.runLater(() -> { if (!closed) { status.setText(failure == null ? "Pending saves completed." : "Some history saves still failed. Check storage access and retry."); refresh(); } }));
        });
        Button clear = button("Clear finished history…", "history-clear", () -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Remove all finished history records? Saved requests and running entries are kept.", ButtonType.CANCEL, ButtonType.OK); confirm.initOwner(stage); confirm.setHeaderText("Clear execution history?");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) repository.clearHistory().whenComplete((ignored, failure) -> Platform.runLater(() -> { if (!closed) { status.setText(failure == null ? "Finished history cleared." : "History could not be cleared."); refresh(); } }));
        });
        count.setId("history-max-entries"); days.setId("history-max-days"); count.setPrefWidth(100); days.setPrefWidth(100);
        saveRetention.setId("history-save-retention"); saveRetention.setDisable(true); saveRetention.setOnAction(event -> {
            saveRetention.setDisable(true); repository.saveHistorySettings(new HistorySettings(count.getValue(), days.getValue())).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (!closed) { saveRetention.setDisable(false); status.setText(failure == null ? "Retention saved; older finished records removed." : "Retention could not be saved."); offset = 0; refresh(); }
            }));
        });
        previous.setId("history-previous"); next.setId("history-next"); previous.setOnAction(event -> { offset = Math.max(0, offset - 100); refresh(); }); next.setOnAction(event -> { offset += 100; refresh(); });
        FlowPane actions = new FlowPane(8, 6, refresh, retry, export, clear, previous, next, page);
        FlowPane retention = new FlowPane(8, 6, new Label("Keep at most"), count, new Label("records for"), days, new Label("days"), saveRetention);
        Label note = new Label("Changing retention immediately removes older finished records. Reports omit headers, bodies, credentials and compared values. Interrupted runs may contain only their last saved checkpoint."); note.setWrapText(true);
        SplitPane stepDetails = new SplitPane(steps, details); stepDetails.setOrientation(javafx.geometry.Orientation.VERTICAL); stepDetails.setDividerPositions(0.64);
        VBox report = new VBox(8, summary, stepDetails); VBox.setVgrow(stepDetails, Priority.ALWAYS);
        SplitPane split = new SplitPane(records, report); split.setOrientation(javafx.geometry.Orientation.VERTICAL); split.setDividerPositions(0.32); VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(10, actions, retention, note, status, split); root.setId("history-window"); root.setPadding(new Insets(14)); root.getStyleClass().add("app-root");
        root.getStylesheets().add(Objects.requireNonNull(MainView.class.getResource("main.css")).toExternalForm()); stage.setScene(new Scene(root, 1180, 850)); stage.setOnHidden(event -> close());
        repository.historySettings().whenComplete((settings, failure) -> Platform.runLater(() -> { if (!closed && failure == null) { count.getValueFactory().setValue(settings.maxEntries()); days.getValueFactory().setValue(settings.maxAgeDays()); saveRetention.setDisable(false); } }));
    }
    public void show() { if (!closed) { stage.show(); stage.toFront(); refresh(); } }
    public boolean isShowing() { return !closed && stage.isShowing(); }
    private static Button button(String text, String id, Runnable action) { Button result = new Button(text); result.setId(id); result.setOnAction(event -> action.run()); return result; }
    private static <T> TableColumn<T, String> column(String title, Function<T, String> value, int width) { TableColumn<T, String> result = new TableColumn<>(title); result.setPrefWidth(width); result.setSortable(false); result.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue()))); return result; }
    private void refresh() {
        long version = ++listVersion; UUID keep = records.getSelectionModel().getSelectedItem() == null ? null : records.getSelectionModel().getSelectedItem().id();
        repository.listHistory(100, offset).whenComplete((rows, failure) -> Platform.runLater(() -> {
            if (closed || version != listVersion) return;
            if (failure != null) { status.setText("History could not be loaded. Refresh to retry."); return; }
            records.getItems().setAll(rows); previous.setDisable(offset == 0); next.setDisable(rows.size() < 100); page.setText("Records " + (rows.isEmpty() ? 0 : offset + 1) + "–" + (offset + rows.size()));
            records.getSelectionModel().select(rows.stream().filter(row -> row.id().equals(keep)).findFirst().orElse(rows.isEmpty() ? null : rows.getFirst()));
        }));
    }
    private void load(HistorySummary record) {
        long version = ++loadVersion; selected = null; export.setDisable(true); steps.getItems().clear(); details.clear();
        if (record == null) { summary.setText("Select a record to reopen its report."); return; }
        summary.setText("Loading report…"); repository.loadHistory(record.id()).whenComplete((value, failure) -> Platform.runLater(() -> {
            if (closed || version != loadVersion) return;
            if (failure != null) { summary.setText("Report no longer exists or could not be read. Refresh history."); return; }
            selected = value; export.setDisable(false); steps.getItems().setAll(value.steps());
            summary.setText(value.title() + " · " + value.state().name().replace('_', ' ') + " · " + value.steps().size() + "/" + value.planned() + " step records · " + value.requestFailures() + " request failures · " + value.failedChecks() + " failed checks · " + value.extractionProblems() + " extraction problems");
            if (!value.steps().isEmpty()) steps.getSelectionModel().selectFirst();
        }));
    }
    private void details(HistoryRecord.Step step) {
        if (step == null) { details.clear(); return; }
        StringBuilder text = new StringBuilder(step.entry().details()).append("\nCompared values, headers and bodies are omitted from history.");
        for (var check : step.entry().assertions()) text.append("\n\nCheck: ").append(check.name()).append(" — ").append(check.outcome()).append("\n").append(check.reason());
        for (var extraction : step.entry().extractions()) text.append("\n\nExtraction: ").append(extraction.variable()).append(" — ").append(extraction.outcome()).append("\n").append(extraction.details());
        if (step.detailsOmitted()) text.append("\nSome rule details were omitted by the report size limit; counts remain complete."); details.setText(text.toString());
    }
    private void export() {
        if (selected == null) return; HistoryRecord record = selected;
        FileChooser chooser = new FileChooser(); chooser.setTitle("Export non-secret report"); chooser.setInitialFileName("api-report.html");
        var html = new FileChooser.ExtensionFilter("Readable HTML report", "*.html"); chooser.getExtensionFilters().addAll(html, new FileChooser.ExtensionFilter("JSON report", "*.json"));
        var chosen = chooser.showSaveDialog(stage); if (chosen == null) return; status.setText("Exporting sanitized report…");
        files.report(chosen.toPath(), record, chooser.getSelectedExtensionFilter() == html).whenComplete((ignored, failure) -> Platform.runLater(() -> { if (!closed) status.setText(failure == null ? "Non-secret report exported." : "Report export failed; the previous destination was not replaced."); }));
    }
    @Override public void close() { if (closed) return; closed = true; loadVersion++; listVersion++; stage.hide(); selected = null; records.getItems().clear(); steps.getItems().clear(); details.clear(); }
}
