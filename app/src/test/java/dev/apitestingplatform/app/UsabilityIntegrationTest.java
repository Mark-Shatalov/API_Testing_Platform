package dev.apitestingplatform.app;

import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.*;
import dev.apitestingplatform.ui.theme.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class UsabilityIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private static <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(10, TimeUnit.SECONDS); }

    @Test void sampleOnlyFillsAnEmptyRequestAndDoesNotSendTraffic() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        var controller = fx(() -> new MainWindowController(engine(sends)));
        Stage stage = fx(() -> stage(controller.createView()));
        try {
            fx(() -> {
                Parent root = stage.getScene().getRoot();
                assertThat(root.lookup("#request-starter").isVisible()).isTrue();
                assertThat(root.lookup(".cancel-button").isVisible()).isFalse();
                assertThat(root.lookup("#response-search").getParent().isVisible()).isFalse();
                ((Button) root.lookup("#request-use-sample")).fire();
                assertThat(((TextField) root.lookup(".url-input")).getText()).isEqualTo("https://jsonplaceholder.typicode.com/todos/1");
                assertThat(root.lookup("#request-starter").isVisible()).isFalse();
                assertThat(controller.hasUnsavedEdits()).isTrue();
                ((TextField) root.lookup(".url-input")).setText("https://example.com/my-request");
                ((Button) root.lookup("#request-use-sample")).fire();
                assertThat(((TextField) root.lookup(".url-input")).getText()).isEqualTo("https://example.com/my-request");
                assertThat(sends).hasValue(0);
                return null;
            });
        } finally { fx(() -> { controller.close(); stage.close(); return null; }); }
    }

    @Test void invalidBodyOpensTheSectionThatNeedsFixingWithoutSending() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        var controller = fx(() -> new MainWindowController(engine(sends)));
        Stage stage = fx(() -> stage(controller.createView()));
        try {
            fx(() -> {
                var draft = controller.requestEditorModel();
                ((TextField) stage.getScene().getRoot().lookup(".url-input")).setText("https://example.com/test");
                draft.bodyType.set(RequestBody.Type.JSON); draft.bodyText.set("{invalid");
                ((Button) stage.getScene().getRoot().lookup(".send-button")).fire(); return null;
            });
            waitFor(() -> !controller.requestEditorModel().bodyError.get().isEmpty());
            fx(() -> {
                TabPane tabs = (TabPane) stage.getScene().getRoot().lookup("#request-editor-tabs");
                assertThat(tabs.getSelectionModel().getSelectedItem().getText()).isEqualTo("Body");
                assertThat(sends).hasValue(0); return null;
            });
        } finally { fx(() -> { controller.close(); stage.close(); return null; }); }
    }

    @Test @SuppressWarnings("unchecked") void searchPreservesParentsAndDraftsAndCanBeCleared() throws Exception {
        try (Fixture f = new Fixture()) {
            fx(() -> {
                f.controller.activeEditor().requestEditorModel().bodyText.set("keep this draft");
                TextField search = (TextField) f.root().lookup("#library-search");
                TreeView<LibraryItem> tree = (TreeView<LibraryItem>) f.root().lookup("#library-tree");
                search.setText("LOGIN");
                assertThat(tree.getRoot().getChildren()).hasSize(1);
                var collection = tree.getRoot().getChildren().getFirst();
                assertThat(collection.getValue().name()).isEqualTo("Authentication");
                var folder = collection.getChildren().getFirst();
                assertThat(folder.getValue().name()).isEqualTo("Session");
                assertThat(folder.getChildren()).extracting(row -> row.getValue().name()).containsExactly("Login");
                search.setText("Authentication");
                assertThat(tree.getRoot().getChildren().getFirst().getChildren()).hasSize(2);
                search.setText("does not exist"); assertThat(tree.getRoot().getChildren()).isEmpty();
                search.clear(); assertThat(tree.getRoot().getChildren()).hasSize(2);
                assertThat(f.controller.activeEditor().requestEditorModel().bodyText.get()).isEqualTo("keep this draft");
                return null;
            });
        }
    }

    @Test void commandsRemainReachableAndToolbarFitsSmallWindowsInBothThemes() throws Exception {
        try (Fixture f = new Fixture()) {
            // Let the native tab opening animation settle before recording visual evidence.
            if (System.getProperty("appearance.snapshots") != null) Thread.sleep(250);
            fx(() -> {
                for (String id : new String[]{"library-new-workspace", "library-new-folder", "library-new-request",
                        "library-save-as", "library-refresh", "library-recover", "library-retry-drafts",
                        "library-clear-temporary", "library-import-workspace", "library-export-workspace",
                        "library-appearance", "library-settings"}) assertThat(menuAction(f.root(), id)).isNotNull();
                TitledPane options = (TitledPane) f.root().lookup("#environment-options");
                f.root().applyCss();
                assertThat(((Label) f.root().lookup("#library-status")).getFont().getFamily()).isEqualTo("Geist");
                assertThat(f.root().lookup("#library-navigation").isVisible()).isTrue();
                assertThat(options.isExpanded()).isFalse(); options.setExpanded(true);
                assertThat(f.root().lookup("#environment-new")).isNotNull(); options.setExpanded(false);
                AppearanceIntegrationTest.snapshot(f.root(), "usability-starter-graphite");
                return null;
            });
            for (var theme : Appearance.Theme.values()) {
                fx(() -> { f.stage.setWidth(1050); f.stage.setHeight(700); f.themes.apply(new Appearance(theme, Appearance.defaults().accent())); return null; });
                waitFor(() -> f.stage.getScene().getWidth() <= 1050 && f.stage.getScene().getHeight() <= 700);
                if (System.getProperty("appearance.snapshots") != null) Thread.sleep(150);
                fx(() -> {
                    f.root().applyCss(); f.root().layout();
                    HBox toolbar = (HBox) f.root().lookup(".library-toolbar");
                    for (Node node : toolbar.getChildren()) assertThat(node.getBoundsInParent().getMaxX())
                            .as("Toolbar control stays inside the window").isLessThanOrEqualTo(toolbar.getWidth());
                    AppearanceIntegrationTest.snapshot(f.root(), "usability-small-" + theme.name().toLowerCase());
                    ((TextField) f.root().lookup(".url-input")).setText("https://example.com/users");
                    var draft = f.controller.activeEditor().requestEditorModel();
                    draft.params.add(new dev.apitestingplatform.ui.viewmodel.RequestEditorViewModel.Row("page", "1", true));
                    f.root().applyCss(); f.root().layout();
                    TableView<?> params = (TableView<?>) f.root().lookup("#params-table");
                    assertThat(params.getHeight()).as("Parameter rows remain usable at the minimum window size").isGreaterThanOrEqualTo(70);
                    assertThat(params.getColumns().getFirst().getWidth()).isLessThanOrEqualTo(52);
                    Node description = f.root().lookup(".response-panel .empty-state-description");
                    Node workspace = f.root().lookup("#library-request-tabs");
                    assertThat(description.localToScene(description.getBoundsInLocal()).getMaxY())
                            .as("Response instructions remain visible inside the workbench")
                            .isLessThanOrEqualTo(workspace.localToScene(workspace.getBoundsInLocal()).getMaxY());
                    AppearanceIntegrationTest.snapshot(f.root(), "usability-small-editor-" + theme.name().toLowerCase());
                    draft.load(RequestDraft.empty());
                    return null;
                });
            }
        }
    }

    private static HttpExecutionEngine engine(AtomicInteger sends) {
        return new HttpExecutionEngine() {
            public RequestExecution execute(RequestConfiguration request) { sends.incrementAndGet(); throw new IllegalStateException("Unexpected send"); }
            public void close() { }
        };
    }
    private static Stage stage(Parent root) {
        Stage stage = new Stage(); stage.setScene(new Scene(root, 1400, 900)); stage.show(); return stage;
    }
    private final class Fixture implements AutoCloseable {
        final SqliteLibraryRepository repository;
        final LibraryWindowController controller;
        final Stage stage;
        final ThemeManager themes;
        Fixture() throws Exception {
            repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector()); await(repository.ready());
            var workspace = await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Usability checks"));
            var collection = await(repository.create(LibraryItem.Kind.COLLECTION, workspace.id(), "Authentication"));
            var folder = await(repository.create(LibraryItem.Kind.FOLDER, collection.id(), "Session"));
            await(repository.createRequest(folder.id(), "Login", RequestDraft.empty()));
            await(repository.createRequest(collection.id(), "Logout", RequestDraft.empty()));
            await(repository.create(LibraryItem.Kind.COLLECTION, workspace.id(), "Other requests"));
            controller = fx(() -> new LibraryWindowController(null, repository, false));
            stage = fx(() -> stage(controller.createView()));
            themes = fx(() -> new ThemeManager(stage.getScene(), Appearance.defaults(), value -> CompletableFuture.completedFuture(null)));
            waitFor(() -> controller.activeEditor() != null);
        }
        Parent root() { return stage.getScene().getRoot(); }
        public void close() throws Exception {
            fx(() -> { themes.close(); controller.close(); stage.close(); return null; }); await(repository.shutdown());
        }
    }
}
