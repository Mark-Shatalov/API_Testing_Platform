package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.domain.variables.Variable;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.VariableRow;
import javafx.collections.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.List;

/** Isolated editable rows. Secret values stay hidden until explicitly revealed. */
public final class VariableEditor extends VBox {
    private final ObservableList<VariableRow> rows;
    private final VBox values = new VBox(8);
    private final CheckBox reveal = new CheckBox("Show secret values");

    public VariableEditor(List<Variable> initial) {
        this(FXCollections.<VariableRow>observableArrayList(row -> new javafx.beans.Observable[]{row.name, row.value, row.secret}));
        rows.setAll(initial.stream().map(VariableRow::new).toList());
    }
    public VariableEditor(ObservableList<VariableRow> rows) {
        this.rows = rows; setSpacing(10); getStyleClass().add("field-editor");
        Button add = new Button("Add variable"); add.setId("variables-add");
        add.setOnAction(event -> { if (rows.size() < 256) rows.add(new VariableRow()); });
        add.disableProperty().bind(javafx.beans.binding.Bindings.size(rows).greaterThanOrEqualTo(256));
        reveal.setId("variables-reveal");
        HBox actions = new HBox(12, add, reveal); actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label hint = new Label("Use {{name}} in requests. Request values override the environment, then workspace values. Maximum 256 per scope.");
        hint.setWrapText(true);
        ScrollPane scroll = new ScrollPane(values); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(230); VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(actions, hint, scroll);
        rows.addListener((ListChangeListener<VariableRow>) change -> {
            boolean rebuild = false; while (change.next()) rebuild |= change.wasAdded() || change.wasRemoved() || change.wasPermutated();
            if (rebuild) rebuild();
        });
        rebuild();
    }
    private void rebuild() {
        values.getChildren().clear();
        for (VariableRow row : rows) {
            TextField name = new TextField(); name.setPromptText("Name"); name.setPrefWidth(150); name.setMaxWidth(180);
            name.setId("variable-name"); name.setTextFormatter(limit(100)); name.textProperty().bindBidirectional(row.name);
            TextField plain = new TextField(); plain.setPromptText("Value"); plain.setId("variable-value"); plain.setTextFormatter(limit(65536));
            PasswordField hidden = new PasswordField(); hidden.setPromptText("Secret value"); hidden.setId("variable-secret-value"); hidden.setTextFormatter(limit(65536));
            plain.textProperty().bindBidirectional(row.value); hidden.textProperty().bindBidirectional(row.value);
            hidden.visibleProperty().bind(row.secret.and(reveal.selectedProperty().not())); hidden.managedProperty().bind(hidden.visibleProperty());
            plain.visibleProperty().bind(hidden.visibleProperty().not()); plain.managedProperty().bind(plain.visibleProperty());
            StackPane value = new StackPane(plain, hidden); HBox.setHgrow(value, Priority.ALWAYS);
            CheckBox secret = new CheckBox("Secret"); secret.setId("variable-secret"); secret.selectedProperty().bindBidirectional(row.secret);
            Button remove = new Button("Remove"); remove.setOnAction(event -> rows.remove(row));
            HBox line = new HBox(8, name, value, secret, remove); line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            values.getChildren().add(line);
        }
        if (rows.isEmpty()) values.getChildren().add(new Label("No variables in this scope."));
    }
    private static TextFormatter<String> limit(int length) {
        return new TextFormatter<>(change -> change.getControlNewText().length() <= length ? change : null);
    }
    public List<Variable> variables() { return rows.stream().map(VariableRow::snapshot).toList(); }
}
