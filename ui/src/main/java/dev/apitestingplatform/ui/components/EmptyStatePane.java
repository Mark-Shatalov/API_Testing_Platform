package dev.apitestingplatform.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Reusable centered empty-state content for tabs and navigation sections. */
public final class EmptyStatePane extends VBox {
    public EmptyStatePane(String title, String description) {
        javafx.scene.layout.StackPane marker = new javafx.scene.layout.StackPane(new UiIcon(UiIcon.Kind.REQUEST, 22));
        marker.getStyleClass().add("empty-state-icon"); marker.setMaxSize(32, 32); marker.setMinSize(32, 32);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-state-title");

        Label descriptionLabel = new Label(description);
        descriptionLabel.setWrapText(true);
        descriptionLabel.setMaxWidth(460);
        descriptionLabel.getStyleClass().add("empty-state-description");

        setAlignment(Pos.CENTER);
        setSpacing(8);
        setFillWidth(true);
        getStyleClass().add("empty-state");
        getChildren().addAll(marker, titleLabel, descriptionLabel);
    }

    public EmptyStatePane compact() {
        getStyleClass().add("empty-state-compact");
        return this;
    }
}

