package dev.apitestingplatform.ui.response;

import dev.apitestingplatform.ui.MainView;
import dev.apitestingplatform.ui.components.ResponsePanel;
import dev.apitestingplatform.ui.controller.MainWindowController;
import dev.apitestingplatform.ui.viewmodel.MainWindowViewModel;
import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.domain.http.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

class ResponseViewerFxTest {
    @TempDir Path directory;
    private ResponsePanel panel;
    private Stage stage;

    @BeforeAll static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterAll static void stopToolkit() { Platform.exit(); }

    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    @BeforeEach void openViewer() throws Exception {
        fx(() -> {
            panel = new ResponsePanel(new MainWindowViewModel());
            Scene scene = new Scene(panel, 1050, 450);
            scene.getStylesheets().add(MainView.class.getResource("main.css").toExternalForm());
            stage = new Stage(); stage.setScene(scene); stage.show();
            return null;
        });
    }

    @AfterEach void closeViewer() throws Exception {
        fx(() -> { panel.close(); stage.close(); return null; });
    }

    private void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (fx(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        fail("Viewer did not reach the expected state");
    }

    @Test void displaysPrettyRawTabsMetadataAndSearchAcrossPages() throws Exception {
        String body = "{\"message\":\"hello\",\"ok\":true}";
        fx(() -> { panel.showResponse(ResponsePresentationTest.json(body)); return null; });
        waitFor(() -> panel.readyProperty().get());
        fx(() -> {
            CodeArea code = (CodeArea) panel.lookup("#response-content");
            assertThat(code.getText()).contains("\n", "\"message\" : \"hello\"");
            assertThat(panel.stateProperty().get()).isEqualTo(ResponseState.SUCCESS);
            TabPane tabs = (TabPane) panel.lookup("#response-tabs");
            assertThat(tabs.getTabs()).extracting(Tab::getText).containsExactly("Body", "Headers", "Cookies", "Test Results");
            for (Tab tab : tabs.getTabs()) tabs.getSelectionModel().select(tab);
            tabs.getSelectionModel().selectFirst();
            ComboBox<?> mode = (ComboBox<?>) panel.lookup("#response-mode");
            mode.getSelectionModel().select(1);
            assertThat(code.getText()).isEqualTo(body);
            assertThat(code.getStyleAtPosition(1)).isEmpty();
            code.selectRange(2, 9);
            assertThat(code.getSelectedText()).isEqualTo("message");
            stage.setWidth(700); stage.setHeight(320);
            panel.applyCss(); panel.layout();
            assertThat(code.getWidth()).isPositive();
            return null;
        });
        String large = "x".repeat(ResponsePresentation.PAGE_SIZE * 3) + "needle";
        fx(() -> { panel.showResponse(ResponsePresentationTest.json(large)); return null; });
        waitFor(() -> panel.readyProperty().get());
        fx(() -> {
            ((TextField) panel.lookup("#response-search")).setText("needle");
            ((Button) panel.lookup("#response-find-next")).fire();
            return null;
        });
        waitFor(() -> ((CodeArea) panel.lookup("#response-content")).getSelectedText().equals("needle"));
        fx(() -> {
            assertThat(((CodeArea) panel.lookup("#response-content")).getText().length())
                    .isLessThanOrEqualTo(ResponsePresentation.PAGE_SIZE + 1);
            return null;
        });
    }

    @Test void supportsLoadingEveryFailureStateAndTestResultRows() throws Exception {
        fx(() -> { panel.showLoading(); assertThat(panel.stateProperty().get()).isEqualTo(ResponseState.LOADING); return null; });
        Map<NetworkError.Type, ResponseState> cases = Map.of(NetworkError.Type.TIMEOUT, ResponseState.TIMEOUT,
                NetworkError.Type.CANCELLED, ResponseState.CANCELLED, NetworkError.Type.TLS, ResponseState.NETWORK_ERROR,
                NetworkError.Type.RESPONSE_TOO_LARGE, ResponseState.RESPONSE_TOO_LARGE);
        for (var entry : cases.entrySet()) {
            fx(() -> { panel.showResponse(ResponsePresentationTest.result(0, new byte[0], Map.of(), entry.getKey())); return null; });
            waitFor(() -> panel.readyProperty().get());
            assertThat(fx(() -> panel.stateProperty().get())).isEqualTo(entry.getValue());
        }
        fx(() -> { panel.showResponse(ResponsePresentationTest.result(404, "not found".getBytes(StandardCharsets.UTF_8), Map.of(), null)); return null; });
        waitFor(() -> panel.readyProperty().get());
        fx(() -> {
            assertThat(panel.stateProperty().get()).isEqualTo(ResponseState.HTTP_ERROR);
            panel.showTests(List.of(new ResponseTestResult("Expected status", false, "Expected 200; received 404")));
            TabPane tabs = (TabPane) panel.lookup("#response-tabs");
            assertThat(((TableView<?>) tabs.getTabs().get(3).getContent()).getItems()).hasSize(1);
            return null;
        });
    }

    @Test void ignoresOldPreparationAndSavesOriginalBytesAsynchronously() throws Exception {
        Path file = directory.resolve("response.json");
        String body = "{\"original\":true}";
        fx(() -> {
            panel.showResponse(ResponsePresentationTest.json("x".repeat(100_000)));
            panel.showLoading();
            panel.showResponse(ResponsePresentationTest.json(body));
            return null;
        });
        waitFor(() -> panel.readyProperty().get());
        fx(() -> { panel.saveTo(file); return null; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(Files.readString(file)).isEqualTo(body);
        assertThat(fx(() -> ((CodeArea) panel.lookup("#response-content")).getText())).contains("original");
    }

    @Test void mainWindowSendAndCancelUseInjectedEngineAndUpdateViewer() throws Exception {
        CompletableFuture<ExecutionResult> result = new CompletableFuture<>();
        HttpExecutionEngine engine = new HttpExecutionEngine() {
            @Override public RequestExecution execute(RequestConfiguration request) {
                assertThat(request.url()).isEqualTo("http://localhost/test");
                return new RequestExecution() {
                    @Override public CompletionStage<ExecutionResult> result() { return result; }
                    @Override public boolean cancel() {
                        return result.complete(ResponsePresentationTest.result(0, new byte[0], Map.of(), NetworkError.Type.CANCELLED));
                    }
                };
            }
            @Override public void close() { }
        };
        MainWindowController controller = new MainWindowController(engine);
        Stage main = fx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(controller.createView(), 1400, 900)); window.show();
            ((TextField) window.getScene().getRoot().lookup(".url-input")).setText("http://localhost/test");
            ((Button) window.getScene().getRoot().lookup(".send-button")).fire();
            assertThat(((Button) window.getScene().getRoot().lookup(".cancel-button")).isDisabled()).isFalse();
            ((Button) window.getScene().getRoot().lookup(".cancel-button")).fire();
            return window;
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                boolean cancelled = fx(() -> ((Label) main.getScene().getRoot().lookup("#response-state"))
                        .getText().equals("Safe error message"));
                if (cancelled) return;
                Thread.sleep(20);
            }
            fail("Cancellation result was not displayed");
        } finally { fx(() -> { controller.close(); main.close(); return null; }); }
    }
}
