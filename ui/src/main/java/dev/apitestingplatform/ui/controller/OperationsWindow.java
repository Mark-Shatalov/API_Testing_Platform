package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.application.operations.AppOperations;
import dev.apitestingplatform.application.storage.LibraryRepository;
import dev.apitestingplatform.domain.operations.OperationalSettings;
import dev.apitestingplatform.domain.run.HistorySettings;
import dev.apitestingplatform.ui.MainView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Operational controls stay non-modal; network settings apply on next launch and restore is staged for restart. */
public final class OperationsWindow implements AutoCloseable {
    private final Stage stage = new Stage(); private final AppOperations operations; private final LibraryRepository repository;
    private final ComboBox<OperationalSettings.ProxyMode> mode = new ComboBox<>();
    private final TextField host = new TextField(), certificate = new TextField();
    private final Spinner<Integer> port = new Spinner<>(1, 65535, 8080), response = new Spinner<>(64, 10240, 10240, 64), keep = new Spinner<>(1, 1000, 100), days = new Spinner<>(1, 365, 30);
    private final Label status = new Label("Loading settings…"), startup = new Label(), pending = new Label();
    private final TextField dataDirectory = pathField("operations-data-directory"), database = pathField("operations-database"), logs = pathField("operations-log-directory");
    private final VBox root = new VBox(14); private boolean closed, busy;
    public OperationsWindow(Window owner, AppOperations operations, LibraryRepository repository, Runnable exportWorkspace) {
        this.operations = operations; this.repository = repository; stage.initOwner(owner); stage.setTitle("Settings, backups and diagnostics"); stage.setMinWidth(760); stage.setMinHeight(650);
        root.setPadding(new Insets(16)); root.setId("operations-content"); root.getStyleClass().add("app-root"); root.getStylesheets().add(MainView.stylesheet());
        mode.getItems().setAll(OperationalSettings.ProxyMode.values()); mode.setId("operations-proxy-mode"); mode.setValue(OperationalSettings.ProxyMode.DIRECT);
        host.setId("operations-proxy-host"); host.setPromptText("Proxy hostname or IP, without credentials"); port.setId("operations-proxy-port"); response.setId("operations-response-limit"); certificate.setId("operations-ca-file"); certificate.setPromptText("Optional additional CA certificate bundle (PEM/DER)");
        host.disableProperty().bind(mode.valueProperty().isNotEqualTo(OperationalSettings.ProxyMode.MANUAL)); port.disableProperty().bind(host.disableProperty());
        Button chooseCertificate = button("Choose CA file…", "operations-choose-ca", () -> { FileChooser chooser = new FileChooser(); chooser.setTitle("Additional trusted CA certificates (no private keys)"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificates", "*.pem", "*.crt", "*.cer", "*.der")); var selected = chooser.showOpenDialog(stage); if (selected != null) certificate.setText(selected.getAbsolutePath()); });
        Label networkHint = hint("Network changes apply after restarting the application. Direct ignores system proxy settings; System uses the OS/JVM selector. Certificate and hostname validation always stay enabled. An additional CA bundle adds trust to the JVM defaults.");
        GridPane network = new GridPane(); network.setHgap(10); network.setVgap(10); network.addRow(0, new Label("Proxy"), mode, new Label("Port"), port); network.addRow(1, new Label("Host"), host); GridPane.setHgrow(host, Priority.ALWAYS); GridPane.setColumnSpan(host, 3);
        network.addRow(2, new Label("Additional CA"), certificate, chooseCertificate); GridPane.setHgrow(certificate, Priority.ALWAYS);
        network.addRow(3, new Label("Capture / preview limit (KiB)"), response);
        Button save = button("Save network settings", "operations-save", () -> {
            try { var settings = new OperationalSettings(mode.getValue(), host.getText(), port.getValue(), certificate.getText(), response.getValue()); perform(operations.saveSettings(settings), "Validating and saving settings…", "Network settings saved. Restart to apply them.", false); }
            catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); }
        });
        keep.setId("operations-history-count"); days.setId("operations-history-days");
        FlowPane retention = new FlowPane(8, 6, new Label("Keep at most"), keep, new Label("records for"), days, new Label("days"), button("Save retention", "operations-save-retention", () -> perform(repository.saveHistorySettings(new HistorySettings(keep.getValue(), days.getValue())), "Updating retention…", "History retention saved; older finished records removed.", false)));
        status.setId("operations-status"); status.setWrapText(true); startup.setId("operations-startup-notice"); startup.setWrapText(true); pending.setId("operations-pending-restore"); pending.setWrapText(true);
        FlowPane backups = new FlowPane(8, 6,
                button("Back up database…", "operations-backup", this::backup), button("Restore after restart…", "operations-restore", this::restore),
                button("Cancel pending restore", "operations-cancel-restore", () -> perform(operations.cancelRestore(), "Cancelling pending restore…", "Pending restore cancelled. Current database is unchanged.", true)),
                button("Export workspace…", "operations-export-workspace", exportWorkspace));
        FlowPane diagnostics = new FlowPane(8, 6, button("Open Logs Folder", "operations-open-logs", () -> perform(operations.openLogs(), "Opening log folder…", "Log folder opened.", false)), button("Create diagnostic archive…", "operations-diagnostics", this::diagnostics));
        root.getChildren().addAll(new Label("NETWORK"), networkHint, network, save, new Separator(), new Label("HISTORY RETENTION"), retention,
                hint("Retention applies immediately. Running history records and saved requests are kept."), new Separator(), new Label("LOCAL DATA"), new Label("Data directory"), dataDirectory, new Label("Database"), database, new Label("Logs"), logs,
                backups, pending, hint("Database backups include encrypted saved data and depend on this Windows profile. A selected restore is verified now and applied on the next launch. Current database files are preserved automatically before replacement."),
                new Separator(), diagnostics, hint("Diagnostics contain only bounded structured events and non-secret version/settings summaries; databases, certificates, addresses, paths, bodies and credentials are excluded."), startup);
        ScrollPane scroll = new ScrollPane(root); scroll.setFitToWidth(true); BorderPane frame = new BorderPane(scroll); frame.setId("operations-window"); frame.getStyleClass().add("app-root"); status.setPadding(new Insets(12)); frame.setBottom(status); stage.setScene(new Scene(frame, 960, 900)); stage.setOnHidden(event -> close());
    }
    private static TextField pathField(String id) { TextField field = new TextField(); field.setId(id); field.setEditable(false); return field; }
    private static Label hint(String text) { Label label = new Label(text); label.setWrapText(true); label.setMinHeight(Region.USE_PREF_SIZE); return label; }
    private static Button button(String text, String id, Runnable action) { Button result = new Button(text); result.setId(id); result.setOnAction(event -> action.run()); return result; }
    public void show() { if (!closed) { if (stage.isShowing()) { stage.toFront(); return; } stage.show(); stage.toFront(); load(); } }
    public boolean isShowing() { return !closed && stage.isShowing(); }
    private void load() { load(true); }
    private void load(boolean resetStatus) {
        busy = true; root.setDisable(true);
        operations.info().whenComplete((info, failure) -> Platform.runLater(() -> {
            if (closed) return; busy = false; root.setDisable(false); if (failure != null) { status.setText("Settings could not be loaded. Close this window and retry."); return; }
            mode.setValue(info.settings().proxyMode()); host.setText(info.settings().proxyHost()); port.getValueFactory().setValue(info.settings().proxyPort()); certificate.setText(info.settings().additionalCaFile()); response.getValueFactory().setValue(info.settings().responseLimitKiB());
            dataDirectory.setText(info.dataDirectory()); database.setText(info.databaseFile()); logs.setText(info.logsDirectory()); startup.setText(info.startupNotice() + (info.logNotice().isEmpty() ? "" : "\n" + info.logNotice())); pending.setText(info.pendingRestore() ? "A verified restore is pending. Restart to apply it, or cancel the pending restore." : "No restore is pending.");
            if (!busy && resetStatus) status.setText("Ready. Network settings apply after restart.");
        }));
        repository.historySettings().whenComplete((settings, failure) -> Platform.runLater(() -> { if (!closed && failure == null) { keep.getValueFactory().setValue(settings.maxEntries()); days.getValueFactory().setValue(settings.maxAgeDays()); } }));
    }
    private void perform(CompletionStage<?> operation, String progress, String success, boolean reload) {
        if (busy || closed) return; busy = true; root.setDisable(true); status.setText(progress);
        operation.whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (closed) return; busy = false; root.setDisable(false);
            if (reload) load(false);
            if (failure == null) status.setText(success);
            else { Throwable cause = failure; while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause(); status.setText(cause instanceof dev.apitestingplatform.application.storage.StorageException || cause instanceof java.io.IOException ? cause.getMessage() : "Operation failed. Current data and editor changes were kept; retry after checking access and disk space."); }
        }));
    }
    private void backup() { FileChooser chooser = new FileChooser(); chooser.setTitle("Back up the complete database"); chooser.setInitialFileName("api-testing-platform-backup.sqlite"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite backup", "*.sqlite")); var selected = chooser.showSaveDialog(stage); if (selected != null) perform(operations.backup(selected.toPath()), "Creating and verifying a consistent database backup…", "Verified backup created.", false); }
    private void restore() { FileChooser chooser = new FileChooser(); chooser.setTitle("Choose a completed backup to restore after restart"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite backup", "*.sqlite")); var selected = chooser.showOpenDialog(stage); if (selected != null) perform(operations.stageRestore(selected.toPath()), "Verifying the selected backup for this Windows profile…", "Verified restore staged. Restart to apply it; the current database will be preserved first.", true); }
    private void diagnostics() { FileChooser chooser = new FileChooser(); chooser.setTitle("Create a diagnostic archive without database or secrets"); chooser.setInitialFileName("api-diagnostics.zip"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Diagnostic archive", "*.zip")); var selected = chooser.showSaveDialog(stage); if (selected != null) perform(operations.diagnostics(selected.toPath()), "Creating a bounded non-secret diagnostic archive…", "Diagnostic archive created.", false); }
    @Override public void close() { if (closed) return; closed = true; stage.hide(); }
}
