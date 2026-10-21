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
    private final ComboBox<LibraryItem> workspaces = new ComboBox<>();
    private final TreeView<LibraryItem> tree = new TreeView<>(new TreeItem<>());
    private final TabPane tabs = new TabPane();
    private final Label status = new Label("Opening local storage…");
    private final Label recoveryStatus = new Label("Recovery: no pending edits");
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final boolean offerRecovery;
    private List<LibraryItem> items = List.of();
    private BorderPane root;
    private boolean updating;
    private boolean closed;
    private boolean closing;
    private long refreshVersion;
    private boolean initializedTab;
    private final Set<UUID> recovering = new HashSet<>();

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
        autosave = new DraftAutosave(repository, Duration.ofMillis(750), failure -> Platform.runLater(() -> {
            if (!closed) { recoveryStatus.setText("Recovery save failed — use Retry recovery save"); error(failure); }
        }), saved -> Platform.runLater(() -> { if (!closed) recoveryStatus.setText("Recovery snapshot saved"); }));
    }

    public Parent createView() {
        workspaces.setId("library-workspaces"); workspaces.setMaxWidth(Double.MAX_VALUE);
        workspaces.setPromptText("Create a workspace");
        workspaces.valueProperty().addListener((value, before, after) -> { if (!updating) populateTree(); });
        tree.setId("library-tree"); tree.setShowRoot(false); tree.getRoot().setExpanded(true);
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
        FlowPane creation = new FlowPane(6, 6, button("+ Workspace", "library-new-workspace", () -> create(LibraryItem.Kind.WORKSPACE)),
                button("+ Collection", "library-new-collection", () -> create(LibraryItem.Kind.COLLECTION)),
                button("+ Folder", "library-new-folder", () -> create(LibraryItem.Kind.FOLDER)),
                button("+ Request", "library-new-request", () -> create(LibraryItem.Kind.REQUEST)));
        FlowPane actions = new FlowPane(6, 6, button("Open", "library-open", this::openSelected),
                button("Rename", "library-rename", this::rename), button("Delete", "library-delete", this::delete),
                button("Move up", "library-up", () -> reorder(-1)), button("Move down", "library-down", () -> reorder(1)),
                button("Workspace…", "library-workspace-menu", this::workspaceActions));
        Label hint = new Label("Double-click a request to open it. Select a collection to add a folder or request."); hint.setWrapText(true);
        Label empty = new Label("No collections yet. Use + Collection to organize your requests."); empty.setWrapText(true);
        empty.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(tree.getRoot().getChildren())); empty.managedProperty().bind(empty.visibleProperty());
        VBox sidebar = new VBox(10, new Label("WORKSPACE"), workspaces, creation, empty, tree, actions, hint);
        sidebar.getStyleClass().add("library-sidebar"); sidebar.setMinWidth(280); sidebar.setPrefWidth(330);
        VBox.setVgrow(tree, Priority.ALWAYS);
        tabs.setId("library-request-tabs"); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        SplitPane split = new SplitPane(sidebar, tabs); split.setDividerPositions(0.24);
        FlowPane toolbar = new FlowPane(8, 6, new Label("API Testing Platform · LOCAL"),
                button("New tab", "library-new-tab", this::newTab), button("Save", "library-save", this::saveActive),
                button("Save As…", "library-save-as", () -> { Entry entry = active(); if (entry != null) save(entry, true, () -> { }); }),
                button("Refresh", "library-refresh", this::refresh),
                button("Retry recovery save", "library-retry-drafts", this::retryDrafts),
                button("Recover tabs…", "library-recover", this::offerRecovery));
        recoveryStatus.setId("library-autosave-status"); toolbar.getChildren().add(recoveryStatus);
        toolbar.getStyleClass().add("library-toolbar");
        status.setId("library-status"); status.setWrapText(true); status.getStyleClass().add("status-bar");
        root = new BorderPane(split, toolbar, null, status, null); root.getStyleClass().add("app-root");
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
        items = loaded; updating = true;
        workspaces.getItems().setAll(items.stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).toList());
        workspaces.setValue(workspaces.getItems().stream().filter(item -> item.id().equals(workspace)).findFirst()
                .orElse(workspaces.getItems().isEmpty() ? null : workspaces.getItems().getFirst()));
        updating = false; populateTree();
        status.getStyleClass().removeAll("validation-error"); status.setText("Local storage ready. Double-click a request to open it.");
        if (!initializedTab && workspaces.getValue() != null) { initializedTab = true; newTab(); }
    }
    private void populateTree() {
        var row = tree.getSelectionModel().getSelectedItem();
        populateTree(row == null || row.getValue() == null ? null : row.getValue().id());
    }
    private void populateTree(UUID selected) {
        tree.getSelectionModel().clearSelection();
        tree.getRoot().getChildren().clear();
        if (workspaces.getValue() == null) return;
        addChildren(tree.getRoot(), workspaces.getValue().id(), selected);
    }
    private void refreshSelecting(UUID id, Runnable done) {
        long version = ++refreshVersion;
        perform(repository.list(), loaded -> {
            if (version != refreshVersion) { done.run(); return; }
            applyItems(loaded);
            LibraryItem created = byId(id);
            if (created != null) {
                UUID workspace = workspaceFor(created);
                updating = true;
                try {
                    workspaces.setValue(workspaces.getItems().stream().filter(item -> item.id().equals(workspace)).findFirst().orElse(null));
                } finally { updating = false; }
                populateTree(created.kind() == LibraryItem.Kind.WORKSPACE ? null : created.id());
            }
            done.run();
        });
    }
    private void addChildren(TreeItem<LibraryItem> parent, UUID id, UUID selected) {
        for (var item : items.stream().filter(node -> id.equals(node.parentId())).toList()) {
            TreeItem<LibraryItem> child = new TreeItem<>(item); child.setExpanded(true); parent.getChildren().add(child);
            addChildren(child, item.id(), selected);
            if (item.id().equals(selected)) tree.getSelectionModel().select(child);
        }
    }
    private LibraryItem byId(UUID id) { return items.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null); }
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
        dialog.setTitle("Workspace actions"); dialog.setHeaderText(workspace.name());
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
    private void open(UUID id, UUID workspace, String title, RequestDraft draft, SavedRequest saved, boolean dirty) {
        if (entries.containsKey(id)) { tabs.getSelectionModel().select(entries.get(id).tab); return; }
        if (entries.size() >= 100) { status.setText("Close a tab before opening more (maximum 100)."); return; }
        MainWindowController controller = new MainWindowController(engine); controller.requestEditorModel().load(draft);
        Tab tab = new Tab(); tab.setContent(controller.createRequestContent());
        Entry entry = new Entry(id, workspace, controller, tab, title, saved); tab.setUserData(entry);
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
        repository.finishSession().whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (failure != null) { closing = false; root.setDisable(false); autosave.resume(); retryDrafts(); error(failure); }
            else { close(); closeWindow.run(); }
        }));
    }
    @Override public void close() {
        if (closed) return; closed = true; autosave.close(); entries.values().forEach(entry -> entry.editor.close()); entries.clear();
    }
}
