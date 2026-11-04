package dev.apitestingplatform.ui.components;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/** Small local vector glyphs. No icon fonts, remote resources or bitmap scaling. */
public final class UiIcon extends StackPane {
    public enum Kind { CONNECTION, LIBRARY, HISTORY, SETTINGS, SEND, PLUS, SAVE, FOLDER, REQUEST, SEARCH }

    public UiIcon(Kind kind, double size) {
        SVGPath path = new SVGPath();
        path.setContent(switch (kind) {
            case CONNECTION -> "M8 5 L2 12 L8 19 M16 5 L22 12 L16 19 M14 3 L10 21";
            case LIBRARY -> "M4 4 H20 V20 H4 Z M9 4 V20 M4 9 H20";
            case HISTORY -> "M3 10 A9 9 0 1 1 3 15 M3 4 V10 H9 M12 7 V12 L16 14";
            case SETTINGS -> "M3 6 H8 M12 6 H21 M3 12 H14 M18 12 H21 M3 18 H5 M9 18 H21 M8 3 V9 M18 9 V15 M5 15 V21";
            case SEND -> "M4 4 L21 12 L4 20 L7 12 Z M7 12 H21";
            case PLUS -> "M12 5 V19 M5 12 H19";
            case SAVE -> "M5 3 H17 L21 7 V21 H3 V3 Z M7 3 V9 H16 V3 M7 21 V14 H17 V21";
            case FOLDER -> "M3 7 V19 H21 V7 H12 L10 4 H3 Z";
            case REQUEST -> "M6 3 H15 L20 8 V21 H6 Z M15 3 V8 H20 M9 13 H17 M9 17 H15";
            case SEARCH -> "M10.5 3 A7.5 7.5 0 1 1 10.5 18 A7.5 7.5 0 1 1 10.5 3 M16 16 L22 22";
        });
        path.setStrokeLineCap(StrokeLineCap.ROUND); path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        path.getStyleClass().add("ui-icon-path");
        path.setScaleX(size / 24); path.setScaleY(size / 24);
        setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size);
        setMouseTransparent(true); getStyleClass().add("ui-icon"); getChildren().add(path);
    }
}
