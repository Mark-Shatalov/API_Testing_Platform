package dev.apitestingplatform.app;

import dev.apitestingplatform.httpclient.EngineLimits;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.ui.components.ResponsePanel;
import dev.apitestingplatform.ui.controller.MainWindowController;
import dev.apitestingplatform.ui.response.ResponseState;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import dev.apitestingplatform.domain.http.RequestBody;
import dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel;
import dev.apitestingplatform.ui.viewmodel.HttpMethodOption;
import org.fxmisc.richtext.CodeArea;
import javafx.stage.Stage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

class ResponseDebuggingIntegrationTest {
    @TempDir Path directory;

    @BeforeAll static void startToolkit() throws Exception {
        FxTestSupport.start();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    @Test void sendsRealHttpRequestsDisplaysResponsesAndSavesTruncatedBytes() throws Exception {
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(2, 2, 32, 1024, 5))) {
            MainWindowController controller = new MainWindowController(engine);
            Stage stage = fx(() -> {
                Stage window = new Stage(); window.setScene(new Scene(controller.createView(), 1400, 900));
                window.show(); return window;
            });
            try {
                ResponsePanel panel = fx(() -> (ResponsePanel) stage.getScene().getRoot().lookup(".response-panel"));
                String url = server.url("/debug").toString();
                server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                        .addHeader("Set-Cookie", "session=abc; HttpOnly").setBody("{\"ok\":true}"));
                send(stage, url);
                awaitReady(panel);
                assertThat(fx(() -> panel.stateProperty().get())).isEqualTo(ResponseState.SUCCESS);
                Path json = directory.resolve("success.json");
                fx(() -> { panel.saveTo(json); return null; });
                awaitFile(json);
                assertThat(Files.readString(json)).isEqualTo("{\"ok\":true}");

                server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
                send(stage, url); awaitReady(panel);
                assertThat(fx(() -> panel.stateProperty().get())).isEqualTo(ResponseState.HTTP_ERROR);

                server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain")
                        .setChunkedBody("x".repeat(128), 8));
                send(stage, url); awaitReady(panel);
                assertThat(fx(() -> panel.stateProperty().get())).isEqualTo(ResponseState.RESPONSE_TOO_LARGE);
                Path prefix = directory.resolve("prefix.txt");
                fx(() -> { panel.saveTo(prefix); return null; });
                awaitFile(prefix);
                assertThat(Files.readString(prefix)).isEqualTo("x".repeat(32));
                assertThat(server.getRequestCount()).isEqualTo(3);
            } finally { fx(() -> { controller.close(); stage.close(); return null; }); }
        }
    }

    @Test void editorSendsJsonParamsHeadersCookiesAndKeepsNewerEdits() throws Exception {
        try (Fixture f = new Fixture()) {
            f.server.enqueue(new MockResponse().setBody("ok").setBodyDelay(200, TimeUnit.MILLISECONDS));
            fx(() -> {
                RequestEditorViewModel draft = f.controller.requestEditorModel();
                draft.params.add(new RequestEditorViewModel.Row("q", "café +", true));
                draft.params.add(new RequestEditorViewModel.Row("ignored", "no", false));
                draft.headers.add(new RequestEditorViewModel.Row("X-Client", "desktop", true));
                draft.headers.add(new RequestEditorViewModel.Row("Bad\nHeader", "no", false));
                draft.cookies.add(new RequestEditorViewModel.Row("session", "abc", true));
                draft.cookies.add(new RequestEditorViewModel.Row("ignored", "no", false));
                draft.bodyType.set(RequestBody.Type.JSON);
                f.selectEditor("Body");
                ((TextArea) f.stage.getScene().getRoot().lookup("#request-body-text")).setText("{\"name\":\"café\"}");
                ((javafx.scene.control.ComboBox<?>) f.stage.getScene().getRoot().lookup(".method-selector"))
                        .getSelectionModel().select(HttpMethodOption.POST.ordinal());
                ((Button) f.stage.getScene().getRoot().lookup("#request-format-json")).fire();
                return null;
            });
            waitFor(() -> f.controller.requestEditorModel().bodyText.get().contains("\n"));
            fx(() -> {
                f.selectEditor("Headers");
                var table = f.stage.getScene().getRoot().lookup("#headers-table");
                var input = table.lookupAll(".text-field").stream().filter(TextField.class::isInstance)
                        .map(TextField.class::cast).filter(field -> field.getText().equals("desktop")).findFirst().orElseThrow();
                input.requestFocus(); input.setText("edited-without-commit");
                assertThat(f.controller.requestEditorModel().headers.getFirst().value.get()).isEqualTo("edited-without-commit");
                return null;
            });
            send(f.stage, f.url());
            var received = f.server.takeRequest(3, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.getMethod()).isEqualTo("POST");
            assertThat(received.getPath()).isEqualTo("/debug?q=caf%C3%A9%20%2B");
            assertThat(received.getHeader("X-Client")).isEqualTo("edited-without-commit");
            assertThat(received.getHeader("Cookie")).isEqualTo("session=abc");
            assertThat(received.getHeader("Content-Type")).contains("application/json");
            assertThat(received.getBody().readUtf8()).contains("\"name\" : \"café\"");
            fx(() -> { f.controller.requestEditorModel().bodyText.set("new unsent edit"); return null; });
            awaitReady(f.panel);
            assertThat(fx(() -> f.controller.requestEditorModel().bodyText.get())).isEqualTo("new unsent edit");
            assertThat(fx(f.controller::hasUnsavedEdits)).isTrue();
        }
    }

    @Test void editorValidatesFieldsBeforeNetworkAndPreservesInvalidDrafts() throws Exception {
        try (Fixture f = new Fixture()) {
            send(f.stage, "not a URL");
            waitFor(() -> !f.controller.requestEditorModel().urlError.get().isEmpty());
            assertThat(f.server.getRequestCount()).isZero();
            fx(() -> {
                var draft = f.controller.requestEditorModel();
                draft.bodyType.set(RequestBody.Type.JSON); draft.bodyText.set("{\n\"x\": }");
                return null;
            });
            send(f.stage, f.url());
            waitFor(() -> f.controller.requestEditorModel().bodyError.get().contains("line 2"));
            assertThat(fx(() -> f.controller.requestEditorModel().bodyText.get())).isEqualTo("{\n\"x\": }");
            fx(() -> {
                var draft = f.controller.requestEditorModel(); draft.bodyType.set(RequestBody.Type.NONE);
                draft.headers.add(new RequestEditorViewModel.Row("Content-Length", "999", true));
                return null;
            });
            send(f.stage, f.url());
            waitFor(() -> !f.controller.requestEditorModel().headers.getFirst().error.get().isEmpty());
            assertThat(f.server.getRequestCount()).isZero();
            fx(() -> { f.controller.requestEditorModel().timeout.set("0"); return null; });
            send(f.stage, f.url());
            assertThat(fx(() -> f.controller.requestEditorModel().timeoutError.get())).contains("Timeout");
            assertThat(f.server.getRequestCount()).isZero();
        }
    }

    @Test void editorSendsUrlEncodedFormsAndMultipartFileUploads() throws Exception {
        Path file = directory.resolve("upload.txt"); Files.writeString(file, "file café");
        try (Fixture f = new Fixture()) {
            fx(() -> {
                var root = f.stage.getScene().getRoot();
                ((javafx.scene.control.ComboBox<?>) root.lookup(".method-selector")).getSelectionModel().select(HttpMethodOption.POST.ordinal());
                var draft = f.controller.requestEditorModel();
                draft.bodyType.set(RequestBody.Type.FORM_URLENCODED);
                draft.form.add(new RequestEditorViewModel.Row("key", "é +", true));
                draft.form.add(new RequestEditorViewModel.Row("ignored", "no", false));
                return null;
            });
            f.server.enqueue(new MockResponse().setBody("ok")); send(f.stage, f.url()); awaitReady(f.panel);
            var form = f.server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(form.getBody().readUtf8()).isEqualTo("key=%C3%A9+%2B");
            fx(() -> {
                var draft = f.controller.requestEditorModel(); draft.bodyType.set(RequestBody.Type.MULTIPART);
                draft.multipart.add(new RequestEditorViewModel.Row("title", "hello", true));
                var upload = new RequestEditorViewModel.Row("upload", file.toString(), true); upload.file.set(true);
                draft.multipart.add(upload);
                var disabled = new RequestEditorViewModel.Row("ignored", directory.resolve("missing.bin").toString(), false);
                disabled.file.set(true); draft.multipart.add(disabled);
                return null;
            });
            f.server.enqueue(new MockResponse().setBody("ok")); send(f.stage, f.url()); awaitReady(f.panel);
            var multipart = f.server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(multipart.getHeader("Content-Type")).startsWith("multipart/form-data; boundary=");
            assertThat(multipart.getBody().readUtf8()).contains("name=\"title\"", "filename=\"upload.txt\"", "file café").doesNotContain("ignored");
            fx(() -> { f.controller.requestEditorModel().bodyType.set(RequestBody.Type.FORM_URLENCODED); return null; });
            assertThat(fx(() -> f.controller.requestEditorModel().form.getFirst().value.get())).isEqualTo("é +");
        }
    }

    @Test void handlesPlainTextUnicodeEmptyAndServerErrorResponsesAndResizesAllEditorTabs() throws Exception {
        try (Fixture f = new Fixture()) {
            f.server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain; charset=UTF-8").setBody("hello café 東京"));
            send(f.stage, f.url()); awaitReady(f.panel);
            assertThat(fx(() -> ((CodeArea) f.panel.lookup("#response-content")).getText())).isEqualTo("hello café 東京");
            f.server.enqueue(new MockResponse().setResponseCode(204));
            send(f.stage, f.url()); awaitReady(f.panel);
            assertThat(fx(() -> ((CodeArea) f.panel.lookup("#response-content")).getText())).isEmpty();
            assertThat(fx(() -> f.panel.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                    .map(Label::getText).anyMatch("204 No Content"::equals))).isTrue();
            f.server.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));
            send(f.stage, f.url()); awaitReady(f.panel);
            assertThat(fx(() -> f.panel.stateProperty().get())).isEqualTo(ResponseState.HTTP_ERROR);
            fx(() -> {
                TabPane tabs = (TabPane) f.stage.getScene().getRoot().lookup("#request-editor-tabs");
                assertThat(tabs.getTabs()).extracting(javafx.scene.control.Tab::getText)
                        .containsExactly("Params", "Headers", "Cookies", "Auth", "Body", "Settings", "Tests");
                for (var tab : tabs.getTabs()) { tabs.getSelectionModel().select(tab); f.stage.getScene().getRoot().applyCss(); }
                f.stage.setWidth(960); f.stage.setHeight(700); f.stage.getScene().getRoot().layout();
                assertThat(tabs.getWidth()).isPositive();
                return null;
            });
        }
    }

    @Test void cancelRemainsAvailableDuringDownloadAndTimeoutAndRedirectsAreDistinct() throws Exception {
        try (Fixture f = new Fixture()) {
            f.server.enqueue(new MockResponse().setChunkedBody("x".repeat(100), 4)
                    .throttleBody(4, 200, TimeUnit.MILLISECONDS));
            send(f.stage, f.url());
            assertThat(f.server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            Thread.sleep(100);
            fx(() -> {
                var root = f.stage.getScene().getRoot();
                assertThat(((Button) root.lookup(".cancel-button")).isDisabled()).isFalse();
                f.controller.requestEditorModel().bodyText.set("keep draft after cancel");
                ((Button) root.lookup(".cancel-button")).fire(); return null;
            });
            awaitReady(f.panel);
            assertThat(fx(() -> f.panel.stateProperty().get())).isEqualTo(ResponseState.CANCELLED);
            assertThat(fx(() -> f.controller.requestEditorModel().bodyText.get())).isEqualTo("keep draft after cancel");
            fx(() -> { f.controller.requestEditorModel().timeout.set("0.15"); return null; });
            f.server.enqueue(new MockResponse().setBody("slow").setBodyDelay(400, TimeUnit.MILLISECONDS));
            send(f.stage, f.url()); awaitReady(f.panel);
            assertThat(fx(() -> f.panel.stateProperty().get())).isEqualTo(ResponseState.TIMEOUT);
            fx(() -> { f.controller.requestEditorModel().timeout.set("3"); return null; });
            f.server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/debug"));
            send(f.stage, f.url()); awaitReady(f.panel);
            assertThat(fx(() -> f.panel.stateProperty().get())).isEqualTo(ResponseState.REDIRECT_ERROR);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (fx(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        fail("UI did not reach the expected state");
    }

    private static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(2, 2, 4096, 8192, 5));
        final MainWindowController controller = new MainWindowController(engine);
        final Stage stage;
        final ResponsePanel panel;
        Fixture() throws Exception {
            server.start();
            stage = fx(() -> {
                Stage window = new Stage(); window.setScene(new Scene(controller.createView(), 1400, 900));
                window.show(); return window;
            });
            panel = fx(() -> (ResponsePanel) stage.getScene().getRoot().lookup(".response-panel"));
        }
        String url() { return server.url("/debug").toString(); }
        void selectEditor(String title) {
            TabPane tabs = (TabPane) stage.getScene().getRoot().lookup("#request-editor-tabs");
            tabs.getTabs().stream().filter(tab -> tab.getText().equals(title)).findFirst()
                    .ifPresent(tab -> tabs.getSelectionModel().select(tab));
            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        }
        @Override public void close() throws Exception {
            fx(() -> { controller.close(); stage.close(); return null; }); engine.close(); server.close();
        }
    }

    private static void send(Stage stage, String url) throws Exception {
        fx(() -> {
            var root = stage.getScene().getRoot();
            ((TextField) root.lookup(".url-input")).setText(url);
            ((Button) root.lookup(".send-button")).fire();
            return null;
        });
    }
    private static void awaitReady(ResponsePanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (fx(() -> panel.readyProperty().get())) return;
            Thread.sleep(20);
        }
        fail("Response viewer did not finish preparing the HTTP result");
    }
    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(Files.exists(path)).isTrue();
    }
}
