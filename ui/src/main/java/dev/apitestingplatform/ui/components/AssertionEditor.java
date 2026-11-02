package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.domain.http.AssertionDefinition;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.AssertionRow;
import javafx.beans.binding.Bindings;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Ordered declarative checks. A disabled check keeps its draft configuration. */
public final class AssertionEditor extends VBox {
    private final RequestEditorViewModel draft;
    private final VBox rows = new VBox(12);
    public AssertionEditor(RequestEditorViewModel draft) {
        this.draft = draft; setSpacing(10); getStyleClass().add("field-editor"); setId("assertion-editor");
        Button add = new Button("Add check"); add.setId("assertion-add");
        add.disableProperty().bind(Bindings.size(draft.assertions).greaterThanOrEqualTo(100));
        add.setOnAction(event -> draft.assertions.add(new AssertionRow("Check " + (draft.assertions.size() + 1))));
        Label hint = new Label("Checks run after Send. JSON equality expects a JSON value: 2, true, null or \"hello\". Time is in milliseconds; size is in bytes."); hint.setWrapText(true);
        ScrollPane scroll = new ScrollPane(rows); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(230); VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(add, hint, RequestEditor.error(draft.testsError, "request-tests-error"), scroll);
        draft.assertions.addListener((ListChangeListener<AssertionRow>) change -> {
            boolean rebuild = false; while (change.next()) rebuild |= change.wasAdded() || change.wasRemoved() || change.wasPermutated(); if (rebuild) rebuild();
        });
        rebuild();
    }
    private void rebuild() {
        rows.getChildren().clear();
        for (int index = 0; index < draft.assertions.size(); index++) {
            AssertionRow row = draft.assertions.get(index);
            CheckBox enabled = new CheckBox("On"); enabled.setId("assertion-enabled"); enabled.selectedProperty().bindBidirectional(row.enabled);
            ComboBox<AssertionDefinition.Type> type = new ComboBox<>(); type.getItems().setAll(AssertionDefinition.Type.values());
            type.valueProperty().bindBidirectional(row.type); type.setId("assertion-type"); type.setPrefWidth(215);
            TextField name = text(row.name, "assertion-name", "Check name", 200); name.setPrefWidth(160);
            Button up = new Button("↑"), down = new Button("↓"), remove = new Button("Remove");
            up.setAccessibleText("Move check up"); down.setAccessibleText("Move check down"); up.setId("assertion-up"); down.setId("assertion-down");
            up.setDisable(index == 0); down.setDisable(index == draft.assertions.size() - 1);
            up.setOnAction(event -> move(row, -1)); down.setOnAction(event -> move(row, 1)); remove.setOnAction(event -> draft.assertions.remove(row));
            FlowPane controls = new FlowPane(8, 6, enabled, type, name, up, down, remove); controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            TextField target = text(row.target, "assertion-target", "Header name or JSONPath, for example Content-Type or $.id", 1024);
            TextField expected = text(row.expected, "assertion-expected", "Expected value or inclusive maximum", 65536);
            VBox targetField = new VBox(4, new Label("Header / JSONPath"), target), expectedField = new VBox(4, new Label("Expected"), expected);
            targetField.visibleProperty().bind(Bindings.createBooleanBinding(() -> switch (row.type.get()) {
                case HEADER_EXISTS, HEADER_CONTAINS, JSON_PATH_EXISTS, JSON_PATH_EQUALS -> true; default -> false;
            }, row.type)); targetField.managedProperty().bind(targetField.visibleProperty());
            expectedField.visibleProperty().bind(Bindings.createBooleanBinding(() -> row.type.get() != AssertionDefinition.Type.HEADER_EXISTS && row.type.get() != AssertionDefinition.Type.JSON_PATH_EXISTS, row.type));
            expectedField.managedProperty().bind(expectedField.visibleProperty());
            VBox fields = new VBox(8, targetField, expectedField); fields.disableProperty().bind(row.enabled.not());
            VBox item = new VBox(8, controls, fields, RequestEditor.error(row.error, "assertion-error")); item.getStyleClass().add("assertion-row");
            rows.getChildren().add(item);
        }
        if (draft.assertions.isEmpty()) rows.getChildren().add(new Label("No checks yet. Add a check to validate this response automatically."));
    }
    private void move(AssertionRow row, int delta) {
        int index = draft.assertions.indexOf(row), target = index + delta;
        if (index < 0 || target < 0 || target >= draft.assertions.size()) return;
        draft.assertions.remove(index); draft.assertions.add(target, row);
    }
    private static TextField text(StringProperty property, String id, String prompt, int limit) {
        TextField field = new TextField(); field.setId(id); field.setPromptText(prompt);
        field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= limit ? change : null)); field.textProperty().bindBidirectional(property); return field;
    }
}
