package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.util.concurrent.*;

/** Request-specific controls only; sending and validation orchestration belong to the controller. */
public final class RequestEditor extends TabPane implements AutoCloseable {
    private final RequestEditorViewModel draft;
    private final ThreadPoolExecutor formatter = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("request-format-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;

    public RequestEditor(RequestEditorViewModel draft) {
        this.draft = draft;
        setId("request-editor-tabs");
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        getStyleClass().add("editor-tabs");
        getTabs().addAll(new Tab("Params", new KeyValueEditor("params", draft.params, false)),
                new Tab("Headers", new KeyValueEditor("headers", draft.headers, false)),
                new Tab("Cookies", new KeyValueEditor("cookies", draft.cookies, false)),
                new Tab("Auth", new EmptyStatePane("Authentication", "Use the Headers tab for explicit Authorization headers. Authentication helpers come in a later phase.")),
                new Tab("Body", body()), new Tab("Settings", settings()),
                new Tab("Tests", new EmptyStatePane("No assertions configured", "Automated response assertions come in a later phase.")));
    }

    private VBox body() {
        ComboBox<RequestBody.Type> mode = new ComboBox<>(FXCollections.observableArrayList(RequestBody.Type.values()));
        mode.setId("request-body-mode");
        mode.setConverter(new StringConverter<>() {
            @Override public String toString(RequestBody.Type value) { return value == null ? "" : switch (value) {
                case NONE -> "None"; case JSON -> "Raw JSON"; case TEXT -> "Text";
                case FORM_URLENCODED -> "x-www-form-urlencoded"; case MULTIPART -> "multipart/form-data";
            }; }
            @Override public RequestBody.Type fromString(String value) { throw new UnsupportedOperationException(); }
        });
        mode.valueProperty().bindBidirectional(draft.bodyType);
        TextArea text = new TextArea();
        text.setId("request-body-text"); text.setPromptText("Enter the request body. Edits are kept when switching body modes.");
        text.textProperty().bindBidirectional(draft.bodyText);
        text.getStyleClass().add("request-body-text");
        // Bound editing prevents accidentally pasting an unbounded body into the draft.
        text.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 8 * 1024 * 1024 ? change : null));
        Button format = new Button("Format JSON"); format.setId("request-format-json");
        format.disableProperty().bind(draft.bodyType.isNotEqualTo(RequestBody.Type.JSON));
        format.setOnAction(event -> formatJson(format));
        Label hint = new Label("Files stream from disk. Multipart headers and boundaries are generated automatically. Request body limit: 8 MiB.");
        hint.setWrapText(true);
        VBox raw = new VBox(text); VBox.setVgrow(text, Priority.ALWAYS);
        KeyValueEditor form = new KeyValueEditor("form", draft.form, false);
        KeyValueEditor multipart = new KeyValueEditor("multipart", draft.multipart, true);
        EmptyStatePane none = new EmptyStatePane("No request body", "Choose a body mode above. Switching modes does not discard drafts.");
        StackPane content = new StackPane(none, raw, form, multipart);
        Runnable select = () -> {
            RequestBody.Type selected = draft.bodyType.get();
            visibility(none, selected == RequestBody.Type.NONE);
            visibility(raw, selected == RequestBody.Type.JSON || selected == RequestBody.Type.TEXT);
            visibility(form, selected == RequestBody.Type.FORM_URLENCODED);
            visibility(multipart, selected == RequestBody.Type.MULTIPART);
        };
        draft.bodyType.addListener(ignored -> select.run()); select.run();
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox result = new VBox(8, new FlowPane(8, 6, mode, format), error(draft.bodyError, "request-body-error"), content, hint);
        result.getStyleClass().add("field-editor");
        return result;
    }

    private void formatJson(Button button) {
        String snapshot = draft.bodyText.get(); long version = draft.revision();
        button.disableProperty().unbind();
        button.setDisable(true);
        try {
            formatter.execute(() -> {
                String formatted = null; String error = "";
                try { formatted = RequestValidation.formatJson(snapshot); }
                catch (IllegalArgumentException exception) { error = exception.getMessage(); }
                String output = formatted; String message = error;
                Platform.runLater(() -> {
                    if (closed) return;
                    button.disableProperty().bind(draft.bodyType.isNotEqualTo(RequestBody.Type.JSON));
                    if (version != draft.revision()) return;
                    draft.bodyError.set(message);
                    if (output != null) draft.bodyText.set(output);
                });
            });
        } catch (RejectedExecutionException busy) {
            button.disableProperty().bind(draft.bodyType.isNotEqualTo(RequestBody.Type.JSON));
            draft.bodyError.set("JSON formatter is busy. Try again.");
        }
    }

    private VBox settings() {
        TextField timeout = new TextField(); timeout.setId("request-timeout");
        timeout.textProperty().bindBidirectional(draft.timeout); timeout.setMaxWidth(220);
        ComboBox<RedirectPolicy> redirects = new ComboBox<>(FXCollections.observableArrayList(RedirectPolicy.values()));
        redirects.setId("request-redirects"); redirects.valueProperty().bindBidirectional(draft.redirects);
        ComboBox<HttpProtocol> protocol = new ComboBox<>(FXCollections.observableArrayList(HttpProtocol.values()));
        protocol.setId("request-protocol"); protocol.valueProperty().bindBidirectional(draft.protocol);
        Label note = new Label("Timeout covers the entire response download and redirects. NORMAL forbids HTTPS-to-HTTP redirects. HTTP/2 is negotiated, with HTTP/1.1 fallback. TLS certificate validation stays enabled.");
        note.setWrapText(true);
        VBox box = new VBox(8, new Label("Request timeout (seconds)"), timeout, error(draft.timeoutError, "request-timeout-error"),
                new Label("Redirect policy"), redirects, new Label("Preferred HTTP version"), protocol, note);
        box.getStyleClass().add("field-editor");
        return box;
    }

    public static Label error(StringProperty message, String id) {
        Label label = new Label(); label.setId(id); label.textProperty().bind(message); label.setWrapText(true);
        label.getStyleClass().add("validation-error");
        label.visibleProperty().bind(message.isNotEmpty()); label.managedProperty().bind(label.visibleProperty());
        return label;
    }

    private static void visibility(javafx.scene.Node node, boolean shown) { node.setVisible(shown); node.setManaged(shown); }
    @Override public void close() { closed = true; formatter.shutdownNow(); }
}
