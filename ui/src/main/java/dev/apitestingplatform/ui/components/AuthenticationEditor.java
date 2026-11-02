package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.domain.http.Authentication;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Authentication templates; resolved credentials never replace the fields the user entered. */
public final class AuthenticationEditor extends VBox {
    private final CheckBox reveal = new CheckBox("Show credentials");
    public AuthenticationEditor(RequestEditorViewModel draft) {
        setSpacing(12); getStyleClass().add("field-editor"); setId("authentication-editor");
        ComboBox<Authentication.Mode> mode = new ComboBox<>(); mode.getItems().setAll(Authentication.Mode.values());
        mode.valueProperty().bindBidirectional(draft.authMode); mode.setId("auth-mode"); mode.setPrefWidth(210);
        reveal.setId("auth-reveal");
        Label hint = new Label("Use literal values or {{variables}}. Credentials are protected when saved. Use HTTPS for real credentials."); hint.setWrapText(true);
        VBox none = new VBox(new Label("No authentication helper. Explicit headers still work in Headers."));
        GridPane bearer = form(); bearer.addRow(0, new Label("Token"), secret(draft.bearerToken, "auth-bearer-token", "Token only, without the Bearer prefix"));
        GridPane basic = form(); basic.addRow(0, new Label("Username"), text(draft.authUsername, "auth-username", "Username or {{username}}"));
        basic.addRow(1, new Label("Password"), secret(draft.authPassword, "auth-password", "Password or {{password}}"));
        GridPane key = form(); key.addRow(0, new Label("Key name"), text(draft.apiKeyName, "auth-key-name", "X-API-Key or api_key"));
        key.addRow(1, new Label("Key value"), secret(draft.apiKeyValue, "auth-key-value", "API key or {{apiKey}}"));
        ComboBox<Authentication.Placement> placement = new ComboBox<>(); placement.getItems().setAll(Authentication.Placement.values());
        placement.valueProperty().bindBidirectional(draft.authPlacement); placement.setId("auth-key-placement");
        key.addRow(2, new Label("Add to"), placement);
        for (Node node : new Node[]{none, bearer, basic, key}) {
            Authentication.Mode choice = node == none ? Authentication.Mode.NONE : node == bearer ? Authentication.Mode.BEARER : node == basic ? Authentication.Mode.BASIC : Authentication.Mode.API_KEY;
            node.visibleProperty().bind(draft.authMode.isEqualTo(choice)); node.managedProperty().bind(node.visibleProperty());
        }
        reveal.visibleProperty().bind(draft.authMode.isNotEqualTo(Authentication.Mode.NONE)); reveal.managedProperty().bind(reveal.visibleProperty());
        getChildren().addAll(new HBox(16, new Label("Authentication"), mode, reveal), hint,
                RequestEditor.error(draft.authError, "request-auth-error"), none, bearer, basic, key);
    }
    private static GridPane form() { GridPane pane = new GridPane(); pane.setHgap(16); pane.setVgap(12); return pane; }
    private static TextField text(StringProperty property, String id, String prompt) {
        TextField field = new TextField(); field.setId(id); field.setPromptText(prompt); field.setPrefWidth(460);
        field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 65536 ? change : null));
        field.textProperty().bindBidirectional(property); GridPane.setHgrow(field, Priority.ALWAYS); return field;
    }
    private Node secret(StringProperty property, String id, String prompt) {
        TextField plain = text(property, id + "-visible", prompt);
        PasswordField hidden = new PasswordField(); hidden.setId(id); hidden.setPromptText(prompt); hidden.setPrefWidth(460);
        hidden.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 65536 ? change : null));
        hidden.textProperty().bindBidirectional(property);
        plain.visibleProperty().bind(reveal.selectedProperty()); plain.managedProperty().bind(plain.visibleProperty());
        hidden.visibleProperty().bind(reveal.selectedProperty().not()); hidden.managedProperty().bind(hidden.visibleProperty());
        StackPane pane = new StackPane(plain, hidden); GridPane.setHgrow(pane, Priority.ALWAYS); return pane;
    }
}
