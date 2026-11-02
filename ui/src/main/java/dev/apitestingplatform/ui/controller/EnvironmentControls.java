package dev.apitestingplatform.ui.controller;

import dev.apitestingplatform.application.storage.LibraryRepository;
import dev.apitestingplatform.application.storage.StorageException;
import dev.apitestingplatform.domain.variables.*;
import dev.apitestingplatform.ui.components.VariableEditor;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Workspace-scoped environment selection and isolated, explicitly saved variable editors. */
public final class EnvironmentControls extends VBox implements AutoCloseable {
    private final LibraryRepository repository;
    private final ComboBox<Environment> environments = new ComboBox<>();
    private final Label message = new Label();
    private final Button create = new Button("New"), edit = new Button("Edit"), delete = new Button("Delete");
    private final Button workspaceVariables = new Button("Workspace variables…");
    private WorkspaceVariables snapshot;
    private UUID workspace;
    private boolean updating, closed;
    private long generation;

    public EnvironmentControls(LibraryRepository repository) {
        this.repository = repository; setSpacing(8); setId("environment-controls");
        environments.setId("environment-selector"); environments.setMaxWidth(Double.MAX_VALUE);
        environments.setPromptText("No environment"); environments.setCellFactory(view -> cell()); environments.setButtonCell(cell());
        environments.valueProperty().addListener((observable, previous, selected) -> {
            if (updating || workspace == null) return;
            UUID scope = workspace; long ticket = ++generation; setBusy(true);
            repository.selectEnvironment(scope, selected == null ? null : selected.id()).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (closed || ticket != generation) return;
                if (failure != null) { updating = true; environments.setValue(previous); updating = false; showError(failure); setBusy(false); }
                else refresh();
            }));
        });
        create.setId("environment-new"); create.setOnAction(event -> editor(null, false));
        edit.setId("environment-edit"); edit.setOnAction(event -> { if (environments.getValue() != null) editor(environments.getValue(), false); });
        delete.setId("environment-delete"); delete.setOnAction(event -> delete());
        workspaceVariables.setId("workspace-variables"); workspaceVariables.setOnAction(event -> editor(null, true));
        message.setId("environment-status"); message.setWrapText(true); message.getStyleClass().add("validation-error");
        message.managedProperty().bind(message.textProperty().isNotEmpty()); message.visibleProperty().bind(message.managedProperty());
        getChildren().addAll(new Label("ENVIRONMENT"), environments, new HBox(6, create, edit, delete), workspaceVariables, message);
        setBusy(true);
    }
    private static ListCell<Environment> cell() {
        return new ListCell<>() { @Override protected void updateItem(Environment item, boolean empty) {
            super.updateItem(item, empty); setText(item == null ? "No environment" : item.name());
        } };
    }
    public void setWorkspace(UUID id) { workspace = id; refresh(); }
    public void refresh() {
        long ticket = ++generation; UUID scope = workspace; snapshot = null; message.setText(""); setBusy(true);
        if (scope == null) { updating = true; environments.getItems().clear(); environments.setValue(null); updating = false; return; }
        repository.loadVariables(scope).whenComplete((loaded, failure) -> Platform.runLater(() -> {
            if (closed || ticket != generation) return;
            if (failure != null) { showError(failure); return; }
            snapshot = loaded; updating = true;
            environments.getItems().clear(); environments.getItems().add(null); environments.getItems().addAll(loaded.environments());
            environments.setValue(loaded.environments().stream().filter(value -> value.id().equals(loaded.selectedEnvironmentId())).findFirst().orElse(null));
            updating = false; setBusy(false);
        }));
    }
    private void setBusy(boolean busy) {
        environments.setDisable(busy); create.setDisable(busy); workspaceVariables.setDisable(busy);
        edit.setDisable(busy || environments.getValue() == null); delete.setDisable(busy || environments.getValue() == null);
    }
    private void editor(Environment environment, boolean workspaceScope) {
        if (snapshot == null || workspace == null) return;
        UUID scope = workspace; long version = snapshot.version();
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(getScene().getWindow());
        dialog.setTitle(workspaceScope ? "Workspace variables" : environment == null ? "New environment" : "Edit environment");
        dialog.setHeaderText(workspaceScope ? "Default values for this workspace" : "Environment variables");
        TextField name = new TextField(environment == null ? "" : environment.name()); name.setId("environment-name"); name.setPromptText("Environment name, for example Local or Test");
        name.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 200 ? change : null));
        VariableEditor editor = new VariableEditor(workspaceScope ? snapshot.variables() : environment == null ? List.of() : environment.variables());
        editor.setId("environment-variable-editor");
        Label error = new Label(); error.setWrapText(true); error.getStyleClass().add("validation-error"); error.setId("environment-editor-error");
        VBox content = new VBox(10); if (!workspaceScope) content.getChildren().addAll(new Label("Name"), name);
        content.getChildren().addAll(editor, error); content.setPrefWidth(650);
        dialog.getDialogPane().setContent(content);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(saveType, ButtonType.CANCEL);
        Button save = (Button) dialog.getDialogPane().lookupButton(saveType); save.setId("environment-save");
        boolean[] saving = {false};
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); if (saving[0]) return;
            CompletionStage<?> pending;
            try {
                List<Variable> values = editor.variables(); Variable.validate(values);
                if (workspaceScope) pending = repository.saveWorkspaceVariables(scope, version, values);
                else if (environment == null) pending = repository.createEnvironment(scope, name.getText(), values);
                else pending = repository.saveEnvironment(new Environment(environment.id(), scope, name.getText(), environment.version(), Instant.now(), values));
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); return; }
            saving[0] = true; content.setDisable(true); save.setDisable(true);
            dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
            pending.whenComplete((result, failure) -> Platform.runLater(() -> {
                saving[0] = false; if (closed) { dialog.close(); return; }
                if (failure != null) {
                    content.setDisable(false); save.setDisable(false); dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                    error.setText(errorText(failure));
                } else { dialog.close(); refresh(); }
            }));
        });
        dialog.setOnCloseRequest(event -> { if (saving[0]) event.consume(); }); dialog.show();
    }
    private void delete() {
        Environment selected = environments.getValue(); if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete this environment and its variables? Saved request templates are kept.", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Delete environment " + selected.name() + "?"); confirm.initOwner(getScene().getWindow());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        setBusy(true); repository.deleteEnvironment(selected.id(), selected.version()).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (closed) return; if (failure != null) { showError(failure); setBusy(false); } else refresh();
        }));
    }
    private static String errorText(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof StorageException ? failure.getMessage() : "Variables could not be loaded or saved. Your editor content is still available.";
    }
    private void showError(Throwable failure) { message.setText(errorText(failure)); }
    @Override public void close() { closed = true; generation++; }
}
