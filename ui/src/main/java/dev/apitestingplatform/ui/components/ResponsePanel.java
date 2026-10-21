package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.domain.http.ExecutionResult;
import dev.apitestingplatform.domain.http.HttpStatus;
import dev.apitestingplatform.ui.response.*;
import dev.apitestingplatform.ui.viewmodel.MainWindowViewModel;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

/** Reusable response viewer; decoding, JSON formatting, searching and file writes run off the FX thread. */
public final class ResponsePanel extends VBox implements AutoCloseable {
    private enum Mode { Pretty, Raw }
    private final MainWindowViewModel viewModel;
    private final ReadOnlyObjectWrapper<ResponseState> state = new ReadOnlyObjectWrapper<>(ResponseState.EMPTY);
    private final ReadOnlyBooleanWrapper ready = new ReadOnlyBooleanWrapper(false);
    private final ThreadPoolExecutor work = executor("response-format-", 2);
    private final ThreadPoolExecutor files = executor("response-save-", 1);
    private final CodeArea content = new CodeArea();
    private final ComboBox<Mode> mode = new ComboBox<>();
    private final TextField query = new TextField();
    private final Label notice = new Label("Send a request to inspect its response.");
    private final Label detail = new Label();
    private final Label pageLabel = new Label();
    private final Label searchResult = new Label();
    private final Label fileStatus = new Label();
    private final SimpleStringProperty contentType = new SimpleStringProperty("—");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button previousPage = new Button("Previous page");
    private final Button nextPage = new Button("Next page");
    private final Button save = new Button("Save Response…");
    private final TableView<ResponsePresentation.HeaderRow> headers = table("No response headers");
    private final TableView<ResponsePresentation.CookieRow> cookies = table("No response cookies");
    private final TableView<ResponseTestResult> tests = table("No assertions configured for this response");
    private ResponsePresentation presentation;
    private ExecutionResult response;
    private Future<?> preparation;
    private Future<?> searching;
    private long revision;
    private long searchRevision;
    private int pageStart;
    private int lastMatch = -1;
    private String lastQuery = "";
    private boolean closed;

    public ResponsePanel(MainWindowViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("response-panel");
        setMinHeight(260);
        Label title = new Label("Response");
        title.getStyleClass().add("panel-title");
        FlowPane metadata = new FlowPane(8, 6,
                new MetricBadge("Status", viewModel.responseStatusProperty()),
                new MetricBadge("Time", viewModel.responseTimeProperty()),
                new MetricBadge("Size", viewModel.responseSizeProperty()),
                new MetricBadge("Content type", contentType));
        HBox.setHgrow(metadata, Priority.ALWAYS);
        HBox heading = new HBox(14, title, metadata);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.getStyleClass().add("response-header");
        progress.setMaxSize(16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        notice.setWrapText(true);
        HBox banner = new HBox(8, progress, notice);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("response-banner");
        notice.setId("response-state");

        headers.getColumns().addAll(List.of(column("Name", ResponsePresentation.HeaderRow::name, 170),
                column("Value", ResponsePresentation.HeaderRow::value, 520)));
        rowCopy(headers, row -> row.name() + ": " + row.value());
        cookies.getColumns().addAll(List.of(column("Name", ResponsePresentation.CookieRow::name, 120),
                column("Value", ResponsePresentation.CookieRow::value, 170),
                column("Domain", ResponsePresentation.CookieRow::domain, 120),
                column("Path", ResponsePresentation.CookieRow::path, 80),
                column("Expires", ResponsePresentation.CookieRow::expires, 180),
                column("Max-Age", ResponsePresentation.CookieRow::maxAge, 80),
                column("Secure", ResponsePresentation.CookieRow::secure, 70),
                column("HttpOnly", ResponsePresentation.CookieRow::httpOnly, 75),
                column("SameSite", ResponsePresentation.CookieRow::sameSite, 85),
                column("Raw Set-Cookie", ResponsePresentation.CookieRow::raw, 360)));
        rowCopy(cookies, ResponsePresentation.CookieRow::raw);
        tests.getColumns().addAll(List.of(column("Test", ResponseTestResult::name, 240),
                column("Result", row -> row.passed() ? "PASS" : "FAIL", 85),
                column("Details", ResponseTestResult::details, 450)));
        rowCopy(tests, row -> row.name() + "\t" + (row.passed() ? "PASS" : "FAIL") + "\t" + row.details());
        TabPane tabs = new TabPane(new Tab("Body", bodyView()), new Tab("Headers", headers),
                new Tab("Cookies", cookies), new Tab("Test Results", tests));
        tabs.setId("response-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("response-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().addAll(heading, banner, tabs);
    }

    private static ThreadPoolExecutor executor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon().name(name, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    private VBox bodyView() {
        mode.getItems().setAll(Mode.Raw);
        mode.setValue(Mode.Raw);
        mode.setId("response-mode");
        mode.setDisable(true);
        mode.valueProperty().addListener((observable, oldValue, newValue) -> {
            pageStart = 0; lastMatch = -1; searchRevision++; renderPage();
        });
        query.setPromptText("Find in captured body (case-sensitive)");
        query.setId("response-search");
        query.setOnAction(event -> search(false));
        HBox.setHgrow(query, Priority.ALWAYS);
        Button previous = new Button("Find previous");
        previous.setOnAction(event -> search(true));
        Button next = new Button("Find next");
        next.setId("response-find-next");
        next.setOnAction(event -> search(false));
        Button copy = new Button("Copy selection");
        copy.setId("response-copy");
        copy.setOnAction(event -> content.copy());
        copy.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> content.getSelectedText().isEmpty(), content.selectedTextProperty()));
        save.setId("response-save");
        save.setDisable(true);
        save.setOnAction(event -> chooseSaveFile());
        FlowPane actions = new FlowPane(8, 6, mode, copy, save, fileStatus);
        HBox searchBar = new HBox(8, query, previous, next, searchResult);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("response-actions");
        searchBar.getStyleClass().add("response-search-bar");
        content.setId("response-content");
        content.setEditable(false);
        content.setWrapText(false);
        content.setParagraphGraphicFactory(LineNumberFactory.get(content));
        content.getStyleClass().add("response-code");
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(content);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        detail.setWrapText(true);
        detail.getStyleClass().add("response-detail");
        previousPage.setOnAction(event -> { pageStart = Math.max(0, pageStart - ResponsePresentation.PAGE_SIZE); renderPage(); });
        nextPage.setOnAction(event -> { pageStart += ResponsePresentation.PAGE_SIZE; renderPage(); });
        HBox pages = new HBox(8, previousPage, pageLabel, nextPage);
        pages.getStyleClass().add("response-pages");
        pages.setAlignment(Pos.CENTER_LEFT);
        previousPage.setDisable(true);
        nextPage.setDisable(true);
        return new VBox(actions, searchBar, detail, scroll, pages);
    }

    public ReadOnlyObjectProperty<ResponseState> stateProperty() { return state.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty readyProperty() { return ready.getReadOnlyProperty(); }

    public void showLoading() {
        requireFxThread();
        clear();
        setState(ResponseState.LOADING, "Sending request…");
    }

    public void showValidationFailure(String message) {
        requireFxThread();
        clear();
        setState(ResponseState.EMPTY, message);
    }

    public void showResponse(ExecutionResult result) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showResponse(result)); return; }
        if (closed) return;
        clear();
        response = result;
        long version = revision;
        setState(ResponseState.LOADING, "Preparing response…");
        viewModel.responseStatusProperty().set(result.statusCode() == 0 ? "—" : HttpStatus.label(result.statusCode()));
        viewModel.responseTimeProperty().set(result.durationMillis() + " ms");
        viewModel.responseSizeProperty().set(result.sizeBytes() + " bytes" + (result.bodyTruncated() ? " (captured)" : ""));
        try {
            preparation = work.submit(() -> {
                try {
                    ResponsePresentation prepared = ResponsePresentation.prepare(result);
                    Platform.runLater(() -> {
                        if (closed || revision != version) return;
                        presentation = prepared;
                        viewModel.responseSizeProperty().set(Math.min(result.sizeBytes(), ResponsePresentation.BODY_LIMIT)
                                + " bytes" + (prepared.truncated() ? " (captured)" : ""));
                        contentType.set(prepared.contentType());
                        headers.getItems().setAll(prepared.headers());
                        cookies.getItems().setAll(prepared.cookies());
                        mode.getItems().setAll(prepared.pretty() == null ? List.of(Mode.Raw) : List.of(Mode.Pretty, Mode.Raw));
                        mode.setValue(prepared.pretty() == null ? Mode.Raw : Mode.Pretty);
                        mode.setDisable(false);
                        detail.setText(prepared.note());
                        ready.set(true);
                        save.setDisable(result.statusCode() == 0 && result.sizeBytes() == 0);
                        ResponseState finalState = ResponseState.of(result);
                        String message = switch (finalState) {
                            case SUCCESS -> result.sizeBytes() == 0 ? "Response received — body is empty" : "Response received";
                            case HTTP_ERROR -> "HTTP error " + result.statusCode() + " — response body is available below";
                            case RESPONSE_TOO_LARGE -> "Response too large — captured prefix available below";
                            case DNS_ERROR -> "DNS error — " + result.error().orElseThrow().message();
                            case TLS_ERROR -> "TLS error — " + result.error().orElseThrow().message();
                            case CONNECTION_ERROR -> "Connection refused — " + result.error().orElseThrow().message();
                            case REDIRECT_ERROR -> "Redirect error — " + result.error().orElseThrow().message();
                            default -> result.error().map(error -> error.message()).orElse("Response received");
                        };
                        setState(finalState, message);
                        renderPage();
                    });
                } catch (RuntimeException failure) {
                    Platform.runLater(() -> {
                        if (closed || revision != version) return;
                        setState(ResponseState.NETWORK_ERROR, "Unable to prepare the body for display. You can still save its captured bytes.");
                        save.setDisable(false);
                    });
                }
            });
        } catch (RejectedExecutionException busy) { setState(ResponseState.NETWORK_ERROR, "Viewer busy; try again."); }
    }

    public void showTests(List<ResponseTestResult> results) {
        List<ResponseTestResult> copy = List.copyOf(results);
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showTests(copy)); return; }
        if (!closed) tests.getItems().setAll(copy);
    }

    private void clear() {
        revision++; searchRevision++;
        if (preparation != null) preparation.cancel(true);
        if (searching != null) searching.cancel(true);
        work.purge();
        response = null; presentation = null; pageStart = 0; lastMatch = -1; lastQuery = "";
        ready.set(false);
        content.clear(); content.getUndoManager().forgetHistory();
        headers.getItems().clear(); cookies.getItems().clear(); tests.getItems().clear();
        mode.setDisable(true); save.setDisable(true);
        previousPage.setDisable(true); nextPage.setDisable(true);
        detail.setText(""); pageLabel.setText(""); searchResult.setText(""); fileStatus.setText("");
        viewModel.responseStatusProperty().set("—"); viewModel.responseTimeProperty().set("—");
        viewModel.responseSizeProperty().set("—"); contentType.set("—");
    }

    private void setState(ResponseState value, String message) {
        state.set(value);
        notice.setText(message);
        notice.getStyleClass().setAll("response-state", "state-" + value.name().toLowerCase(java.util.Locale.ROOT));
        progress.setVisible(value == ResponseState.LOADING); progress.setManaged(progress.isVisible());
    }

    private String source() {
        if (presentation == null) return "";
        return mode.getValue() == Mode.Pretty && presentation.pretty() != null ? presentation.pretty() : presentation.raw();
    }

    private void renderPage() {
        if (presentation == null) return;
        String source = source();
        pageStart = Math.min(pageStart, Math.max(0, source.length() - 1));
        int end = Math.min(source.length(), pageStart + ResponsePresentation.PAGE_SIZE);
        // Do not split UTF-16 surrogate pairs at page boundaries.
        if (pageStart > 0 && Character.isLowSurrogate(source.charAt(pageStart))) pageStart--;
        if (end < source.length() && end > 0 && Character.isHighSurrogate(source.charAt(end - 1))) end--;
        String page = source.substring(pageStart, end);
        content.replaceText(page); content.getUndoManager().forgetHistory();
        if (!page.isEmpty() && mode.getValue() == Mode.Pretty) content.setStyleSpans(0, JsonHighlighting.styles(page));
        else if (!page.isEmpty()) content.setStyle(0, page.length(), List.of());
        content.moveTo(0);
        previousPage.setDisable(pageStart == 0);
        nextPage.setDisable(end >= source.length());
        pageLabel.setText(source.isEmpty() ? "Empty body" : "Characters " + (pageStart + 1) + "–" + end + " of " + source.length());
    }

    private void search(boolean backwards) {
        if (presentation == null || query.getText().isEmpty()) { searchResult.setText("Enter search text"); return; }
        if (searching != null) searching.cancel(true);
        work.purge();
        String text = source();
        String needle = query.getText();
        int start = lastQuery.equals(needle) && lastMatch >= 0 ? lastMatch + (backwards ? -1 : 1)
                : backwards ? text.length() : pageStart;
        long version = revision;
        long searchVersion = ++searchRevision;
        searchResult.setText("Searching…");
        try {
            searching = work.submit(() -> {
                int match = BodySearch.find(text, needle, start, backwards);
                Platform.runLater(() -> {
                    if (closed || revision != version || searchVersion != searchRevision) return;
                    lastQuery = needle; lastMatch = match;
                    if (match < 0) { searchResult.setText("No match"); return; }
                    pageStart = (match / ResponsePresentation.PAGE_SIZE) * ResponsePresentation.PAGE_SIZE;
                    if (match + needle.length() > pageStart + ResponsePresentation.PAGE_SIZE) pageStart = match;
                    renderPage();
                    content.selectRange(match - pageStart, Math.min(content.getLength(), match - pageStart + needle.length()));
                    content.requestFollowCaret();
                    searchResult.setText("Match at " + (match + 1));
                });
            });
        } catch (RejectedExecutionException busy) { searchResult.setText("Viewer busy"); }
    }

    private void chooseSaveFile() {
        if (response == null || getScene() == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(response.bodyTruncated() ? "Save captured response prefix" : "Save response body");
        chooser.setInitialFileName(presentation != null && presentation.json() ? "response.json" : "response.bin");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        saveTo(file.toPath());
    }

    /** Saves the current snapshot asynchronously. FileChooser is the normal user-facing entry point. */
    public void saveTo(java.nio.file.Path destination) {
        requireFxThread();
        if (response == null || closed) return;
        ExecutionResult snapshot = response;
        long version = revision;
        save.setDisable(true); fileStatus.setText("Saving…");
        try {
            files.execute(() -> {
                String message;
                try { ResponseBodyFiles.save(snapshot, destination); message = "Saved captured bytes"; }
                catch (java.io.IOException | RuntimeException failure) { message = "Unable to save response body"; }
                String finalMessage = message;
                Platform.runLater(() -> {
                    if (closed || revision != version) return;
                    fileStatus.setText(finalMessage); save.setDisable(false);
                });
            });
        } catch (RejectedExecutionException busy) { fileStatus.setText("Save already in progress"); }
    }

    private static <T> TableView<T> table(String empty) {
        TableView<T> table = new TableView<>();
        table.setPlaceholder(new Label(empty));
        table.getStyleClass().add("response-table");
        return table;
    }
    private static <T> TableColumn<T, String> column(String label, Function<T, String> value, int width) {
        TableColumn<T, String> column = new TableColumn<>(label);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }
    private static <T> void rowCopy(TableView<T> table, Function<T, String> text) {
        Runnable action = () -> {
            T selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            ClipboardContent content = new ClipboardContent(); content.putString(text.apply(selected));
            Clipboard.getSystemClipboard().setContent(content);
        };
        MenuItem copy = new MenuItem("Copy selected row");
        copy.setOnAction(event -> action.run());
        table.setContextMenu(new ContextMenu(copy));
        table.setOnKeyPressed(event -> { if (event.isShortcutDown() && event.getCode() == KeyCode.C) { action.run(); event.consume(); } });
    }
    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Viewer mutations must run on the FX thread");
    }
    @Override public void close() {
        requireFxThread();
        closed = true; revision++; searchRevision++;
        work.shutdownNow(); files.shutdownNow(); content.dispose();
    }
}
