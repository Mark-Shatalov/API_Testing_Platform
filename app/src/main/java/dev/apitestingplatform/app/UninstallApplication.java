package dev.apitestingplatform.app;

import dev.apitestingplatform.persistence.sqlite.DataDirectory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Optional;

/** Confirmation window for the "Uninstall API Testing Platform" Start-menu shortcut. */
public final class UninstallApplication extends Application {
    static final String TITLE = "Uninstall API Testing Platform";
    private static final ButtonType UNINSTALL = new ButtonType("Uninstall", ButtonBar.ButtonData.OK_DONE);

    @Override public void start(Stage ignored) {
        Platform.setImplicitExit(false);
        Optional<String> productCode = AppUninstaller.installedProductCode();
        if (productCode.isEmpty()) {
            finish(Alert.AlertType.INFORMATION, "API Testing Platform is not installed with Windows Installer on this computer, so there is nothing to uninstall here.");
            return;
        }
        String local = System.getenv("LOCALAPPDATA");
        Path data = null;
        try { if (local != null && !local.isBlank()) data = DataDirectory.database().getParent(); }
        catch (IllegalStateException unavailable) { data = null; }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "", UNINSTALL, ButtonType.CANCEL);
        confirm.setTitle(TITLE);
        confirm.setHeaderText("Uninstall API Testing Platform from this computer?");
        Label details = new Label("Removes the application, its bundled Java runtime and its Start-menu shortcuts. "
                + "Windows asks for administrator permission and shows the progress.");
        details.setWrapText(true); details.setMaxWidth(500);
        CheckBox removeData = new CheckBox("Also delete my saved requests, environments, history, settings and logs"
                + (data == null ? "" : "\n(" + data + ")"));
        removeData.setWrapText(true); removeData.setMaxWidth(500); removeData.setDisable(data == null);
        Label keep = new Label("Leave it unticked to keep your data for a later reinstall. Files you exported elsewhere are never touched.");
        keep.setWrapText(true); keep.setMaxWidth(500);
        confirm.getDialogPane().setContent(new VBox(12, details, removeData, keep));
        ((Button) confirm.getDialogPane().lookupButton(UNINSTALL)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        if (confirm.getDialogPane().getScene() != null && confirm.getDialogPane().getScene().getWindow() instanceof Stage window)
            ApiTestingPlatformApplication.addIcons(window);
        if (confirm.showAndWait().filter(UNINSTALL::equals).isEmpty()) { Platform.exit(); return; }

        if (removeData.isSelected() && data != null) {
            UserDataRemoval.Result removal = UserDataRemoval.remove(Path.of(local), data);
            if (removal.outcome() == UserDataRemoval.Outcome.IN_USE) {
                finish(Alert.AlertType.WARNING, "API Testing Platform is running. Close it, then run Uninstall again. Nothing was removed.");
                return;
            }
            if (removal.outcome() == UserDataRemoval.Outcome.INCOMPLETE || removal.outcome() == UserDataRemoval.Outcome.REFUSED) {
                Alert warning = new Alert(Alert.AlertType.WARNING, UserDataRemovalApplication.message(removal) + "\n\nThe application will still be uninstalled.", ButtonType.OK);
                warning.setTitle(TITLE); warning.setHeaderText(null); warning.showAndWait();
            }
        }
        try {
            AppUninstaller.start(productCode.get());
        } catch (java.io.IOException failure) {
            finish(Alert.AlertType.ERROR, "Windows Installer could not be started. Uninstall API Testing Platform from Windows Settings, Apps instead.");
            return;
        }
        // Exit immediately so msiexec can remove this launcher and its runtime.
        Platform.exit();
        System.exit(0);
    }

    private static void finish(Alert.AlertType type, String text) {
        Alert alert = new Alert(type, text, ButtonType.OK);
        alert.setTitle(TITLE); alert.setHeaderText(null);
        alert.showAndWait();
        Platform.exit();
    }
}
