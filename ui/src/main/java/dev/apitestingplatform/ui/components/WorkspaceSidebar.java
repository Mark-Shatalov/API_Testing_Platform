package dev.apitestingplatform.ui.components;

import javafx.collections.FXCollections;
import javafx.geometry.Orientation;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Workspace selector and resizable collection/request navigation trees. */
public final class WorkspaceSidebar extends VBox {
    private final TreeSection collectionsSection;
    private final TreeSection requestsSection;

    public WorkspaceSidebar() {
        Label workspaceLabel = new Label("WORKSPACE");
        workspaceLabel.getStyleClass().add("field-label");

        ComboBox<String> workspaceSelector = new ComboBox<>(
                FXCollections.observableArrayList("Default Workspace"));
        workspaceSelector.getSelectionModel().selectFirst();
        workspaceSelector.setMaxWidth(Double.MAX_VALUE);
        workspaceSelector.getStyleClass().add("workspace-selector");

        VBox workspaceHeader = new VBox(7, workspaceLabel, workspaceSelector);
        workspaceHeader.getStyleClass().add("workspace-header");

        collectionsSection = new TreeSection(
                "COLLECTIONS",
                "+ Collection",
                "No collections yet",
                "Create a collection to organize API requests.");
        requestsSection = new TreeSection(
                "REQUESTS",
                "+ Request",
                "No requests yet",
                "Create a request to begin building an API call.");

        SplitPane navigationSplit = new SplitPane(collectionsSection, requestsSection);
        navigationSplit.setOrientation(Orientation.VERTICAL);
        navigationSplit.setDividerPositions(0.5);
        navigationSplit.getStyleClass().add("sidebar-split");
        VBox.setVgrow(navigationSplit, Priority.ALWAYS);

        setMinWidth(260);
        setPrefWidth(310);
        setMaxWidth(440);
        getStyleClass().add("sidebar");
        getChildren().addAll(workspaceHeader, navigationSplit);
    }

    public TreeSection collectionsSection() {
        return collectionsSection;
    }

    public TreeSection requestsSection() {
        return requestsSection;
    }
}

