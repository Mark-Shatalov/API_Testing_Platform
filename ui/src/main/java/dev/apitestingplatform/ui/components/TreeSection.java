package dev.apitestingplatform.ui.components;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Reusable sidebar tree with its own action and empty state. */
public final class TreeSection extends VBox {
    private final Button createButton;

    public TreeSection(String title, String actionText, String emptyTitle, String emptyDescription) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("section-title");

        createButton = new Button(actionText);
        createButton.getStyleClass().add("sidebar-action-button");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox heading = new HBox(8, titleLabel, spacer, createButton);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.getStyleClass().add("tree-section-heading");

        TreeItem<String> rootItem = new TreeItem<>(title);
        rootItem.setExpanded(true);
        TreeView<String> tree = new TreeView<>(rootItem);
        tree.setShowRoot(false);
        tree.getStyleClass().add("navigation-tree");

        EmptyStatePane emptyState = new EmptyStatePane(emptyTitle, emptyDescription).compact();
        emptyState.visibleProperty().bind(Bindings.isEmpty(rootItem.getChildren()));
        emptyState.managedProperty().bind(emptyState.visibleProperty());
        tree.visibleProperty().bind(Bindings.isNotEmpty(rootItem.getChildren()));
        tree.managedProperty().bind(tree.visibleProperty());

        StackPane content = new StackPane(tree, emptyState);
        content.setMinHeight(120);
        VBox.setVgrow(content, Priority.ALWAYS);

        setMinHeight(160);
        getStyleClass().add("tree-section");
        getChildren().addAll(heading, content);
    }

    public Button createButton() {
        return createButton;
    }
}

