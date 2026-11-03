package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.ui.MainView;
import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.run.RunEntry;
import dev.apitestingplatform.domain.http.ExtractionResult;
import dev.apitestingplatform.ui.components.RequestToolbar;
import dev.apitestingplatform.ui.components.RequestEditor;
import dev.apitestingplatform.ui.components.ResponsePanel;
import dev.apitestingplatform.ui.components.WorkspaceSidebar;
import dev.apitestingplatform.ui.viewmodel.MainWindowViewModel;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel;
import dev.apitestingplatform.application.http.HttpExecutionEngine;
import dev.apitestingplatform.application.http.RequestExecution;
import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.application.http.VariableResolver;
import dev.apitestingplatform.application.http.AssertionEngine;
import dev.apitestingplatform.application.http.ExtractionEngine;
import dev.apitestingplatform.application.http.RunVariables;
import dev.apitestingplatform.domain.http.ExtractionDefinition;
import dev.apitestingplatform.application.http.SecretRedactor;
import dev.apitestingplatform.domain.http.AssertionDefinition;
import dev.apitestingplatform.domain.http.AssertionResult;
import dev.apitestingplatform.ui.response.ResponseTestResult;
import dev.apitestingplatform.domain.http.RequestConfiguration;
import dev.apitestingplatform.domain.http.RequestBody;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.concurrent.*;
import dev.apitestingplatform.domain.http.ExecutionResult;
import dev.apitestingplatform.domain.http.NetworkError;

/** Presentation orchestration: immutable submission snapshots and background validation/execution. */
public final class MainWindowController implements AutoCloseable {
    private final MainWindowViewModel viewModel = new MainWindowViewModel();
    private final RequestEditorViewModel draft = new RequestEditorViewModel(viewModel);
    private final ThreadPoolExecutor validation = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("request-validate-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final HttpExecutionEngine engine;
    private RequestEditor editor;
    private Future<?> pendingValidation;
    private Future<?> pendingAssertions;
    private java.util.List<AssertionDefinition> runningAssertions = java.util.List.of();
    private SecretRedactor runningRedactor = SecretRedactor.empty();
    private record ResponseWork(java.util.List<AssertionDefinition> assertions, java.util.List<ExtractionDefinition> extractions,
            RunVariables session, RunVariables.Snapshot snapshot, SecretRedactor redactor, RequestHistory history) { }
    private ResponseWork runningWork;
    private HistoryService historyService;
    private java.util.function.Supplier<HistoryService.Context> historyContext;
    private RequestHistory currentHistory;
    private final Label historyNotice = new Label();
    public void setHistory(HistoryService service, java.util.function.Supplier<HistoryService.Context> context) { historyService = service; historyContext = context; }
    public void cancelForWindowClose() {
        if (currentHistory != null) saveHistory(currentHistory, RunEntry.Outcome.CANCELLED, 0, 0, 0, java.util.List.of(), java.util.List.of(), "Request was cancelled.", SecretRedactor.empty());
        onCancelRequested();
    }
    private final RunVariables privateSession = new RunVariables();
    private java.util.function.Supplier<RunVariables> runVariables = () -> privateSession;
    public void setRunVariables(java.util.function.Supplier<RunVariables> provider) { runVariables = Objects.requireNonNull(provider); }
    public void clearPrivateSession() { privateSession.clear(); }
    public void clearTemporaryVariables() {
        runVariables.get().clear(); viewModel.showMessage("Temporary variables cleared. Saved environment values are unchanged.");
    }
    private long submission;
    private ResponsePanel responsePanel;
    private java.nio.file.Path managedDatabase;
    public void setManagedDatabase(java.nio.file.Path database) { managedDatabase = database; }
    private RequestExecution active;
    private boolean closed;
    private final Label target = new Label("Preview the resolved target before sending.");
    private java.util.function.Supplier<CompletionStage<VariableResolver.Context>> variableContext =
            () -> CompletableFuture.completedFuture(VariableResolver.Context.empty());

    public void setVariableContext(java.util.function.Supplier<CompletionStage<VariableResolver.Context>> provider) {
        variableContext = Objects.requireNonNull(provider);
    }

    public MainWindowController() { this(null); }
    public MainWindowController(HttpExecutionEngine engine) { this.engine = engine; }
    public RequestEditorViewModel requestEditorModel() { return draft; }
    public boolean hasUnsavedEdits() { return draft.dirtyProperty().get(); }

    public Parent createView() {
        WorkspaceSidebar sidebar = new WorkspaceSidebar();
        sidebar.collectionsSection().createButton().setOnAction(
                event -> viewModel.showMessage("Collection creation will be added with collection management."));
        sidebar.requestsSection().createButton().setOnAction(
                event -> viewModel.showMessage("Request creation will be added with request management."));

        SplitPane horizontalSplit = new SplitPane(sidebar, createRequestWorkspace());
        horizontalSplit.setDividerPositions(0.22);
        horizontalSplit.getStyleClass().add("main-split");

        BorderPane root = new BorderPane();
        root.setTop(createApplicationBar());
        root.setCenter(horizontalSplit);
        root.setBottom(createStatusBar());
        root.getStyleClass().add("app-root");
        root.getStylesheets().add(Objects.requireNonNull(
                MainView.class.getResource("main.css"),
                "UI stylesheet is missing").toExternalForm());
        return root;
    }

    private Parent createApplicationBar() {
        Label productName = new Label("API Testing Platform");
        productName.getStyleClass().add("brand-title");

        Label offlineBadge = new Label("LOCAL");
        offlineBadge.getStyleClass().add("offline-badge");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label context = new Label("Default Workspace");
        context.getStyleClass().add("window-context");

        HBox bar = new HBox(12, productName, offlineBadge, spacer, context);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("application-bar");
        return bar;
    }

    private Parent createRequestWorkspace() {
        Tab requestTab = new Tab("Untitled Request", createRequestContent());
        requestTab.textProperty().bind(javafx.beans.binding.Bindings.when(draft.dirtyProperty())
                .then("Untitled Request *").otherwise("Untitled Request"));
        requestTab.setClosable(false);
        TabPane requestTabs = new TabPane(requestTab);
        requestTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        requestTabs.getStyleClass().add("request-tabs");
        return requestTabs;
    }

    /** Reusable editor/response workspace for a saved-request tab. */
    public Parent createRequestContent() {
        RequestToolbar toolbar = new RequestToolbar(
                viewModel,
                this::onSendRequested,
                this::onCancelRequested);

        editor = new RequestEditor(draft);
        VBox.setVgrow(editor, Priority.ALWAYS);
        Button preview = new Button("Preview target"); preview.setId("request-preview-target");
        preview.setOnAction(event -> prepareRequest(false)); preview.disableProperty().bind(viewModel.operationInProgressProperty());
        target.setId("request-resolved-target"); target.setWrapText(true); HBox.setHgrow(target, Priority.ALWAYS);
        Button clearSession = new Button("Clear temporary variables"); clearSession.setId("request-clear-temporary");
        clearSession.setOnAction(event -> clearTemporaryVariables());
        HBox targetBar = new HBox(10, preview, clearSession, target); targetBar.setAlignment(Pos.CENTER_LEFT); targetBar.getStyleClass().add("target-preview");
        historyNotice.setId("request-history-status"); historyNotice.setWrapText(true); historyNotice.visibleProperty().bind(historyNotice.textProperty().isNotEmpty()); historyNotice.managedProperty().bind(historyNotice.visibleProperty());
        VBox requestEditor = new VBox(toolbar, targetBar, historyNotice, RequestEditor.error(draft.urlError, "request-url-error"),
                RequestEditor.error(draft.summary, "request-validation-summary"), editor);
        requestEditor.setMinHeight(260);
        requestEditor.getStyleClass().add("request-editor");

        responsePanel = new ResponsePanel(viewModel);
        responsePanel.setManagedDatabase(managedDatabase);
        SplitPane requestAndResponse = new SplitPane(requestEditor, responsePanel);
        requestAndResponse.setOrientation(Orientation.VERTICAL);
        requestAndResponse.setDividerPositions(0.55);
        requestAndResponse.getStyleClass().add("workspace-split");

        BorderPane content = new BorderPane(requestAndResponse);
        content.setBottom(createStatusBar());
        return content;
    }

    private Parent createStatusBar() {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(14, 14);
        progress.visibleProperty().bind(viewModel.operationInProgressProperty());
        progress.managedProperty().bind(progress.visibleProperty());

        Label message = new Label();
        message.textProperty().bind(viewModel.statusMessageProperty());

        HBox statusBar = new HBox(8, progress, message);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        return statusBar;
    }

    private void onSendRequested() {
        prepareRequest(true);
    }
    private void prepareRequest(boolean send) {
        if (send && engine == null) { viewModel.showMessage("HTTP engine is unavailable."); return; }
        if (viewModel.operationInProgressProperty().get() || closed) return;
        if (send && historyService != null) {
            currentHistory = new RequestHistory(historyService, historyContext.get()); currentHistory.begin(); historyNotice.setText("History recording started.");
        }
        var snapshot = draft.snapshot();
        if (snapshot.isEmpty()) { saveHistorySimple(RunEntry.Outcome.INVALID_REQUEST, "Request validation failed."); viewModel.showMessage(draft.summary.get()); return; }
        RequestConfiguration configuration = snapshot.orElseThrow();
        var localVariables = draft.variables.stream().map(RequestEditorViewModel.VariableRow::snapshot).toList();
        var authentication = draft.authentication();
        var extractions = draft.extractions.stream().map(RequestEditorViewModel.ExtractionRow::snapshot).toList();
        final RunVariables session;
        final RunVariables.Snapshot runSnapshot;
        try { session = runVariables.get(); runSnapshot = session.snapshot(); }
        catch (RuntimeException unavailable) { saveHistorySimple(RunEntry.Outcome.INVALID_REQUEST, "Temporary session is unavailable."); draft.variableError.set("Temporary session is unavailable. Clear temporary variables in the toolbar and try again."); return; }
        var assertions = draft.assertions.stream().map(RequestEditorViewModel.AssertionRow::snapshot).toList();
        CompletionStage<VariableResolver.Context> context;
        try { context = variableContext.get(); }
        catch (RuntimeException failure) { saveHistorySimple(RunEntry.Outcome.INVALID_REQUEST, "Workspace variables are unavailable."); draft.variableError.set("Workspace variables are unavailable. Refresh and try again."); return; }
        long revision = draft.revision();
        long ticket = ++submission;
        viewModel.beginOperation();
        target.setText("Preparing target…");
        viewModel.showMessage("Validating request…");
        if (send) responsePanel.showLoading();
        try {
            pendingValidation = validation.submit(() -> {
                java.util.List<RequestValidation.Issue> checked;
                VariableResolver.Resolved resolved = null;
                try {
                    resolved = VariableResolver.resolve(configuration, context.toCompletableFuture().get(10, TimeUnit.SECONDS).withRun(runSnapshot.values()), localVariables, authentication);
                    checked = resolved.issues().isEmpty() ? RequestValidation.validate(resolved.request()) : resolved.issues();
                    if (send && checked.isEmpty()) {
                        var postIssues = new java.util.ArrayList<>(AssertionEngine.validate(assertions));
                        postIssues.addAll(ExtractionEngine.validate(extractions)); checked = java.util.List.copyOf(postIssues);
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                catch (ExecutionException | TimeoutException failure) {
                    checked = java.util.List.of(new RequestValidation.Issue("variables", "Workspace variables could not be loaded. Refresh environments and try again."));
                }
                catch (RuntimeException failure) {
                    checked = java.util.List.of(new RequestValidation.Issue("body", "The request could not be validated. Check its content and upload metadata."));
                }
                var issues = checked;
                var prepared = resolved;
                Platform.runLater(() -> {
                    if (closed || submission != ticket) return;
                    pendingValidation = null;
                    if (!issues.isEmpty()) {
                        target.setText("Target unavailable — fix the marked fields.");
                        if (draft.revision() == revision) draft.showIssues(issues);
                        if (send) saveHistory(currentHistory, RunEntry.Outcome.INVALID_REQUEST, 0, 0, 0, historyNotRun(assertions, prepared == null ? SecretRedactor.empty() : prepared.redactor()), ExtractionEngine.notRun(extractions, SecretRedactor.empty(), "Request was not sent.").results(), "Request validation failed.", prepared == null ? SecretRedactor.empty() : prepared.redactor());
                        viewModel.finishOperation("Request not sent: validation failed."
                                + (draft.revision() != revision ? " The editor has newer unsent edits." : " See the marked fields."));
                        if (send) {
                            responsePanel.showValidationFailure("Request not sent — fix the validation errors in the editor.");
                            responsePanel.showTests(notRun(assertions, prepared == null ? SecretRedactor.empty() : prepared.redactor(), "Request was not sent because validation failed. See the marked editor fields."));
                            responsePanel.showExtractions(ExtractionEngine.notRun(extractions, prepared == null ? SecretRedactor.empty() : prepared.redactor(), "Request was not sent because validation failed.").results());
                        }
                        return;
                    }
                    target.setText("Target: " + prepared.preview());
                    if (send) execute(prepared.request(), revision, new ResponseWork(assertions, extractions, session, runSnapshot, prepared.redactor(), currentHistory));
                    else viewModel.finishOperation("Target preview ready. Secret variables are masked; no request was sent.");
                });
            });
        } catch (RejectedExecutionException unavailable) {
            if (send) saveHistorySimple(RunEntry.Outcome.INVALID_REQUEST, "Request worker is busy.");
            viewModel.finishOperation("Request validation is busy. Try again.");
            responsePanel.showValidationFailure("Request not sent — validation is busy.");
        }
    }

    private void execute(RequestConfiguration configuration, long draftRevision, ResponseWork work) {
        responsePanel.showLoading();
        viewModel.showMessage("Sending request…"); runningWork = work;
        try { active = engine.execute(configuration); }
        catch (RuntimeException exception) {
            presentResponse(localError(NetworkError.Type.IO, "Unable to start the request."), work);
            return;
        }
        RequestExecution execution = active;
        execution.result().whenComplete((result, failure) -> Platform.runLater(() -> {
            if (closed || active != execution) return;
            active = null;
            if (failure == null) {
                if (result.error().map(error -> error.type() == NetworkError.Type.INVALID_REQUEST).orElse(false)
                        && configuration.body().type() == RequestBody.Type.MULTIPART && draft.revision() == draftRevision) {
                    draft.bodyError.set("Upload could not start. Check that all enabled file parts point to readable regular files.");
                }
                presentResponse(result, work);
            }
            else presentResponse(localError(NetworkError.Type.IO, "The request could not be completed."), work);
        }));
    }
    private void presentResponse(ExecutionResult result, ResponseWork work) {
        responsePanel.showResponse(result);
        var assertions = work.assertions(); var redactor = work.redactor();
        String completion = result.error().map(error -> error.type() == NetworkError.Type.CANCELLED ? "Request cancelled" : "Request failed").orElse("Request completed");
        if (assertions.isEmpty() && work.extractions().isEmpty()) { saveResponseHistory(work, result, java.util.List.of(), java.util.List.of()); runningWork = null; viewModel.finishOperation(completion); return; }
        long ticket = submission; runningAssertions = assertions; runningRedactor = redactor;
        responsePanel.showTestsPending(); responsePanel.showExtractionsPending(); viewModel.showMessage("Checking and extracting response data…");
        try {
            pendingAssertions = validation.submit(() -> {
                ExtractionEngine.Evaluation extraction;
                java.util.List<AssertionResult> checks;
                try {
                    extraction = ExtractionEngine.evaluate(result, work.extractions(), redactor);
                    SecretRedactor combined = redactor.withSecrets(extraction.values().stream().map(dev.apitestingplatform.domain.variables.Variable::value).toList());
                    checks = AssertionEngine.evaluate(result, assertions, combined);
                } catch (RuntimeException failure) {
                    extraction = ExtractionEngine.notRun(work.extractions(), redactor, "Response processing could not finish safely.");
                    checks = historyNotRun(assertions, redactor);
                }
                var evaluated = checks; var extracted = extraction;
                Platform.runLater(() -> {
                    if (closed || ticket != submission) return;
                    pendingAssertions = null; runningWork = null; runningAssertions = java.util.List.of(); runningRedactor = SecretRedactor.empty();
                    responsePanel.showTests(evaluated.stream().map(ResponseTestResult::from).toList());
                    var published = work.extractions().isEmpty() ? extracted.results() : work.session().publish(work.snapshot(), extracted);
                    responsePanel.showExtractions(published); saveResponseHistory(work, result, evaluated, published);
                    viewModel.finishOperation(completion + " — response processing finished");
                });
            });
        } catch (RejectedExecutionException busy) {
            runningWork = null; runningAssertions = java.util.List.of(); runningRedactor = SecretRedactor.empty();
            responsePanel.showTests(notRun(assertions, redactor, "Response worker is busy. Send again to retry."));
            abandonExtractions(work, "Response worker is busy. Send again to retry."); saveResponseHistory(work, result, historyNotRun(assertions, redactor), ExtractionEngine.notRun(work.extractions(), redactor, "Worker is busy.").results()); viewModel.finishOperation(completion);
        }
    }
    private void abandonExtractions(ResponseWork work, String reason) {
        if (work != null && !work.extractions().isEmpty()) responsePanel.showExtractions(work.session().publish(work.snapshot(), ExtractionEngine.notRun(work.extractions(), work.redactor(), reason)));
    }
    private static java.util.List<ResponseTestResult> notRun(java.util.List<AssertionDefinition> assertions, SecretRedactor redactor, String reason) {
        return assertions.stream().map(rule -> new ResponseTestResult(redactor.text(rule.name()), rule.enabled() ? AssertionResult.Outcome.NOT_RUN : AssertionResult.Outcome.DISABLED,
                "", "", rule.enabled() ? reason : "Check is disabled.")).toList();
    }

    private void onCancelRequested() {
        if (active != null) active.cancel();
        else if (pendingAssertions != null) {
            submission++; pendingAssertions.cancel(true); pendingAssertions = null; validation.purge();
            responsePanel.showTests(notRun(runningAssertions, runningRedactor, "Check evaluation was cancelled."));
            abandonExtractions(runningWork, "Response processing was cancelled."); saveHistorySimple(RunEntry.Outcome.CANCELLED, "Response processing was cancelled."); runningWork = null;
            runningAssertions = java.util.List.of(); runningRedactor = SecretRedactor.empty(); viewModel.finishOperation("Response processing cancelled; response is still available.");
        }
        else if (pendingValidation != null) {
            submission++;
            pendingValidation.cancel(true); pendingValidation = null; validation.purge();
            saveHistorySimple(RunEntry.Outcome.CANCELLED, "Request was cancelled before sending.");
            viewModel.finishOperation("Request cancelled");
            responsePanel.showResponse(localError(NetworkError.Type.CANCELLED, "The request was cancelled."));
        }
    }

    private static java.util.List<AssertionResult> historyNotRun(java.util.List<AssertionDefinition> rules, SecretRedactor redactor) {
        return rules.stream().map(rule -> new AssertionResult(rule.id(), redactor.text(rule.name()), rule.enabled() ? AssertionResult.Outcome.NOT_RUN : AssertionResult.Outcome.DISABLED, "", "", "Check could not run.")).toList();
    }
    private void saveResponseHistory(ResponseWork work, ExecutionResult response, java.util.List<AssertionResult> checks, java.util.List<ExtractionResult> extractions) {
        var outcome = response.error().isPresent() ? response.error().get().type() == NetworkError.Type.CANCELLED ? RunEntry.Outcome.CANCELLED : RunEntry.Outcome.NETWORK_ERROR
                : response.statusCode() >= 400 ? RunEntry.Outcome.HTTP_ERROR : RunEntry.Outcome.SUCCESS;
        saveHistory(work.history(), outcome, response.statusCode(), response.durationMillis(), response.sizeBytes(), checks, extractions,
                response.error().map(error -> "Network error: " + error.type().name()).orElse("HTTP response received."), work.redactor());
    }
    private void saveHistorySimple(RunEntry.Outcome outcome, String reason) { saveHistory(currentHistory, outcome, 0, 0, 0, java.util.List.of(), java.util.List.of(), reason, SecretRedactor.empty()); }
    private void saveHistory(RequestHistory capture, RunEntry.Outcome outcome, int status, long time, long bytes, java.util.List<AssertionResult> checks, java.util.List<ExtractionResult> extractions, String reason, SecretRedactor redactor) {
        if (capture == null) return; if (currentHistory == capture) currentHistory = null; long version = submission;
        capture.finish(outcome, status, time, bytes, checks, extractions, reason, redactor).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (!closed && submission == version) historyNotice.setText(failure == null ? "History saved (response data and compared values omitted)." : "History save failed. Open History and use Retry pending saves; the response remains available.");
        }));
    }
    private static ExecutionResult localError(NetworkError.Type type, String message) {
        return new ExecutionResult(0, java.util.Map.of(), new byte[0], 0, 0,
                java.util.Optional.of(new NetworkError(type, message)), java.time.Instant.now(), java.util.Optional.empty());
    }

    @Override public void close() {
        if (closed) return; cancelForWindowClose(); closed = true;
        submission++;
        validation.shutdownNow();
        abandonExtractions(runningWork, "Request tab was closed."); runningWork = null; privateSession.clear();
        if (active != null) active.cancel();
        if (editor != null) editor.close();
        if (responsePanel != null) responsePanel.close();
    }
}

