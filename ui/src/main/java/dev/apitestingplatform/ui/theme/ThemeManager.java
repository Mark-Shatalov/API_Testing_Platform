package dev.apitestingplatform.ui.theme;

import dev.apitestingplatform.ui.MainView;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** One palette for the main scene, owned dialogs and popups. FX thread only. */
public final class ThemeManager implements AutoCloseable {
    private final Scene scene;
    private final Function<Appearance, CompletionStage<Void>> save;
    private final String stylesheet = Objects.requireNonNull(MainView.class.getResource("main.css")).toExternalForm();
    private final WeakHashMap<Parent, String> originalStyles = new WeakHashMap<>();
    private final ListChangeListener<Window> windows = change -> {
        while (change.next()) if (change.wasAdded())
            for (Window window : change.getAddedSubList()) if (belongsToApp(window)) style(window.getScene());
    };
    private Appearance appearance;
    private Dialog<Void> settings;

    public ThemeManager(Scene scene, Appearance initial, Function<Appearance, CompletionStage<Void>> save) {
        this.scene = Objects.requireNonNull(scene); this.save = Objects.requireNonNull(save);
        apply(initial); Window.getWindows().addListener(windows);
    }
    public Appearance appearance() { return appearance; }
    private boolean belongsToApp(Window window) {
        if (window == scene.getWindow()) return true;
        Window owner = window instanceof Stage stage ? stage.getOwner()
                : window instanceof PopupWindow popup ? popup.getOwnerWindow() : null;
        return owner != null && belongsToApp(owner);
    }
    public void apply(Appearance value) {
        appearance = Objects.requireNonNull(value); style(scene);
        for (Window window : Window.getWindows()) if (belongsToApp(window)) style(window.getScene());
    }
    private void style(Scene target) {
        if (target == null) return;
        Parent root = target.getRoot();
        if (!root.getStylesheets().contains(stylesheet)) root.getStylesheets().add(stylesheet);
        root.getStyleClass().removeAll("theme-light", "theme-graphite");
        root.getStyleClass().add(appearance.theme() == Appearance.Theme.LIGHT ? "theme-light" : "theme-graphite");
        String original = originalStyles.computeIfAbsent(root, key -> key.getStyle());
        Color accent = Color.web(appearance.accent());
        // Relative luminance keeps button text readable for pale and dark custom accents.
        double luminance = 0.2126 * linear(accent.getRed()) + 0.7152 * linear(accent.getGreen()) + 0.0722 * linear(accent.getBlue());
        root.setStyle(original + "; -app-accent: " + appearance.accent()
                + "; -app-on-accent: " + (luminance > 0.179 ? "#111318" : "#ffffff") + ";");
    }
    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    public void showSettings(Window owner) {
        if (settings != null) { settings.getDialogPane().getScene().getWindow().requestFocus(); return; }
        Appearance before = appearance;
        Dialog<Void> dialog = new Dialog<>(); settings = dialog;
        dialog.initOwner(owner); dialog.setTitle("Appearance");
        dialog.setHeaderText("Theme and accent color");
        ComboBox<Appearance.Theme> theme = new ComboBox<>();
        theme.getItems().setAll(Appearance.Theme.values()); theme.setValue(before.theme());
        theme.setId("appearance-theme"); theme.setMaxWidth(Double.MAX_VALUE);
        ColorPicker accent = new ColorPicker(Color.web(before.accent())); accent.setId("appearance-accent");
        accent.setMaxWidth(Double.MAX_VALUE);
        Label note = new Label("Changes preview immediately. Save keeps them for the next launch."); note.setWrapText(true);
        Label error = new Label(); error.getStyleClass().add("validation-error"); error.setWrapText(true); error.setId("appearance-error");
        Button reset = new Button("Reset to defaults"); reset.setId("appearance-reset");
        GridPane content = new GridPane(); content.setHgap(18); content.setVgap(14); content.setPadding(new Insets(12));
        content.addRow(0, new Label("Theme"), theme); content.addRow(1, new Label("Accent color"), accent);
        content.add(note, 0, 2, 2, 1); content.add(reset, 0, 3, 2, 1); content.add(error, 0, 4, 2, 1); content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(saveType, ButtonType.CANCEL);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType); saveButton.setId("appearance-save");
        Runnable preview = () -> apply(new Appearance(theme.getValue(), hex(accent.getValue())));
        theme.valueProperty().addListener((observable, oldValue, newValue) -> { if (newValue != null) preview.run(); });
        accent.valueProperty().addListener((observable, oldValue, newValue) -> { if (newValue != null) preview.run(); });
        reset.setOnAction(event -> { theme.setValue(Appearance.defaults().theme()); accent.setValue(Color.web(Appearance.defaults().accent())); });
        boolean[] saving = {false}, committed = {false};
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); if (saving[0]) return;
            Appearance selected = appearance;
            saving[0] = true; content.setDisable(true); saveButton.setDisable(true);
            dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true); error.setText("");
            CompletionStage<Void> pending;
            try { pending = save.apply(selected); }
            catch (RuntimeException failure) { pending = java.util.concurrent.CompletableFuture.failedFuture(failure); }
            pending.whenComplete((ignored, failure) -> Platform.runLater(() -> {
                saving[0] = false;
                if (failure == null) { committed[0] = true; dialog.close(); }
                else {
                    content.setDisable(false); saveButton.setDisable(false);
                    dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                    error.setText("Appearance could not be saved. Check your data folder permissions and try again.");
                }
            }));
        });
        dialog.setOnCloseRequest(event -> { if (saving[0]) event.consume(); });
        dialog.setOnHidden(event -> { if (!committed[0]) apply(before); settings = null; });
        dialog.show();
    }
    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }
    @Override public void close() { Window.getWindows().removeListener(windows); }
}
