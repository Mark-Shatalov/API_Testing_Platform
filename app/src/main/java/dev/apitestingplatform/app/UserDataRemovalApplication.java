package dev.apitestingplatform.app;

import dev.apitestingplatform.persistence.sqlite.DataDirectory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.nio.file.Path;

/** Confirmation window for the "Remove API Testing Platform Data" Start menu shortcut. */
public final class UserDataRemovalApplication extends Application {
    static final String TITLE = "Remove API Testing Platform Data";
    private static final ButtonType REMOVE = new ButtonType("Remove data", ButtonBar.ButtonData.OK_DONE);

    @Override public void start(Stage ignored) {
        // The confirmation closes before the background removal reports back; exit explicitly instead.
        Platform.setImplicitExit(false);
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) { finish(Alert.AlertType.ERROR, "LOCALAPPDATA is unavailable, so no data was removed."); return; }
        Path data;
        try { data = DataDirectory.database().getParent(); }
        catch (IllegalStateException unavailable) { finish(Alert.AlertType.ERROR, "The data folder could not be located, so no data was removed."); return; }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "", REMOVE, ButtonType.CANCEL);
        confirm.setTitle(TITLE);
        confirm.setHeaderText("Permanently remove your local API Testing Platform data?");
        Label details = new Label(description(data));
        details.setWrapText(true); details.setMaxWidth(520);
        confirm.getDialogPane().setContent(details);
        // Cancel is the default: pressing Enter must never delete data.
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(REMOVE)).setDefaultButton(false);
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        if (confirm.showAndWait().filter(REMOVE::equals).isEmpty()) { Platform.exit(); return; }

        Thread.ofPlatform().daemon().name("user-data-removal").start(() -> {
            UserDataRemoval.Result result = UserDataRemoval.remove(Path.of(local), data);
            Platform.runLater(() -> finish(result.outcome() == UserDataRemoval.Outcome.REMOVED || result.outcome() == UserDataRemoval.Outcome.NOTHING_TO_REMOVE
                    ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING, message(result)));
        });
    }

    static String description(Path data) {
        return "This deletes " + data + " for the current Windows user, including saved workspaces, collections, requests, "
                + "environments, encrypted secrets, history, recovery drafts, automatic backups, logs, settings and caches.\n\n"
                + "It cannot be undone. Use Workspace → Export workspace first if you need a portable copy. Files you exported "
                + "or backed up to other folders are not touched. The application stays installed; uninstall it separately from "
                + "Windows Settings → Apps.";
    }

    static String message(UserDataRemoval.Result result) {
        return switch (result.outcome()) {
            case REMOVED -> "Local data was removed from " + result.directory() + ".";
            case NOTHING_TO_REMOVE -> "There is no local data at " + result.directory() + ".";
            case IN_USE -> "API Testing Platform is running. Close it, then run this shortcut again. Nothing was removed.";
            case REFUSED -> "The data folder location was not recognised, so nothing was removed.";
            case INCOMPLETE -> result.remainingEntries() + " item(s) could not be removed, usually because another program has them open. "
                    + "Close it and run this shortcut again, or delete " + result.directory() + " in File Explorer.";
        };
    }

    private static void finish(Alert.AlertType type, String text) {
        Alert alert = new Alert(type, text, ButtonType.OK);
        alert.setTitle(TITLE); alert.setHeaderText(null);
        alert.showAndWait();
        Platform.exit();
    }
}
