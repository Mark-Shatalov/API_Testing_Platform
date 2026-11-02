package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.ui.MainView;
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
    private long submission;
    private ResponsePanel responsePanel;
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
        HBox targetBar = new HBox(10, preview, target); targetBar.setAlignment(Pos.CENTER_LEFT); targetBar.getStyleClass().add("target-preview");
        VBox requestEditor = new VBox(toolbar, targetBar, RequestEditor.error(draft.urlError, "request-url-error"),
                RequestEditor.error(draft.summary, "request-validation-summary"), editor);
        requestEditor.setMinHeight(260);
        requestEditor.getStyleClass().add("request-editor");

        responsePanel = new ResponsePanel(viewModel);
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
        var snapshot = draft.snapshot();
        if (snapshot.isEmpty()) { viewModel.showMessage(draft.summary.get()); return; }
        RequestConfiguration configuration = snapshot.orElseThrow();
        var localVariables = draft.variables.stream().map(RequestEditorViewModel.VariableRow::snapshot).toList();
        var authentication = draft.authentication();
        var assertions = draft.assertions.stream().map(RequestEditorViewModel.AssertionRow::snapshot).toList();
        CompletionStage<VariableResolver.Context> context;
        try { context = variableContext.get(); }
        catch (RuntimeException failure) { draft.variableError.set("Workspace variables are unavailable. Refresh and try again."); return; }
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
                    resolved = VariableResolver.resolve(configuration, context.toCompletableFuture().get(10, TimeUnit.SECONDS), localVariables, authentication);
                    checked = resolved.issues().isEmpty() ? RequestValidation.validate(resolved.request()) : resolved.issues();
                    if (send && checked.isEmpty()) checked = AssertionEngine.validate(assertions);
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
                        viewModel.finishOperation("Request not sent: validation failed."
                                + (draft.revision() != revision ? " The editor has newer unsent edits." : " See the marked fields."));
                        if (send) {
                            responsePanel.showValidationFailure("Request not sent — fix the validation errors in the editor.");
                            responsePanel.showTests(notRun(assertions, prepared == null ? SecretRedactor.empty() : prepared.redactor(), "Request was not sent because validation failed. See the marked editor fields."));
                        }
                        return;
                    }
                    target.setText("Target: " + prepared.preview());
                    if (send) execute(prepared.request(), revision, assertions, prepared.redactor());
                    else viewModel.finishOperation("Target preview ready. Secret variables are masked; no request was sent.");
                });
            });
        } catch (RejectedExecutionException unavailable) {
            viewModel.finishOperation("Request validation is busy. Try again.");
            responsePanel.showValidationFailure("Request not sent — validation is busy.");
        }
    }

    private void execute(RequestConfiguration configuration, long draftRevision, java.util.List<AssertionDefinition> assertions, SecretRedactor redactor) {
        responsePanel.showLoading();
        viewModel.showMessage("Sending request…");
        try { active = engine.execute(configuration); }
        catch (RuntimeException exception) {
            presentResponse(localError(NetworkError.Type.IO, "Unable to start the request."), assertions, redactor);
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
                presentResponse(result, assertions, redactor);
            }
            else presentResponse(localError(NetworkError.Type.IO, "The request could not be completed."), assertions, redactor);
        }));
    }
    private void presentResponse(ExecutionResult result, java.util.List<AssertionDefinition> assertions, SecretRedactor redactor) {
        responsePanel.showResponse(result);
        String completion = result.error().map(error -> error.type() == NetworkError.Type.CANCELLED ? "Request cancelled" : "Request failed").orElse("Request completed");
        if (assertions.isEmpty()) { viewModel.finishOperation(completion); return; }
        long ticket = submission; runningAssertions = assertions; runningRedactor = redactor;
        responsePanel.showTestsPending(); viewModel.showMessage("Checking response…");
        try {
            pendingAssertions = validation.submit(() -> {
                java.util.List<ResponseTestResult> checks;
                try { checks = AssertionEngine.evaluate(result, assertions, redactor).stream().map(ResponseTestResult::from).toList(); }
                catch (RuntimeException failure) { checks = notRun(assertions, redactor, "The checks could not be evaluated safely."); }
                var evaluated = checks;
                Platform.runLater(() -> {
                    if (closed || ticket != submission) return;
                    pendingAssertions = null; runningAssertions = java.util.List.of(); runningRedactor = SecretRedactor.empty();
                    responsePanel.showTests(evaluated); viewModel.finishOperation(completion + " — checks finished");
                });
            });
        } catch (RejectedExecutionException busy) {
            runningAssertions = java.util.List.of(); runningRedactor = SecretRedactor.empty();
            responsePanel.showTests(notRun(assertions, redactor, "Check worker is busy. Send again to retry.")); viewModel.finishOperation(completion);
        }
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
            runningAssertions = java.util.List.of(); runningRedactor = SecretRedactor.empty(); viewModel.finishOperation("Checks cancelled; response is still available.");
        }
        else if (pendingValidation != null) {
            submission++;
            pendingValidation.cancel(true); pendingValidation = null; validation.purge();
            viewModel.finishOperation("Request cancelled");
            responsePanel.showResponse(localError(NetworkError.Type.CANCELLED, "The request was cancelled."));
        }
    }

    private static ExecutionResult localError(NetworkError.Type type, String message) {
        return new ExecutionResult(0, java.util.Map.of(), new byte[0], 0, 0,
                java.util.Optional.of(new NetworkError(type, message)), java.time.Instant.now(), java.util.Optional.empty());
    }

    @Override public void close() {
        closed = true;
        submission++;
        validation.shutdownNow();
        if (active != null) active.cancel();
        if (editor != null) editor.close();
        if (responsePanel != null) responsePanel.close();
    }
}

