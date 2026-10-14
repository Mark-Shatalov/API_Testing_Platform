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
import javafx.stage.Stage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class ResponseDebuggingIntegrationTest {
    @TempDir Path directory;

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    @Test void sendsRealHttpRequestsDisplaysResponsesAndSavesTruncatedBytes() throws Exception {
        CountDownLatch toolkit = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); toolkit.countDown(); });
        assertThat(toolkit.await(10, TimeUnit.SECONDS)).isTrue();
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
        } finally { Platform.exit(); }
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
