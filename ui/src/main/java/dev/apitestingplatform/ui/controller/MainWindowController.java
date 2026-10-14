package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.ui.MainView;
import dev.apitestingplatform.ui.components.EmptyStatePane;
import dev.apitestingplatform.ui.components.RequestToolbar;
import dev.apitestingplatform.ui.components.ResponsePanel;
import dev.apitestingplatform.ui.components.WorkspaceSidebar;
import dev.apitestingplatform.ui.viewmodel.MainWindowViewModel;
import dev.apitestingplatform.application.http.HttpExecutionEngine;
import dev.apitestingplatform.application.http.RequestExecution;
import dev.apitestingplatform.domain.http.RequestConfiguration;
import dev.apitestingplatform.domain.http.HttpMethod;
import dev.apitestingplatform.domain.http.HttpProtocol;
import dev.apitestingplatform.domain.http.RedirectPolicy;
import dev.apitestingplatform.domain.http.RequestBody;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
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

/** Handles presentation events without depending on application use cases. */
public final class MainWindowController implements AutoCloseable {
    private final MainWindowViewModel viewModel = new MainWindowViewModel();
    private final HttpExecutionEngine engine;
    private ResponsePanel responsePanel;
    private RequestExecution active;
    private boolean closed;

    public MainWindowController() { this(null); }
    public MainWindowController(HttpExecutionEngine engine) { this.engine = engine; }

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
        RequestToolbar toolbar = new RequestToolbar(
                viewModel,
                this::onSendRequested,
                this::onCancelRequested);

        TabPane editorTabs = new TabPane(
                editorTab("Params", "No query parameters", "Add query parameters to the request URL."),
                editorTab("Headers", "No request headers", "Add headers that should be sent with the request."),
                editorTab("Auth", "No authentication", "Choose and configure an authentication method."),
                editorTab("Body", "No request body", "Choose a body type and enter request content."),
                editorTab("Tests", "No response tests", "Add assertions for status, headers, and JSON content."));
        editorTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        editorTabs.getStyleClass().add("editor-tabs");
        VBox.setVgrow(editorTabs, Priority.ALWAYS);

        VBox requestEditor = new VBox(toolbar, editorTabs);
        requestEditor.setMinHeight(260);
        requestEditor.getStyleClass().add("request-editor");

        responsePanel = new ResponsePanel(viewModel);
        SplitPane requestAndResponse = new SplitPane(requestEditor, responsePanel);
        requestAndResponse.setOrientation(Orientation.VERTICAL);
        requestAndResponse.setDividerPositions(0.55);
        requestAndResponse.getStyleClass().add("workspace-split");

        Tab requestTab = new Tab("Untitled Request", requestAndResponse);
        requestTab.setClosable(false);

        TabPane requestTabs = new TabPane(requestTab);
        requestTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        requestTabs.getStyleClass().add("request-tabs");
        return requestTabs;
    }

    private Tab editorTab(String title, String emptyTitle, String description) {
        return new Tab(title, new EmptyStatePane(emptyTitle, description));
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
        if (engine == null) { viewModel.showMessage("HTTP engine is unavailable."); return; }
        if (viewModel.operationInProgressProperty().get() || closed) return;
        RequestConfiguration configuration = new RequestConfiguration(
                HttpMethod.valueOf(viewModel.requestMethodProperty().get().name()),
                viewModel.requestUrlProperty().get(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                RequestBody.none(), java.time.Duration.ofSeconds(30), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        viewModel.beginOperation();
        responsePanel.showLoading();
        active = engine.execute(configuration);
        RequestExecution execution = active;
        execution.result().whenComplete((result, failure) -> Platform.runLater(() -> {
            if (closed || active != execution) return;
            active = null;
            viewModel.finishOperation(failure == null ? "Request completed" : "Request failed");
            if (failure == null) responsePanel.showResponse(result);
        }));
    }

    private void onCancelRequested() {
        if (active != null) active.cancel();
    }

    @Override public void close() {
        closed = true;
        if (active != null) active.cancel();
        if (responsePanel != null) responsePanel.close();
    }
}

