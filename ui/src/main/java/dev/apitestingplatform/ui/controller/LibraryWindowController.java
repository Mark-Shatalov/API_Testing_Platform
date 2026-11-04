package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.application.http.HttpExecutionEngine;
import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.ui.MainView;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Workspace use-case coordinator. SQL, file access and encryption remain behind the application port. */
public final class LibraryWindowController implements AutoCloseable {
    private final LibraryRepository repository;
    private final HttpExecutionEngine engine;
    private final DraftAutosave autosave;
    private final HistoryService history;
    private final dev.apitestingplatform.importexport.TransferFiles transferFiles;
    private HistoryWindow historyWindow;
    private final dev.apitestingplatform.application.run.CollectionRunner collectionRunner;
    private CollectionRunWindow runWindow;
    private final EnvironmentControls environments;
    private final ComboBox<LibraryItem> workspaces = new ComboBox<>();
    private final TreeView<LibraryItem> tree = new TreeView<>(new TreeItem<>());
    private final TextField search = new TextField();
    private Set<UUID> filteredItems;
    private final TabPane tabs = new TabPane();
    private final Label status = new Label("Opening local storage…");
    private final Label recoveryStatus = new Label("Recovery: no pending edits");
    private final Map<UUID, dev.apitestingplatform.application.http.RunVariables> collectionSessions = new HashMap<>();
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final boolean offerRecovery;
    private List<LibraryItem> items = List.of();
    private Map<UUID, LibraryItem> itemIndex = Map.of();
    private final Map<UUID, TreeItem<LibraryItem>> treeIndex = new HashMap<>();
    private Map<UUID, List<LibraryItem>> childrenIndex = Map.of();
    private BorderPane root;
    private boolean updating;
    private boolean closed;
    private boolean closing;
    private long refreshVersion;
    private boolean initializedTab;
    private final Set<UUID> recovering = new HashSet<>();
    private Runnable appearanceAction = () -> { };
    private dev.apitestingplatform.application.operations.AppOperations operations;
    private OperationsWindow operationsWindow;
    private Runnable readyAction = () -> { };
    private boolean readyReported;
    private dev.apitestingplatform.application.operations.EventLog log = dev.apitestingplatform.application.operations.EventLog.none();
    public void setEventLog(dev.apitestingplatform.application.operations.EventLog value) { log = Objects.requireNonNull(value); if (collectionRunner != null) collectionRunner.setEventLog(value); }

    public void setOperations(dev.apitestingplatform.application.operations.AppOperations value) { operations = value; }
    public void setOnReady(Runnable action) { readyAction = Objects.requireNonNull(action); }
    public void showSettings() {
        if (operations == null || root == null) return;
        if (operationsWindow != null && operationsWindow.isShowing()) { operationsWindow.show(); return; }
        operationsWindow = new OperationsWindow(root.getScene().getWindow(), operations, repository, this::exportWorkspace);
        operationsWindow.show();
    }
    public void reportUnexpectedFailure() {
        if (!closed) { status.setText("An unexpected operation failed. Editor changes were kept. Retry the action or open Settings to create diagnostics."); }
    }

    public void setAppearanceAction(Runnable action) { appearanceAction = Objects.requireNonNull(action); }

    private static final class Entry {
        final UUID tabId;
        UUID workspaceId;
        final MainWindowController editor;
        final Tab tab;
        final SimpleStringProperty title;
        SavedRequest saved;
        boolean saving;
        boolean removing;
        Entry(UUID tabId, UUID workspace, MainWindowController editor, Tab tab, String title, SavedRequest saved) {
            this.tabId = tabId; workspaceId = workspace; this.editor = editor; this.tab = tab;
            this.title = new SimpleStringProperty(title); this.saved = saved;
        }
    }
    public LibraryWindowController(HttpExecutionEngine engine, LibraryRepository repository) { this(engine, repository, true); }
    public LibraryWindowController(HttpExecutionEngine engine, LibraryRepository repository, boolean offerRecovery) {
        this.engine = engine; this.repository = Objects.requireNonNull(repository); this.offerRecovery = offerRecovery;
        transferFiles = new dev.apitestingplatform.importexport.TransferFiles(repository.databasePath());
        history = new HistoryService(repository);
        collectionRunner = engine == null ? null : new dev.apitestingplatform.application.run.CollectionRunner(engine, history);
        environments = new EnvironmentControls(repository);
        autosave = new DraftAutosave(repository, Duration.ofMillis(750), failure -> Platform.runLater(() -> {
            if (!closed) { recoveryStatus.setText("Recovery save failed — use Retry recovery save"); error(failure); }
        }), saved -> Platform.runLater(() -> { if (!closed) recoveryStatus.setText("Recovery snapshot saved"); }));
    }

    public Parent createView() {
        workspaces.setId("library-workspaces"); workspaces.setMaxWidth(Double.MAX_VALUE);
        workspaces.setPromptText("Create a workspace");
        workspaces.valueProperty().addListener((value, before, after) -> {
            if (!updating) {
                populateTree(); environments.setWorkspace(after == null ? null : after.id());
                if (initializedTab && after != null) activateWorkspaceTab(after.id());
            }
        });
        tree.setId("library-tree"); tree.setFixedCellSize(28); tree.setShowRoot(false); tree.getRoot().setExpanded(true);
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override protected void updateItem(LibraryItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item.kind()) {
                    case COLLECTION -> "Collection: "; case FOLDER -> "Folder: "; case REQUEST -> ""; case WORKSPACE -> "";
                } + item.name());
            }
        });
        tree.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelected(); });
        tree.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelected(); });
        workspaces.getStyleClass().add("workspace-selector");
        workspaces.setAccessibleText("Current workspace");
        Label collectionsTitle = new Label("Collections"); collectionsTitle.getStyleClass().add("navigation-title");
        Button newCollection = button("New collection", "library-new-collection", () -> create(LibraryItem.Kind.COLLECTION));
        MenuButton add = menu("Add", "library-add-menu",
                action("Saved request…", "library-new-request", () -> create(LibraryItem.Kind.REQUEST)),
                action("Folder…", "library-new-folder", () -> create(LibraryItem.Kind.FOLDER)));
        HBox creation = new HBox(6, newCollection, add);
        search.setId("library-search"); search.setPromptText("Find saved requests…");
        search.setAccessibleText("Find collections, folders and requests by name");
        search.textProperty().addListener((observable, before, after) -> populateTree());
        search.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ESCAPE) { search.clear(); event.consume(); } });
        Button open = button("Open", "library-open", this::openSelected);
        open.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> tree.getSelectionModel().getSelectedItem() == null
                        || tree.getSelectionModel().getSelectedItem().getValue().kind() != LibraryItem.Kind.REQUEST,
                tree.getSelectionModel().selectedItemProperty()));
        MenuButton organize = menu("Organize", "library-organize-menu",
                action("Rename…", "library-rename", this::rename), action("Delete…", "library-delete", this::delete),
                new SeparatorMenuItem(), action("Move up", "library-up", () -> reorder(-1)),
                action("Move down", "library-down", () -> reorder(1)));
        organize.disableProperty().bind(tree.getSelectionModel().selectedItemProperty().isNull());
        FlowPane actions = new FlowPane(6, 6, open,
                button("Run collection…", "library-run-collection", this::runSelectedCollection), organize);
        Label hint = new Label("Double-click a saved request to open it. Collections group requests you can run together."); hint.setWrapText(true);
        hint.getStyleClass().add("help-text");
        hint.setMinHeight(Region.USE_PREF_SIZE);
        Label empty = new Label(); empty.setWrapText(true); empty.getStyleClass().add("help-text");
        empty.textProperty().bind(javafx.beans.binding.Bindings.when(search.textProperty().isEmpty())
                .then("Keep requests for later\nCreate a collection, then save your request into it.")
                .otherwise("No matching requests. Try a different name or clear the search."));
        empty.setMinHeight(Region.USE_PREF_SIZE);
        empty.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(tree.getRoot().getChildren())); empty.managedProperty().bind(empty.visibleProperty());
        Label workspaceTitle = new Label("Workspace"); workspaceTitle.getStyleClass().add("navigation-title");
        VBox sidebar = new VBox(10, workspaceTitle, workspaces, environments, collectionsTitle, creation, search, empty, tree, actions, hint);
        sidebar.getStyleClass().add("library-sidebar"); sidebar.setMinWidth(280); sidebar.setPrefWidth(310);
        VBox.setVgrow(tree, Priority.ALWAYS);
        tabs.setId("library-request-tabs"); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getStyleClass().add("request-tabs");
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) -> {
            if (selected != null && selected.getUserData() instanceof Entry entry) {
                LibraryItem workspace = byId(entry.workspaceId);
                if (workspace != null && !workspace.equals(workspaces.getValue())) workspaces.setValue(workspace);
            }
        });
        SplitPane split = new SplitPane(sidebar, tabs); split.setDividerPositions(0.24);
        Label brand = new Label("API Testing Platform"); brand.getStyleClass().add("workbench-brand");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button newRequest = button("New request", "library-new-tab", this::newTab);
        newRequest.setTooltip(new Tooltip("Open a new request tab (Ctrl+N). Save it to a collection when ready."));
        Button save = button("Save", "library-save", this::saveActive);
        save.setTooltip(new Tooltip("Save the current request to a collection (Ctrl+S)"));
        MenuButton workspaceMenu = menu("Workspace", "library-workspace-actions",
                action("New workspace…", "library-new-workspace", () -> create(LibraryItem.Kind.WORKSPACE)),
                action("Manage current workspace…", "library-workspace-menu", this::workspaceActions), new SeparatorMenuItem(),
                action("Import workspace…", "library-import-workspace", this::importWorkspace),
                action("Export workspace…", "library-export-workspace", this::exportWorkspace));
        MenuButton tools = menu("Tools", "library-tools-menu",
                action("Save request as…", "library-save-as", () -> { Entry entry = active(); if (entry != null) save(entry, true, () -> { }); }),
                action("Refresh library", "library-refresh", this::refresh), new SeparatorMenuItem(),
                action("Recover unsaved tabs…", "library-recover", this::offerRecovery),
                action("Retry draft recovery save", "library-retry-drafts", this::retryDrafts), new SeparatorMenuItem(),
                action("Clear temporary variables", "library-clear-temporary", this::clearTemporaryVariables));
        MenuButton settings = menu("Settings", "library-preferences-menu",
                action("Appearance…", "library-appearance", () -> appearanceAction.run()),
                action("Network, backups and diagnostics…", "library-settings", this::showSettings));
        HBox toolbar = new HBox(8, brand, spacer, newRequest, save,
                button("History…", "library-history", this::showHistory), workspaceMenu, tools, settings);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        recoveryStatus.setId("library-autosave-status"); recoveryStatus.getStyleClass().add("draft-status");
        toolbar.getStyleClass().add("library-toolbar");
        status.setId("library-status"); status.setWrapText(true); status.getStyleClass().add("status-bar");
        Region statusSpacer = new Region(); HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, status, statusSpacer, recoveryStatus); statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("library-status-bar");
        root = new BorderPane(split, toolbar, null, statusBar, null); root.getStyleClass().add("app-root");
        root.getStylesheets().add(Objects.requireNonNull(MainView.class.getResource("main.css")).toExternalForm());
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.S) { saveActive(); event.consume(); }
            else if (event.isShortcutDown() && event.getCode() == KeyCode.N) { newTab(); event.consume(); }
        });
        perform(repository.list(), loaded -> {
            if (loaded.isEmpty()) perform(repository.create(LibraryItem.Kind.WORKSPACE, null, "Default Workspace"), ignored -> {
                refresh(); if (offerRecovery) offerRecovery();
            });
            else { applyItems(loaded); if (offerRecovery) offerRecovery(); }
        });
        return root;
    }
    private static Button button(String text, String id, Runnable action) {
        Button button = new Button(text); button.setId(id); button.setOnAction(event -> action.run()); return button;
    }
    private static MenuItem action(String text, String id, Runnable action) {
        MenuItem item = new MenuItem(text); item.setId(id); item.setOnAction(event -> action.run()); return item;
    }
    private static MenuButton menu(String text, String id, MenuItem... actions) {
        MenuButton menu = new MenuButton(text); menu.setId(id); menu.getItems().setAll(actions); return menu;
    }
    private <T> void perform(CompletionStage<T> operation, Consumer<T> success) {
        operation.whenComplete((value, failure) -> Platform.runLater(() -> {
            if (closed) return;
            if (failure != null) error(failure); else success.accept(value);
        }));
    }
    private void error(Throwable failure) {
        if (closed) return;
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        status.setText(failure instanceof StorageException ? failure.getMessage()
                : "Storage action failed. Unsaved editor content has not been discarded.");
        status.getStyleClass().add("validation-error");
    }
    public void refresh() {
        long version = ++refreshVersion;
        perform(repository.list(), loaded -> { if (version == refreshVersion) applyItems(loaded); });
    }
    private void applyItems(List<LibraryItem> loaded) {
        UUID workspace = workspaces.getValue() == null ? null : workspaces.getValue().id();
        items = loaded;
        Map<UUID, LibraryItem> indexed = new HashMap<>(); Map<UUID, List<LibraryItem>> children = new HashMap<>();
        for (var item : items) { indexed.put(item.id(), item); children.computeIfAbsent(item.parentId(), ignored -> new ArrayList<>()).add(item); }
        itemIndex = indexed; childrenIndex = children; updating = true;
        workspaces.getItems().setAll(items.stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).toList());
        workspaces.setValue(workspaces.getItems().stream().filter(item -> item.id().equals(workspace)).findFirst()
                .orElse(workspaces.getItems().isEmpty() ? null : workspaces.getItems().getFirst()));
        updating = false; populateTree();
        environments.setWorkspace(workspaces.getValue() == null ? null : workspaces.getValue().id());
        status.getStyleClass().removeAll("validation-error"); status.setText("Ready. Enter a URL to send a request; save it to keep it in a collection.");
        if (!initializedTab && workspaces.getValue() != null) { initializedTab = true; newTab(); }
        if (!readyReported) { readyReported = true; Platform.runLater(readyAction); }
    }
    private void populateTree() {
        var row = tree.getSelectionModel().getSelectedItem();
        populateTree(row == null || row.getValue() == null ? null : row.getValue().id());
    }
    private void populateTree(UUID selected) {
        tree.getSelectionModel().clearSelection();
        tree.getRoot().getChildren().clear(); treeIndex.clear();
        if (workspaces.getValue() == null) return;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        filteredItems = null;
        if (!query.isEmpty()) {
            filteredItems = new HashSet<>();
            Set<UUID> matching = new HashSet<>();
            for (var item : items) if (item.kind() != LibraryItem.Kind.WORKSPACE
                    && item.name().toLowerCase(Locale.ROOT).contains(query)) matching.add(item.id());
            ArrayDeque<UUID> pending = new ArrayDeque<>(matching);
            while (!pending.isEmpty()) {
                UUID id = pending.removeFirst();
                if (filteredItems.add(id)) for (var child : childrenIndex.getOrDefault(id, List.of())) pending.add(child.id());
            }
            for (UUID id : matching) {
                LibraryItem ancestor = byId(id);
                while (ancestor != null) { filteredItems.add(ancestor.id()); ancestor = byId(ancestor.parentId()); }
            }
        }
        addChildren(tree.getRoot(), workspaces.getValue().id(), selected);
        if (selected != null && treeIndex.containsKey(selected)) tree.getSelectionModel().select(treeIndex.get(selected));
    }
    private void refreshSelecting(UUID id, Runnable done) {
        long version = ++refreshVersion;
        perform(repository.list(), loaded -> {
            if (version != refreshVersion) { done.run(); return; }
            applyItems(loaded);
            LibraryItem created = byId(id);
            if (created != null) {
                search.clear();
                UUID workspace = workspaceFor(created);
                updating = true;
                try {
                    workspaces.setValue(workspaces.getItems().stream().filter(item -> item.id().equals(workspace)).findFirst().orElse(null));
                } finally { updating = false; }
                populateTree(created.kind() == LibraryItem.Kind.WORKSPACE ? null : created.id());
                environments.setWorkspace(workspace);
                if (created.kind() == LibraryItem.Kind.WORKSPACE && initializedTab) activateWorkspaceTab(workspace);
            }
            done.run();
        });
    }
    private void addChildren(TreeItem<LibraryItem> parent, UUID id, UUID selected) {
        List<TreeItem<LibraryItem>> children = new ArrayList<>();
        for (var item : childrenIndex.getOrDefault(id, List.of())) {
            if (filteredItems != null && !filteredItems.contains(item.id())) continue;
            TreeItem<LibraryItem> child = new TreeItem<>(item); child.setExpanded(true); children.add(child); treeIndex.put(item.id(), child);
            if (item.kind() != LibraryItem.Kind.REQUEST) addChildren(child, item.id(), selected);
        }
        parent.getChildren().setAll(children);
    }
    private LibraryItem byId(UUID id) { return id == null ? null : itemIndex.get(id); }
    private LibraryItem selected() {
        var row = tree.getSelectionModel().getSelectedItem(); return row == null ? workspaces.getValue() : row.getValue();
    }
    private UUID selectedParent(LibraryItem.Kind kind) {
        if (kind == LibraryItem.Kind.WORKSPACE) return null;
        LibraryItem item = kind == LibraryItem.Kind.COLLECTION ? workspaces.getValue() : selected();
        if (item != null && item.kind() == LibraryItem.Kind.REQUEST) item = byId(item.parentId());
        if (kind == LibraryItem.Kind.FOLDER && item != null && item.kind() == LibraryItem.Kind.FOLDER) item = byId(item.parentId());
        if (item == null || (kind != LibraryItem.Kind.COLLECTION && item.kind() == LibraryItem.Kind.WORKSPACE)) {
            return chooseParent(kind);
        }
        return item.id();
    }
    private UUID chooseParent(LibraryItem.Kind kind) {
        LibraryItem workspace = workspaces.getValue();
        if (workspace == null) {
            Alert prompt = new Alert(Alert.AlertType.INFORMATION, "Create and select a workspace before adding collections, folders or requests.", ButtonType.OK);
            prompt.setHeaderText("A workspace is required"); prompt.initOwner(root.getScene().getWindow()); prompt.showAndWait();
            return null;
        }
        List<Destination> parents = items.stream()
                .filter(item -> item.kind() == LibraryItem.Kind.COLLECTION || kind == LibraryItem.Kind.REQUEST && item.kind() == LibraryItem.Kind.FOLDER)
                .filter(item -> workspaceFor(item).equals(workspace.id()))
                .map(item -> new Destination(item, path(item))).toList();
        if (parents.isEmpty()) {
            ButtonType create = new ButtonType("Create collection…");
            Alert prompt = new Alert(Alert.AlertType.INFORMATION,
                    "Folders and saved requests belong to a collection. Create a collection in this workspace first.", create, ButtonType.CANCEL);
            prompt.setHeaderText("No collections in this workspace"); prompt.initOwner(root.getScene().getWindow());
            if (prompt.showAndWait().orElse(ButtonType.CANCEL) == create) create(LibraryItem.Kind.COLLECTION);
            return null;
        }
        ChoiceDialog<Destination> prompt = new ChoiceDialog<>(parents.getFirst(), parents);
        prompt.setTitle("New " + kind.name().toLowerCase(Locale.ROOT));
        prompt.setHeaderText(kind == LibraryItem.Kind.FOLDER ? "Choose a collection for the folder" : "Choose a collection or folder for the request");
        prompt.setContentText("Parent:"); prompt.initOwner(root.getScene().getWindow());
        return prompt.showAndWait().map(destination -> destination.item().id()).orElse(null);
    }
    private Optional<String> name(String title, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial); dialog.setTitle(title); dialog.setHeaderText(title);
        dialog.setContentText("Name (1–200 characters):"); if (root.getScene() != null) dialog.initOwner(root.getScene().getWindow());
        return dialog.showAndWait().map(String::strip).filter(value -> !value.isEmpty() && value.length() <= 200 && value.chars().noneMatch(c -> c < 32));
    }
    private void create(LibraryItem.Kind kind) {
        UUID parent = selectedParent(kind); if (kind != LibraryItem.Kind.WORKSPACE && parent == null) return;
        name("New " + kind.name().toLowerCase(Locale.ROOT), "Untitled " + kind.name().toLowerCase(Locale.ROOT)).ifPresent(name ->
                perform(repository.create(kind, parent, name), created -> {
                    refreshSelecting(created.id(), () -> { if (created.kind() == LibraryItem.Kind.REQUEST) openRequest(created.id()); });
                }));
    }
    private void workspaceActions() {
        var workspace = workspaces.getValue(); if (workspace == null) return;
        ChoiceDialog<String> dialog = new ChoiceDialog<>("Rename", "Rename", "Delete", "Move up", "Move down");
        dialog.setTitle("Workspace actions"); dialog.setHeaderText(workspace.name()); dialog.initOwner(root.getScene().getWindow());
        dialog.showAndWait().ifPresent(action -> {
            tree.getSelectionModel().clearSelection();
            switch (action) { case "Rename" -> rename(); case "Delete" -> delete(); case "Move up" -> reorder(-1); case "Move down" -> reorder(1); default -> { } }
        });
    }
    private void rename() {
        LibraryItem selected = selected(); if (selected == null) return;
        name("Rename", selected.name()).ifPresent(name -> perform(repository.rename(selected.id(), name), renamed -> {
            for (Entry entry : entries.values()) if (entry.saved != null && entry.saved.item().id().equals(renamed.id())) {
                entry.saved = new SavedRequest(renamed, entry.saved.draft()); entry.title.set(renamed.name());
            }
            refresh();
        }));
    }
    private boolean descendant(UUID id, UUID ancestor) {
        LibraryItem node = byId(id);
        while (node != null) { if (node.id().equals(ancestor)) return true; node = byId(node.parentId()); }
        return false;
    }
    private void delete() {
        LibraryItem selected = selected(); if (selected == null) return;
        var affected = entries.values().stream().filter(entry -> entry.saved != null && descendant(entry.saved.item().id(), selected.id())
                || selected.kind() == LibraryItem.Kind.WORKSPACE && entry.workspaceId.equals(selected.id())).toList();
        if (affected.stream().anyMatch(entry -> entry.saving)) { status.setText("Wait for saving to finish before deleting."); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete '" + selected.name()
                + "' and all its children? Unsaved edits in affected tabs will also be discarded.", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Delete local data?"); confirm.initOwner(root.getScene().getWindow());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        perform(repository.delete(selected.id()), ignored -> { affected.forEach(this::remove); refresh(); });
    }
    private void reorder(int delta) {
        LibraryItem selected = selected(); if (selected == null) return;
        int count = (int) items.stream().filter(item -> Objects.equals(item.parentId(), selected.parentId())).count();
        int target = selected.position() + delta; if (target < 0 || target >= count) return;
        perform(repository.reorder(selected.id(), target), ignored -> refresh());
    }
    public void showHistory() {
        if (historyWindow == null || !historyWindow.isShowing()) historyWindow = new HistoryWindow(root.getScene().getWindow(), repository, history, transferFiles);
        historyWindow.show();
    }
    public CompletionStage<Void> exportWorkspace(UUID workspace, java.nio.file.Path path, boolean includeSecrets) {
        UUID operation = transferStarted();
        return repository.exportWorkspace(workspace).thenCompose(snapshot -> transferFiles.workspace(path, snapshot, includeSecrets)).whenComplete((ignored, failure) -> transferFinished(operation, failure));
    }
    public CompletionStage<UUID> importWorkspace(java.nio.file.Path path) {
        UUID operation = transferStarted();
        return transferFiles.read(path).thenCompose(document -> repository.importWorkspace(document.workspace())).whenComplete((ignored, failure) -> transferFinished(operation, failure));
    }
    private UUID transferStarted() { UUID id = UUID.randomUUID(); log.record(dev.apitestingplatform.application.operations.EventLog.Level.INFO, dev.apitestingplatform.application.operations.EventLog.Component.TRANSFER, dev.apitestingplatform.application.operations.EventLog.Code.TRANSFER_STARTED, id); return id; }
    private void transferFinished(UUID id, Throwable failure) { log.record(failure == null ? dev.apitestingplatform.application.operations.EventLog.Level.INFO : dev.apitestingplatform.application.operations.EventLog.Level.WARN, dev.apitestingplatform.application.operations.EventLog.Component.TRANSFER,
            failure == null ? dev.apitestingplatform.application.operations.EventLog.Code.TRANSFER_FINISHED : dev.apitestingplatform.application.operations.EventLog.Code.TRANSFER_FAILED, id); }
    private void exportWorkspace() {
        LibraryItem workspace = workspaces.getValue(); if (workspace == null) { status.setText("Select a workspace to export."); return; }
        Dialog<Boolean> options = new Dialog<>(); options.initOwner(root.getScene().getWindow()); options.setTitle("Export workspace"); options.setHeaderText("Export the saved workspace as a portable JSON file");
        CheckBox secrets = new CheckBox("Include secret values and request payloads in plain text"); secrets.setId("transfer-include-secrets");
        Label hint = new Label("Export saved collections, requests, environments and rules. Secret values and request payloads are omitted by default; missing inputs appear as {{REDACTED}}. Names, endpoints and non-secret variables remain. Save edits first. Enable the option below only if this file must include credentials, body drafts and upload paths in plain text."); hint.setWrapText(true); hint.setPrefWidth(640); hint.setMinHeight(100);
        VBox body = new VBox(12, hint, secrets); body.setPrefWidth(640); options.getDialogPane().setContent(body); options.getDialogPane().setPrefSize(720, 300); options.getDialogPane().setId("workspace-export-options");
        ButtonType write = new ButtonType("Choose file…", ButtonBar.ButtonData.OK_DONE); options.getDialogPane().getButtonTypes().addAll(write, ButtonType.CANCEL); Button chooseFile = (Button) options.getDialogPane().lookupButton(write); chooseFile.setPrefWidth(130); chooseFile.setMinWidth(130); ButtonBar.setButtonUniformSize(chooseFile, false);
        options.setResultConverter(button -> button == write ? secrets.isSelected() : null); var choice = options.showAndWait(); if (choice.isEmpty()) return;
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser(); chooser.setTitle("Export saved workspace"); chooser.setInitialFileName("workspace.json"); chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("API Testing Platform workspace", "*.json"));
        var destination = chooser.showSaveDialog(root.getScene().getWindow()); if (destination == null) return; status.setText("Exporting saved workspace…");
        exportWorkspace(workspace.id(), destination.toPath(), choice.get()).whenComplete((ignored, failure) -> Platform.runLater(() -> { if (!closed) status.setText(failure == null ? choice.get() ? "Workspace exported with secret values in plain text." : "Workspace exported with sensitive inputs omitted." : "Workspace export failed or exceeded limits. The previous destination was not replaced."); }));
    }
    private void importWorkspace() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser(); chooser.setTitle("Import as a new workspace copy"); chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("API Testing Platform workspace version 1", "*.json"));
        var source = chooser.showOpenDialog(root.getScene().getWindow()); if (source == null) return; status.setText("Validating and importing a new workspace copy…");
        importWorkspace(source.toPath()).whenComplete((workspace, failure) -> Platform.runLater(() -> {
            if (closed) return;
            if (failure != null) {
                Throwable reason = failure; while (reason instanceof CompletionException && reason.getCause() != null) reason = reason.getCause();
                status.setText(reason instanceof java.io.IOException ? reason.getMessage() : "Import failed. Existing data was unchanged; no partial workspace was added."); return;
            }
            refreshSelecting(workspace, () -> status.setText("Imported a new workspace copy. Existing data was kept. Review {{REDACTED}} inputs before sending; no requests were executed."));
        }));
    }
    private void runSelectedCollection() {
        if (collectionRunner == null) { status.setText("HTTP engine is unavailable."); return; }
        LibraryItem item = selected();
        if (item == null && active() != null && active().saved != null) item = active().saved.item();
        while (item != null && item.kind() != LibraryItem.Kind.COLLECTION && item.kind() != LibraryItem.Kind.WORKSPACE) item = byId(item.parentId());
        if (item == null || item.kind() != LibraryItem.Kind.COLLECTION) {
            var choices = items.stream().filter(value -> value.kind() == LibraryItem.Kind.COLLECTION && workspaces.getValue() != null && value.parentId().equals(workspaces.getValue().id())).toList();
            if (choices.isEmpty()) { status.setText("Create a collection and save requests before running it."); return; }
            if (choices.size() == 1) item = choices.getFirst();
            else {
                ChoiceDialog<LibraryItem> dialog = new ChoiceDialog<>(choices.getFirst(), choices); dialog.setTitle("Run collection"); dialog.setHeaderText("Choose the collection to run"); dialog.initOwner(root.getScene().getWindow());
                item = dialog.showAndWait().orElse(null);
            }
        }
        if (item != null) showCollectionRun(item.id());
    }
    public void showCollectionRun(UUID collectionId) {
        if (collectionRunner == null) { status.setText("HTTP engine is unavailable."); return; }
        if (runWindow != null && runWindow.isShowing()) { runWindow.show(); return; }
        LibraryItem collection = byId(collectionId);
        if (collection == null || collection.kind() != LibraryItem.Kind.COLLECTION) { status.setText("Refresh and select a saved collection."); return; }
        runWindow = new CollectionRunWindow(root.getScene().getWindow(), repository, collectionRunner, collectionId, collection.name()); runWindow.show();
    }
    private void openSelected() { LibraryItem item = selected(); if (item != null && item.kind() == LibraryItem.Kind.REQUEST) openRequest(item.id()); }
    public void openRequest(UUID id) {
        var existing = entries.values().stream().filter(entry -> entry.saved != null && entry.saved.item().id().equals(id)).findFirst();
        if (existing.isPresent()) { tabs.getSelectionModel().select(existing.get().tab); return; }
        perform(repository.load(id), saved -> {
            var duplicate = entries.values().stream().filter(entry -> entry.saved != null && entry.saved.item().id().equals(id)).findFirst();
            if (duplicate.isPresent()) tabs.getSelectionModel().select(duplicate.get().tab);
            else open(UUID.randomUUID(), workspaceFor(saved.item()), saved.item().name(), saved.draft(), saved, false);
        });
    }
    private UUID workspaceFor(LibraryItem item) {
        while (item != null && item.kind() != LibraryItem.Kind.WORKSPACE) item = byId(item.parentId());
        return item != null ? item.id() : workspaces.getValue() != null ? workspaces.getValue().id() : new UUID(0, 0);
    }
    public void newTab() {
        if (workspaces.getValue() == null) { status.setText("Create a workspace first."); return; }
        open(UUID.randomUUID(), workspaces.getValue().id(), "Untitled Request", RequestDraft.empty(), null, false);
    }
    private void activateWorkspaceTab(UUID workspace) {
        Entry current = active(); if (current != null && current.workspaceId.equals(workspace)) return;
        var existing = entries.values().stream().filter(entry -> entry.workspaceId.equals(workspace)).findFirst();
        if (existing.isPresent()) tabs.getSelectionModel().select(existing.get().tab); else newTab();
    }
    private void open(UUID id, UUID workspace, String title, RequestDraft draft, SavedRequest saved, boolean dirty) {
        if (entries.containsKey(id)) { tabs.getSelectionModel().select(entries.get(id).tab); return; }
        if (entries.size() >= 100) { status.setText("Close a tab before opening more (maximum 100)."); return; }
        MainWindowController controller = new MainWindowController(engine); controller.setManagedDatabase(repository.databasePath()); controller.requestEditorModel().load(draft);
        Tab tab = new Tab(); tab.setContent(controller.createRequestContent());
        Entry entry = new Entry(id, workspace, controller, tab, title, saved); tab.setUserData(entry);
        controller.setHistory(history, () -> {
            LibraryItem ancestor = entry.saved == null ? null : byId(entry.saved.item().parentId());
            while (ancestor != null && ancestor.kind() == LibraryItem.Kind.FOLDER) ancestor = byId(ancestor.parentId());
            return new HistoryService.Context(entry.workspaceId, ancestor != null && ancestor.kind() == LibraryItem.Kind.COLLECTION ? ancestor.id() : null,
                    entry.saved == null ? null : entry.saved.item().id(), entry.title.get());
        });
        controller.setRunVariables(() -> sessionFor(entry));
        controller.setVariableContext(() -> repository.loadVariables(entry.workspaceId).thenApply(values ->
                new dev.apitestingplatform.application.http.VariableResolver.Context(values.variables(), values.selectedVariables())));
        tab.textProperty().bind(entry.title.concat(javafx.beans.binding.Bindings.when(controller.requestEditorModel().dirtyProperty()).then(" *").otherwise("")));
        controller.requestEditorModel().changesProperty().addListener((observable, before, after) -> {
            recoveryStatus.setText("Recovery pending…");
            autosave.changed(new RecoveryDraft(entry.tabId, entry.saved == null ? null : entry.saved.item().id(), entry.workspaceId,
                    entry.title.get(), controller.requestEditorModel().capture(), Instant.now()));
        });
        tab.setOnCloseRequest(event -> { event.consume(); closeTab(entry); });
        entries.put(id, entry); tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab);
        if (dirty) controller.requestEditorModel().markDirty();
    }
    private dev.apitestingplatform.application.http.RunVariables sessionFor(Entry entry) {
        LibraryItem item = entry.saved == null ? null : byId(entry.saved.item().parentId());
        while (item != null && item.kind() == LibraryItem.Kind.FOLDER) item = byId(item.parentId());
        UUID scope = item != null && item.kind() == LibraryItem.Kind.COLLECTION ? item.id() : entry.tabId;
        var existing = collectionSessions.get(scope); if (existing != null) return existing;
        if (collectionSessions.size() >= 100) throw new IllegalStateException("Clear temporary sessions before opening more.");
        var created = new dev.apitestingplatform.application.http.RunVariables(); collectionSessions.put(scope, created); return created;
    }
    private void clearTemporaryVariables() {
        collectionSessions.values().forEach(dev.apitestingplatform.application.http.RunVariables::clear); collectionSessions.clear();
        entries.values().forEach(entry -> entry.editor.clearPrivateSession());
        status.setText("All temporary variables cleared. Saved environments and request definitions are unchanged.");
    }
    private Entry active() { return tabs.getSelectionModel().getSelectedItem() == null ? null : (Entry) tabs.getSelectionModel().getSelectedItem().getUserData(); }
    public MainWindowController activeEditor() { return active() == null ? null : active().editor; }
    public void saveActive() { Entry entry = active(); if (entry != null) save(entry, false, () -> { }); }
    private record Destination(LibraryItem item, String label) { @Override public String toString() { return label; } }
    private void save(Entry entry, boolean saveAs, Runnable done) {
        if (entry.saving) return;
        long revision = entry.editor.requestEditorModel().revision();
        RequestDraft snapshot = entry.editor.requestEditorModel().capture();
        CompletionStage<SavedRequest> operation;
        if (entry.saved == null || saveAs) {
            List<Destination> destinations = items.stream().filter(item -> item.kind() == LibraryItem.Kind.COLLECTION || item.kind() == LibraryItem.Kind.FOLDER)
                    .map(item -> new Destination(item, path(item))).toList();
            if (destinations.isEmpty()) { status.setText("Create a collection before saving a request."); return; }
            ChoiceDialog<Destination> dialog = new ChoiceDialog<>(destinations.getFirst(), destinations);
            dialog.setTitle("Save request"); dialog.setHeaderText("Choose a collection or folder"); dialog.initOwner(root.getScene().getWindow());
            var destination = dialog.showAndWait(); if (destination.isEmpty()) return;
            var title = name("Request name", entry.title.get()); if (title.isEmpty()) return;
            operation = repository.createRequest(destination.get().item().id(), title.get(), snapshot);
        } else operation = repository.save(entry.saved.item().id(), entry.saved.item().version(), snapshot);
        entry.saving = true; status.setText("Saving request…");
        operation.whenComplete((saved, failure) -> Platform.runLater(() -> {
            entry.saving = false; if (closed) return;
            if (failure != null) { error(failure); return; }
            entry.saved = saved; entry.workspaceId = workspaceFor(saved.item()); entry.title.set(saved.item().name()); entry.editor.requestEditorModel().markSaved(revision);
            if (active() == entry && byId(entry.workspaceId) != null) workspaces.setValue(byId(entry.workspaceId));
            if (!entry.editor.hasUnsavedEdits()) perform(autosave.clear(entry.tabId), ignored -> { });
            else autosave.changed(new RecoveryDraft(entry.tabId, saved.item().id(), entry.workspaceId, entry.title.get(), entry.editor.requestEditorModel().capture(), Instant.now()));
            refresh(); done.run();
        }));
    }
    private String path(LibraryItem item) {
        List<String> names = new ArrayList<>(); while (item != null) { names.addFirst(item.name()); item = byId(item.parentId()); }
        return String.join(" / ", names);
    }
    private void closeTab(Entry entry) {
        if (entry.saving) { status.setText("Wait for this request to finish saving."); return; }
        if (!entry.editor.hasUnsavedEdits()) { remove(entry); return; }
        ButtonType save = new ButtonType("Save"), discard = new ButtonType("Discard");
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION, "Save changes to '" + entry.title.get() + "'?", save, discard, ButtonType.CANCEL);
        prompt.setHeaderText("Unsaved request"); prompt.initOwner(root.getScene().getWindow());
        ButtonType choice = prompt.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == discard) remove(entry);
        else if (choice == save) save(entry, false, () -> { if (!entry.editor.hasUnsavedEdits()) remove(entry); });
    }
    private void remove(Entry entry) {
        if (entry.removing) return;
        entry.removing = true; entry.tab.getContent().setDisable(true);
        autosave.clear(entry.tabId).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            entry.removing = false;
            if (closed) return;
            if (failure != null) { entry.tab.getContent().setDisable(false); error(failure); return; }
            entries.remove(entry.tabId); tabs.getTabs().remove(entry.tab); entry.editor.close();
        }));
    }
    private void offerRecovery() {
        perform(repository.recoverableDrafts(), drafts -> {
            if (drafts.isEmpty()) return;
            ButtonType recover = new ButtonType("Recover"), discard = new ButtonType("Discard drafts");
            Alert prompt = new Alert(Alert.AlertType.CONFIRMATION, drafts.size() + " unfinished request tab(s) were found. Saved requests will not be overwritten.", recover, discard, ButtonType.CANCEL);
            prompt.setHeaderText("Recover unfinished tabs?"); prompt.initOwner(root.getScene().getWindow());
            ButtonType choice = prompt.showAndWait().orElse(ButtonType.CANCEL);
            if (choice == recover) recover(drafts);
            else if (choice == discard) drafts.forEach(draft -> perform(repository.deleteDraft(draft.tabId()), ignored -> { }));
        });
    }
    public void recoverDrafts() { perform(repository.recoverableDrafts(), this::recover); }
    private void recover(List<RecoveryDraft> drafts) {
        for (var draft : drafts) {
            if (entries.containsKey(draft.tabId()) || recovering.contains(draft.tabId())) continue;
            if (entries.size() + recovering.size() >= 100) { status.setText("Some recovery drafts remain on disk. Close tabs and recover again later."); break; }
            recovering.add(draft.tabId());
            repository.claimDraft(draft.tabId()).thenCompose(claimed -> {
                if (claimed.requestId() == null) return CompletableFuture.completedFuture((SavedRequest) null);
                return repository.load(claimed.requestId()).exceptionally(failure -> null);
            }).whenComplete((saved, failure) -> Platform.runLater(() -> {
                recovering.remove(draft.tabId()); if (closed) return;
                if (failure != null) { error(failure); return; }
                UUID workspace = byId(draft.workspaceId()) != null ? draft.workspaceId()
                        : workspaces.getValue() != null ? workspaces.getValue().id() : draft.workspaceId();
                open(draft.tabId(), workspace, draft.title(), draft.draft(), saved, true);
            }));
        }
    }
    private void retryDrafts() {
        for (Entry entry : entries.values()) if (entry.editor.hasUnsavedEdits()) autosave.changed(new RecoveryDraft(entry.tabId,
                entry.saved == null ? null : entry.saved.item().id(), entry.workspaceId, entry.title.get(), entry.editor.requestEditorModel().capture(), Instant.now()));
        status.setText("Recovery snapshots scheduled. Saved requests are unchanged.");
    }
    /** Cancels debounce before clearing session drafts; closing is asynchronous and retryable. */
    public void requestWindowClose(Runnable closeWindow) {
        if (closing) return;
        if (!recovering.isEmpty() || entries.values().stream().anyMatch(entry -> entry.saving || entry.removing)) { status.setText("Wait for saves or recovery to finish before closing."); return; }
        if (entries.values().stream().anyMatch(entry -> entry.editor.hasUnsavedEdits())) {
            Alert prompt = new Alert(Alert.AlertType.CONFIRMATION, "Discard unsaved changes and close? Choose Cancel to return and Save your requests.", ButtonType.CANCEL, ButtonType.OK);
            prompt.setHeaderText("Unsaved request tabs"); prompt.initOwner(root.getScene().getWindow());
            if (prompt.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        closing = true; root.setDisable(true); autosave.pause();
        entries.values().forEach(entry -> entry.editor.cancelForWindowClose());
        if (runWindow != null) runWindow.close();
        CompletionStage<Void> stopped = collectionRunner == null ? CompletableFuture.completedFuture(null) : collectionRunner.cancelAndWait();
        stopped.thenCompose(ignored -> repository.finishSession()).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (failure != null) { closing = false; root.setDisable(false); autosave.resume(); retryDrafts(); error(failure); }
            else { close(); closeWindow.run(); }
        }));
    }
    @Override public void close() {
        if (closed) return; closed = true; if (operationsWindow != null) operationsWindow.close(); if (historyWindow != null) historyWindow.close(); transferFiles.close(); if (runWindow != null) runWindow.close(); if (collectionRunner != null) collectionRunner.close(); collectionSessions.values().forEach(dev.apitestingplatform.application.http.RunVariables::clear); collectionSessions.clear(); environments.close(); autosave.close(); entries.values().forEach(entry -> entry.editor.close()); entries.clear();
    }
}
