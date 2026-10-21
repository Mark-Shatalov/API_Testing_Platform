package dev.apitestingplatform.ui.viewmodel;

import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.RequestDraft;
import javafx.beans.Observable;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Mutable draft, deliberately separate from the immutable snapshot sent to the engine. */
public final class RequestEditorViewModel {
    public static final class Row {
        public final BooleanProperty enabled = new SimpleBooleanProperty(true);
        public final StringProperty name = new SimpleStringProperty("");
        public final StringProperty value = new SimpleStringProperty("");
        public final BooleanProperty file = new SimpleBooleanProperty(false);
        public final StringProperty contentType = new SimpleStringProperty("application/octet-stream");
        public final StringProperty error = new SimpleStringProperty("");

        public Row() { }
        public Row(String name, String value, boolean enabled) {
            this.name.set(name); this.value.set(value); this.enabled.set(enabled);
        }
        RequestField snapshot() { return new RequestField(name.get(), value.get(), enabled.get()); }
    }

    public final ObservableList<Row> params = rows();
    public final ObservableList<Row> headers = rows();
    public final ObservableList<Row> cookies = rows();
    public final ObservableList<Row> form = rows();
    public final ObservableList<Row> multipart = rows();
    public final ObjectProperty<RequestBody.Type> bodyType = new SimpleObjectProperty<>(RequestBody.Type.NONE);
    public final StringProperty bodyText = new SimpleStringProperty("");
    public final StringProperty timeout = new SimpleStringProperty("30");
    public final ObjectProperty<RedirectPolicy> redirects = new SimpleObjectProperty<>(RedirectPolicy.NORMAL);
    public final ObjectProperty<HttpProtocol> protocol = new SimpleObjectProperty<>(HttpProtocol.HTTP_2);
    public final StringProperty urlError = new SimpleStringProperty("");
    public final StringProperty bodyError = new SimpleStringProperty("");
    public final StringProperty timeoutError = new SimpleStringProperty("");
    public final StringProperty summary = new SimpleStringProperty("");
    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper(false);
    private final MainWindowViewModel main;
    private long revision;
    private boolean loading;
    private final ReadOnlyLongWrapper changes = new ReadOnlyLongWrapper();

    public RequestEditorViewModel(MainWindowViewModel main) {
        this.main = main;
        for (Observable value : List.of(params, headers, cookies, form, multipart, bodyType, bodyText,
                timeout, redirects, protocol, main.requestUrlProperty(), main.requestMethodProperty())) {
            value.addListener(ignored -> {
                // Validate observable values so successive edits notify even without an attached control.
                if (value instanceof javafx.beans.value.ObservableValue<?> property) property.getValue();
                if (!loading) { revision++; dirty.set(true); changes.set(revision); }
            });
        }
    }

    private static ObservableList<Row> rows() {
        return FXCollections.observableArrayList(row -> new Observable[]{row.enabled, row.name, row.value,
                row.file, row.contentType});
    }

    public long revision() { return revision; }
    public ReadOnlyBooleanProperty dirtyProperty() { return dirty.getReadOnlyProperty(); }
    public ReadOnlyLongProperty changesProperty() { return changes.getReadOnlyProperty(); }
    public void markSaved(long savedRevision) { if (revision == savedRevision) dirty.set(false); }
    public RequestDraft capture() {
        return new RequestDraft(HttpMethod.valueOf(main.requestMethodProperty().get().name()), main.requestUrlProperty().get(),
                params.stream().map(Row::snapshot).toList(), headers.stream().map(Row::snapshot).toList(),
                cookies.stream().map(Row::snapshot).toList(), bodyType.get(), bodyText.get(), form.stream().map(Row::snapshot).toList(),
                multipart.stream().map(row -> new RequestDraft.Part(row.name.get(), row.value.get(), row.file.get(), row.contentType.get(), row.enabled.get())).toList(),
                timeout.get(), redirects.get(), protocol.get());
    }
    public void load(RequestDraft saved) {
        loading = true;
        try {
            main.requestMethodProperty().set(HttpMethodOption.valueOf(saved.method().name())); main.requestUrlProperty().set(saved.url());
            loadRows(params, saved.params()); loadRows(headers, saved.headers()); loadRows(cookies, saved.cookies()); loadRows(form, saved.form());
            multipart.setAll(saved.multipart().stream().map(part -> {
                Row row = new Row(part.name(), part.value(), part.enabled()); row.file.set(part.file()); row.contentType.set(part.contentType()); return row;
            }).toList());
            bodyType.set(saved.bodyType()); bodyText.set(saved.bodyText()); timeout.set(saved.timeout()); redirects.set(saved.redirects()); protocol.set(saved.protocol());
            revision++; dirty.set(false); clearErrors();
        } finally { loading = false; }
    }
    public void markDirty() { revision++; dirty.set(true); changes.set(revision); }
    private static void loadRows(ObservableList<Row> target, List<RequestField> saved) {
        target.setAll(saved.stream().map(field -> new Row(field.name(), field.value(), field.enabled())).toList());
    }

    public Optional<RequestConfiguration> snapshot() {
        clearErrors();
        Duration duration;
        try {
            java.math.BigDecimal seconds = new java.math.BigDecimal(timeout.get());
            long nanos = seconds.movePointRight(9).longValueExact();
            duration = Duration.ofNanos(nanos);
            if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofDays(1)) > 0) throw new ArithmeticException();
        } catch (NumberFormatException | ArithmeticException exception) {
            timeoutError.set("Timeout must be a positive number of seconds, at most 86400 (up to 9 decimal places).");
            summary.set("Fix the timeout in Settings before sending.");
            return Optional.empty();
        }
        RequestBody body;
        try {
            body = switch (bodyType.get()) {
                case NONE -> RequestBody.none();
                case JSON -> RequestBody.json(bodyText.get());
                case TEXT -> RequestBody.text(bodyText.get());
                case FORM_URLENCODED -> RequestBody.form(form.stream().map(Row::snapshot).toList());
                case MULTIPART -> RequestBody.multipart(multipart.stream().map(row -> new MultipartPart(
                        row.name.get(), row.file.get() ? "" : row.value.get(),
                        row.file.get() && row.enabled.get() ? Optional.of(uploadPath(row.value.get())) : Optional.empty(),
                        row.file.get() ? row.contentType.get() : "text/plain; charset=UTF-8", row.enabled.get())).toList());
            };
        } catch (IllegalArgumentException exception) {
            bodyError.set("Choose a valid file path for every enabled file part.");
            summary.set("Fix the multipart file paths in Body before sending.");
            return Optional.empty();
        }
        return Optional.of(new RequestConfiguration(HttpMethod.valueOf(main.requestMethodProperty().get().name()),
                main.requestUrlProperty().get(), params.stream().map(Row::snapshot).toList(),
                headers.stream().map(Row::snapshot).toList(), cookies.stream().map(Row::snapshot).toList(),
                body, duration, redirects.get(), protocol.get()));
    }

    private static Path uploadPath(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("Missing file path");
        return Path.of(value);
    }

    public void clearErrors() {
        urlError.set(""); bodyError.set(""); timeoutError.set(""); summary.set("");
        for (var rows : List.of(params, headers, cookies, form, multipart)) rows.forEach(row -> row.error.set(""));
    }

    public void showIssues(List<RequestValidation.Issue> issues) {
        clearErrors();
        for (var issue : issues) {
            switch (issue.field()) {
                case "url" -> urlError.set(issue.message());
                case "body" -> bodyError.set(bodyError.get().isEmpty() ? issue.message() : bodyError.get() + "\n" + issue.message());
                default -> {
                    String[] field = issue.field().split("\\.");
                    var rows = switch (field[0]) {
                        case "params" -> params; case "headers" -> headers; case "cookies" -> cookies;
                        case "form" -> form; case "multipart" -> multipart;
                        default -> throw new IllegalArgumentException("Unknown validation field");
                    };
                    rows.get(Integer.parseInt(field[1])).error.set(issue.message());
                }
            }
        }
        if (!issues.isEmpty()) summary.set("Request not sent. Fix " + issues.size() + " error(s): "
                + String.join(", ", issues.stream().map(RequestValidation.Issue::field).distinct().toList()) + ".");
    }
}
