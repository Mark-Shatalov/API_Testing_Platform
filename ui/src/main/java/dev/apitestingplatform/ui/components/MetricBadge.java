package dev.apitestingplatform.ui.components;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/** Label/value pair used for response metadata. */
public final class MetricBadge extends HBox {
    public MetricBadge(String label, ObservableValue<String> value) {
        Label nameLabel = new Label(label);
        nameLabel.getStyleClass().add("metric-name");

        Label valueLabel = new Label();
        valueLabel.textProperty().bind(value);
        valueLabel.setMaxWidth(260);
        javafx.scene.control.Tooltip tooltip = new javafx.scene.control.Tooltip();
        tooltip.textProperty().bind(value);
        valueLabel.setTooltip(tooltip);
        valueLabel.getStyleClass().add("metric-value");

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        getStyleClass().add("metric-badge");
        getChildren().addAll(nameLabel, valueLabel);
        if (label.equals("Status")) {
            Runnable update = () -> {
                String status = value.getValue() == null ? "" : value.getValue();
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("success"), status.startsWith("2"));
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("failure"), status.startsWith("4") || status.startsWith("5"));
            };
            value.addListener(ignored -> update.run()); update.run();
        }
    }
}

