package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.Row;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.util.function.Function;

/** Virtualized reusable row editor. Text fields update the draft immediately, including while focused. */
public final class KeyValueEditor extends VBox {
    public KeyValueEditor(String id, ObservableList<Row> rows, boolean multipart) {
        setSpacing(8);
        getStyleClass().add("field-editor");
        TableView<Row> table = new TableView<>(rows);
        table.setId(id + "-table");
        table.setPlaceholder(new Label("No rows. Add a row; unchecked rows are not sent."));
        TableColumn<Row, Row> enabled = new TableColumn<>("On");
        enabled.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
        enabled.setCellFactory(column -> new TableCell<>() {
            private final CheckBox check = new CheckBox();
            private Row bound;
            @Override protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                if (bound != null) check.selectedProperty().unbindBidirectional(bound.enabled);
                bound = empty ? null : row;
                if (bound != null) check.selectedProperty().bindBidirectional(bound.enabled);
                setGraphic(bound == null ? null : check);
            }
        });
        enabled.setPrefWidth(48);
        table.getColumns().add(enabled);
        table.getColumns().add(textColumn("Name", row -> row.name, 180));
        table.getColumns().add(textColumn(multipart ? "Value / local file path" : "Value", row -> row.value, 300));
        if (multipart) {
            TableColumn<Row, String> type = new TableColumn<>("Kind");
            type.setCellValueFactory(cell -> javafx.beans.binding.Bindings.when(cell.getValue().file).then("File").otherwise("Text"));
            type.setPrefWidth(65);
            table.getColumns().add(type);
            table.getColumns().add(textColumn("File content type", row -> row.contentType, 180));
        }
        TableColumn<Row, String> error = new TableColumn<>("Validation");
        error.setCellValueFactory(cell -> cell.getValue().error);
        error.setPrefWidth(280);
        error.getStyleClass().add("validation-error");
        table.getColumns().add(error);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);
        Button add = new Button(multipart ? "Add text part" : "Add row");
        add.setId(id + "-add");
        add.setOnAction(event -> { Row row = new Row(); rows.add(row); table.scrollTo(row); });
        add.disableProperty().bind(javafx.beans.binding.Bindings.size(rows).greaterThanOrEqualTo(1000));
        Button remove = new Button("Remove selected");
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(event -> rows.remove(table.getSelectionModel().getSelectedItem()));
        FlowPane actions = new FlowPane(8, 6, add, remove);
        if (multipart) {
            Button file = new Button("Add file…");
            file.setId("multipart-add-file");
            file.disableProperty().bind(add.disableProperty());
            file.setOnAction(event -> {
                FileChooser chooser = new FileChooser(); chooser.setTitle("Choose multipart upload");
                var chosen = chooser.showOpenDialog(getScene().getWindow());
                if (chosen == null) return;
                Row row = new Row("file", chosen.getAbsolutePath(), true); row.file.set(true);
                rows.add(row); table.scrollTo(row);
            });
            actions.getChildren().add(file);
        }
        getChildren().addAll(actions, table);
    }

    private static TableColumn<Row, Row> textColumn(String title, Function<Row, StringProperty> property, int width) {
        TableColumn<Row, Row> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setPrefWidth(width);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final TextField text = new TextField();
            { text.setAccessibleText(title); }
            private StringProperty bound;
            @Override protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                if (bound != null) text.textProperty().unbindBidirectional(bound);
                bound = empty ? null : property.apply(row);
                if (bound != null) text.textProperty().bindBidirectional(bound);
                setGraphic(bound == null ? null : text);
            }
        });
        return column;
    }
}
