package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.ExtractionRow;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Declarative configuration only. Runtime values never enter the saved view model. */
public final class ExtractionEditor extends VBox {
    private final RequestEditorViewModel draft;
    private final VBox rows = new VBox(12);
    public ExtractionEditor(RequestEditorViewModel draft) {
        this.draft = draft; setSpacing(10); setId("extraction-editor"); getStyleClass().add("field-editor");
        Button add = new Button("Add extraction"); add.setId("extraction-add");
        add.disableProperty().bind(Bindings.size(draft.extractions).greaterThanOrEqualTo(100));
        add.setOnAction(event -> draft.extractions.add(new ExtractionRow()));
        Label hint = new Label("After Send, extract exactly one JSON value into {{variable}}. Saved requests share temporary values within their collection; unsaved tabs keep their own session. Values are masked and discarded on restart. Priority: request > temporary > environment > workspace. Use Clear temporary variables to start fresh."); hint.setWrapText(true);
        ScrollPane scroll = new ScrollPane(rows); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(230); VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(add, hint, RequestEditor.error(draft.extractionsError, "request-extractions-error"), scroll);
        draft.extractions.addListener((ListChangeListener<ExtractionRow>) change -> {
            boolean rebuild = false; while (change.next()) rebuild |= change.wasAdded() || change.wasRemoved() || change.wasPermutated(); if (rebuild) rebuild();
        });
        rebuild();
    }
    private void rebuild() {
        rows.getChildren().clear();
        for (int index = 0; index < draft.extractions.size(); index++) {
            ExtractionRow row = draft.extractions.get(index);
            CheckBox on = new CheckBox("On"); on.setId("extraction-enabled"); on.selectedProperty().bindBidirectional(row.enabled);
            Button up = new Button("↑"), down = new Button("↓"), remove = new Button("Remove");
            up.setAccessibleText("Move extraction up"); down.setAccessibleText("Move extraction down"); up.setId("extraction-up"); down.setId("extraction-down");
            up.setDisable(index == 0); down.setDisable(index == draft.extractions.size() - 1);
            up.setOnAction(event -> move(row, -1)); down.setOnAction(event -> move(row, 1)); remove.setOnAction(event -> draft.extractions.remove(row));
            TextField variable = new TextField(); variable.setId("extraction-variable"); variable.setPromptText("token"); variable.textProperty().bindBidirectional(row.variable);
            variable.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 100 ? change : null));
            TextField path = new TextField(); path.setId("extraction-path"); path.setPromptText("$.access_token"); path.textProperty().bindBidirectional(row.path);
            path.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 1024 ? change : null));
            VBox fields = new VBox(4, new Label("Variable name (without braces)"), variable, new Label("Response JSONPath"), path); fields.disableProperty().bind(row.enabled.not());
            VBox item = new VBox(8, new FlowPane(8, 6, on, up, down, remove), fields, RequestEditor.error(row.error, "extraction-error")); item.getStyleClass().add("assertion-row");
            rows.getChildren().add(item);
        }
        if (draft.extractions.isEmpty()) rows.getChildren().add(new Label("No extractions yet. Add one to reuse response data in a later request."));
    }
    private void move(ExtractionRow row, int delta) {
        int index = draft.extractions.indexOf(row), destination = index + delta;
        if (index < 0 || destination < 0 || destination >= draft.extractions.size()) return;
        draft.extractions.remove(index); draft.extractions.add(destination, row);
    }
}
